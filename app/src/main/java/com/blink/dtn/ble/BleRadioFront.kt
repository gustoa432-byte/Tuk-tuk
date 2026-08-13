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
import java.util.concurrent.atomic.AtomicReference

/**
 * BLE radio front: GATT server + advertise + scan cycle.
 * Peer table / ingress / TX live elsewhere; this owns the peripheral/discovery stack.
 * Cadence follows [MeshDutyPrefs] so Economy / Norm / Max actually change radio duty.
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
        /** Dense auto-switch flipped preset to CROWD — re-apply cadence. */
        fun onCrowdAutoSwitched() {}
    }

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter

    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null
    private var scanJob: Job? = null
    private var emergencyJob: Job? = null
    private val cadenceRef = AtomicReference(MeshDutyCadence.forPreset(MeshDutyPreset.NORMAL))
    @Volatile private var running = false
    @Volatile private var emergencyActive = false
    private var preEmergencyCadence: MeshDutyCadence? = null
    private var scanFailJob: kotlinx.coroutines.Job? = null
    private val scanFailAttempts = java.util.concurrent.atomic.AtomicInteger(0)

    fun start() {
        running = true
        emergencyActive = false
        emergencyJob?.cancel()
        emergencyJob = null
        cadenceRef.set(MeshDutyPrefs.cadence())
        startGattServer()
        startAdvertising()
        startScanningCycle()
    }

    fun stop() {
        running = false
        emergencyActive = false
        emergencyJob?.cancel()
        emergencyJob = null
        preEmergencyCadence = null
        scanJob?.cancel()
        scanJob = null
        scanFailJob?.cancel()
        scanFailJob = null
        scanFailAttempts.set(0)
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

    /**
     * Explicit SOS radio scream: LOW_LATENCY + HIGH TX for at most [durationMs],
     * then hard-reset advertise to LOW_POWER (never leave HIGH on forever).
     */
    fun startEmergencyBeacon(durationMs: Long = EMERGENCY_BEACON_MS) {
        if (!running) return
        emergencyJob?.cancel()
        if (!emergencyActive) {
            preEmergencyCadence = cadenceRef.get()
            emergencyActive = true
        }
        val scream = (preEmergencyCadence ?: cadenceRef.get()).copy(
            advertiseMode = AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY,
            advertiseTxPower = AdvertiseSettings.ADVERTISE_TX_POWER_HIGH
        )
        cadenceRef.set(scream)
        restartAdvertisingOnly()
        Log.w("DTN", "Emergency beacon ON for ${durationMs}ms")
        emergencyJob = scopeProvider().launch {
            delay(durationMs.coerceIn(5_000L, EMERGENCY_BEACON_MS))
            stopEmergencyBeacon()
        }
    }

    fun stopEmergencyBeacon() {
        emergencyJob?.cancel()
        emergencyJob = null
        if (!emergencyActive && preEmergencyCadence == null) return
        emergencyActive = false
        val restore = (preEmergencyCadence ?: MeshDutyPrefs.cadence()).copy(
            advertiseMode = AdvertiseSettings.ADVERTISE_MODE_LOW_POWER,
            advertiseTxPower = AdvertiseSettings.ADVERTISE_TX_POWER_LOW
        )
        preEmergencyCadence = null
        cadenceRef.set(restore)
        if (running) {
            restartAdvertisingOnly()
            startScanningCycle()
        }
        Log.w("DTN", "Emergency beacon OFF → LOW_POWER advertise")
    }

    fun isEmergencyBeaconActive(): Boolean = emergencyActive

    /** Hot-swap scan/advertise aggressiveness without tearing down GATT server. */
    fun applyCadence(cadence: MeshDutyCadence) {
        if (emergencyActive) {
            // Keep screaming until timer; remember requested cadence for restore.
            preEmergencyCadence = cadence
            Log.i("DTN", "Cadence queued until emergency beacon ends")
            return
        }
        cadenceRef.set(cadence)
        if (!running) return
        restartAdvertisingOnly()
        startScanningCycle()
        Log.i("DTN", "Radio cadence applied scan=${cadence.scanOnMs}/${cadence.scanOffMs}")
    }

    private fun restartAdvertisingOnly() {
        try {
            advertiser?.stopAdvertising(advertiseCallback)
        } catch (_: Exception) {
        }
        startAdvertising()
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
        val cadence = cadenceRef.get()
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(cadence.advertiseMode)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(cadence.advertiseTxPower)
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

            while (isActive) {
                val cadence = cadenceRef.get()
                val settings = ScanSettings.Builder()
                    .setScanMode(cadence.scanMode)
                    .build()
                try {
                    scanner?.startScan(filters, settings, scanCallback)
                    scanFailAttempts.set(0)
                } catch (e: SecurityException) {
                    Log.e("DTN", "SecurityException in startScanning: ${e.message}")
                }

                delay(cadence.scanOnMs)

                try {
                    scanner?.stopScan(scanCallback)
                } catch (e: SecurityException) {
                    Log.e("DTN", "SecurityException in stopScanning: ${e.message}")
                }

                delay(cadence.scanOffMs)
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
            val mac = result.device.address
            DenseCrowdDetector.noteAdvertiser(mac)
            if (DenseCrowdDetector.maybeAutoSwitch(context)) {
                deps.onCrowdAutoSwitched()
            }
            // In crowd mode skip PeerDirectory MAC persist storm — only verified nodes later.
            if (!MeshDutyPrefs.isCrowd()) {
                com.blink.dtn.telemetry.PeerDirectory.noteBleDevice(result.device, result.rssi)
            }
            if (deps.noteDiscovered(result.device)) {
                deps.onNewPeerFromScan(result.device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e("DTN", "Scan failed: $errorCode")
            if (!running) return
            val attempt = scanFailAttempts.getAndIncrement()
            if (!BleRadioBackoff.shouldRetryScan(attempt)) {
                Log.w("DTN", "Scan retry cap reached — waiting for next cadence")
                scanFailAttempts.set(0)
                return
            }
            val wait = BleRadioBackoff.scanDelayMs(attempt)
            scanFailJob?.cancel()
            scanFailJob = scopeProvider().launch {
                delay(wait)
                if (!running) return@launch
                startScanningCycle()
            }
        }
    }

    companion object {
        /** Hard ceiling — emergency HIGH advertise never exceeds this. */
        const val EMERGENCY_BEACON_MS = 3L * 60L * 1000L
    }
}
