package com.pauldemarco.flutter_blue.models;

import android.bluetooth.BluetoothGatt;

public class BluetoothDeviceCache {
    public final BluetoothGatt gatt;
    public int mtu;

    public BluetoothDeviceCache(BluetoothGatt gatt) {
        this.gatt = gatt;
        mtu = 20;
    }
}
