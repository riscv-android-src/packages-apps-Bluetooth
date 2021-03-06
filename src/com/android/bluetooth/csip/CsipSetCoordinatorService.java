/*
 * Copyright 2021 HIMSA II K/S - www.himsa.com.
 * Represented by EHIMA - www.ehima.com
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

package com.android.bluetooth.csip;

import static android.Manifest.permission.BLUETOOTH_CONNECT;
import static android.Manifest.permission.BLUETOOTH_PRIVILEGED;

import android.annotation.CallbackExecutor;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.bluetooth.BluetoothCsipSetCoordinator;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothUuid;
import android.bluetooth.IBluetoothCsipSetCoordinator;
import android.bluetooth.IBluetoothCsipSetCoordinatorCallback;
import android.bluetooth.IBluetoothCsipSetCoordinatorLockCallback;
import android.content.AttributionSource;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.HandlerThread;
import android.os.ParcelUuid;
import android.os.RemoteException;
import android.util.Log;
import android.util.Pair;

import com.android.bluetooth.Utils;
import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.btservice.ProfileService;
import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

/**
 * Provides Bluetooth CSIP Set Coordinator profile, as a service.
 * @hide
 */
public class CsipSetCoordinatorService extends ProfileService {
    private static final boolean DBG = false;
    private static final String TAG = "CsipSetCoordinatorService";

    // Upper limit of all CSIP devices: Bonded or Connected
    private static final int MAX_CSIS_STATE_MACHINES = 10;
    private static CsipSetCoordinatorService sCsipSetCoordinatorService;

    private AdapterService mAdapterService;
    private HandlerThread mStateMachinesThread;
    private BluetoothDevice mPreviousAudioDevice;

    @VisibleForTesting CsipSetCoordinatorNativeInterface mCsipSetCoordinatorNativeInterface;

    private final Map<BluetoothDevice, CsipSetCoordinatorStateMachine> mStateMachines =
            new HashMap<>();

    private final Map<Integer, ParcelUuid> mGroupIdToUuidMap = new HashMap<>();
    private final Map<BluetoothDevice, Set<Integer>> mDeviceGroupIdMap = new ConcurrentHashMap<>();
    private final Map<Integer, Integer> mGroupIdToGroupSize = new HashMap<>();
    private final Map<ParcelUuid, Map<Executor, IBluetoothCsipSetCoordinatorCallback>> mCallbacks =
            new HashMap<>();
    private final Map<Integer, Pair<UUID, IBluetoothCsipSetCoordinatorLockCallback>> mLocks =
            new ConcurrentHashMap<>();

    private BroadcastReceiver mBondStateChangedReceiver;
    private BroadcastReceiver mConnectionStateChangedReceiver;

    @Override
    protected IProfileServiceBinder initBinder() {
        return new BluetoothCsisBinder(this);
    }

    @Override
    protected void create() {
        if (DBG) {
            Log.d(TAG, "create()");
        }
    }

    @Override
    protected boolean start() {
        if (DBG) {
            Log.d(TAG, "start()");
        }
        if (sCsipSetCoordinatorService != null) {
            throw new IllegalStateException("start() called twice");
        }

        // Get AdapterService, CsipSetCoordinatorNativeInterface.
        // None of them can be null.
        mAdapterService = Objects.requireNonNull(AdapterService.getAdapterService(),
                "AdapterService cannot be null when CsipSetCoordinatorService starts");
        mCsipSetCoordinatorNativeInterface = Objects.requireNonNull(
                CsipSetCoordinatorNativeInterface.getInstance(),
                "CsipSetCoordinatorNativeInterface cannot be null when"
                .concat("CsipSetCoordinatorService starts"));

        // Start handler thread for state machines
        mStateMachines.clear();
        mStateMachinesThread = new HandlerThread("CsipSetCoordinatorService.StateMachines");
        mStateMachinesThread.start();

        // Setup broadcast receivers
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        mBondStateChangedReceiver = new BondStateChangedReceiver();
        registerReceiver(mBondStateChangedReceiver, filter);
        filter = new IntentFilter();
        filter.addAction(BluetoothCsipSetCoordinator.ACTION_CSIS_CONNECTION_STATE_CHANGED);
        mConnectionStateChangedReceiver = new ConnectionStateChangedReceiver();
        registerReceiver(mConnectionStateChangedReceiver, filter);

        // Mark service as started
        setCsipSetCoordinatorService(this);

        // Initialize native interface
        mCsipSetCoordinatorNativeInterface.init();

        return true;
    }

    @Override
    protected boolean stop() {
        if (DBG) {
            Log.d(TAG, "stop()");
        }
        if (sCsipSetCoordinatorService == null) {
            Log.w(TAG, "stop() called before start()");
            return true;
        }

        // Cleanup native interface
        mCsipSetCoordinatorNativeInterface.cleanup();
        mCsipSetCoordinatorNativeInterface = null;

        // Mark service as stopped
        setCsipSetCoordinatorService(null);

        // Unregister broadcast receivers
        unregisterReceiver(mBondStateChangedReceiver);
        mBondStateChangedReceiver = null;
        unregisterReceiver(mConnectionStateChangedReceiver);
        mConnectionStateChangedReceiver = null;

        // Destroy state machines and stop handler thread
        synchronized (mStateMachines) {
            for (CsipSetCoordinatorStateMachine sm : mStateMachines.values()) {
                sm.doQuit();
                sm.cleanup();
            }
            mStateMachines.clear();
        }

        if (mStateMachinesThread != null) {
            mStateMachinesThread.quitSafely();
            mStateMachinesThread = null;
        }

        mDeviceGroupIdMap.clear();
        mCallbacks.clear();
        mGroupIdToGroupSize.clear();
        mGroupIdToUuidMap.clear();

        mLocks.clear();

        // Clear AdapterService, CsipSetCoordinatorNativeInterface
        mCsipSetCoordinatorNativeInterface = null;
        mAdapterService = null;

        return true;
    }

    @Override
    protected void cleanup() {
        if (DBG) {
            Log.d(TAG, "cleanup()");
        }
    }

    /**
     * Get the CsipSetCoordinatorService instance
     * @return CsipSetCoordinatorService instance
     */
    public static synchronized CsipSetCoordinatorService getCsipSetCoordinatorService() {
        if (sCsipSetCoordinatorService == null) {
            Log.w(TAG, "getCsipSetCoordinatorService(): service is NULL");
            return null;
        }

        if (!sCsipSetCoordinatorService.isAvailable()) {
            Log.w(TAG, "getCsipSetCoordinatorService(): service is not available");
            return null;
        }
        return sCsipSetCoordinatorService;
    }

    private static synchronized void setCsipSetCoordinatorService(
            CsipSetCoordinatorService instance) {
        if (DBG) {
            Log.d(TAG, "setCsipSetCoordinatorService(): set to: " + instance);
        }
        sCsipSetCoordinatorService = instance;
    }

    /**
     * Connect the given Bluetooth device.
     *
     * @return true if connection is successful, false otherwise.
     */
    public boolean connect(BluetoothDevice device) {
        enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, "Need BLUETOOTH_PRIVILEGED permission");
        if (DBG) {
            Log.d(TAG, "connect(): " + device);
        }
        if (device == null) {
            return false;
        }

        if (getConnectionPolicy(device) == BluetoothProfile.CONNECTION_POLICY_FORBIDDEN) {
            return false;
        }
        ParcelUuid[] featureUuids = mAdapterService.getRemoteUuids(device);
        if (!Utils.arrayContains(featureUuids, BluetoothUuid.COORDINATED_SET)) {
            Log.e(TAG, "Cannot connect to " + device + " : Remote does not have CSIS UUID");
            return false;
        }

        synchronized (mStateMachines) {
            CsipSetCoordinatorStateMachine smConnect = getOrCreateStateMachine(device);
            if (smConnect == null) {
                Log.e(TAG, "Cannot connect to " + device + " : no state machine");
            }
            smConnect.sendMessage(CsipSetCoordinatorStateMachine.CONNECT);
        }

        return true;
    }

    /**
     * Disconnect the given Bluetooth device.
     *
     * @return true if disconnect is successful, false otherwise.
     */
    public boolean disconnect(BluetoothDevice device) {
        enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, "Need BLUETOOTH_PRIVILEGED permission");
        if (DBG) {
            Log.d(TAG, "disconnect(): " + device);
        }
        if (device == null) {
            return false;
        }
        synchronized (mStateMachines) {
            CsipSetCoordinatorStateMachine sm = getOrCreateStateMachine(device);
            if (sm != null) {
                sm.sendMessage(CsipSetCoordinatorStateMachine.DISCONNECT);
            }
        }

        return true;
    }

    public List<BluetoothDevice> getConnectedDevices() {
        enforceCallingOrSelfPermission(BLUETOOTH_CONNECT, "Need BLUETOOTH_CONNECT permission");
        synchronized (mStateMachines) {
            List<BluetoothDevice> devices = new ArrayList<>();
            for (CsipSetCoordinatorStateMachine sm : mStateMachines.values()) {
                if (sm.isConnected()) {
                    devices.add(sm.getDevice());
                }
            }
            return devices;
        }
    }

    /**
     * Check whether can connect to a peer device.
     * The check considers a number of factors during the evaluation.
     *
     * @param device the peer device to connect to
     * @return true if connection is allowed, otherwise false
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public boolean okToConnect(BluetoothDevice device) {
        // Check if this is an incoming connection in Quiet mode.
        if (mAdapterService.isQuietModeEnabled()) {
            Log.e(TAG, "okToConnect: cannot connect to " + device + " : quiet mode enabled");
            return false;
        }
        // Check connectionPolicy and accept or reject the connection.
        int connectionPolicy = getConnectionPolicy(device);
        int bondState = mAdapterService.getBondState(device);
        // Allow this connection only if the device is bonded. Any attempt to connect while
        // bonding would potentially lead to an unauthorized connection.
        if (bondState != BluetoothDevice.BOND_BONDED) {
            Log.w(TAG, "okToConnect: return false, bondState=" + bondState);
            return false;
        } else if (connectionPolicy != BluetoothProfile.CONNECTION_POLICY_UNKNOWN
                && connectionPolicy != BluetoothProfile.CONNECTION_POLICY_ALLOWED) {
            // Otherwise, reject the connection if connectionPolicy is not valid.
            Log.w(TAG, "okToConnect: return false, connectionPolicy=" + connectionPolicy);
            return false;
        }
        return true;
    }

    List<BluetoothDevice> getDevicesMatchingConnectionStates(int[] states) {
        enforceCallingOrSelfPermission(BLUETOOTH_CONNECT, "Need BLUETOOTH_CONNECT permission");
        ArrayList<BluetoothDevice> devices = new ArrayList<>();
        if (states == null) {
            return devices;
        }
        final BluetoothDevice[] bondedDevices = mAdapterService.getBondedDevices();
        if (bondedDevices == null) {
            return devices;
        }
        synchronized (mStateMachines) {
            for (BluetoothDevice device : bondedDevices) {
                final ParcelUuid[] featureUuids = device.getUuids();
                if (!Utils.arrayContains(featureUuids, BluetoothUuid.COORDINATED_SET)) {
                    continue;
                }
                int connectionState = BluetoothProfile.STATE_DISCONNECTED;
                CsipSetCoordinatorStateMachine sm = mStateMachines.get(device);
                if (sm != null) {
                    connectionState = sm.getConnectionState();
                }
                for (int state : states) {
                    if (connectionState == state) {
                        devices.add(device);
                        break;
                    }
                }
            }
            return devices;
        }
    }

    /**
     * Register for CSIS
     */
    public void registerCsisMemberObserver(@CallbackExecutor Executor executor, ParcelUuid uuid,
            IBluetoothCsipSetCoordinatorCallback callback) {
        Map<Executor, IBluetoothCsipSetCoordinatorCallback> entries =
                mCallbacks.getOrDefault(uuid, null);
        if (entries == null) {
            entries = new HashMap<>();
            entries.put(executor, callback);
            Log.d(TAG, " Csis adding new callback for " + uuid);
            mCallbacks.put(uuid, entries);
            return;
        }

        if (entries.containsKey(executor)) {
            if (entries.get(executor) == callback) {
                Log.d(TAG, " Execute and callback already added " + uuid);
                return;
            }
        }

        Log.d(TAG, " Csis adding callback " + uuid);
        entries.put(executor, callback);
    }

    /**
     * Get the list of devices that have state machines.
     *
     * @return the list of devices that have state machines
     */
    @VisibleForTesting
    List<BluetoothDevice> getDevices() {
        List<BluetoothDevice> devices = new ArrayList<>();
        synchronized (mStateMachines) {
            for (CsipSetCoordinatorStateMachine sm : mStateMachines.values()) {
                devices.add(sm.getDevice());
            }
            return devices;
        }
    }

    /**
     * Get the current connection state of the profile
     *
     * @param device is the remote bluetooth device
     * @return {@link BluetoothProfile#STATE_DISCONNECTED} if this profile is disconnected,
     * {@link BluetoothProfile#STATE_CONNECTING} if this profile is being connected,
     * {@link BluetoothProfile#STATE_CONNECTED} if this profile is connected, or
     * {@link BluetoothProfile#STATE_DISCONNECTING} if this profile is being disconnected
     */
    public int getConnectionState(BluetoothDevice device) {
        enforceCallingOrSelfPermission(BLUETOOTH_CONNECT, "Need BLUETOOTH_CONNECT permission");
        synchronized (mStateMachines) {
            CsipSetCoordinatorStateMachine sm = mStateMachines.get(device);
            if (sm == null) {
                return BluetoothProfile.STATE_DISCONNECTED;
            }
            return sm.getConnectionState();
        }
    }

    /**
     * Set connection policy of the profile and connects it if connectionPolicy is
     * {@link BluetoothProfile#CONNECTION_POLICY_ALLOWED} or disconnects if connectionPolicy is
     * {@link BluetoothProfile#CONNECTION_POLICY_FORBIDDEN}
     *
     * <p> The device should already be paired.
     * Connection policy can be one of:
     * {@link BluetoothProfile#CONNECTION_POLICY_ALLOWED},
     * {@link BluetoothProfile#CONNECTION_POLICY_FORBIDDEN},
     * {@link BluetoothProfile#CONNECTION_POLICY_UNKNOWN}
     *
     * @param device the remote device
     * @param connectionPolicy is the connection policy to set to for this profile
     * @return true on success, otherwise false
     */
    public boolean setConnectionPolicy(BluetoothDevice device, int connectionPolicy) {
        enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, "Need BLUETOOTH_PRIVILEGED permission");
        if (DBG) {
            Log.d(TAG, "Saved connectionPolicy " + device + " = " + connectionPolicy);
        }
        mAdapterService.getDatabase().setProfileConnectionPolicy(
                device, BluetoothProfile.CSIP_SET_COORDINATOR, connectionPolicy);
        if (connectionPolicy == BluetoothProfile.CONNECTION_POLICY_ALLOWED) {
            connect(device);
        } else if (connectionPolicy == BluetoothProfile.CONNECTION_POLICY_FORBIDDEN) {
            disconnect(device);
        }
        return true;
    }

    /**
     * Get the connection policy of the profile.
     *
     * @param device the remote device
     * @return connection policy of the specified device
     */
    public int getConnectionPolicy(BluetoothDevice device) {
        enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, "Need BLUETOOTH_PRIVILEGED permission");
        return mAdapterService.getDatabase().getProfileConnectionPolicy(
                device, BluetoothProfile.CSIP_SET_COORDINATOR);
    }

    /**
     * Lock a given group.
     * @param groupId group ID to lock
     * @param callback callback with the lock request result
     * @return unique lock identifier used for unlocking
     *
     * @hide
     */
    public @Nullable UUID groupLock(
            int groupId, @NonNull IBluetoothCsipSetCoordinatorLockCallback callback) {
        if (callback == null) {
            return null;
        }

        UUID uuid = UUID.randomUUID();
        synchronized (mLocks) {
            if (mLocks.containsKey(groupId)) {
                try {
                    callback.onGroupLockSet(groupId,
                            BluetoothCsipSetCoordinator.GROUP_LOCK_FAILED_LOCKED_BY_OTHER, true);
                } catch (RemoteException e) {
                    throw e.rethrowFromSystemServer();
                }
                return null;
            }

            mLocks.put(groupId, new Pair<>(uuid, callback));
        }

        mCsipSetCoordinatorNativeInterface.groupLockSet(groupId, true);
        return uuid;
    }

    /**
     * Unlock a given group.
     * @param lockUuid unique lock identifier used for unlocking
     *
     * @hide
     */
    public void groupUnlock(@NonNull UUID lockUuid) {
        if (lockUuid == null) {
            return;
        }

        synchronized (mLocks) {
            for (Map.Entry<Integer, Pair<UUID, IBluetoothCsipSetCoordinatorLockCallback>> entry :
                    mLocks.entrySet()) {
                Pair<UUID, IBluetoothCsipSetCoordinatorLockCallback> uuidCbPair = entry.getValue();
                if (uuidCbPair.first.equals(lockUuid)) {
                    mCsipSetCoordinatorNativeInterface.groupLockSet(entry.getKey(), false);
                    return;
                }
            }
        }
    }

    /**
     * Get collection of group IDs for a given UUID
     * @param uuid
     * @return list of group IDs
     */
    public List<Integer> getAllGroupIds(ParcelUuid uuid) {
        return mGroupIdToUuidMap.entrySet()
                .stream()
                .filter(e -> uuid.equals(e.getValue()))
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }

    /**
     * Get device's groups/
     * @param device
     * @return map of group id and related uuids.
     */
    public Map<Integer, ParcelUuid> getGroupUuidMapByDevice(BluetoothDevice device) {
        Set<Integer> device_groups = mDeviceGroupIdMap.getOrDefault(device, new HashSet<>());
        return mGroupIdToUuidMap.entrySet()
                .stream()
                .filter(e -> device_groups.contains(e.getKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /**
     * Get group desired size
     * @param groupId group ID
     * @return the number of group members
     */
    public int getDesiredGroupSize(int groupId) {
        return mGroupIdToGroupSize.getOrDefault(groupId,
                IBluetoothCsipSetCoordinator.CSIS_GROUP_SIZE_UNKNOWN);
    }

    private void handleDeviceAvailable(BluetoothDevice device, int groupId, UUID uuid) {
        ParcelUuid parcel_uuid = new ParcelUuid(uuid);
        if (!getAllGroupIds(parcel_uuid).contains(groupId)) {
            mGroupIdToUuidMap.put(groupId, parcel_uuid);
        }

        if (!mDeviceGroupIdMap.containsKey(device)) {
            mDeviceGroupIdMap.put(device, new HashSet<Integer>());
        }

        Set<Integer> all_device_groups = mDeviceGroupIdMap.get(device);
        all_device_groups.add(groupId);
    }

    private void executeCallback(Executor exec, IBluetoothCsipSetCoordinatorCallback callback,
            BluetoothDevice device, int groupId) throws RemoteException {
        exec.execute(() -> {
            try {
                callback.onCsisSetMemberAvailable(device, groupId);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        });
    }

    private void handleSetMemberAvailable(BluetoothDevice device, int groupId) {
        if (!mGroupIdToUuidMap.containsKey(groupId)) {
            Log.e(TAG, " UUID not found for group id " + groupId);
            return;
        }

        if (mCallbacks.isEmpty()) {
            return;
        }

        ParcelUuid uuid = mGroupIdToUuidMap.get(groupId);
        if (mCallbacks.get(uuid) == null) {
            Log.e(TAG, " There is no clients for uuid: " + uuid);
            return;
        }

        for (Map.Entry<Executor, IBluetoothCsipSetCoordinatorCallback> entry :
                mCallbacks.get(uuid).entrySet()) {
            if (DBG) {
                Log.d(TAG, " executing " + uuid + " " + entry.getKey());
            }
            try {
                executeCallback(entry.getKey(), entry.getValue(), device, groupId);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    void handleGroupLockChanged(int groupId, int status, boolean isLocked) {
        synchronized (mLocks) {
            if (!mLocks.containsKey(groupId)) {
                return;
            }

            IBluetoothCsipSetCoordinatorLockCallback cb = mLocks.get(groupId).second;
            try {
                cb.onGroupLockSet(groupId, status, isLocked);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }

            // Unlocking invalidates the existing lock if exist
            if (!isLocked) {
                mLocks.remove(groupId);
            }
        }
    }

    void messageFromNative(CsipSetCoordinatorStackEvent stackEvent) {
        BluetoothDevice device = stackEvent.device;
        Log.d(TAG, "Message from native: " + stackEvent);

        Intent intent = null;
        int groupId = stackEvent.valueInt1;
        if (stackEvent.type == CsipSetCoordinatorStackEvent.EVENT_TYPE_DEVICE_AVAILABLE) {
            Objects.requireNonNull(device, "Device should never be null, event: " + stackEvent);

            intent = new Intent(BluetoothCsipSetCoordinator.ACTION_CSIS_DEVICE_AVAILABLE);
            intent.putExtra(BluetoothDevice.EXTRA_DEVICE, stackEvent.device);
            intent.putExtra(BluetoothCsipSetCoordinator.EXTRA_CSIS_GROUP_ID, groupId);
            intent.putExtra(
                    BluetoothCsipSetCoordinator.EXTRA_CSIS_GROUP_SIZE, stackEvent.valueInt2);
            intent.putExtra(
                    BluetoothCsipSetCoordinator.EXTRA_CSIS_GROUP_TYPE_UUID, stackEvent.valueUuid1);

            handleDeviceAvailable(device, groupId, stackEvent.valueUuid1);
        } else if (stackEvent.type
                == CsipSetCoordinatorStackEvent.EVENT_TYPE_SET_MEMBER_AVAILABLE) {
            Objects.requireNonNull(device, "Device should never be null, event: " + stackEvent);
            /* Notify registered parties */
            handleSetMemberAvailable(device, stackEvent.valueInt1);

            /* Sent intent as well */
            intent = new Intent(BluetoothCsipSetCoordinator.ACTION_CSIS_SET_MEMBER_AVAILABLE);
            intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
            intent.putExtra(BluetoothCsipSetCoordinator.EXTRA_CSIS_GROUP_ID, groupId);
        } else if (stackEvent.type == CsipSetCoordinatorStackEvent.EVENT_TYPE_GROUP_LOCK_CHANGED) {
            int lock_status = stackEvent.valueInt2;
            boolean lock_state = stackEvent.valueBool1;
            handleGroupLockChanged(groupId, lock_status, lock_state);
        }

        if (intent != null) {
            intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT
                    | Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND);
            sendBroadcast(intent, BLUETOOTH_PRIVILEGED);
        }

        synchronized (mStateMachines) {
            CsipSetCoordinatorStateMachine sm = mStateMachines.get(device);

            if (stackEvent.type
                    == CsipSetCoordinatorStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED) {
                if (sm == null) {
                    switch (stackEvent.valueInt1) {
                        case CsipSetCoordinatorStackEvent.CONNECTION_STATE_CONNECTED:
                        case CsipSetCoordinatorStackEvent.CONNECTION_STATE_CONNECTING:
                            sm = getOrCreateStateMachine(device);
                            break;
                        default:
                            break;
                    }
                }

                if (sm == null) {
                    Log.e(TAG, "Cannot process stack event: no state machine: " + stackEvent);
                    return;
                }
                sm.sendMessage(CsipSetCoordinatorStateMachine.STACK_EVENT, stackEvent);
            }
        }
    }

    private CsipSetCoordinatorStateMachine getOrCreateStateMachine(BluetoothDevice device) {
        if (device == null) {
            Log.e(TAG, "getOrCreateStateMachine failed: device cannot be null");
            return null;
        }
        synchronized (mStateMachines) {
            CsipSetCoordinatorStateMachine sm = mStateMachines.get(device);
            if (sm != null) {
                return sm;
            }
            // Limit the maximum number of state machines to avoid DoS attack
            if (mStateMachines.size() >= MAX_CSIS_STATE_MACHINES) {
                Log.e(TAG,
                        "Maximum number of CSIS state machines reached: "
                                + MAX_CSIS_STATE_MACHINES);
                return null;
            }
            if (DBG) {
                Log.d(TAG, "Creating a new state machine for " + device);
            }
            sm = CsipSetCoordinatorStateMachine.make(device, this,
                    mCsipSetCoordinatorNativeInterface, mStateMachinesThread.getLooper());
            mStateMachines.put(device, sm);
            return sm;
        }
    }

    // Remove state machine if the bonding for a device is removed
    private class BondStateChangedReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(intent.getAction())) {
                return;
            }
            int state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR);
            BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            Objects.requireNonNull(device, "ACTION_BOND_STATE_CHANGED with no EXTRA_DEVICE");
            bondStateChanged(device, state);
        }
    }

    /**
     * Process a change in the bonding state for a device.
     *
     * @param device the device whose bonding state has changed
     * @param bondState the new bond state for the device. Possible values are:
     * {@link BluetoothDevice#BOND_NONE},
     * {@link BluetoothDevice#BOND_BONDING},
     * {@link BluetoothDevice#BOND_BONDED}.
     */
    @VisibleForTesting
    void bondStateChanged(BluetoothDevice device, int bondState) {
        if (DBG) {
            Log.d(TAG, "Bond state changed for device: " + device + " state: " + bondState);
        }
        // Remove state machine if the bonding for a device is removed
        if (bondState != BluetoothDevice.BOND_NONE) {
            return;
        }

        synchronized (mStateMachines) {
            CsipSetCoordinatorStateMachine sm = mStateMachines.get(device);
            if (sm == null) {
                return;
            }
            if (sm.getConnectionState() != BluetoothProfile.STATE_DISCONNECTED) {
                return;
            }
            removeStateMachine(device);
        }
    }

    private void removeStateMachine(BluetoothDevice device) {
        synchronized (mStateMachines) {
            CsipSetCoordinatorStateMachine sm = mStateMachines.get(device);
            if (sm == null) {
                Log.w(TAG,
                        "removeStateMachine: device " + device + " does not have a state machine");
                return;
            }
            Log.i(TAG, "removeStateMachine: removing state machine for device: " + device);
            sm.doQuit();
            sm.cleanup();
            mStateMachines.remove(device);
        }
    }

    @VisibleForTesting
    synchronized void connectionStateChanged(BluetoothDevice device, int fromState, int toState) {
        if ((device == null) || (fromState == toState)) {
            Log.e(TAG,
                    "connectionStateChanged: unexpected invocation. device=" + device
                            + " fromState=" + fromState + " toState=" + toState);
            return;
        }

        // Check if the device is disconnected - if unbond, remove the state machine
        if (toState == BluetoothProfile.STATE_DISCONNECTED) {
            int bondState = mAdapterService.getBondState(device);
            if (bondState == BluetoothDevice.BOND_NONE) {
                if (DBG) {
                    Log.d(TAG, device + " is unbond. Remove state machine");
                }
                removeStateMachine(device);
            }
        }
    }

    private class ConnectionStateChangedReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!BluetoothCsipSetCoordinator.ACTION_CSIS_CONNECTION_STATE_CHANGED.equals(
                        intent.getAction())) {
                return;
            }
            BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            int toState = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1);
            int fromState = intent.getIntExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, -1);
            connectionStateChanged(device, fromState, toState);
        }
    }

    /**
     * Binder object: must be a static class or memory leak may occur
     */
    @VisibleForTesting
    static class BluetoothCsisBinder
            extends IBluetoothCsipSetCoordinator.Stub implements IProfileServiceBinder {
        private CsipSetCoordinatorService mService;

        private CsipSetCoordinatorService getService(AttributionSource source) {
            if (!Utils.checkCallerIsSystemOrActiveUser(TAG)
                    || !Utils.checkServiceAvailable(mService, TAG)) {
                return null;
            }

            return mService;
        }

        BluetoothCsisBinder(CsipSetCoordinatorService svc) {
            mService = svc;
        }

        @Override
        public void cleanup() {
            mService = null;
        }

        @Override
        public boolean connect(BluetoothDevice device, AttributionSource source) {
            CsipSetCoordinatorService service = getService(source);
            if (service == null) {
                return false;
            }
            return service.connect(device);
        }

        @Override
        public boolean disconnect(BluetoothDevice device, AttributionSource source) {
            CsipSetCoordinatorService service = getService(source);
            if (service == null) {
                return false;
            }
            return service.disconnect(device);
        }

        @Override
        public List<BluetoothDevice> getConnectedDevices(AttributionSource source) {
            CsipSetCoordinatorService service = getService(source);
            if (service == null) {
                return new ArrayList<>();
            }
            return service.getConnectedDevices();
        }

        @Override
        public List<BluetoothDevice> getDevicesMatchingConnectionStates(int[] states,
                AttributionSource source) {
            CsipSetCoordinatorService service = getService(source);
            if (service == null) {
                return new ArrayList<>();
            }
            return service.getDevicesMatchingConnectionStates(states);
        }

        @Override
        public int getConnectionState(BluetoothDevice device, AttributionSource source) {
            CsipSetCoordinatorService service = getService(source);
            if (service == null) {
                return BluetoothProfile.STATE_DISCONNECTED;
            }
            return service.getConnectionState(device);
        }

        @Override
        public boolean setConnectionPolicy(BluetoothDevice device, int connectionPolicy,
                AttributionSource source) {
            CsipSetCoordinatorService service = getService(source);
            if (service == null) {
                return false;
            }
            return service.setConnectionPolicy(device, connectionPolicy);
        }

        @Override
        public int getConnectionPolicy(BluetoothDevice device, AttributionSource source) {
            CsipSetCoordinatorService service = getService(source);
            if (service == null) {
                return BluetoothProfile.CONNECTION_POLICY_UNKNOWN;
            }
            return service.getConnectionPolicy(device);
        }

        @Override
        public ParcelUuid groupLock(
                int groupId, @NonNull IBluetoothCsipSetCoordinatorLockCallback callback,
                AttributionSource source) {
            CsipSetCoordinatorService service = getService(source);
            if (service == null) {
                return null;
            }
            UUID lockUuid = service.groupLock(groupId, callback);
            return lockUuid == null ? null : new ParcelUuid(lockUuid);
        }

        @Override
        public void groupUnlock(@NonNull ParcelUuid lockUuid, AttributionSource source) {
            CsipSetCoordinatorService service = getService(source);
            if (service == null) {
                return;
            }
            service.groupUnlock(lockUuid.getUuid());
        }

        @Override
        public List<Integer> getAllGroupIds(ParcelUuid uuid, AttributionSource source) {
            CsipSetCoordinatorService service = getService(source);
            if (service == null) {
                return new ArrayList<Integer>();
            }

            return service.getAllGroupIds(uuid);
        }

        @Override
        public Map<Integer, ParcelUuid> getGroupUuidMapByDevice(BluetoothDevice device,
                AttributionSource source) {
            CsipSetCoordinatorService service = getService(source);
            if (service == null) {
                return null;
            }

            return service.getGroupUuidMapByDevice(device);
        }

        @Override
        public int getDesiredGroupSize(int groupId, AttributionSource source) {
            CsipSetCoordinatorService service = getService(source);
            if (service == null) {
                return IBluetoothCsipSetCoordinator.CSIS_GROUP_SIZE_UNKNOWN;
            }

            return service.getDesiredGroupSize(groupId);
        }
    }

    @Override
    public void dump(StringBuilder sb) {
        super.dump(sb);
        for (CsipSetCoordinatorStateMachine sm : mStateMachines.values()) {
            sm.dump(sb);
        }
    }
}
