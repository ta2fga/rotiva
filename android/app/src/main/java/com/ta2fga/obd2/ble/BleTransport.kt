package com.ta2fga.obd2.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import com.ta2fga.obd2.core.ElmError
import com.ta2fga.obd2.core.Transport
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

private const val BASE = "-0000-1000-8000-00805f9b34fb"
fun uuid16(s: String): UUID = UUID.fromString("0000$s$BASE")
fun short16(u: UUID): String {
    val s = u.toString().lowercase()
    return if (s.endsWith(BASE) && s.startsWith("0000")) s.substring(4, 8) else s
}

/** (servis, bildirim/notify, yazma/write): önce FFF0 (gerçek "OBDII" adaptörü), sonra FFE0 ve 18F0. */
val KNOWN_UART = listOf(Triple("fff0", "fff1", "fff2"), Triple("ffe0", "ffe1", "ffe1"), Triple("18f0", "2af0", "2af1"))

val OBD_HINTS = listOf("obd", "elm", "v-link", "vlink", "vgate", "icar", "viecar")
fun isObdName(name: String?): Boolean { val n = (name ?: "").lowercase(); return OBD_HINTS.any { it in n } }

/** (notify, write) karakteristikleri. Bilinen UART'lar önce; yoksa ilk notify + ilk write. */
fun pickCharacteristics(services: List<BluetoothGattService>): Pair<BluetoothGattCharacteristic, BluetoothGattCharacteristic> {
    val by = services.associateBy { short16(it.uuid) }
    for ((sv, rx, tx) in KNOWN_UART) {
        val s = by[sv] ?: continue
        val ch = s.characteristics.associateBy { short16(it.uuid) }
        val n = ch[rx]; val w = ch[tx]
        if (n != null && w != null) return n to w
    }
    var notify: BluetoothGattCharacteristic? = null
    var write: BluetoothGattCharacteristic? = null
    for (s in services) {
        if (short16(s.uuid) in listOf("1800", "1801")) continue
        for (c in s.characteristics) {
            val p = c.properties
            if (notify == null && (p and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0) notify = c
            if (write == null && (p and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0) write = c
        }
    }
    if (notify == null || write == null) throw ElmError("BLE cihazında uygun UART karakteristiği bulunamadı")
    return notify to write
}

/**
 * BLE ELM327 taşıması. Yazmalar HER ZAMAN yanıtlı (WRITE_TYPE_DEFAULT): birçok adaptör (FFF2) yanıtsız yazmayı yok sayar.
 */
@SuppressLint("MissingPermission")
class BleTransport(private val ctx: Context, private val device: BluetoothDevice,
                   private val log: (String) -> Unit = {}) : Transport {
    private class Ev(val kind: String, val status: Int, val extra: Int = 0)

    private val rx = LinkedBlockingQueue<ByteArray>()
    private val events = LinkedBlockingQueue<Ev>()
    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var lost = false
    private var writeChar: BluetoothGattCharacteristic? = null
    private var chunk = 20
    var info = ""; private set

    private val cb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) events.put(Ev("conn", status))
            else { lost = true; events.put(Ev("disc", status)) }
        }
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) { events.put(Ev("svc", status)) }
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) { events.put(Ev("mtu", status, mtu)) }
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) { events.put(Ev("desc", status)) }
        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) { events.put(Ev("write", status)) }
        @Deprecated("API 33 öncesi")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) c.value?.let { rx.put(it.copyOf()) }
        }
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            rx.put(value.copyOf())
        }
    }

    private fun waitFor(kind: String, timeoutMs: Long): Ev {
        val end = System.currentTimeMillis() + timeoutMs
        while (true) {
            val left = end - System.currentTimeMillis()
            if (left <= 0) throw ElmError("BLE zaman aşımı ($kind)")
            val e = events.poll(left, TimeUnit.MILLISECONDS) ?: throw ElmError("BLE zaman aşımı ($kind)")
            if (e.kind == kind) return e
            if (e.kind == "disc") throw ElmError("BLE bağlantısı koptu")
        }
    }

    override fun open() {
        lost = false; events.clear(); rx.clear()
        val g = try {
            device.connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) { throw ElmError("Bluetooth izni yok") } ?: throw ElmError("GATT açılamadı")
        gatt = g
        try {
            waitFor("conn", 15000)
            if (!g.discoverServices()) throw ElmError("Servis keşfi başlatılamadı")
            val st = waitFor("svc", 15000)
            if (st.status != BluetoothGatt.GATT_SUCCESS) throw ElmError("Servis keşfi başarısız (${st.status})")
            val (notify, write) = pickCharacteristics(g.services)
            write.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            writeChar = write
            info = "servis ${short16(notify.service.uuid)} notify ${short16(notify.uuid)} write ${short16(write.uuid)}"
            log(info)
            if (g.requestMtu(185)) {
                try { chunk = maxOf(20, waitFor("mtu", 2500).extra - 3) } catch (_: ElmError) { chunk = 20 }
            }
            g.setCharacteristicNotification(notify, true)
            val cccd = notify.getDescriptor(uuid16("2902")) ?: throw ElmError("CCCD bulunamadı")
            val indicate = (notify.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) == 0
            val v = if (indicate) BluetoothGattDescriptor.ENABLE_INDICATION_VALUE else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(cccd, v)
            else { @Suppress("DEPRECATION") run { cccd.value = v; g.writeDescriptor(cccd) } }
            waitFor("desc", 5000)
        } catch (e: Exception) {
            close(); throw if (e is ElmError) e else ElmError("BLE hatası: ${e.message}")
        }
    }

    override fun close() {
        val g = gatt ?: return
        gatt = null
        try { g.disconnect() } catch (_: Exception) {}
        try { g.close() } catch (_: Exception) {}
        lost = true
    }

    override fun write(data: ByteArray) {
        val g = gatt; val wc = writeChar
        if (g == null || wc == null || lost) throw ElmError("BLE bağlantısı koptu")
        var i = 0
        while (i < data.size) {
            val part = data.copyOfRange(i, minOf(data.size, i + chunk))
            events.removeAll { it.kind == "write" }
            val ok = if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(wc, part, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION") run {
                    wc.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    wc.value = part
                    g.writeCharacteristic(wc)
                }
            }
            if (!ok) throw ElmError("BLE yazma başlatılamadı")
            val r = waitFor("write", 3000)
            if (r.status != BluetoothGatt.GATT_SUCCESS) throw ElmError("BLE yazma hatası (${r.status})")
            i += chunk
        }
    }

    override fun read(timeoutMs: Long): ByteArray {
        var out = rx.poll(timeoutMs, TimeUnit.MILLISECONDS)
        if (out == null) {
            if (lost) throw ElmError("BLE bağlantısı koptu")
            return ByteArray(0)
        }
        while (true) { out = out!! + (rx.poll() ?: break) }
        return out!!
    }
}
