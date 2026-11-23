package com.ta2fga.obd2.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class ScanDevice(val name: String, val address: String, val rssi: Int, val obd: Boolean)

/** Yakındaki BLE cihazlarını tarar (OBD benzeri adlar önce). */
@SuppressLint("MissingPermission")
class BleScanner(private val ctx: Context) {
    private val _devices = MutableStateFlow<List<ScanDevice>>(emptyList())
    val devices: StateFlow<List<ScanDevice>> = _devices
    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning
    private val found = LinkedHashMap<String, ScanDevice>()

    private val cb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, r: ScanResult) {
            val name = r.scanRecord?.deviceName ?: runCatching { r.device.name }.getOrNull() ?: "(adsız)"
            found[r.device.address] = ScanDevice(name, r.device.address, r.rssi, isObdName(name))
            _devices.value = found.values.sortedWith(compareBy({ !it.obd }, { -it.rssi }))
        }
        override fun onScanFailed(errorCode: Int) { _scanning.value = false }
    }

    fun adapterOn(): Boolean = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter?.isEnabled == true

    fun start(): Boolean {
        val sc = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter?.bluetoothLeScanner ?: return false
        found.clear(); _devices.value = emptyList()
        return try {
            sc.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
            _scanning.value = true; true
        } catch (e: SecurityException) { false }
    }

    fun stop() {
        try { (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter?.bluetoothLeScanner?.stopScan(cb) } catch (_: Exception) {}
        _scanning.value = false
    }
}
