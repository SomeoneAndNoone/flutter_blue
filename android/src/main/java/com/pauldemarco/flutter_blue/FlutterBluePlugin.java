// Copyright 2017, Paul DeMarco.
// All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.pauldemarco.flutter_blue;

import android.app.Activity;
import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.ParcelUuid;
import android.util.Log;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;
import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.EventChannel.EventSink;
import io.flutter.plugin.common.EventChannel.StreamHandler;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.plugin.common.PluginRegistry.RequestPermissionsResultListener;

import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;

import com.pauldemarco.flutter_blue.models.BluetoothDeviceCache;
import com.pauldemarco.flutter_blue.util.BluetoothUtils;
import com.pauldemarco.flutter_blue.util.LogUtil;
import com.pauldemarco.flutter_blue.util.ProtoMaker;

import static android.bluetooth.le.AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY;
import static android.bluetooth.le.AdvertiseSettings.ADVERTISE_TX_POWER_HIGH;


/**
 * FlutterBluePlugin
 */
public class FlutterBluePlugin implements FlutterPlugin, ActivityAware, MethodCallHandler, RequestPermissionsResultListener {
    private static final String LOG_CHANNEL = "LOG_CHANNEL";
    private static final String LOG_TYPE_ERROR = "ERROR";
    private static final String LOG_TYPE_DEBUG = "DEBUG";
    public static final String TAG = "FlutterBluePlugin";

    private Context context;
    private MethodChannel channel;
    private static final String NAMESPACE = "plugins.pauldemarco.com/flutter_blue";

    private EventChannel stateChannel;
    private BluetoothManager mBluetoothManager;
    private BluetoothAdapter mBluetoothAdapter;

    private FlutterPluginBinding pluginBinding;
    private ActivityPluginBinding activityBinding;
    private Activity activity;

    private static final int REQUEST_FINE_LOCATION_PERMISSIONS = 1452;
    static final private UUID CCCD_ID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private final Map<String, BluetoothDeviceCache> mDevices = new HashMap<>();
    private static final int untitledCompanyManufacturerId = 65535;

    // Pending call and result for startScan, in the case where permissions are needed
    private MethodCall pendingCall;
    private Result pendingResult;
    private final ArrayList<String> macDeviceScanned = new ArrayList<>();
    private boolean allowDuplicates = false;

    @Override
    public void onAttachedToEngine(@NonNull FlutterPluginBinding binding) {
        Log.i(TAG, "FlutterBlue in Native: setUpPluginInstances");
        pluginBinding = binding;
        context = pluginBinding.getApplicationContext();

        BinaryMessenger messenger = pluginBinding.getBinaryMessenger();

        channel = new MethodChannel(messenger, NAMESPACE + "/methods");
        channel.setMethodCallHandler(this);
        stateChannel = new EventChannel(messenger, NAMESPACE + "/state");
        stateChannel.setStreamHandler(stateHandler);
        mBluetoothManager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        mBluetoothAdapter = mBluetoothManager.getAdapter();
    }

    @Override
    public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
        Log.i(TAG, "FlutterBlue in Native: teardown");
        pluginBinding = null;
        context = null;
        channel.setMethodCallHandler(null);
        channel = null;
        stateChannel.setStreamHandler(null);
        stateChannel = null;
        mBluetoothAdapter = null;
        mBluetoothManager = null;
    }

    @Override
    public void onAttachedToActivity(@NonNull ActivityPluginBinding binding) {
        this.activityBinding = binding;
        this.activity = activityBinding.getActivity();
        // setup for activity listeners.
        this.activityBinding.addRequestPermissionsResultListener(this);
    }

    @Override
    public void onDetachedFromActivity() {
        activityBinding.removeRequestPermissionsResultListener(this);
        activityBinding = null;
        this.activity = null;
    }

    @Override
    public void onDetachedFromActivityForConfigChanges() {
        onDetachedFromActivity();
    }

    @Override
    public void onReattachedToActivityForConfigChanges(@NonNull ActivityPluginBinding binding) {
        onAttachedToActivity(binding);
    }


    /// START ---------------------------- METHOD CALLS FROM FLUTTER --------------------------------------
    @RequiresApi(api = Build.VERSION_CODES.M)
    @Override
    public void onMethodCall(@NonNull MethodCall call, @NonNull Result result) {
        if (mBluetoothAdapter == null && !"isAvailable".equals(call.method)) {
            result.error("bluetooth_unavailable", "the device does not have bluetooth", null);
            return;
        }

        switch (call.method) {
            case "setLogLevel": {
                int logLevelIndex = (int) call.arguments;
                LogUtil.setLogLevel(LogUtil.LogLevel.values()[logLevelIndex]);
                result.success(null);
                break;
            }

            case "state": {
                Protos.BluetoothState.Builder p = Protos.BluetoothState.newBuilder();
                try {
                    switch (mBluetoothAdapter.getState()) {
                        case BluetoothAdapter.STATE_OFF:
                            p.setState(Protos.BluetoothState.State.OFF);
                            break;
                        case BluetoothAdapter.STATE_ON:
                            p.setState(Protos.BluetoothState.State.ON);
                            break;
                        case BluetoothAdapter.STATE_TURNING_OFF:
                            p.setState(Protos.BluetoothState.State.TURNING_OFF);
                            break;
                        case BluetoothAdapter.STATE_TURNING_ON:
                            p.setState(Protos.BluetoothState.State.TURNING_ON);
                            break;
                        default:
                            p.setState(Protos.BluetoothState.State.UNKNOWN);
                            break;
                    }
                } catch (SecurityException e) {
                    p.setState(Protos.BluetoothState.State.UNAUTHORIZED);
                }
                result.success(p.build().toByteArray());
                break;
            }

            case "isAvailable": {
                result.success(mBluetoothAdapter != null);
                break;
            }

            case "enableAdapter": {
                boolean resultValue = true;
                if (!mBluetoothAdapter.isEnabled()) {
                    resultValue = mBluetoothAdapter.enable();
                }
                invokeMethodUIThread(LOG_CHANNEL, ProtoMaker.log(LOG_TYPE_DEBUG, "enable adapter result: " + resultValue).toByteArray());
                result.success(resultValue);
                break;
            }

            case "disableAdapter": {
                boolean resultValue = true;

                if (mBluetoothAdapter.isEnabled()) {
                    disconnectAllDevices();
                    resultValue = mBluetoothAdapter.disable();

                    macDeviceScanned.clear();
                }
                invokeMethodUIThread(LOG_CHANNEL, ProtoMaker.log(LOG_TYPE_DEBUG, "disable adapter result: " + resultValue).toByteArray());
                result.success(resultValue);
                break;
            }

            case "isOn": {
                result.success(mBluetoothAdapter.isEnabled());
                break;
            }

            case "startAdvertising": {
                result.success(startAdvertising(call));
                break;
            }

            case "stopAdvertising": {
                result.success(stopAdvertising());
                break;
            }

            case "getCachedDevice": {
                String mac = call.arguments();
                BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(mac);
                Protos.BluetoothDevice bluetoothDevice = ProtoMaker.from(device);
                result.success(bluetoothDevice.toByteArray());
                break;
            }

            case "startScan": {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                        != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(
                            activityBinding.getActivity(),
                            new String[]{
                                    Manifest.permission.ACCESS_FINE_LOCATION
                            },
                            REQUEST_FINE_LOCATION_PERMISSIONS);
                    pendingCall = call;
                    pendingResult = result;
                    break;
                }
                startScan(call, result);
                break;
            }

            case "stopScan": {
                stopScan();
                result.success(null);
                break;
            }

            case "getConnectedDevices": {
                List<BluetoothDevice> devices = mBluetoothManager.getConnectedDevices(BluetoothProfile.GATT);
                Protos.ConnectedDevicesResponse.Builder p = Protos.ConnectedDevicesResponse.newBuilder();
                for (BluetoothDevice d : devices) {
                    p.addDevices(ProtoMaker.from(d));
                }
                result.success(p.build().toByteArray());
                LogUtil.log(LogUtil.LogLevel.EMERGENCY, "FlutterBlue in Native: Connected devices count in BluetoothManager: " + devices.size());
                LogUtil.log(LogUtil.LogLevel.EMERGENCY, "FlutterBlue in Native: Connected devices count in cache: " + mDevices.size());
                break;
            }

            case "connect": {
                byte[] data = call.arguments();
                Protos.ConnectRequest options;
                try {
                    options = Protos.ConnectRequest.newBuilder().mergeFrom(data).build();
                } catch (InvalidProtocolBufferException e) {
                    result.error("RuntimeException", e.getMessage(), e);
                    break;
                }
                String deviceId = options.getRemoteId();
                BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(deviceId);
                boolean isConnected = mBluetoothManager.getConnectedDevices(BluetoothProfile.GATT).contains(device);

                // If device is already connected, return error
                if (mDevices.containsKey(deviceId) && isConnected) {
                    result.error("already_connected", "connection with device already exists", null);
                    return;
                }

                // If device was connected to previously but is now disconnected, attempt a reconnect
                if (mDevices.containsKey(deviceId) && !isConnected) {
                    BluetoothDeviceCache deviceCache = mDevices.get(deviceId);
                    if (deviceCache != null && deviceCache.gatt.connect()) {
                        result.success(null);
                    } else {
                        result.error("reconnect_error", "error when reconnecting to device", null);
                    }
                    return;
                }

                // New request, connect and add gattServer to Map
                BluetoothGatt gattServer;
                gattServer = device.connectGatt(context, options.getAndroidAutoConnect(), mGattCallback, BluetoothDevice.TRANSPORT_LE);

                mDevices.put(deviceId, new BluetoothDeviceCache(gattServer));
                result.success(null);
                break;
            }

            case "disconnectAll": {
                boolean hasAnyDisconnected = disconnectAllDevices();
                result.success(hasAnyDisconnected);
                break;
            }

            case "disconnect": {
                disconnectDevice((String) call.arguments);
                result.success(null);
                break;
            }

            case "deviceState": {
                String deviceId = (String) call.arguments;
                BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(deviceId);
                int state = mBluetoothManager.getConnectionState(device, BluetoothProfile.GATT);
                try {
                    result.success(ProtoMaker.from(device, state).toByteArray());
                } catch (Exception e) {
                    result.error("device_state_error", e.getMessage(), e);
                }
                break;
            }

            case "discoverServices": {
                String deviceId = (String) call.arguments;
                try {
                    BluetoothGatt gatt = BluetoothUtils.locateGatt(deviceId, mDevices);
                    if (gatt.discoverServices()) {
                        result.success(null);
                    } else {
                        result.error("discover_services_error", "unknown reason", null);
                    }
                } catch (Exception e) {
                    result.error("discover_services_error", e.getMessage(), e);
                }
                break;
            }

            case "services": {
                String deviceId = (String) call.arguments;
                try {
                    BluetoothGatt gatt = BluetoothUtils.locateGatt(deviceId, mDevices);
                    Protos.DiscoverServicesResult.Builder p = Protos.DiscoverServicesResult.newBuilder();
                    p.setRemoteId(deviceId);
                    for (BluetoothGattService s : gatt.getServices()) {
                        p.addServices(ProtoMaker.from(gatt.getDevice(), s, gatt));
                    }
                    result.success(p.build().toByteArray());
                } catch (Exception e) {
                    result.error("get_services_error", e.getMessage(), e);
                }
                break;
            }

            case "readCharacteristic": {
                byte[] data = call.arguments();
                Protos.ReadCharacteristicRequest request;
                try {
                    request = Protos.ReadCharacteristicRequest.newBuilder().mergeFrom(data).build();
                } catch (InvalidProtocolBufferException e) {
                    result.error("RuntimeException", e.getMessage(), e);
                    break;
                }

                BluetoothGatt gattServer;
                BluetoothGattCharacteristic characteristic;
                try {
                    gattServer = BluetoothUtils.locateGatt(request.getRemoteId(), mDevices);
                    characteristic = BluetoothUtils.locateCharacteristic(gattServer, request.getServiceUuid(), request.getSecondaryServiceUuid(), request.getCharacteristicUuid());
                } catch (Exception e) {
                    result.error("read_characteristic_error", e.getMessage(), null);
                    return;
                }

                if (gattServer.readCharacteristic(characteristic)) {
                    result.success(null);
                } else {
                    result.error("read_characteristic_error", "unknown reason, may occur if readCharacteristic was called before last read finished.", null);
                }
                break;
            }

            case "readDescriptor": {
                byte[] data = call.arguments();
                Protos.ReadDescriptorRequest request;
                try {
                    request = Protos.ReadDescriptorRequest.newBuilder().mergeFrom(data).build();
                } catch (InvalidProtocolBufferException e) {
                    result.error("RuntimeException", e.getMessage(), e);
                    break;
                }

                BluetoothGatt gattServer;
                BluetoothGattCharacteristic characteristic;
                BluetoothGattDescriptor descriptor;
                try {
                    gattServer = BluetoothUtils.locateGatt(request.getRemoteId(), mDevices);
                    characteristic = BluetoothUtils.locateCharacteristic(gattServer, request.getServiceUuid(), request.getSecondaryServiceUuid(), request.getCharacteristicUuid());
                    descriptor = BluetoothUtils.locateDescriptor(characteristic, request.getDescriptorUuid());
                } catch (Exception e) {
                    result.error("read_descriptor_error", e.getMessage(), null);
                    return;
                }

                if (gattServer.readDescriptor(descriptor)) {
                    result.success(null);
                } else {
                    result.error("read_descriptor_error", "unknown reason, may occur if readDescriptor was called before last read finished.", null);
                }
                break;
            }

            case "writeCharacteristic": {
                byte[] data = call.arguments();
                Protos.WriteCharacteristicRequest request;
                try {
                    request = Protos.WriteCharacteristicRequest.newBuilder().mergeFrom(data).build();
                } catch (InvalidProtocolBufferException e) {
                    result.error("RuntimeException", e.getMessage(), e);
                    break;
                }

                BluetoothGatt gattServer;
                BluetoothGattCharacteristic characteristic;
                try {
                    gattServer = BluetoothUtils.locateGatt(request.getRemoteId(), mDevices);
                    characteristic = BluetoothUtils.locateCharacteristic(gattServer, request.getServiceUuid(), request.getSecondaryServiceUuid(), request.getCharacteristicUuid());
                } catch (Exception e) {
                    result.error("write_characteristic_error", e.getMessage(), null);
                    return;
                }

                // Set characteristic to new value
                if (!characteristic.setValue(request.getValue().toByteArray())) {
                    result.error("write_characteristic_error", "could not set the local value of characteristic", null);
                }

                // Apply the correct write type
                if (request.getWriteType() == Protos.WriteCharacteristicRequest.WriteType.WITHOUT_RESPONSE) {
                    characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
                } else {
                    characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                }

                if (!gattServer.writeCharacteristic(characteristic)) {
                    result.error("write_characteristic_error", "writeCharacteristic failed", null);
                    return;
                }

                result.success(null);
                break;
            }

            case "writeDescriptor": {
                byte[] data = call.arguments();
                Protos.WriteDescriptorRequest request;
                try {
                    request = Protos.WriteDescriptorRequest.newBuilder().mergeFrom(data).build();
                } catch (InvalidProtocolBufferException e) {
                    result.error("RuntimeException", e.getMessage(), e);
                    break;
                }

                BluetoothGatt gattServer;
                BluetoothGattCharacteristic characteristic;
                BluetoothGattDescriptor descriptor;
                try {
                    gattServer = BluetoothUtils.locateGatt(request.getRemoteId(), mDevices);
                    characteristic = BluetoothUtils.locateCharacteristic(gattServer, request.getServiceUuid(), request.getSecondaryServiceUuid(), request.getCharacteristicUuid());
                    descriptor = BluetoothUtils.locateDescriptor(characteristic, request.getDescriptorUuid());
                } catch (Exception e) {
                    result.error("write_descriptor_error", e.getMessage(), null);
                    return;
                }

                // Set descriptor to new value
                if (!descriptor.setValue(request.getValue().toByteArray())) {
                    result.error("write_descriptor_error", "could not set the local value for descriptor", null);
                }

                if (!gattServer.writeDescriptor(descriptor)) {
                    result.error("write_descriptor_error", "writeCharacteristic failed", null);
                    return;
                }

                result.success(null);
                break;
            }

            case "setNotification": {
                byte[] data = call.arguments();
                Protos.SetNotificationRequest request;
                try {
                    request = Protos.SetNotificationRequest.newBuilder().mergeFrom(data).build();
                } catch (InvalidProtocolBufferException e) {
                    result.error("RuntimeException", e.getMessage(), e);
                    break;
                }

                BluetoothGatt gattServer;
                BluetoothGattCharacteristic characteristic;
                BluetoothGattDescriptor cccDescriptor;
                try {
                    gattServer = BluetoothUtils.locateGatt(request.getRemoteId(), mDevices);
                    characteristic = BluetoothUtils.locateCharacteristic(gattServer, request.getServiceUuid(), request.getSecondaryServiceUuid(), request.getCharacteristicUuid());
                    cccDescriptor = characteristic.getDescriptor(CCCD_ID);
                    if (cccDescriptor == null) {
                        throw new Exception("could not locate CCCD descriptor for characteristic: " + characteristic.getUuid().toString());
                    }
                } catch (Exception e) {
                    result.error("set_notification_error", e.getMessage(), null);
                    return;
                }

                byte[] value = null;

                if (request.getEnable()) {
                    boolean canNotify = (characteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) > 0;
                    boolean canIndicate = (characteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_INDICATE) > 0;
                    if (!canIndicate && !canNotify) {
                        result.error("set_notification_error", "the characteristic cannot notify or indicate", null);
                        return;
                    }
                    if (canIndicate) {
                        value = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE;
                    }
                    if (canNotify) {
                        value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;
                    }
                } else {
                    value = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE;
                }

                if (!gattServer.setCharacteristicNotification(characteristic, request.getEnable())) {
                    result.error("set_notification_error", "could not set characteristic notifications to :" + request.getEnable(), null);
                    return;
                }

                if (!cccDescriptor.setValue(value)) {
                    result.error("set_notification_error", "error when setting the descriptor value to: " + Arrays.toString(value), null);
                    return;
                }

                if (!gattServer.writeDescriptor(cccDescriptor)) {
                    result.error("set_notification_error", "error when writing the descriptor", null);
                    return;
                }

                result.success(null);
                break;
            }

            case "mtu": {
                String deviceId = (String) call.arguments;
                BluetoothDeviceCache cache = mDevices.get(deviceId);
                if (cache != null) {
                    Protos.MtuSizeResponse.Builder p = Protos.MtuSizeResponse.newBuilder();
                    p.setRemoteId(deviceId);
                    p.setMtu(cache.mtu);
                    result.success(p.build().toByteArray());
                } else {
                    result.error("mtu", "no instance of BluetoothGatt, have you connected first?", null);
                }
                break;
            }

            case "requestMtu": {
                byte[] data = call.arguments();
                Protos.MtuSizeRequest request;
                try {
                    request = Protos.MtuSizeRequest.newBuilder().mergeFrom(data).build();
                } catch (InvalidProtocolBufferException e) {
                    result.error("RuntimeException", e.getMessage(), e);
                    break;
                }

                BluetoothGatt gatt;
                try {
                    gatt = BluetoothUtils.locateGatt(request.getRemoteId(), mDevices);
                    int mtu = request.getMtu();
                    if (gatt.requestMtu(mtu)) {
                        result.success(null);
                    } else {
                        result.error("requestMtu", "gatt.requestMtu returned false", null);
                    }
                } catch (Exception e) {
                    result.error("requestMtu", e.getMessage(), e);
                }
                break;
            }

            default: {
                result.notImplemented();
                break;
            }
        }
    }

    boolean disconnectAllDevices() {
        boolean hasAnyDeviceRemoved = false;
        for (Map.Entry<String, BluetoothDeviceCache> entry : mDevices.entrySet()) {
            hasAnyDeviceRemoved = true;
            BluetoothDeviceCache cache = entry.getValue();
            BluetoothGatt gattServer = cache.gatt;
            LogUtil.log(LogUtil.LogLevel.DEBUG, "FlutterBlue in Native: DISCONNECTING device: " + gattServer.getDevice().getAddress());

            gattServer.disconnect();
            gattServer.close();
        }
        mDevices.clear();

        List<BluetoothDevice> devices = mBluetoothManager.getConnectedDevices(BluetoothProfile.GATT);
        for (int i = 0; i < devices.size(); i++) {
            invokeMethodUIThread(LOG_CHANNEL, ProtoMaker.log(LOG_TYPE_ERROR, "There is still connected device WHY?: " + (devices.get(i).getAddress())).toByteArray());
            LogUtil.log(LogUtil.LogLevel.DEBUG, "FlutterBlue in Native: THERE IS STILL CONNECTED device WHY?: " + (devices.get(i).getAddress()));
        }

        return hasAnyDeviceRemoved;
    }

    void disconnectDevice(String deviceId) {
        BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(deviceId);
        int state = mBluetoothManager.getConnectionState(device, BluetoothProfile.GATT);
        BluetoothDeviceCache cache = mDevices.remove(deviceId);
        if (cache != null) {
            BluetoothGatt gattServer = cache.gatt;
            gattServer.disconnect();
            if (state == BluetoothProfile.STATE_DISCONNECTED) {
                gattServer.close();
            }
        }
    }

    /// END ---------------------------- METHOD CALLS FROM DART --------------------------------------

    /// START ---------------------------- SCANNING RELATED METHODS --------------------------------------
    private void startScan(MethodCall call, Result result) {
        byte[] data = call.arguments();
        Protos.ScanSettings settings;
        try {
            settings = Protos.ScanSettings.newBuilder().mergeFrom(data).build();
            allowDuplicates = settings.getAllowDuplicates();
            macDeviceScanned.clear();
            startBluetoothLeScan(settings);
            result.success(null);
        } catch (Exception e) {
            result.error("startScan", e.getMessage(), e);
        }
    }

    private void stopScan() {
        BluetoothLeScanner scanner = mBluetoothAdapter.getBluetoothLeScanner();
        if (scanner != null) scanner.stopScan(leScanMethod());
    }

    private ScanCallback scanCallback;

    private ScanCallback leScanMethod() {
        if (scanCallback == null) {
            scanCallback = new ScanCallback() {

                @Override
                public void onScanResult(int callbackType, ScanResult result) {
                    super.onScanResult(callbackType, result);
                    if (!allowDuplicates && result != null && result.getDevice() != null && result.getDevice().getAddress() != null) {
                        if (macDeviceScanned.contains(result.getDevice().getAddress())) return;
                        macDeviceScanned.add(result.getDevice().getAddress());
                    }

                    if (result != null) {
                        BluetoothDevice device = result.getDevice();
                        if (device != null) {
                            Protos.ScanResult scanResult = ProtoMaker.from(result.getDevice(), result);
                            invokeMethodUIThread("ScanResult", scanResult.toByteArray());
                        }
                    }
                }

                @Override
                public void onBatchScanResults(List<ScanResult> results) {
                    super.onBatchScanResults(results);
                }

                @Override
                public void onScanFailed(int errorCode) {
                    super.onScanFailed(errorCode);
                    invokeMethodUIThread(LOG_CHANNEL, ProtoMaker.log(LOG_TYPE_ERROR, "OnScanFailed: errorCode:" + errorCode).toByteArray());
                    Protos.ScanResult scanResult = ProtoMaker.scanResultError(errorCode);
                    invokeMethodUIThread("ScanResult", scanResult.toByteArray());
                }
            };
        }
        return scanCallback;
    }

    private void startBluetoothLeScan(Protos.ScanSettings proto) throws IllegalStateException {
        BluetoothLeScanner scanner = mBluetoothAdapter.getBluetoothLeScanner();
        if (scanner == null)
            throw new IllegalStateException("getBluetoothLeScanner() is null. Is the Adapter on?");
        int scanMode = proto.getAndroidScanMode();

        // add service uuids filter
        ArrayList<ScanFilter> filters = new ArrayList<>();
        for (int i = 0; i < proto.getServiceUuidsCount(); i++) {
            String uuid = proto.getServiceUuids(i);
            ScanFilter f = new ScanFilter.Builder().setServiceUuid(ParcelUuid.fromString(uuid)).build();
            filters.add(f);
        }

        // add device names filter
        for (int i = 0; i < proto.getFilterDeviceNamesCount(); i++) {
            filters.add(new ScanFilter.Builder()
                    .setDeviceName(proto.getFilterDeviceNames(i))
                    .build()
            );
        }

        // add mac addresses filter
        for (int i = 0; i < proto.getFilterMacAddressesCount(); i++) {
            filters.add(new ScanFilter.Builder()
                    .setDeviceAddress(proto.getFilterMacAddresses(i))
                    .build()
            );
        }

        ScanSettings settings = new ScanSettings.Builder().setScanMode(scanMode).build();
        scanner.startScan(filters, settings, leScanMethod());
    }

    @Override
    public boolean onRequestPermissionsResult(
            int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQUEST_FINE_LOCATION_PERMISSIONS) {
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startScan(pendingCall, pendingResult);
            } else {
                pendingResult.error(
                        "no_permissions", "flutter_blue plugin requires location permissions for scanning", null);
                pendingResult = null;
                pendingCall = null;
            }
            return true;
        }
        return false;
    }
    /// END ---------------------------- SCANNING RELATED METHODS --------------------------------------

    /// START ---------------------------- ADVERTISING RELATED METHODS --------------------------------------
    private boolean startAdvertising(MethodCall call) {

        BluetoothLeAdvertiser advertiser = mBluetoothAdapter.getBluetoothLeAdvertiser();

        if (advertiser != null) {
            AdvertiseSettings.Builder settingsBuilder = new AdvertiseSettings.Builder();
            settingsBuilder.setConnectable(false)
                    .setTimeout(0) // will be turned on indefinitely
                    .setAdvertiseMode(ADVERTISE_MODE_LOW_LATENCY)
                    .setTxPowerLevel(ADVERTISE_TX_POWER_HIGH);

            byte[] manufacturerData = call.arguments();

            AdvertiseData advertiseData = new AdvertiseData.Builder()
                    .setIncludeDeviceName(false)
                    .addManufacturerData(untitledCompanyManufacturerId, manufacturerData)
                    .build();
            advertiser.startAdvertising(settingsBuilder.build(), advertiseData, mAdvertiseCallback);
            return true;
        }

        return false;

    }

    private boolean stopAdvertising() {
        BluetoothLeAdvertiser advertiser = mBluetoothAdapter.getBluetoothLeAdvertiser();
        if (advertiser != null) {
            advertiser.stopAdvertising(mAdvertiseCallback);
            return true;
        }
        return false;
    }

    private final AdvertiseCallback mAdvertiseCallback = new AdvertiseCallback() {
        @Override
        public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            invokeMethodUIThread(LOG_CHANNEL, ProtoMaker.log(LOG_TYPE_DEBUG, "FlutterBlue in Native: Peripheral advertising started").toByteArray());
            Log.d(TAG, "FlutterBlue in Native: Peripheral advertising started");
        }

        @Override
        public void onStartFailure(int errorCode) {
            invokeMethodUIThread(LOG_CHANNEL, ProtoMaker.log(LOG_TYPE_ERROR, "FlutterBlue in Native: Peripheral advertising failed. errorCode: " + errorCode).toByteArray());
            Log.d(TAG, "FlutterBlue in Native: Peripheral advertising failed: " + errorCode);
        }
    };
    /// END ---------------------------- ADVERTISING RELATED METHODS --------------------------------------

    /// START ---------------------------- BLUETOOTH ON/OFF STATE HANDLER --------------------------------------
    private final StreamHandler stateHandler = new StreamHandler() {
        private EventSink sink;

        private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                final String action = intent.getAction();

                if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
                    final int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE,
                            BluetoothAdapter.ERROR);
                    switch (state) {
                        case BluetoothAdapter.STATE_OFF:
                            sink.success(Protos.BluetoothState.newBuilder().setState(Protos.BluetoothState.State.OFF).build().toByteArray());
                            break;
                        case BluetoothAdapter.STATE_TURNING_OFF:
                            sink.success(Protos.BluetoothState.newBuilder().setState(Protos.BluetoothState.State.TURNING_OFF).build().toByteArray());
                            break;
                        case BluetoothAdapter.STATE_ON:
                            sink.success(Protos.BluetoothState.newBuilder().setState(Protos.BluetoothState.State.ON).build().toByteArray());
                            break;
                        case BluetoothAdapter.STATE_TURNING_ON:
                            sink.success(Protos.BluetoothState.newBuilder().setState(Protos.BluetoothState.State.TURNING_ON).build().toByteArray());
                            break;
                    }
                }
            }
        };

        @Override
        public void onListen(Object o, EventChannel.EventSink eventSink) {
            sink = eventSink;
            IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
            context.registerReceiver(mReceiver, filter);
        }

        @Override
        public void onCancel(Object o) {
            sink = null;
            context.unregisterReceiver(mReceiver);
        }
    };
    /// END ---------------------------- BLUETOOTH ON/OFF STATE HANDLER --------------------------------------

    /// START ---------------------------- CONNECTION STATUS RELATED METHODS  --------------------------------------
    private final BluetoothGattCallback mGattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            LogUtil.log(LogUtil.LogLevel.DEBUG, "FlutterBlue in Native: [onConnectionStateChange] status: " + status + " newState: " + newState);
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                if (!mDevices.containsKey(gatt.getDevice().getAddress())) {
                    gatt.close();
                }
            }

            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    // We successfully connected, proceed with service discovery
                    LogUtil.log(LogUtil.LogLevel.DEBUG, "FlutterBlue in Native: Device CONNECTED successfully, mac: " + gatt.getDevice().getAddress());
                }
            } else {
                // An error happened...figure out what happened!
                // Programmatically disconnected - 0
                // Device went out of range - 8
                // Disconnected by device - 19
                // Issue with bond - 22
                // Device not found - 133(some phone it gives 62)
                LogUtil.log(LogUtil.LogLevel.DEBUG, "FlutterBlue in Native: ERROR happened: BluetoothGatt status: " + status);
                gatt.close();
            }
            invokeMethodUIThread("DeviceState", ProtoMaker.from(gatt.getDevice(), newState).toByteArray());
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            LogUtil.log(LogUtil.LogLevel.DEBUG, "FlutterBlue in Native: [onServicesDiscovered] count: " + gatt.getServices().size() + " status: " + status);
            Protos.DiscoverServicesResult.Builder p = Protos.DiscoverServicesResult.newBuilder();
            p.setRemoteId(gatt.getDevice().getAddress());
            for (BluetoothGattService s : gatt.getServices()) {
                p.addServices(ProtoMaker.from(gatt.getDevice(), s, gatt));
            }
            invokeMethodUIThread("DiscoverServicesResult", p.build().toByteArray());
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            LogUtil.log(LogUtil.LogLevel.DEBUG, "FlutterBlue in Native: [onCharacteristicRead] uuid: " + characteristic.getUuid().toString() + " status: " + status);
            Protos.ReadCharacteristicResponse.Builder p = Protos.ReadCharacteristicResponse.newBuilder();
            p.setRemoteId(gatt.getDevice().getAddress());
            p.setCharacteristic(ProtoMaker.from(gatt.getDevice(), characteristic, gatt));
            invokeMethodUIThread("ReadCharacteristicResponse", p.build().toByteArray());
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            LogUtil.log(LogUtil.LogLevel.DEBUG, "FlutterBlue in Native: [onCharacteristicWrite] uuid: " + characteristic.getUuid().toString() + " status: " + status);
            Protos.WriteCharacteristicRequest.Builder request = Protos.WriteCharacteristicRequest.newBuilder();
            request.setRemoteId(gatt.getDevice().getAddress());
            request.setCharacteristicUuid(characteristic.getUuid().toString());
            request.setServiceUuid(characteristic.getService().getUuid().toString());
            Protos.WriteCharacteristicResponse.Builder p = Protos.WriteCharacteristicResponse.newBuilder();
            p.setRequest(request);
            p.setSuccess(status == BluetoothGatt.GATT_SUCCESS);
            invokeMethodUIThread("WriteCharacteristicResponse", p.build().toByteArray());
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            LogUtil.log(LogUtil.LogLevel.DEBUG, "FlutterBlue in Native: [onCharacteristicChanged] uuid: " + characteristic.getUuid().toString());
            Protos.OnCharacteristicChanged.Builder p = Protos.OnCharacteristicChanged.newBuilder();
            p.setRemoteId(gatt.getDevice().getAddress());
            p.setCharacteristic(ProtoMaker.from(gatt.getDevice(), characteristic, gatt));
            invokeMethodUIThread("OnCharacteristicChanged", p.build().toByteArray());
        }

        @Override
        public void onDescriptorRead(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            LogUtil.log(LogUtil.LogLevel.DEBUG, "FlutterBlue in Native: [onDescriptorRead] uuid: " + descriptor.getUuid().toString() + " status: " + status);
            // Rebuild the ReadAttributeRequest and send back along with response
            Protos.ReadDescriptorRequest.Builder q = Protos.ReadDescriptorRequest.newBuilder();
            q.setRemoteId(gatt.getDevice().getAddress());
            q.setCharacteristicUuid(descriptor.getCharacteristic().getUuid().toString());
            q.setDescriptorUuid(descriptor.getUuid().toString());
            if (descriptor.getCharacteristic().getService().getType() == BluetoothGattService.SERVICE_TYPE_PRIMARY) {
                q.setServiceUuid(descriptor.getCharacteristic().getService().getUuid().toString());
            } else {
                // Reverse search to find service
                for (BluetoothGattService s : gatt.getServices()) {
                    for (BluetoothGattService ss : s.getIncludedServices()) {
                        if (ss.getUuid().equals(descriptor.getCharacteristic().getService().getUuid())) {
                            q.setServiceUuid(s.getUuid().toString());
                            q.setSecondaryServiceUuid(ss.getUuid().toString());
                            break;
                        }
                    }
                }
            }
            Protos.ReadDescriptorResponse.Builder p = Protos.ReadDescriptorResponse.newBuilder();
            p.setRequest(q);
            p.setValue(ByteString.copyFrom(descriptor.getValue()));
            invokeMethodUIThread("ReadDescriptorResponse", p.build().toByteArray());
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            LogUtil.log(LogUtil.LogLevel.DEBUG, "FlutterBlue in Native: [onDescriptorWrite] uuid: " + descriptor.getUuid().toString() + " status: " + status);
            Protos.WriteDescriptorRequest.Builder request = Protos.WriteDescriptorRequest.newBuilder();
            request.setRemoteId(gatt.getDevice().getAddress());
            request.setDescriptorUuid(descriptor.getUuid().toString());
            request.setCharacteristicUuid(descriptor.getCharacteristic().getUuid().toString());
            request.setServiceUuid(descriptor.getCharacteristic().getService().getUuid().toString());
            Protos.WriteDescriptorResponse.Builder p = Protos.WriteDescriptorResponse.newBuilder();
            p.setRequest(request);
            p.setSuccess(status == BluetoothGatt.GATT_SUCCESS);
            invokeMethodUIThread("WriteDescriptorResponse", p.build().toByteArray());

            if (descriptor.getUuid().compareTo(CCCD_ID) == 0) {
                // SetNotificationResponse
                Protos.SetNotificationResponse.Builder q = Protos.SetNotificationResponse.newBuilder();
                q.setRemoteId(gatt.getDevice().getAddress());
                q.setCharacteristic(ProtoMaker.from(gatt.getDevice(), descriptor.getCharacteristic(), gatt));
                invokeMethodUIThread("SetNotificationResponse", q.build().toByteArray());
            }
        }

        @Override
        public void onReliableWriteCompleted(BluetoothGatt gatt, int status) {
            LogUtil.log(LogUtil.LogLevel.DEBUG, "FlutterBlue in Native: [onReliableWriteCompleted] status: " + status);
        }

        @Override
        public void onReadRemoteRssi(BluetoothGatt gatt, int rssi, int status) {
            LogUtil.log(LogUtil.LogLevel.DEBUG, "FlutterBlue in Native: [onReadRemoteRssi] rssi: " + rssi + " status: " + status);
        }

        @Override
        public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
            LogUtil.log(LogUtil.LogLevel.DEBUG, "FlutterBlue in Native: [onMtuChanged] mtu: " + mtu + " status: " + status);
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (mDevices.containsKey(gatt.getDevice().getAddress())) {
                    BluetoothDeviceCache cache = mDevices.get(gatt.getDevice().getAddress());
                    if (cache != null) {
                        cache.mtu = mtu;
                        Protos.MtuSizeResponse.Builder p = Protos.MtuSizeResponse.newBuilder();
                        p.setRemoteId(gatt.getDevice().getAddress());
                        p.setMtu(mtu);
                        invokeMethodUIThread("MtuSize", p.build().toByteArray());
                    }
                }
            }
        }
    };
    /// END ---------------------------- CONNECTION STATUS RELATED METHODS  --------------------------------------

    /// START ---------------------------- SEND NATIVE INSTRUCTIONS TO DART  --------------------------------------
    private void invokeMethodUIThread(final String name, final byte[] byteArray) {
        if (activity != null) {
            activity.runOnUiThread(
                    () -> {
                        if (channel != null) {
                            channel.invokeMethod(name, byteArray);
                        } else {
                            Log.e(TAG, "FlutterBlue in Native: ERROR: CHANNEL IS NULL");
                        }
                    });
        } else {
            Log.e(TAG, "FlutterBlue in Native: Activity is Null");
        }
    }
    /// END ---------------------------- SEND NATIVE INSTRUCTIONS TO FLUTTER  --------------------------------------
}
