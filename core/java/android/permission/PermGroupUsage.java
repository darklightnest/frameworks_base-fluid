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

package android.permission;

import android.annotation.NonNull;
import android.annotation.Nullable;

/**
 * Represents the usage of a permission group by an app. Supports package name, user, permission
 * group, whether or not the access is running or recent, whether the access is tied to a phone
 * call, and an optional special attribution.
 *
 * @hide
 */
public final class PermGroupUsage {

    private final String mPackageName;
    private final int mUid;
    private final String mPermGroupName;
    private final boolean mIsActive;
    private final boolean mIsPhoneCall;
    private final CharSequence mAttribution;

    PermGroupUsage(@NonNull String packageName, int uid,
            @NonNull String permGroupName, boolean isActive, boolean isPhoneCall,
            @Nullable CharSequence attribution) {
        this.mPackageName = packageName;
        this.mUid = uid;
        this.mPermGroupName = permGroupName;
        this.mIsActive = isActive;
        this.mIsPhoneCall = isPhoneCall;
        this.mAttribution = attribution;
    }

    public @NonNull String getPackageName() {
        return mPackageName;
    }

    public int getUid() {
        return mUid;
    }

    public @NonNull String getPermGroupName() {
        return mPermGroupName;
    }

    public boolean isActive() {
        return mIsActive;
    }

    public boolean isPhoneCall() {
        return mIsPhoneCall;
    }

    public @Nullable CharSequence getAttribution() {
        return mAttribution;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(this))
                + "packageName: " + mPackageName + ", UID: " + mUid + ", permGroup: "
                + mPermGroupName + ", isActive: " + mIsActive + ",attribution: " + mAttribution;
    }
}
