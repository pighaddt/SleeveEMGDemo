package com.itri.sleeveemgdemo

import android.bluetooth.*
import android.content.Context
import android.util.Log
import java.util.*

/**
 * Created by HabaCo on 2020/5/5.
 */

class BleConnector(private val context: Context) : BluetoothGattCallback(){

    //// VSP_TX_FIFO
    private val VSP_TX_UUID = UUID.fromString("569a2000-b87f-490c-92cb-11ba5ea5167c")

    //// notification descriptor uuid
    private val uuid_descriptor_notify: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private var device: BluetoothDevice? = null

    private var gatt: BluetoothGatt? = null

    private var characteristic_vsp_tx: BluetoothGattCharacteristic? = null

    var onConnecting = { }

    var onNotificationChanged = { }

    var onServiceDiscovering = { }

    var onServiceDiscoverCompleted = { }

    var onDisconnected = { }

    var onDataIn = { _: ByteArray -> }

    fun selectDevice(device: BluetoothDevice) {
        this.device = device
    }

    fun selectedDevice() = this.device

    fun connect() {
        if (gatt == null) {
            onConnecting()
            gatt = this.device?.connectGatt(context, false, this)
        }
    }

    fun disconnect() {
        gatt?.disconnect()
        gatt = null
        this.device = null
    }

    fun enableDataIncome() {
        characteristic_vsp_tx?.run {
            gatt?.setCharacteristicNotification(this, true)
            with(getDescriptor(uuid_descriptor_notify)) {
                value = android.bluetooth.BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt?.writeDescriptor(this)
            }
        }
    }

    fun disableDataIncome() {
        characteristic_vsp_tx?.run {
            gatt?.setCharacteristicNotification(this, false)
            with(getDescriptor(uuid_descriptor_notify)) {
                value = android.bluetooth.BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                gatt?.writeDescriptor(this)
            }
        }
    }

    override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
        if (newState == BluetoothGatt.STATE_CONNECTED) {
            onServiceDiscovering()
            gatt?.discoverServices()
        } else if (newState == BluetoothGatt.STATE_DISCONNECTED){
            onDisconnected()
        }
    }

    override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
        super.onServicesDiscovered(gatt, status)

        if (status == BluetoothGatt.GATT_SUCCESS) {
            onServiceDiscoverCompleted()

            gatt?.services?.forEach {
                it.characteristics.forEach { ch ->
                    if (ch.uuid == VSP_TX_UUID) {
                        //// fetch reference
                        characteristic_vsp_tx = ch
                    }
                }
            }
        } else {
            disconnect()
        }
    }

    override fun onCharacteristicChanged(
        gatt: BluetoothGatt?,
        characteristic: BluetoothGattCharacteristic?
    ) {
        characteristic?.run {
            onDataIn(value)
        }
    }

    override fun onDescriptorWrite(
        gatt: BluetoothGatt?,
        descriptor: BluetoothGattDescriptor?,
        status: Int
    ) {
        if (status == BluetoothGatt.GATT_SUCCESS) {
            onNotificationChanged()
        }
    }
}