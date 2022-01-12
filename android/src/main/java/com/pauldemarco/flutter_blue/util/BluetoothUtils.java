package com.pauldemarco.flutter_blue.util;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;

import com.pauldemarco.flutter_blue.models.BluetoothDeviceCache;

import java.util.Map;
import java.util.UUID;

public class BluetoothUtils {
    public static BluetoothGatt locateGatt(String remoteId, Map<String, BluetoothDeviceCache> mDevices) throws Exception {
        BluetoothDeviceCache cache = mDevices.get(remoteId);
        if (cache == null || cache.gatt == null) {
            throw new Exception("no instance of BluetoothGatt, have you connected first?");
        } else {
            return cache.gatt;
        }
    }

    public static BluetoothGattCharacteristic locateCharacteristic(BluetoothGatt gattServer, String serviceId, String secondaryServiceId, String characteristicId) throws Exception {
        BluetoothGattService primaryService = gattServer.getService(UUID.fromString(serviceId));
        if (primaryService == null) {
            throw new Exception("service (" + serviceId + ") could not be located on the device");
        }
        BluetoothGattService secondaryService = null;
        if (secondaryServiceId.length() > 0) {
            for (BluetoothGattService s : primaryService.getIncludedServices()) {
                if (s.getUuid().equals(UUID.fromString(secondaryServiceId))) {
                    secondaryService = s;
                }
            }
            if (secondaryService == null) {
                throw new Exception("secondary service (" + secondaryServiceId + ") could not be located on the device");
            }
        }
        BluetoothGattService service = (secondaryService != null) ? secondaryService : primaryService;
        BluetoothGattCharacteristic characteristic = service.getCharacteristic(UUID.fromString(characteristicId));
        if (characteristic == null) {
            throw new Exception("characteristic (" + characteristicId + ") could not be located in the service (" + service.getUuid().toString() + ")");
        }
        return characteristic;
    }

    public static BluetoothGattDescriptor locateDescriptor(BluetoothGattCharacteristic characteristic, String descriptorId) throws Exception {
        BluetoothGattDescriptor descriptor = characteristic.getDescriptor(UUID.fromString(descriptorId));
        if (descriptor == null) {
            throw new Exception("descriptor (" + descriptorId + ") could not be located in the characteristic (" + characteristic.getUuid().toString() + ")");
        }
        return descriptor;
    }
}
