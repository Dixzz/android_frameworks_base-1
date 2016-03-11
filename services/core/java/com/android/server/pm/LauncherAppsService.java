/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.server.pm;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.AppGlobals;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ILauncherApps;
import android.content.pm.IOnAppsChangedListener;
import android.content.pm.IPackageManager;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ParceledListSlice;
import android.content.pm.ResolveInfo;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutServiceInternal;
import android.content.pm.ShortcutServiceInternal.ShortcutChangeListener;
import android.content.pm.UserInfo;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.IInterface;
import android.os.ParcelFileDescriptor;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.util.Log;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.content.PackageMonitor;
import com.android.internal.util.Preconditions;
import com.android.server.LocalServices;
import com.android.server.SystemService;

import java.util.List;

/**
 * Service that manages requests and callbacks for launchers that support
 * managed profiles.
 */
public class LauncherAppsService extends SystemService {

    private final LauncherAppsImpl mLauncherAppsImpl;

    public LauncherAppsService(Context context) {
        super(context);
        mLauncherAppsImpl = new LauncherAppsImpl(context);
    }

    @Override
    public void onStart() {
        publishBinderService(Context.LAUNCHER_APPS_SERVICE, mLauncherAppsImpl);
    }

    @VisibleForTesting
    static class LauncherAppsImpl extends ILauncherApps.Stub {
        private static final boolean DEBUG = false;
        private static final String TAG = "LauncherAppsService";
        private final Context mContext;
        private final PackageManager mPm;
        private final UserManager mUm;
        private final ShortcutServiceInternal mShortcutServiceInternal;
        private final PackageCallbackList<IOnAppsChangedListener> mListeners
                = new PackageCallbackList<IOnAppsChangedListener>();

        private final MyPackageMonitor mPackageMonitor = new MyPackageMonitor();

        public LauncherAppsImpl(Context context) {
            mContext = context;
            mPm = mContext.getPackageManager();
            mUm = (UserManager) mContext.getSystemService(Context.USER_SERVICE);
            mShortcutServiceInternal = Preconditions.checkNotNull(
                    LocalServices.getService(ShortcutServiceInternal.class));
            mShortcutServiceInternal.addListener(mPackageMonitor);
        }

        /*
         * @see android.content.pm.ILauncherApps#addOnAppsChangedListener(
         *          android.content.pm.IOnAppsChangedListener)
         */
        @Override
        public void addOnAppsChangedListener(IOnAppsChangedListener listener) throws RemoteException {
            synchronized (mListeners) {
                if (DEBUG) {
                    Log.d(TAG, "Adding listener from " + Binder.getCallingUserHandle());
                }
                if (mListeners.getRegisteredCallbackCount() == 0) {
                    if (DEBUG) {
                        Log.d(TAG, "Starting package monitoring");
                    }
                    startWatchingPackageBroadcasts();
                }
                mListeners.unregister(listener);
                mListeners.register(listener, Binder.getCallingUserHandle());
            }
        }

        /*
         * @see android.content.pm.ILauncherApps#removeOnAppsChangedListener(
         *          android.content.pm.IOnAppsChangedListener)
         */
        @Override
        public void removeOnAppsChangedListener(IOnAppsChangedListener listener)
                throws RemoteException {
            synchronized (mListeners) {
                if (DEBUG) {
                    Log.d(TAG, "Removing listener from " + Binder.getCallingUserHandle());
                }
                mListeners.unregister(listener);
                if (mListeners.getRegisteredCallbackCount() == 0) {
                    stopWatchingPackageBroadcasts();
                }
            }
        }

        /**
         * Register a receiver to watch for package broadcasts
         */
        private void startWatchingPackageBroadcasts() {
            mPackageMonitor.register(mContext, null, UserHandle.ALL, true);
        }

        /**
         * Unregister package broadcast receiver
         */
        private void stopWatchingPackageBroadcasts() {
            if (DEBUG) {
                Log.d(TAG, "Stopped watching for packages");
            }
            mPackageMonitor.unregister();
        }

        void checkCallbackCount() {
            synchronized (mListeners) {
                if (DEBUG) {
                    Log.d(TAG, "Callback count = " + mListeners.getRegisteredCallbackCount());
                }
                if (mListeners.getRegisteredCallbackCount() == 0) {
                    stopWatchingPackageBroadcasts();
                }
            }
        }

        /**
         * Checks if the caller is in the same group as the userToCheck.
         */
        @VisibleForTesting // We override it in unit tests
        void ensureInUserProfiles(UserHandle userToCheck, String message) {
            final int callingUserId = UserHandle.getCallingUserId();
            final int targetUserId = userToCheck.getIdentifier();

            if (targetUserId == callingUserId) return;

            long ident = Binder.clearCallingIdentity();
            try {
                UserInfo callingUserInfo = mUm.getUserInfo(callingUserId);
                UserInfo targetUserInfo = mUm.getUserInfo(targetUserId);
                if (targetUserInfo == null
                        || targetUserInfo.profileGroupId == UserInfo.NO_PROFILE_GROUP_ID
                        || targetUserInfo.profileGroupId != callingUserInfo.profileGroupId) {
                    throw new SecurityException(message);
                }
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @VisibleForTesting // We override it in unit tests
        void verifyCallingPackage(String callingPackage) {
            int packageUid = -1;
            try {
                packageUid = mPm.getPackageUidAsUser(callingPackage,
                        PackageManager.MATCH_ENCRYPTION_AWARE_AND_UNAWARE
                                | PackageManager.MATCH_UNINSTALLED_PACKAGES,
                        UserHandle.getUserId(getCallingUid()));
            } catch (NameNotFoundException e) {
                Log.e(TAG, "Package not found: " + callingPackage);
            }
            if (packageUid != Binder.getCallingUid()) {
                throw new SecurityException("Calling package name mismatch");
            }
        }

        /**
         * Checks if the user is enabled.
         */
        private boolean isUserEnabled(UserHandle user) {
            long ident = Binder.clearCallingIdentity();
            try {
                UserInfo targetUserInfo = mUm.getUserInfo(user.getIdentifier());
                return targetUserInfo != null && targetUserInfo.isEnabled();
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override
        public ParceledListSlice<ResolveInfo> getLauncherActivities(String packageName, UserHandle user)
                throws RemoteException {
            ensureInUserProfiles(user, "Cannot retrieve activities for unrelated profile " + user);
            if (!isUserEnabled(user)) {
                return null;
            }

            final Intent mainIntent = new Intent(Intent.ACTION_MAIN, null);
            mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);
            mainIntent.setPackage(packageName);
            long ident = Binder.clearCallingIdentity();
            try {
                List<ResolveInfo> apps = mPm.queryIntentActivitiesAsUser(mainIntent,
                        PackageManager.MATCH_ENCRYPTION_AWARE_AND_UNAWARE, user.getIdentifier());
                return new ParceledListSlice<>(apps);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override
        public ResolveInfo resolveActivity(Intent intent, UserHandle user)
                throws RemoteException {
            ensureInUserProfiles(user, "Cannot resolve activity for unrelated profile " + user);
            if (!isUserEnabled(user)) {
                return null;
            }

            long ident = Binder.clearCallingIdentity();
            try {
                ResolveInfo app = mPm.resolveActivityAsUser(intent,
                        PackageManager.MATCH_ENCRYPTION_AWARE_AND_UNAWARE, user.getIdentifier());
                return app;
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override
        public boolean isPackageEnabled(String packageName, UserHandle user)
                throws RemoteException {
            ensureInUserProfiles(user, "Cannot check package for unrelated profile " + user);
            if (!isUserEnabled(user)) {
                return false;
            }

            long ident = Binder.clearCallingIdentity();
            try {
                IPackageManager pm = AppGlobals.getPackageManager();
                PackageInfo info = pm.getPackageInfo(packageName,
                        PackageManager.MATCH_ENCRYPTION_AWARE_AND_UNAWARE, user.getIdentifier());
                return info != null && info.applicationInfo.enabled;
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override
        public ApplicationInfo getApplicationInfo(String packageName, int flags, UserHandle user)
                throws RemoteException {
            ensureInUserProfiles(user, "Cannot check package for unrelated profile " + user);
            if (!isUserEnabled(user)) {
                return null;
            }

            long ident = Binder.clearCallingIdentity();
            try {
                IPackageManager pm = AppGlobals.getPackageManager();
                ApplicationInfo info = pm.getApplicationInfo(packageName, flags,
                        user.getIdentifier());
                return info;
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        private void enforceShortcutPermission(UserHandle user) {
            ensureInUserProfiles(user, "Cannot start activity for unrelated profile " + user);
            // STOPSHIP Implement it
        }

        @Override
        public ParceledListSlice getShortcuts(String callingPackage, long changedSince,
                String packageName, ComponentName componentName, int flags, UserHandle user)
                throws RemoteException {
            enforceShortcutPermission(user);
            verifyCallingPackage(callingPackage);

            return new ParceledListSlice<>(
                    mShortcutServiceInternal.getShortcuts(callingPackage, changedSince, packageName,
                    componentName, flags, user.getIdentifier()));
        }

        @Override
        public ParceledListSlice getShortcutInfo(String callingPackage, String packageName,
                List<String> ids, UserHandle user) throws RemoteException {
            enforceShortcutPermission(user);
            verifyCallingPackage(callingPackage);

            return new ParceledListSlice<>(
                    mShortcutServiceInternal.getShortcutInfo(callingPackage, packageName,
                    ids, user.getIdentifier()));
        }

        @Override
        public void pinShortcuts(String callingPackage, String packageName, List<String> ids,
                UserHandle user) throws RemoteException {
            enforceShortcutPermission(user);
            verifyCallingPackage(callingPackage);

            mShortcutServiceInternal.pinShortcuts(callingPackage, packageName,
                    ids, user.getIdentifier());
        }

        @Override
        public int getShortcutIconResId(String callingPackage, ShortcutInfo shortcut,
                UserHandle user) {
            enforceShortcutPermission(user);
            verifyCallingPackage(callingPackage);

            return mShortcutServiceInternal.getShortcutIconResId(callingPackage, shortcut,
                    user.getIdentifier());
        }

        @Override
        public ParcelFileDescriptor getShortcutIconFd(String callingPackage, ShortcutInfo shortcut,
                UserHandle user) {
            enforceShortcutPermission(user);
            verifyCallingPackage(callingPackage);

            return mShortcutServiceInternal.getShortcutIconFd(callingPackage, shortcut,
                    user.getIdentifier());
        }

        @Override
        public boolean startShortcut(String callingPackage, String packageName, String shortcutId,
                Rect sourceBounds, Bundle startActivityOptions, UserHandle user)
                throws RemoteException {
            enforceShortcutPermission(user);
            verifyCallingPackage(callingPackage);

            final Intent intent = mShortcutServiceInternal.createShortcutIntent(callingPackage,
                    packageName, shortcutId, user.getIdentifier());
            if (intent == null) {
                return false;
            }
            // Note the target activity doesn't have to be exported.

            intent.setSourceBounds(sourceBounds);
            prepareIntentForLaunch(intent, sourceBounds);

            final long ident = Binder.clearCallingIdentity();
            try {
                mContext.startActivityAsUser(intent, startActivityOptions, user);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
            return true;
        }

        @Override
        public boolean isActivityEnabled(ComponentName component, UserHandle user)
                throws RemoteException {
            ensureInUserProfiles(user, "Cannot check component for unrelated profile " + user);
            if (!isUserEnabled(user)) {
                return false;
            }

            long ident = Binder.clearCallingIdentity();
            try {
                IPackageManager pm = AppGlobals.getPackageManager();
                ActivityInfo info = pm.getActivityInfo(component,
                        PackageManager.MATCH_ENCRYPTION_AWARE_AND_UNAWARE, user.getIdentifier());
                return info != null;
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override
        public void startActivityAsUser(ComponentName component, Rect sourceBounds,
                Bundle opts, UserHandle user) throws RemoteException {
            ensureInUserProfiles(user, "Cannot start activity for unrelated profile " + user);
            if (!isUserEnabled(user)) {
                throw new IllegalStateException("Cannot start activity for disabled profile "  + user);
            }

            Intent launchIntent = new Intent(Intent.ACTION_MAIN);
            launchIntent.addCategory(Intent.CATEGORY_LAUNCHER);
            prepareIntentForLaunch(launchIntent, sourceBounds);
            launchIntent.setPackage(component.getPackageName());

            long ident = Binder.clearCallingIdentity();
            try {
                IPackageManager pm = AppGlobals.getPackageManager();
                ActivityInfo info = pm.getActivityInfo(component,
                        PackageManager.MATCH_ENCRYPTION_AWARE_AND_UNAWARE, user.getIdentifier());
                if (!info.exported) {
                    throw new SecurityException("Cannot launch non-exported components "
                            + component);
                }

                // Check that the component actually has Intent.CATEGORY_LAUCNCHER
                // as calling startActivityAsUser ignores the category and just
                // resolves based on the component if present.
                List<ResolveInfo> apps = mPm.queryIntentActivitiesAsUser(launchIntent,
                        PackageManager.MATCH_ENCRYPTION_AWARE_AND_UNAWARE, user.getIdentifier());
                final int size = apps.size();
                for (int i = 0; i < size; ++i) {
                    ActivityInfo activityInfo = apps.get(i).activityInfo;
                    if (activityInfo.packageName.equals(component.getPackageName()) &&
                            activityInfo.name.equals(component.getClassName())) {
                        // Found an activity with category launcher that matches
                        // this component so ok to launch.
                        launchIntent.setComponent(component);
                        mContext.startActivityAsUser(launchIntent, opts, user);
                        return;
                    }
                }
                throw new SecurityException("Attempt to launch activity without "
                        + " category Intent.CATEGORY_LAUNCHER " + component);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        private void prepareIntentForLaunch(@NonNull Intent launchIntent,
                @Nullable Rect sourceBounds) {
            launchIntent.setSourceBounds(sourceBounds);
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        }

        @Override
        public void showAppDetailsAsUser(ComponentName component, Rect sourceBounds,
                Bundle opts, UserHandle user) throws RemoteException {
            ensureInUserProfiles(user, "Cannot show app details for unrelated profile " + user);
            if (!isUserEnabled(user)) {
                throw new IllegalStateException("Cannot show app details for disabled profile "
                        + user);
            }

            long ident = Binder.clearCallingIdentity();
            try {
                String packageName = component.getPackageName();
                Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", packageName, null));
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                intent.setSourceBounds(sourceBounds);
                mContext.startActivityAsUser(intent, opts, user);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }


        private class MyPackageMonitor extends PackageMonitor implements ShortcutChangeListener {

            /** Checks if user is a profile of or same as listeningUser.
              * and the user is enabled. */
            private boolean isEnabledProfileOf(UserHandle user, UserHandle listeningUser,
                    String debugMsg) {
                if (user.getIdentifier() == listeningUser.getIdentifier()) {
                    if (DEBUG) Log.d(TAG, "Delivering msg to same user " + debugMsg);
                    return true;
                }
                long ident = Binder.clearCallingIdentity();
                try {
                    UserInfo userInfo = mUm.getUserInfo(user.getIdentifier());
                    UserInfo listeningUserInfo = mUm.getUserInfo(listeningUser.getIdentifier());
                    if (userInfo == null || listeningUserInfo == null
                            || userInfo.profileGroupId == UserInfo.NO_PROFILE_GROUP_ID
                            || userInfo.profileGroupId != listeningUserInfo.profileGroupId
                            || !userInfo.isEnabled()) {
                        if (DEBUG) {
                            Log.d(TAG, "Not delivering msg from " + user + " to " + listeningUser + ":"
                                    + debugMsg);
                        }
                        return false;
                    } else {
                        if (DEBUG) {
                            Log.d(TAG, "Delivering msg from " + user + " to " + listeningUser + ":"
                                    + debugMsg);
                        }
                        return true;
                    }
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            }

            // TODO Simplify with lambdas.

            @Override
            public void onPackageAdded(String packageName, int uid) {
                UserHandle user = new UserHandle(getChangingUserId());
                final int n = mListeners.beginBroadcast();
                for (int i = 0; i < n; i++) {
                    IOnAppsChangedListener listener = mListeners.getBroadcastItem(i);
                    UserHandle listeningUser = (UserHandle) mListeners.getBroadcastCookie(i);
                    if (!isEnabledProfileOf(user, listeningUser, "onPackageAdded")) continue;
                    try {
                        listener.onPackageAdded(user, packageName);
                    } catch (RemoteException re) {
                        Slog.d(TAG, "Callback failed ", re);
                    }
                }
                mListeners.finishBroadcast();

                super.onPackageAdded(packageName, uid);
            }

            @Override
            public void onPackageRemoved(String packageName, int uid) {
                UserHandle user = new UserHandle(getChangingUserId());
                final int n = mListeners.beginBroadcast();
                for (int i = 0; i < n; i++) {
                    IOnAppsChangedListener listener = mListeners.getBroadcastItem(i);
                    UserHandle listeningUser = (UserHandle) mListeners.getBroadcastCookie(i);
                    if (!isEnabledProfileOf(user, listeningUser, "onPackageRemoved")) continue;
                    try {
                        listener.onPackageRemoved(user, packageName);
                    } catch (RemoteException re) {
                        Slog.d(TAG, "Callback failed ", re);
                    }
                }
                mListeners.finishBroadcast();

                super.onPackageRemoved(packageName, uid);
            }

            @Override
            public void onPackageModified(String packageName) {
                UserHandle user = new UserHandle(getChangingUserId());
                final int n = mListeners.beginBroadcast();
                for (int i = 0; i < n; i++) {
                    IOnAppsChangedListener listener = mListeners.getBroadcastItem(i);
                    UserHandle listeningUser = (UserHandle) mListeners.getBroadcastCookie(i);
                    if (!isEnabledProfileOf(user, listeningUser, "onPackageModified")) continue;
                    try {
                        listener.onPackageChanged(user, packageName);
                    } catch (RemoteException re) {
                        Slog.d(TAG, "Callback failed ", re);
                    }
                }
                mListeners.finishBroadcast();

                super.onPackageModified(packageName);
            }

            @Override
            public void onPackagesAvailable(String[] packages) {
                UserHandle user = new UserHandle(getChangingUserId());
                final int n = mListeners.beginBroadcast();
                for (int i = 0; i < n; i++) {
                    IOnAppsChangedListener listener = mListeners.getBroadcastItem(i);
                    UserHandle listeningUser = (UserHandle) mListeners.getBroadcastCookie(i);
                    if (!isEnabledProfileOf(user, listeningUser, "onPackagesAvailable")) continue;
                    try {
                        listener.onPackagesAvailable(user, packages, isReplacing());
                    } catch (RemoteException re) {
                        Slog.d(TAG, "Callback failed ", re);
                    }
                }
                mListeners.finishBroadcast();

                super.onPackagesAvailable(packages);
            }

            @Override
            public void onPackagesUnavailable(String[] packages) {
                UserHandle user = new UserHandle(getChangingUserId());
                final int n = mListeners.beginBroadcast();
                for (int i = 0; i < n; i++) {
                    IOnAppsChangedListener listener = mListeners.getBroadcastItem(i);
                    UserHandle listeningUser = (UserHandle) mListeners.getBroadcastCookie(i);
                    if (!isEnabledProfileOf(user, listeningUser, "onPackagesUnavailable")) continue;
                    try {
                        listener.onPackagesUnavailable(user, packages, isReplacing());
                    } catch (RemoteException re) {
                        Slog.d(TAG, "Callback failed ", re);
                    }
                }
                mListeners.finishBroadcast();

                super.onPackagesUnavailable(packages);
            }

            @Override
            public void onPackagesSuspended(String[] packages) {
                UserHandle user = new UserHandle(getChangingUserId());
                final int n = mListeners.beginBroadcast();
                for (int i = 0; i < n; i++) {
                    IOnAppsChangedListener listener = mListeners.getBroadcastItem(i);
                    UserHandle listeningUser = (UserHandle) mListeners.getBroadcastCookie(i);
                    if (!isEnabledProfileOf(user, listeningUser, "onPackagesSuspended")) continue;
                    try {
                        listener.onPackagesSuspended(user, packages);
                    } catch (RemoteException re) {
                        Slog.d(TAG, "Callback failed ", re);
                    }
                }
                mListeners.finishBroadcast();

                super.onPackagesSuspended(packages);
            }

            @Override
            public void onPackagesUnsuspended(String[] packages) {
                UserHandle user = new UserHandle(getChangingUserId());
                final int n = mListeners.beginBroadcast();
                for (int i = 0; i < n; i++) {
                    IOnAppsChangedListener listener = mListeners.getBroadcastItem(i);
                    UserHandle listeningUser = (UserHandle) mListeners.getBroadcastCookie(i);
                    if (!isEnabledProfileOf(user, listeningUser, "onPackagesUnsuspended")) continue;
                    try {
                        listener.onPackagesUnsuspended(user, packages);
                    } catch (RemoteException re) {
                        Slog.d(TAG, "Callback failed ", re);
                    }
                }
                mListeners.finishBroadcast();

                super.onPackagesUnsuspended(packages);
            }

            @Override
            public void onShortcutChanged(@NonNull String packageName,
                    @NonNull List<ShortcutInfo> shortcuts, @UserIdInt int userId) {
                final UserHandle user = UserHandle.of(userId);

                final int n = mListeners.beginBroadcast();
                for (int i = 0; i < n; i++) {
                    IOnAppsChangedListener listener = mListeners.getBroadcastItem(i);
                    UserHandle listeningUser = (UserHandle) mListeners.getBroadcastCookie(i);
                    if (!isEnabledProfileOf(user, listeningUser, "onShortcutChanged")) continue;
                    try {
                        listener.onShortcutChanged(user, packageName,
                                new ParceledListSlice<>(shortcuts));
                    } catch (RemoteException re) {
                        Slog.d(TAG, "Callback failed ", re);
                    }
                }
                mListeners.finishBroadcast();
            }
        }

        class PackageCallbackList<T extends IInterface> extends RemoteCallbackList<T> {
            @Override
            public void onCallbackDied(T callback, Object cookie) {
                checkCallbackCount();
            }
        }
    }
}
