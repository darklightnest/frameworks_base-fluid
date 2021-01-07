/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.location.timezone;

import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.service.timezone.TimeZoneProviderService.TEST_COMMAND_RESULT_ERROR_KEY;
import static android.service.timezone.TimeZoneProviderService.TEST_COMMAND_RESULT_SUCCESS_KEY;

import static com.android.server.location.timezone.LocationTimeZoneManagerService.warnLog;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteCallback;
import android.service.timezone.ITimeZoneProvider;
import android.service.timezone.ITimeZoneProviderManager;
import android.service.timezone.TimeZoneProviderSuggestion;
import android.util.IndentingPrintWriter;

import com.android.internal.annotations.GuardedBy;
import com.android.server.ServiceWatcher;

import java.util.Objects;
import java.util.function.Predicate;

/**
 * System server-side proxy for ITimeZoneProvider implementations, i.e. this provides the
 * system server object used to communicate with a remote {@link
 * android.service.timezone.TimeZoneProviderService} over Binder, which could be running in a
 * different process. As "remote" providers are bound / unbound this proxy will rebind to the "best"
 * available remote process.
 */
class RealLocationTimeZoneProviderProxy extends LocationTimeZoneProviderProxy {

    @NonNull private final ServiceWatcher mServiceWatcher;

    @GuardedBy("mSharedLock")
    @Nullable private ManagerProxy mManagerProxy;

    @GuardedBy("mSharedLock")
    @NonNull private TimeZoneProviderRequest mRequest;

    RealLocationTimeZoneProviderProxy(
            @NonNull Context context, @NonNull Handler handler,
            @NonNull ThreadingDomain threadingDomain, @NonNull String action,
            int enableOverlayResId, int nonOverlayPackageResId) {
        super(context, threadingDomain);
        mManagerProxy = null;
        mRequest = TimeZoneProviderRequest.createStopUpdatesRequest();

        // A predicate that is used to confirm that an intent service can be used as a
        // location-based TimeZoneProvider. The service must:
        // 1) Declare android:permission="android.permission.BIND_TIME_ZONE_PROVIDER_SERVICE" - this
        //    ensures that the provider will only communicate with the system server.
        // 2) Be in an application that has been granted the
        //    android.permission.INSTALL_LOCATION_TIME_ZONE_PROVIDER_SERVICE permission. This
        //    ensures only trusted time zone providers will be discovered.
        final String requiredClientPermission = Manifest.permission.BIND_TIME_ZONE_PROVIDER_SERVICE;
        final String requiredPermission =
                Manifest.permission.INSTALL_LOCATION_TIME_ZONE_PROVIDER_SERVICE;
        Predicate<ResolveInfo> intentServiceCheckPredicate = resolveInfo -> {
            ServiceInfo serviceInfo = resolveInfo.serviceInfo;

            boolean hasClientPermissionRequirement =
                    requiredClientPermission.equals(serviceInfo.permission);

            String packageName = serviceInfo.packageName;
            PackageManager packageManager = context.getPackageManager();
            int checkResult = packageManager.checkPermission(requiredPermission, packageName);
            boolean hasRequiredPermission = checkResult == PERMISSION_GRANTED;

            boolean result = hasClientPermissionRequirement && hasRequiredPermission;
            if (!result) {
                warnLog("resolveInfo=" + resolveInfo + " does not meet requirements:"
                        + " hasClientPermissionRequirement=" + hasClientPermissionRequirement
                        + ", hasRequiredPermission=" + hasRequiredPermission);
            }
            return result;
        };
        mServiceWatcher = new ServiceWatcher(context, handler, action, this::onBind, this::onUnbind,
                enableOverlayResId, nonOverlayPackageResId, intentServiceCheckPredicate);
    }

    @Override
    void onInitialize() {
        if (!register()) {
            throw new IllegalStateException("Unable to register binder proxy");
        }
    }

    private boolean register() {
        boolean resolves = mServiceWatcher.checkServiceResolves();
        if (resolves) {
            mServiceWatcher.register();
        }
        return resolves;
    }

    private void onBind(IBinder binder, ComponentName componentName) {
        mThreadingDomain.assertCurrentThread();

        synchronized (mSharedLock) {
            // When a new remote is first bound we create the ManagerProxy that will be passed to
            // it. By creating a new one for each bind the ManagerProxy can check whether it is
            // still the current proxy and if not it can ignore incoming calls.
            mManagerProxy = new ManagerProxy();
            mListener.onProviderBound();

            // Send the current request to the remote.
            trySendCurrentRequest();
        }
    }

    private void onUnbind() {
        mThreadingDomain.assertCurrentThread();

        synchronized (mSharedLock) {
            // Clear the ManagerProxy used with the old remote so we will ignore calls from any old
            // remotes that somehow hold a reference to it.
            mManagerProxy = null;
            mListener.onProviderUnbound();
        }
    }

    @Override
    final void setRequest(@NonNull TimeZoneProviderRequest request) {
        mThreadingDomain.assertCurrentThread();

        Objects.requireNonNull(request);
        synchronized (mSharedLock) {
            mRequest = request;

            // Two possible outcomes here: Either we are already bound to a remote service, in
            // which case trySendCurrentRequest() will communicate the request immediately, or we
            // are not bound to the remote service yet, in which case it will be sent during
            // onBindOnHandlerThread() instead.
            trySendCurrentRequest();
        }
    }

    @GuardedBy("mSharedLock")
    private void trySendCurrentRequest() {
        ManagerProxy managerProxy = mManagerProxy;
        TimeZoneProviderRequest request = mRequest;
        mServiceWatcher.runOnBinder(binder -> {
            ITimeZoneProvider service = ITimeZoneProvider.Stub.asInterface(binder);
            if (request.sendUpdates()) {
                service.startUpdates(managerProxy, request.getInitializationTimeout().toMillis());
            } else {
                service.stopUpdates();
            }
        });
    }

    /**
     * A stubbed implementation.
     */
    @Override
    void handleTestCommand(@NonNull TestCommand testCommand, @Nullable RemoteCallback callback) {
        mThreadingDomain.assertCurrentThread();

        if (callback != null) {
            Bundle result = new Bundle();
            result.putBoolean(TEST_COMMAND_RESULT_SUCCESS_KEY, false);
            result.putString(TEST_COMMAND_RESULT_ERROR_KEY, "Not implemented");
            callback.sendResult(result);
        }
    }

    @Override
    public void dump(@NonNull IndentingPrintWriter ipw, @Nullable String[] args) {
        synchronized (mSharedLock) {
            ipw.println("mRequest=" + mRequest);
            mServiceWatcher.dump(null, ipw, args);
        }
    }

    /**
     * A system Server-side proxy for the ITimeZoneProviderManager, i.e. this is a local binder stub
     * Each "remote" TimeZoneProvider is passed a binder instance that it then uses to communicate
     * back with the system server, invoking the logic here.
     */
    private class ManagerProxy extends ITimeZoneProviderManager.Stub {

        // executed on binder thread
        @Override
        public void onTimeZoneProviderSuggestion(TimeZoneProviderSuggestion suggestion) {
            onTimeZoneProviderEvent(TimeZoneProviderEvent.createSuggestionEvent(suggestion));
        }

        // executed on binder thread
        @Override
        public void onTimeZoneProviderUncertain() {
            onTimeZoneProviderEvent(TimeZoneProviderEvent.createUncertainEvent());

        }

        // executed on binder thread
        @Override
        public void onTimeZoneProviderPermanentFailure(String failureReason) {
            onTimeZoneProviderEvent(
                    TimeZoneProviderEvent.createPermanentFailureEvent(failureReason));
        }

        private void onTimeZoneProviderEvent(TimeZoneProviderEvent event) {
            synchronized (mSharedLock) {
                if (mManagerProxy != this) {
                    // Ignore incoming calls if this instance is no longer the current
                    // mManagerProxy.
                    return;
                }
            }
            handleTimeZoneProviderEvent(event);
        }
    }
}
