/*
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.rollback;

import static android.util.StatsLog.WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_REASON__REASON_APP_CRASH;
import static android.util.StatsLog.WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_REASON__REASON_APP_NOT_RESPONDING;
import static android.util.StatsLog.WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_REASON__REASON_EXPLICIT_HEALTH_CHECK;
import static android.util.StatsLog.WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_REASON__REASON_NATIVE_CRASH;
import static android.util.StatsLog.WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_REASON__REASON_UNKNOWN;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.VersionedPackage;
import android.content.rollback.PackageRollbackInfo;
import android.content.rollback.RollbackInfo;
import android.content.rollback.RollbackManager;
import android.os.Environment;
import android.os.FileUtils;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.os.SystemProperties;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.Slog;
import android.util.StatsLog;

import com.android.internal.annotations.GuardedBy;
import com.android.server.PackageWatchdog;
import com.android.server.PackageWatchdog.FailureReasons;
import com.android.server.PackageWatchdog.PackageHealthObserver;
import com.android.server.PackageWatchdog.PackageHealthObserverImpact;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * {@link PackageHealthObserver} for {@link RollbackManagerService}.
 * This class monitors crashes and triggers RollbackManager rollback accordingly.
 * It also monitors native crashes for some short while after boot.
 *
 * @hide
 */
public final class RollbackPackageHealthObserver implements PackageHealthObserver {
    private static final String TAG = "RollbackPackageHealthObserver";
    private static final String NAME = "rollback-observer";

    private static final String LOGGING_PARENT_KEY = "android.content.pm.LOGGING_PARENT";

    private final Context mContext;
    private final Handler mHandler;
    private final File mLastStagedRollbackIdsFile;
    // Staged rollback ids that have been committed but their session is not yet ready
    @GuardedBy("mPendingStagedRollbackIds")
    private final Set<Integer> mPendingStagedRollbackIds = new ArraySet<>();

    RollbackPackageHealthObserver(Context context) {
        mContext = context;
        HandlerThread handlerThread = new HandlerThread("RollbackPackageHealthObserver");
        handlerThread.start();
        mHandler = handlerThread.getThreadHandler();
        File dataDir = new File(Environment.getDataDirectory(), "rollback-observer");
        dataDir.mkdirs();
        mLastStagedRollbackIdsFile = new File(dataDir, "last-staged-rollback-ids");
        PackageWatchdog.getInstance(mContext).registerHealthObserver(this);
    }

    @Override
    public int onHealthCheckFailed(@Nullable VersionedPackage failedPackage,
            @FailureReasons int failureReason) {
        // For native crashes, we will roll back any available rollbacks
        if (failureReason == PackageWatchdog.FAILURE_REASON_NATIVE_CRASH
                && !mContext.getSystemService(RollbackManager.class)
                .getAvailableRollbacks().isEmpty()) {
            return PackageHealthObserverImpact.USER_IMPACT_MEDIUM;
        }
        if (getAvailableRollback(failedPackage) == null) {
            // Don't handle the notification, no rollbacks available for the package
            return PackageHealthObserverImpact.USER_IMPACT_NONE;
        } else {
            // Rollback is available, we may get a callback into #execute
            return PackageHealthObserverImpact.USER_IMPACT_MEDIUM;
        }
    }

    @Override
    public boolean execute(@Nullable VersionedPackage failedPackage,
            @FailureReasons int rollbackReason) {
        if (rollbackReason == PackageWatchdog.FAILURE_REASON_NATIVE_CRASH) {
            rollbackAll();
            return true;
        }

        RollbackInfo rollback = getAvailableRollback(failedPackage);
        if (rollback == null) {
            Slog.w(TAG, "Expected rollback but no valid rollback found for " + failedPackage);
            return false;
        }
        rollbackPackage(rollback, failedPackage, rollbackReason);
        // Assume rollback executed successfully
        return true;
    }

    @Override
    public String getName() {
        return NAME;
    }

    /**
     * Start observing health of {@code packages} for {@code durationMs}.
     * This may cause {@code packages} to be rolled back if they crash too freqeuntly.
     */
    public void startObservingHealth(List<String> packages, long durationMs) {
        PackageWatchdog.getInstance(mContext).startObservingHealth(this, packages, durationMs);
    }

    /** Verifies the rollback state after a reboot and schedules polling for sometime after reboot
     * to check for native crashes and mitigate them if needed.
     */
    public void onBootCompletedAsync() {
        mHandler.post(()->onBootCompleted());
    }

    private void onBootCompleted() {
        RollbackManager rollbackManager = mContext.getSystemService(RollbackManager.class);
        if (!rollbackManager.getAvailableRollbacks().isEmpty()) {
            // TODO(gavincorkery): Call into Package Watchdog from outside the observer
            PackageWatchdog.getInstance(mContext).scheduleCheckAndMitigateNativeCrashes();
        }

        List<Integer> rollbackIds = popLastStagedRollbackIds();
        Iterator<Integer> rollbackIterator = rollbackIds.iterator();
        while (rollbackIterator.hasNext()) {
            int rollbackId = rollbackIterator.next();
            logRollbackStatusOnBoot(rollbackId, rollbackManager.getRecentlyCommittedRollbacks());
        }
    }

    private void logRollbackStatusOnBoot(int rollbackId,
            List<RollbackInfo> recentlyCommittedRollbacks) {
        PackageInstaller packageInstaller = mContext.getPackageManager().getPackageInstaller();

        RollbackInfo rollback = null;
        for (RollbackInfo info : recentlyCommittedRollbacks) {
            if (rollbackId == info.getRollbackId()) {
                rollback = info;
                break;
            }
        }

        if (rollback == null) {
            Slog.e(TAG, "rollback info not found for last staged rollback: " + rollbackId);
            return;
        }

        // Identify the logging parent for this rollback. When all configurations are correct, each
        // package in the rollback refers to the same logging parent, except for the logging parent
        // itself. If a logging parent is missing for a package, we use the package itself for
        // logging. This might result in over-logging, but we prefer this over no logging.
        final Set<String> loggingPackageNames = new ArraySet<>();
        for (PackageRollbackInfo packageRollback : rollback.getPackages()) {
            final String loggingParentName = getLoggingParentName(packageRollback.getPackageName());
            if (loggingParentName != null) {
                loggingPackageNames.add(loggingParentName);
            } else {
                loggingPackageNames.add(packageRollback.getPackageName());
            }
        }

        // Use the version of the logging parent that was installed before
        // we rolled back for logging purposes.
        final List<VersionedPackage> oldLoggingPackages = new ArrayList<>();
        for (PackageRollbackInfo packageRollback : rollback.getPackages()) {
            if (loggingPackageNames.contains(packageRollback.getPackageName())) {
                oldLoggingPackages.add(packageRollback.getVersionRolledBackFrom());
            }
        }

        int sessionId = rollback.getCommittedSessionId();
        PackageInstaller.SessionInfo sessionInfo = packageInstaller.getSessionInfo(sessionId);
        if (sessionInfo == null) {
            Slog.e(TAG, "On boot completed, could not load session id " + sessionId);
            return;
        }

        for (VersionedPackage oldLoggingPackage : oldLoggingPackages) {
            if (sessionInfo.isStagedSessionApplied()) {
                logEvent(oldLoggingPackage,
                        StatsLog.WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_TYPE__ROLLBACK_SUCCESS,
                        WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_REASON__REASON_UNKNOWN, "");
            } else if (sessionInfo.isStagedSessionFailed()) {
                logEvent(oldLoggingPackage,
                        StatsLog.WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_TYPE__ROLLBACK_FAILURE,
                        WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_REASON__REASON_UNKNOWN, "");
            }
        }
    }

    private RollbackInfo getAvailableRollback(VersionedPackage failedPackage) {
        RollbackManager rollbackManager = mContext.getSystemService(RollbackManager.class);
        for (RollbackInfo rollback : rollbackManager.getAvailableRollbacks()) {
            for (PackageRollbackInfo packageRollback : rollback.getPackages()) {
                if (packageRollback.getVersionRolledBackFrom().equals(failedPackage)) {
                    return rollback;
                }
                // TODO(b/147666157): Extract version number of apk-in-apex so that we don't have
                //  to rely on complicated reasoning as below

                // Due to b/147666157, for apk in apex, we do not know the version we are rolling
                // back from. But if a package X is embedded in apex A exclusively (not embedded in
                // any other apex), which is not guaranteed, then it is sufficient to check only
                // package names here, as the version of failedPackage and the PackageRollbackInfo
                // can't be different. If failedPackage has a higher version, then it must have
                // been updated somehow. There are two ways: it was updated by an update of apex A
                // or updated directly as apk. In both cases, this rollback would have gotten
                // expired when onPackageReplaced() was called. Since the rollback exists, it has
                // same version as failedPackage.
                if (packageRollback.isApkInApex()
                        && packageRollback.getVersionRolledBackFrom().getPackageName()
                        .equals(failedPackage.getPackageName())) {
                    return rollback;
                }
            }
        }
        return null;
    }

    @Nullable
    private String getLoggingParentName(String packageName) {
        PackageManager packageManager = mContext.getPackageManager();
        try {
            ApplicationInfo ai = packageManager.getApplicationInfo(packageName,
                    PackageManager.GET_META_DATA);
            if (ai.metaData == null) {
                return null;
            }
            return ai.metaData.getString(LOGGING_PARENT_KEY);
        } catch (Exception e) {
            Slog.w(TAG, "Unable to discover logging parent package: " + packageName, e);
            return null;
        }
    }

    private BroadcastReceiver listenForStagedSessionReady(RollbackManager rollbackManager,
            int rollbackId, @Nullable VersionedPackage logPackage) {
        BroadcastReceiver sessionUpdatedReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                handleStagedSessionChange(rollbackManager,
                        rollbackId, this /* BroadcastReceiver */, logPackage);
            }
        };
        IntentFilter sessionUpdatedFilter =
                new IntentFilter(PackageInstaller.ACTION_SESSION_UPDATED);
        mContext.registerReceiver(sessionUpdatedReceiver, sessionUpdatedFilter);
        return sessionUpdatedReceiver;
    }

    private void handleStagedSessionChange(RollbackManager rollbackManager, int rollbackId,
            BroadcastReceiver listener, @Nullable VersionedPackage logPackage) {
        PackageInstaller packageInstaller =
                mContext.getPackageManager().getPackageInstaller();
        List<RollbackInfo> recentRollbacks =
                rollbackManager.getRecentlyCommittedRollbacks();
        for (int i = 0; i < recentRollbacks.size(); i++) {
            RollbackInfo recentRollback = recentRollbacks.get(i);
            int sessionId = recentRollback.getCommittedSessionId();
            if ((rollbackId == recentRollback.getRollbackId())
                    && (sessionId != PackageInstaller.SessionInfo.INVALID_ID)) {
                PackageInstaller.SessionInfo sessionInfo =
                        packageInstaller.getSessionInfo(sessionId);
                if (sessionInfo.isStagedSessionReady() && markStagedSessionHandled(rollbackId)) {
                    mContext.unregisterReceiver(listener);
                    if (logPackage != null) {
                        // We save the rollback id so that after reboot, we can log if rollback was
                        // successful or not. If logPackage is null, then there is nothing to log.
                        saveStagedRollbackId(rollbackId);
                    }
                    logEvent(logPackage,
                            StatsLog
                            .WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_TYPE__ROLLBACK_BOOT_TRIGGERED,
                            WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_REASON__REASON_UNKNOWN,
                            "");
                } else if (sessionInfo.isStagedSessionFailed()
                        && markStagedSessionHandled(rollbackId)) {
                    logEvent(logPackage,
                            StatsLog.WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_TYPE__ROLLBACK_FAILURE,
                            WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_REASON__REASON_UNKNOWN,
                            "");
                    mContext.unregisterReceiver(listener);
                }
            }
        }

        // Wait for all pending staged sessions to get handled before rebooting.
        if (isPendingStagedSessionsEmpty()) {
            mContext.getSystemService(PowerManager.class).reboot("Rollback staged install");
        }
    }

    /**
     * Returns {@code true} if staged session associated with {@code rollbackId} was marked
     * as handled, {@code false} if already handled.
     */
    private boolean markStagedSessionHandled(int rollbackId) {
        synchronized (mPendingStagedRollbackIds) {
            return mPendingStagedRollbackIds.remove(rollbackId);
        }
    }

    /**
     * Returns {@code true} if all pending staged rollback sessions were marked as handled,
     * {@code false} if there is any left.
     */
    private boolean isPendingStagedSessionsEmpty() {
        synchronized (mPendingStagedRollbackIds) {
            return mPendingStagedRollbackIds.isEmpty();
        }
    }

    private void saveStagedRollbackId(int stagedRollbackId) {
        try {
            FileOutputStream fos = new FileOutputStream(
                    mLastStagedRollbackIdsFile, /*append*/true);
            PrintWriter pw = new PrintWriter(fos);
            pw.append(",").append(String.valueOf(stagedRollbackId));
            pw.flush();
            FileUtils.sync(fos);
            pw.close();
        } catch (IOException e) {
            Slog.e(TAG, "Failed to save last staged rollback id", e);
            mLastStagedRollbackIdsFile.delete();
        }
    }

    private List<Integer> popLastStagedRollbackIds() {
        try (BufferedReader reader =
                     new BufferedReader(new FileReader(mLastStagedRollbackIdsFile))) {
            String line = reader.readLine();
            // line is of format : ",id1,id2,id3....,idn"
            String[] sessionIdsStr = line.split(",");
            ArrayList<Integer> result = new ArrayList<>();
            for (String sessionIdStr: sessionIdsStr) {
                if (!TextUtils.isEmpty(sessionIdStr.trim())) {
                    result.add(Integer.parseInt(sessionIdStr));
                }
            }
            return result;
        } catch (Exception ignore) {
            return Collections.emptyList();
        } finally {
            mLastStagedRollbackIdsFile.delete();
        }
    }

    private static String rollbackTypeToString(int type) {
        switch (type) {
            case StatsLog.WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_TYPE__ROLLBACK_INITIATE:
                return "ROLLBACK_INITIATE";
            case StatsLog.WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_TYPE__ROLLBACK_SUCCESS:
                return "ROLLBACK_SUCCESS";
            case StatsLog.WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_TYPE__ROLLBACK_FAILURE:
                return "ROLLBACK_FAILURE";
            case StatsLog.WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_TYPE__ROLLBACK_BOOT_TRIGGERED:
                return "ROLLBACK_BOOT_TRIGGERED";
            default:
                return "UNKNOWN";
        }
    }

    private static String rollbackReasonToString(int reason) {
        switch (reason) {
            case StatsLog.WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_REASON__REASON_NATIVE_CRASH:
                return "REASON_NATIVE_CRASH";
            case StatsLog.WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_REASON__REASON_EXPLICIT_HEALTH_CHECK:
                return "REASON_EXPLICIT_HEALTH_CHECK";
            case StatsLog.WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_REASON__REASON_APP_CRASH:
                return "REASON_APP_CRASH";
            case StatsLog.WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_REASON__REASON_APP_NOT_RESPONDING:
                return "REASON_APP_NOT_RESPONDING";
            default:
                return "UNKNOWN";
        }
    }

    private static void logEvent(@Nullable VersionedPackage logPackage, int type,
            int rollbackReason, @NonNull String failingPackageName) {
        Slog.i(TAG, "Watchdog event occurred with type: " + rollbackTypeToString(type)
                + " logPackage: " + logPackage
                + " rollbackReason: " + rollbackReasonToString(rollbackReason)
                + " failedPackageName: " + failingPackageName);
        if (logPackage != null) {
            StatsLog.logWatchdogRollbackOccurred(type, logPackage.getPackageName(),
                    logPackage.getLongVersionCode(), rollbackReason, failingPackageName);
        }
    }

    /**
     * Returns true if the package name is the name of a module.
     */
    private boolean isModule(String packageName) {
        PackageManager pm = mContext.getPackageManager();
        try {
            return pm.getModuleInfo(packageName, 0) != null;
        } catch (PackageManager.NameNotFoundException ignore) {
            return false;
        }
    }

    /**
     * Rolls back the session that owns {@code failedPackage}
     *
     * @param rollback {@code rollbackInfo} of the {@code failedPackage}
     * @param failedPackage the package that needs to be rolled back
     */
    private void rollbackPackage(RollbackInfo rollback, VersionedPackage failedPackage,
            @FailureReasons int rollbackReason) {
        final RollbackManager rollbackManager = mContext.getSystemService(RollbackManager.class);
        int reasonToLog = mapFailureReasonToMetric(rollbackReason);
        final String failedPackageToLog;
        if (rollbackReason == PackageWatchdog.FAILURE_REASON_NATIVE_CRASH) {
            failedPackageToLog = SystemProperties.get(
                    "sys.init.updatable_crashing_process_name", "");
        } else {
            failedPackageToLog = failedPackage.getPackageName();
        }
        VersionedPackage logPackageTemp = null;
        if (isModule(failedPackage.getPackageName())) {
            String logPackageName = getLoggingParentName(failedPackage.getPackageName());
            if (logPackageName == null) {
                logPackageName = failedPackage.getPackageName();
            }
            try {
                logPackageTemp = new VersionedPackage(logPackageName, mContext.getPackageManager()
                        .getPackageInfo(logPackageName, 0 /* flags */).getLongVersionCode());
            } catch (PackageManager.NameNotFoundException e) {
                logPackageTemp = null;
            }
        }

        final VersionedPackage logPackage = logPackageTemp;
        logEvent(logPackage,
                StatsLog.WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_TYPE__ROLLBACK_INITIATE,
                reasonToLog, failedPackageToLog);
        final LocalIntentReceiver rollbackReceiver = new LocalIntentReceiver((Intent result) -> {
            int status = result.getIntExtra(RollbackManager.EXTRA_STATUS,
                    RollbackManager.STATUS_FAILURE);
            if (status == RollbackManager.STATUS_SUCCESS) {
                if (rollback.isStaged()) {
                    int rollbackId = rollback.getRollbackId();
                    synchronized (mPendingStagedRollbackIds) {
                        mPendingStagedRollbackIds.add(rollbackId);
                    }
                    BroadcastReceiver listener =
                            listenForStagedSessionReady(rollbackManager, rollbackId,
                                    logPackage);
                    handleStagedSessionChange(rollbackManager, rollbackId, listener,
                            logPackage);
                } else {
                    logEvent(logPackage,
                            StatsLog.WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_TYPE__ROLLBACK_SUCCESS,
                            reasonToLog, failedPackageToLog);
                }
            } else {
                if (rollback.isStaged()) {
                    markStagedSessionHandled(rollback.getRollbackId());
                }
                logEvent(logPackage,
                        StatsLog.WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_TYPE__ROLLBACK_FAILURE,
                        reasonToLog, failedPackageToLog);
            }
        });

        mHandler.post(() ->
                rollbackManager.commitRollback(rollback.getRollbackId(),
                        Collections.singletonList(failedPackage),
                        rollbackReceiver.getIntentSender()));
    }

    private void rollbackAll() {
        Slog.i(TAG, "Rolling back all available rollbacks");
        RollbackManager rollbackManager = mContext.getSystemService(RollbackManager.class);
        List<RollbackInfo> rollbacks = rollbackManager.getAvailableRollbacks();

        // Add all rollback ids to mPendingStagedRollbackIds, so that we do not reboot before all
        // pending staged rollbacks are handled.
        synchronized (mPendingStagedRollbackIds) {
            for (RollbackInfo rollback : rollbacks) {
                if (rollback.isStaged()) {
                    mPendingStagedRollbackIds.add(rollback.getRollbackId());
                }
            }
        }

        for (RollbackInfo rollback : rollbacks) {
            VersionedPackage sample = rollback.getPackages().get(0).getVersionRolledBackFrom();
            rollbackPackage(rollback, sample, PackageWatchdog.FAILURE_REASON_NATIVE_CRASH);
        }
    }


    private int mapFailureReasonToMetric(@FailureReasons int failureReason) {
        switch (failureReason) {
            case PackageWatchdog.FAILURE_REASON_NATIVE_CRASH:
                return WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_REASON__REASON_NATIVE_CRASH;
            case PackageWatchdog.FAILURE_REASON_EXPLICIT_HEALTH_CHECK:
                return WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_REASON__REASON_EXPLICIT_HEALTH_CHECK;
            case PackageWatchdog.FAILURE_REASON_APP_CRASH:
                return WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_REASON__REASON_APP_CRASH;
            case PackageWatchdog.FAILURE_REASON_APP_NOT_RESPONDING:
                return WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_REASON__REASON_APP_NOT_RESPONDING;
            default:
                return WATCHDOG_ROLLBACK_OCCURRED__ROLLBACK_REASON__REASON_UNKNOWN;
        }
    }

}
