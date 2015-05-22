/**
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

package com.android.server.fingerprint;

import android.app.ActivityManager;
import android.app.ActivityManagerNative;
import android.app.AppOpsManager;
import android.app.IUserSwitchObserver;
import android.content.ContentResolver;
import android.content.Context;
import android.os.Binder;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.IRemoteCallback;
import android.os.Looper;
import android.os.MessageQueue;
import android.os.RemoteException;
import android.os.SELinux;
import android.os.ServiceManager;
import android.util.Slog;

import com.android.server.SystemService;

import android.hardware.fingerprint.Fingerprint;
import android.hardware.fingerprint.FingerprintManager;
import android.hardware.fingerprint.IFingerprintService;
import android.hardware.fingerprint.IFingerprintDaemon;
import android.hardware.fingerprint.IFingerprintDaemonCallback;
import android.hardware.fingerprint.IFingerprintServiceReceiver;

import static android.Manifest.permission.MANAGE_FINGERPRINT;
import static android.Manifest.permission.USE_FINGERPRINT;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * A service to manage multiple clients that want to access the fingerprint HAL API.
 * The service is responsible for maintaining a list of clients and dispatching all
 * fingerprint -related events.
 *
 * @hide
 */
public class FingerprintService extends SystemService implements IBinder.DeathRecipient {
    private static final String TAG = "FingerprintService";
    private static final boolean DEBUG = true;
    private static final String FP_DATA_DIR = "fpdata";
    private static final String FINGERPRINTD = "android.hardware.fingerprint.IFingerprintDaemon";
    private static final int MSG_USER_SWITCHING = 10;
    private static final int ENROLLMENT_TIMEOUT_MS = 60 * 1000; // 1 minute

    private ClientMonitor mAuthClient = null;
    private ClientMonitor mEnrollClient = null;
    private ClientMonitor mRemoveClient = null;
    private final AppOpsManager mAppOps;

    // Message types. Used internally to dispatch messages to the correct callback.
    // Must agree with the list in fingerprint.h
    private static final int FINGERPRINT_ERROR = -1;
    private static final int FINGERPRINT_ACQUIRED = 1;
    private static final int FINGERPRINT_TEMPLATE_ENROLLING = 3;
    private static final int FINGERPRINT_TEMPLATE_REMOVED = 4;
    private static final int FINGERPRINT_AUTHENTICATED = 5;
    private static final long MS_PER_SEC = 1000;
    private static final long FAIL_LOCKOUT_TIMEOUT_MS = 30*1000;
    private static final int MAX_FAILED_ATTEMPTS = 5;

    Handler mHandler = new Handler() {
        public void handleMessage(android.os.Message msg) {
            switch (msg.what) {
                case MSG_USER_SWITCHING:
                    handleUserSwitching(msg.arg1);
                    break;

                default:
                    Slog.w(TAG, "Unknown message:" + msg.what);
            }
        }
    };

    private final FingerprintUtils mFingerprintUtils = FingerprintUtils.getInstance();
    private Context mContext;
    private long mHalDeviceId;
    private int mFailedAttempts;
    private IFingerprintDaemon mDaemon;

    private final Runnable mLockoutReset = new Runnable() {
        @Override
        public void run() {
            resetFailedAttempts();
        }
    };

    public FingerprintService(Context context) {
        super(context);
        mContext = context;
        mAppOps = context.getSystemService(AppOpsManager.class);
    }

    @Override
    public void binderDied() {
        Slog.v(TAG, "fingerprintd died");
        mDaemon = null;
    }

    public IFingerprintDaemon getFingerprintDaemon() {
        if (mDaemon == null) {
            mDaemon = IFingerprintDaemon.Stub.asInterface(ServiceManager.getService(FINGERPRINTD));
            if (mDaemon == null) {
                Slog.w(TAG, "fingerprind service not available");
            } else {
                try {
                    mDaemon.asBinder().linkToDeath(this, 0);
                }   catch (RemoteException e) {
                    Slog.w(TAG, "caught remote exception in linkToDeath: ", e);
                    mDaemon = null; // try again!
                }
            }
        }
        return mDaemon;
    }

    protected void dispatchEnumerate(long deviceId, int[] fingerIds, int[] groupIds) {
        if (fingerIds.length != groupIds.length) {
            Slog.w(TAG, "fingerIds and groupIds differ in length: f[]="
                    + fingerIds + ", g[]=" + groupIds);
            return;
        }
        if (DEBUG) Slog.w(TAG, "Enumerate: f[]=" + fingerIds + ", g[]=" + groupIds);
        // TODO: update fingerprint/name pairs
    }

    protected void dispatchRemoved(long deviceId, int fingerId, int groupId) {
        final ClientMonitor client = mRemoveClient;
        if (fingerId != 0) {
            ContentResolver res = mContext.getContentResolver();
            removeTemplateForUser(mRemoveClient, fingerId);
        }
        if (client != null && client.sendRemoved(fingerId, groupId)) {
            removeClient(mRemoveClient);
        }
    }

    protected void dispatchError(long deviceId, int error) {
        if (mEnrollClient != null) {
            final IBinder token = mEnrollClient.token;
            if (mEnrollClient.sendError(error)) {
                stopEnrollment(token, false);
            }
        } else if (mAuthClient != null) {
            final IBinder token = mAuthClient.token;
            if (mAuthClient.sendError(error)) {
                stopAuthentication(token, false);
            }
        } else if (mRemoveClient != null) {
            if (mRemoveClient.sendError(error)) removeClient(mRemoveClient);
        }
    }

    protected void dispatchAuthenticated(long deviceId, int fingerId, int groupId) {
        if (mAuthClient != null) {
            final IBinder token = mAuthClient.token;
            if (mAuthClient.sendAuthenticated(fingerId, groupId)) {
                stopAuthentication(token, false);
                removeClient(mAuthClient);
            }
        }
    }

    protected void dispatchAcquired(long deviceId, int acquiredInfo) {
        if (mEnrollClient != null) {
            if (mEnrollClient.sendAcquired(acquiredInfo)) {
                removeClient(mEnrollClient);
            }
        } else if (mAuthClient != null) {
            if (mAuthClient.sendAcquired(acquiredInfo)) {
                removeClient(mAuthClient);
            }
        }

    }

    void handleUserSwitching(int userId) {
        updateActiveGroup(userId);
    }

    protected void dispatchEnrollResult(long deviceId, int fingerId, int groupId, int remaining) {
        if (mEnrollClient != null) {
            if (mEnrollClient.sendEnrollResult(fingerId, groupId, remaining)) {
                if (remaining == 0) {
                    ContentResolver res = mContext.getContentResolver();
                    addTemplateForUser(mEnrollClient, fingerId);
                    removeClient(mEnrollClient);
                }
            }
        }
    }

    private void removeClient(ClientMonitor client) {
        if (client == null) return;
        client.destroy();
        if (client == mAuthClient) {
            mAuthClient = null;
        } else if (client == mEnrollClient) {
            mEnrollClient = null;
        } else if (client == mRemoveClient) {
            mRemoveClient = null;
        }
    }

    private boolean inLockoutMode() {
        return mFailedAttempts > MAX_FAILED_ATTEMPTS;
    }

    private void resetFailedAttempts() {
        if (DEBUG && inLockoutMode()) {
            Slog.v(TAG, "Reset fingerprint lockout");
        }
        mFailedAttempts = 0;
    }

    private boolean handleFailedAttempt(ClientMonitor clientMonitor) {
        mFailedAttempts++;
        if (mFailedAttempts > MAX_FAILED_ATTEMPTS) {
            // Failing multiple times will continue to push out the lockout time.
            mHandler.removeCallbacks(mLockoutReset);
            mHandler.postDelayed(mLockoutReset, FAIL_LOCKOUT_TIMEOUT_MS);
            if (clientMonitor != null
                    && !clientMonitor.sendError(FingerprintManager.FINGERPRINT_ERROR_LOCKOUT)) {
                Slog.w(TAG, "Cannot send lockout message to client");
            }
            return true;
        }
        return false;
    }

    private void removeTemplateForUser(ClientMonitor clientMonitor, int fingerId) {
        mFingerprintUtils.removeFingerprintIdForUser(mContext, fingerId, clientMonitor.userId);
    }

    private void addTemplateForUser(ClientMonitor clientMonitor, int fingerId) {
        mFingerprintUtils.addFingerprintForUser(mContext, fingerId, clientMonitor.userId);
    }

    void startEnrollment(IBinder token, byte[] cryptoToken, int groupId,
            IFingerprintServiceReceiver receiver, int flags) {
        IFingerprintDaemon daemon = getFingerprintDaemon();
        if (daemon == null) {
            Slog.w(TAG, "enroll: no fingeprintd!");
            return;
        }
        stopPendingOperations();
        mEnrollClient = new ClientMonitor(token, receiver, groupId);
        final int timeout = (int) (ENROLLMENT_TIMEOUT_MS / MS_PER_SEC);
        try {
            final int result = daemon.enroll(cryptoToken, groupId, timeout);
            if (result != 0) {
                Slog.w(TAG, "startEnroll failed, result=" + result);
            }
        } catch (RemoteException e) {
            Slog.e(TAG, "startEnroll failed", e);
        }
    }

    public long startPreEnroll(IBinder token) {
        IFingerprintDaemon daemon = getFingerprintDaemon();
        if (daemon == null) {
            Slog.w(TAG, "startPreEnroll: no fingeprintd!");
            return 0;
        }
        try {
            return daemon.preEnroll();
        } catch (RemoteException e) {
            Slog.e(TAG, "startPreEnroll failed", e);
        }
        return 0;
    }

    private void stopPendingOperations() {
        if (mEnrollClient != null) {
            stopEnrollment(mEnrollClient.token, true);
        }
        if (mAuthClient != null) {
            stopAuthentication(mAuthClient.token, true);
        }
        // mRemoveClient is allowed to continue
    }

    void stopEnrollment(IBinder token, boolean notify) {
        IFingerprintDaemon daemon = getFingerprintDaemon();
        if (daemon == null) {
            Slog.w(TAG, "stopEnrollment: no fingeprintd!");
            return;
        }
        final ClientMonitor client = mEnrollClient;
        if (client == null || client.token != token) return;
        try {
            int result = daemon.cancelEnrollment();
            if (result != 0) {
                Slog.w(TAG, "startEnrollCancel failed, result = " + result);
            }
        } catch (RemoteException e) {
            Slog.e(TAG, "stopEnrollment failed", e);
        }
        if (notify) {
            client.sendError(FingerprintManager.FINGERPRINT_ERROR_CANCELED);
        }
        removeClient(mEnrollClient);
    }

    void startAuthentication(IBinder token, long opId, int groupId,
            IFingerprintServiceReceiver receiver, int flags) {
        IFingerprintDaemon daemon = getFingerprintDaemon();
        if (daemon == null) {
            Slog.w(TAG, "startAuthentication: no fingeprintd!");
            return;
        }
        stopPendingOperations();
        mAuthClient = new ClientMonitor(token, receiver, groupId);
        if (inLockoutMode()) {
            Slog.v(TAG, "In lockout mode; disallowing authentication");
            if (!mAuthClient.sendError(FingerprintManager.FINGERPRINT_ERROR_LOCKOUT)) {
                Slog.w(TAG, "Cannot send timeout message to client");
            }
            mAuthClient = null;
            return;
        }
        final int timeout = (int) (ENROLLMENT_TIMEOUT_MS / MS_PER_SEC);
        try {
            final int result = daemon.authenticate(opId, groupId);
            if (result != 0) {
                Slog.w(TAG, "startAuthentication failed, result=" + result);
            }
        } catch (RemoteException e) {
            Slog.e(TAG, "startAuthentication failed", e);
        }
    }

    void stopAuthentication(IBinder token, boolean notify) {
        IFingerprintDaemon daemon = getFingerprintDaemon();
        if (daemon == null) {
            Slog.w(TAG, "stopAuthentication: no fingeprintd!");
            return;
        }
        final ClientMonitor client = mAuthClient;
        if (client == null || client.token != token) return;
        try {
            int result = daemon.cancelAuthentication();
            if (result != 0) {
                Slog.w(TAG, "stopAuthentication failed, result=" + result);
            }
        } catch (RemoteException e) {
            Slog.e(TAG, "stopAuthentication failed", e);
        }
        if (notify) {
            client.sendError(FingerprintManager.FINGERPRINT_ERROR_CANCELED);
        }
        removeClient(mAuthClient);
    }

    void startRemove(IBinder token, int fingerId, int userId,
            IFingerprintServiceReceiver receiver) {
        IFingerprintDaemon daemon = getFingerprintDaemon();
        if (daemon == null) {
            Slog.w(TAG, "startRemove: no fingeprintd!");
            return;
        }
        mRemoveClient = new ClientMonitor(token, receiver, userId);
        // The fingerprint template ids will be removed when we get confirmation from the HAL
        try {
            final int result = daemon.remove(fingerId, userId);
            if (result != 0) {
                Slog.w(TAG, "startRemove with id = " + fingerId + " failed, result=" + result);
            }
        } catch (RemoteException e) {
            Slog.e(TAG, "startRemove failed", e);
        }
    }

    public List<Fingerprint> getEnrolledFingerprints(int groupId) {
        return mFingerprintUtils.getFingerprintsForUser(mContext, groupId);
    }

    public boolean hasEnrolledFingerprints(int groupId) {
        return mFingerprintUtils.getFingerprintsForUser(mContext, groupId).size() > 0;
    }

    void checkPermission(String permission) {
        getContext().enforceCallingOrSelfPermission(permission,
                "Must have " + permission + " permission.");
    }

    private boolean canUseFingerprint(String opPackageName) {
        checkPermission(USE_FINGERPRINT);

        return mAppOps.noteOp(AppOpsManager.OP_USE_FINGERPRINT, Binder.getCallingUid(),
                opPackageName) == AppOpsManager.MODE_ALLOWED;
    }

    private class ClientMonitor implements IBinder.DeathRecipient {
        IBinder token;
        IFingerprintServiceReceiver receiver;
        int userId;

        public ClientMonitor(IBinder token, IFingerprintServiceReceiver receiver, int userId) {
            this.token = token;
            this.receiver = receiver;
            this.userId = userId;
            try {
                token.linkToDeath(this, 0);
            } catch (RemoteException e) {
                Slog.w(TAG, "caught remote exception in linkToDeath: ", e);
            }
        }

        public void destroy() {
            if (token != null) {
                try {
                    token.unlinkToDeath(this, 0);
                } catch (NoSuchElementException e) {
                    // TODO: remove when duplicate call bug is found
                    Slog.e(TAG, "destroy(): " + this + ":", new Exception("here"));
                }
                token = null;
            }
            receiver = null;
        }

        public void binderDied() {
            token = null;
            removeClient(this);
            receiver = null;
        }

        protected void finalize() throws Throwable {
            try {
                if (token != null) {
                    if (DEBUG) Slog.w(TAG, "removing leaked reference: " + token);
                    removeClient(this);
                }
            } finally {
                super.finalize();
            }
        }

        /*
         * @return true if we're done.
         */
        private boolean sendRemoved(int fingerId, int groupId) {
            if (receiver == null) return true; // client not listening
            try {
                receiver.onRemoved(mHalDeviceId, fingerId, groupId);
                return fingerId == 0;
            } catch (RemoteException e) {
                Slog.w(TAG, "Failed to notify Removed:", e);
            }
            return false;
        }

        /*
         * @return true if we're done.
         */
        private boolean sendEnrollResult(int fpId, int groupId, int remaining) {
            if (receiver == null) return true; // client not listening
            FingerprintUtils.vibrateFingerprintSuccess(getContext());
            try {
                receiver.onEnrollResult(mHalDeviceId, fpId, groupId, remaining);
                return remaining == 0;
            } catch (RemoteException e) {
                Slog.w(TAG, "Failed to notify EnrollResult:", e);
                return true;
            }
        }

        /*
         * @return true if we're done.
         */
        private boolean sendAuthenticated(int fpId, int groupId) {
            boolean result = false;
            if (receiver != null) {
                try {
                    receiver.onAuthenticated(mHalDeviceId, fpId, groupId);
                } catch (RemoteException e) {
                    Slog.w(TAG, "Failed to notify Authenticated:", e);
                    result = true; // client failed
                }
            } else {
                result = true; // client not listening
            }
            if (fpId <= 0) {
                FingerprintUtils.vibrateFingerprintError(getContext());
                result |= handleFailedAttempt(this);
            } else {
                FingerprintUtils.vibrateFingerprintSuccess(getContext());
                result |= true; // we have a valid fingerprint
                mLockoutReset.run();
            }
            return result;
        }

        /*
         * @return true if we're done.
         */
        private boolean sendAcquired(int acquiredInfo) {
            if (receiver == null) return true; // client not listening
            try {
                receiver.onAcquired(mHalDeviceId, acquiredInfo);
                return false; // acquisition continues...
            } catch (RemoteException e) {
                Slog.w(TAG, "Failed to invoke sendAcquired:", e);
                return true; // client failed
            }
        }

        /*
         * @return true if we're done.
         */
        private boolean sendError(int error) {
            if (receiver != null) {
                try {
                    receiver.onError(mHalDeviceId, error);
                } catch (RemoteException e) {
                    Slog.w(TAG, "Failed to invoke sendError:", e);
                }
            }
            return true; // errors always terminate progress
        }
    }

    private IFingerprintDaemonCallback mDaemonCallback = new IFingerprintDaemonCallback.Stub() {

        @Override
        public void onEnrollResult(long deviceId, int fingerId, int groupId, int remaining) {
            dispatchEnrollResult(deviceId, fingerId, groupId, remaining);
        }

        @Override
        public void onAcquired(long deviceId, int acquiredInfo) {
            dispatchAcquired(deviceId, acquiredInfo);
        }

        @Override
        public void onAuthenticated(long deviceId, int fingerId, int groupId) {
            dispatchAuthenticated(deviceId, fingerId, groupId);
        }

        @Override
        public void onError(long deviceId, int error) {
            dispatchError(deviceId, error);
        }

        @Override
        public void onRemoved(long deviceId, int fingerId, int groupId) {
            dispatchRemoved(deviceId, fingerId, groupId);
        }

        @Override
        public void onEnumerate(long deviceId, int[] fingerIds, int[] groupIds) {
            dispatchEnumerate(deviceId, fingerIds, groupIds);
        }

    };

    private final class FingerprintServiceWrapper extends IFingerprintService.Stub {
        @Override // Binder call
        public long preEnroll(IBinder token) {
            checkPermission(MANAGE_FINGERPRINT);
            return startPreEnroll(token);
        }

        @Override // Binder call
        public void enroll(final IBinder token, final byte[] cryptoToken, final int groupId,
                final IFingerprintServiceReceiver receiver, final int flags) {
            checkPermission(MANAGE_FINGERPRINT);
            final byte [] cryptoClone = Arrays.copyOf(cryptoToken, cryptoToken.length);
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    startEnrollment(token, cryptoClone, groupId, receiver, flags);
                }
            });
        }

        @Override // Binder call
        public void cancelEnrollment(final IBinder token) {
            checkPermission(MANAGE_FINGERPRINT);
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    stopEnrollment(token, true);
                }
            });
        }

        @Override // Binder call
        public void authenticate(final IBinder token, final long opId, final int groupId,
                final IFingerprintServiceReceiver receiver, final int flags, String opPackageName) {
            checkPermission(USE_FINGERPRINT);
            if (!canUseFingerprint(opPackageName)) {
                return;
            }
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    startAuthentication(token, opId, groupId, receiver, flags);
                }
            });
        }

        @Override // Binder call
        public void cancelAuthentication(final IBinder token, String opPackageName) {
            if (!canUseFingerprint(opPackageName)) {
                return;
            }
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    stopAuthentication(token, true);
                }
            });
        }

        @Override // Binder call
        public void remove(final IBinder token, final int fingerId, final int groupId,
                final IFingerprintServiceReceiver receiver) {
            checkPermission(MANAGE_FINGERPRINT); // TODO: Maybe have another permission
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    startRemove(token, fingerId, groupId, receiver);
                }
            });

        }

        @Override // Binder call
        public boolean isHardwareDetected(long deviceId, String opPackageName) {
            if (!canUseFingerprint(opPackageName)) {
                return false;
            }
            return mHalDeviceId != 0;
        }

        @Override // Binder call
        public void rename(final int fingerId, final int groupId, final String name) {
            checkPermission(MANAGE_FINGERPRINT);
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mFingerprintUtils.renameFingerprintForUser(mContext, fingerId, groupId, name);
                }
            });
        }

        @Override // Binder call
        public List<Fingerprint> getEnrolledFingerprints(int groupId, String opPackageName) {
            if (!canUseFingerprint(opPackageName)) {
                return Collections.emptyList();
            }
            return FingerprintService.this.getEnrolledFingerprints(groupId);
        }

        @Override // Binder call
        public boolean hasEnrolledFingerprints(int groupId, String opPackageName) {
            if (!canUseFingerprint(opPackageName)) {
                return false;
            }
            return FingerprintService.this.hasEnrolledFingerprints(groupId);
        }

        @Override // Binder call
        public long getAuthenticatorId(String opPackageName) {
            if (!canUseFingerprint(opPackageName)) {
                return 0;
            }
            return FingerprintService.this.getAuthenticatorId();
        }
    }

    @Override
    public void onStart() {
        publishBinderService(Context.FINGERPRINT_SERVICE, new FingerprintServiceWrapper());
        IFingerprintDaemon daemon = getFingerprintDaemon();
        if (daemon != null) {
            try {
                daemon.init(mDaemonCallback);
                mHalDeviceId = daemon.openHal();
            	updateActiveGroup(ActivityManager.getCurrentUser());
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to open fingeprintd HAL", e);
            }
        }
        if (DEBUG) Slog.v(TAG, "Fingerprint HAL id: " + mHalDeviceId);
        listenForUserSwitches();
    }

    private void updateActiveGroup(int userId) {
        IFingerprintDaemon daemon = getFingerprintDaemon();
        if (daemon != null) {
            try {
                // TODO: if this is a managed profile, use the profile parent's directory for
                // storage.
                final File systemDir = Environment.getUserSystemDirectory(userId);
                final File fpDir = new File(systemDir, FP_DATA_DIR);
                if (!fpDir.exists()) {
                    if (!fpDir.mkdir()) {
                        Slog.v(TAG, "Cannot make directory: " + fpDir.getAbsolutePath());
                        return;
                    }
                    // Calling mkdir() from this process will create a directory with our
                    // permissions (inherited from the containing dir). This command fixes
                    // the label.
                    if (!SELinux.restorecon(fpDir)) {
                        Slog.w(TAG, "Restorecons failed. Directory will have wrong label.");
                        return;
                    }
                }
                daemon.setActiveGroup(userId, fpDir.getAbsolutePath().getBytes());
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to setActiveGroup():", e);
            }
        }
    }

    private void listenForUserSwitches() {
        try {
            ActivityManagerNative.getDefault().registerUserSwitchObserver(
                new IUserSwitchObserver.Stub() {
                    @Override
                    public void onUserSwitching(int newUserId, IRemoteCallback reply) {
                        mHandler.obtainMessage(MSG_USER_SWITCHING, newUserId, 0 /* unused */)
                                .sendToTarget();
                    }
                    @Override
                    public void onUserSwitchComplete(int newUserId) throws RemoteException {
                        // Ignore.
                    }
                    @Override
                    public void onForegroundProfileSwitch(int newProfileId) {
                        // Ignore.
                    }
                });
        } catch (RemoteException e) {
            Slog.w(TAG, "Failed to listen for user switching event" ,e);
        }
    }

    public long getAuthenticatorId() {
        IFingerprintDaemon daemon = getFingerprintDaemon();
        if (daemon != null) {
            try {
                return daemon.getAuthenticatorId();
            } catch (RemoteException e) {
                Slog.e(TAG, "getAuthenticatorId failed", e);
            }
        }
        return 0;
    }

}
