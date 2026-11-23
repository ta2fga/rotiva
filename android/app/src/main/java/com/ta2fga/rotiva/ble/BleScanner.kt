package com.ta2fga.rotiva.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull

data class ScanDevice(val name: String, val address: String, val rssi: Int, val obd: Boolean)

/** Bilinen BLE ELM327 adları (donanım süzgeci tam eşleşme ister). */
val OBD_NAMES = listOf("OBDII", "OBD2", "OBDBLE", "OBD-II", "V-LINK", "IOS-Vlink", "vLinker MC-IOS", "Viecar", "VEEPEAK")

/** Taramada OBD adaptörünü işaret eden reklam: bilinen UART servisi ya da OBD benzeri ad. */
fun looksLikeObd(name: String?, serviceUuids: List<String>): Boolean =
    isObdName(name) || serviceUuids.any { u -> KNOWN_UART.any { it.first == u } }

/** Yakındaki BLE cihazlarını tarar (OBD benzeri adlar önce). */
@SuppressLint("MissingPermission")
class BleScanner(private val ctx: Context) {
    private val _devices = MutableStateFlow<List<ScanDevice>>(emptyList())
    val devices: StateFlow<List<ScanDevice>> = _devices
    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning
    private val found = LinkedHashMap<String, ScanDevice>()
    private val main = Handler(Looper.getMainLooper())
    private val autoStop = Runnable { stop() }

    private val cb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, r: ScanResult) {
            val name = r.scanRecord?.deviceName ?: runCatching { r.device.name }.getOrNull() ?: "(adsız)"
            val svc = r.scanRecord?.serviceUuids?.map { short16(it.uuid) } ?: emptyList()
            found[r.device.address] = ScanDevice(name, r.device.address, r.rssi, looksLikeObd(name, svc))
            _devices.value = found.values.sortedWith(compareBy({ !it.obd }, { -it.rssi }))
        }
        override fun onScanFailed(errorCode: Int) { _scanning.value = false }
    }

    private fun leScanner() = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter?.bluetoothLeScanner

    fun adapterOn(): Boolean = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter?.isEnabled == true

    /** Elle seçim için süzgeçsiz tarama (alışılmadık adlı adaptörler de görünsün); [durationMs] sonra durur. */
    fun start(durationMs: Long = 15000): Boolean {
        val sc = leScanner() ?: return false
        found.clear(); _devices.value = emptyList()
        return try {
            sc.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
            _scanning.value = true
            main.removeCallbacks(autoStop); main.postDelayed(autoStop, durationMs)
            true
        } catch (e: SecurityException) { false }
    }

    fun stop() {
        main.removeCallbacks(autoStop)
        try { leScanner()?.stopScan(cb) } catch (_: Exception) {}
        _scanning.value = false
    }

    /**
     * Süzgeçli hızlı arama: [address] verilirse yalnızca o cihaz, yoksa FFF0/FFE0/18F0 servisi ya da bilinen
     * OBD adı. İLK eşleşmede durur (sabit süre beklemez). Bulunamazsa [timeoutMs] sonunda null.
     */
    suspend fun findFirst(address: String?, timeoutMs: Long): ScanDevice? {
        val sc = leScanner() ?: return null
        val hit = CompletableDeferred<ScanDevice>()
        val filters = if (address != null) listOf(ScanFilter.Builder().setDeviceAddress(address).build())
        else KNOWN_UART.map { ScanFilter.Builder().setServiceUuid(ParcelUuid(uuid16(it.first))).build() } +
            OBD_NAMES.map { ScanFilter.Builder().setDeviceName(it).build() }
        val c = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, r: ScanResult) {
                val name = r.scanRecord?.deviceName ?: runCatching { r.device.name }.getOrNull() ?: "(adsız)"
                if (address == null || r.device.address.equals(address, ignoreCase = true))
                    hit.complete(ScanDevice(name, r.device.address, r.rssi, true))
            }
            override fun onScanFailed(errorCode: Int) { hit.completeExceptionally(IllegalStateException("tarama hatası $errorCode")) }
        }
        try {
            sc.startScan(filters, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), c)
        } catch (e: SecurityException) { return null }
        return try {
            withTimeoutOrNull(timeoutMs) { runCatching { hit.await() }.getOrNull() }
        } finally {
            try { sc.stopScan(c) } catch (_: Exception) {}
        }
    }
}
