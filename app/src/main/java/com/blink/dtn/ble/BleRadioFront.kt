package com.blink.dtn.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * BLE radio front: GATT server + advertise + scan cycle.
 * Peer table / ingress / TX live elsewhere; this owns the peripheral/discovery stack.
 */
@SuppressLint("MissingPermission")
internal class BleRadioFront(
    private val context: Context,
    private val scopeProvider: () -> CoroutineScope,
    private val deps: Deps
) {
    interface Deps {
        fun serviceUuid(): UUID
        fun characteristicUuid(): UUID
        /** @return true if [device] was newly added to the discovery set. */
        fun noteDiscovered(device: BluetoothDevice): Boolean
        /** @return true if [device] was newly added to the discovery set. */
        fun noteGattClientConnected(device: BluetoothDevice): Boolean
        fun noteGattClientDisconnected(device: BluetoothDevice)
        fun onNewPeerFromScan(device: BluetoothDevice)
        fun onNewPeerFromGatt(device: BluetoothDevice)
        fun onWriteValue(device: BluetoothDevice, value: ByteArray)
        fun showToast(msg: String)
    }

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter

    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null
    private var scanJob: Job? = null

    fun start() {
        startGattServer()
        startAdvertising()
        startScanningCycle()
    }

    fun stop() {
        scanJob?.cancel()
        scanJob = null
        try {
            scanner?.stopScan(scanCallback)
        } catch (e: SecurityException) {
            Log.e("DTN", "SecurityException stopping scan: ${e.message}")
        }
        try {
            advertiser?.stopAdvertising(advertiseCallback)
        } catch (e: SecurityException) {
            Log.e("DTN", "SecurityException stopping advertise: ${e.message}")
        }
        try {
            gattServer?.close()
        } catch (e: SecurityException) {
            Log.e("DTN", "SecurityException closing GATT server: ${e.message}")
        }
        gattServer = null
        advertiser = null
        scanner = null
    }

    private fun startGattServer() {
        try {
            gattServer = bluetoothManager.openGattServer(context, gattServerCallback)
            val service = BluetoothGattService(
                deps.serviceUuid(),
                BluetoothGattService.SERVICE_TYPE_PRIMARY
            )
            val characteristic = BluetoothGattCharacteristic(
                deps.characteristicUuid(),
                BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE
            )
            service.addCharacteristic(characteristic)
            gattServer?.addService(service)
        } catch (e: SecurityException) {
            Log.e("DTN", "SecurityException in startGattServer: ${e.message}")
        }
    }

    private fun startAdvertising() {
        advertiser = bluetoothAdapter?.bluetoothLeAdvertiser
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(deps.serviceUuid()))
            .build()

        try {
            advertiser?.startAdvertising(settings, data, advertiseCallback)
        } catch (e: SecurityException) {
            Log.e("DTN", "SecurityException in startAdvertising: ${e.message}")
        }
    }

    private fun startScanningCycle() {
        scanner = bluetoothAdapter?.bluetoothLeScanner
        scanJob?.cancel()
        scanJob = scopeProvider().launch {
            val filters = listOf(
                ScanFilter.Builder().setServiceUuid(ParcelUuid(deps.serviceUuid())).build()
            )
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
                .build()

            while (isActive) {
                try {
                    scanner?.startScan(filters, settings, scanCallback)
                } catch (e: SecurityException) {
                    Log.e("DTN", "SecurityException in startScanning: ${e.message}")
                }

                delay(10_000)

                try {
                    scanner?.stopScan(scanCallback)
                } catch (e: SecurityException) {
                    Log.e("DTN", "SecurityException in stopScanning: ${e.message}")
                }

                delay(20_000)
            }
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            super.onConnectionStateChange(device, status, newState)
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                if (deps.noteGattClientConnected(device)) {
                    deps.onNewPeerFromGatt(device)
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                deps.noteGattClientDisconnected(device)
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            super.onCharacteristicWriteRequest(
                device, requestId, characteristic, preparedWrite, responseNeeded, offset, value
            )
            if (characteristic.uuid != deps.characteristicUuid()) return
            try {
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                }
                deps.onWriteValue(device, value)
            } catch (e: SecurityException) {
                Log.e("DTN", "SecurityException in write request: ${e.message}")
                deps.showToast("Security Exception: ${e.message}")
            } catch (e: Exception) {
                Log.e("DTN", "Error decoding message: ${e.message}")
                deps.showToast("BLE Rx Error: ${e.message}")
            }
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.d("DTN", "Advertise started successfully")
        }

        override fun onStartFailure(errorCode: Int) {
            Log.e("DTN", "Advertise failed: $errorCode")
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            com.blink.dtn.telemetry.PeerDirectory.noteBleDevice(result.device, result.rssi)
            if (deps.noteDiscovered(result.device)) {
                deps.onNewPeerFromScan(result.device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e("DTN", "Scan failed: $errorCode")
        }
    }
}
