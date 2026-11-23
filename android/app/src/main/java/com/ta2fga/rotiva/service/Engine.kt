package com.ta2fga.rotiva.service

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.ta2fga.rotiva.ble.BleScanner
import com.ta2fga.rotiva.ble.BleTransport
import com.ta2fga.rotiva.core.*
import com.ta2fga.rotiva.data.Prefs
import com.ta2fga.rotiva.sensors.GpsFix
import com.ta2fga.rotiva.sensors.SensorHub
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max

enum class Mode(val label: String) { NONE("Bağlı değil"), OBD("OBD adaptörü"), DEMO("Demo"), PHONE("Telefon modu") }
enum class LinkState { IDLE, CONNECTING, CONNECTED, RECONNECTING }

data class Conn(val mode: Mode = Mode.NONE, val state: LinkState = LinkState.IDLE, val detail: String = "",
                val obdOk: Boolean = false, val gpsOk: Boolean = false)

/** Canlı değerler (2 Hz). */
data class Live(
    val speed: Double? = null, val rpm: Double? = null, val gear: Int? = null, val load: Double? = null,
    val coolant: Double? = null, val alt: Double? = null, val grade: Double? = null,
    val gLong: Double? = null, val gLat: Double? = null, val calibrated: Boolean = false,
    val gpsAcc: Double? = null, val hasBaro: Boolean = false, val hasAccel: Boolean = false,
)

data class TripUi(
    val active: Boolean = false, val paused: Boolean = false, val distanceKm: Double = 0.0, val durationS: Double = 0.0,
    val ascent: Double = 0.0, val descent: Double = 0.0, val score: Int? = null, val samples: Int = 0,
)

/**
 * Uygulama motoru (süreç tekili): kaynaklar (OBD/Demo/Telefon) + sensörler + kayıt. Ön plan servisi
 * ([RecordingService]) süreci canlı tutar; ekran kapalıyken de kayıt sürer.
 */
object Engine {
    private lateinit var app: Context
    private var inited = false
    lateinit var prefs: Prefs; private set
    lateinit var recorder: TripRecorder; private set
    lateinit var store: TripStore; private set
    lateinit var scanner: BleScanner; private set

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val obdDispatcher = Executors.newSingleThreadExecutor { Thread(it, "obd-io") }.asCoroutineDispatcher()

    val conn = MutableStateFlow(Conn())
    val values = MutableStateFlow<Map<String, Double?>>(emptyMap())
    val live = MutableStateFlow(Live())
    val trip = MutableStateFlow(TripUi())
    val message = MutableStateFlow<String?>(null)
    val finishedTripId = MutableStateFlow<String?>(null)
    val unfinished = MutableStateFlow<TripRecorder.Unfinished?>(null)
    val catalog: List<Item> by lazy { buildCatalog(null) + PHONE_ITEMS }
    /** UI'nin görmek istediği anahtarlar (Panom + görünür liste). */
    val wanted = MutableStateFlow<Set<String>>(emptySet())
    val log = ArrayDeque<String>()

    private var mainJob: Job? = null
    private var obdJob: Job? = null
    private var tickJob: Job? = null
    private var hub: SensorHub? = null
    private var elm: Elm327? = null
    private var mode = Mode.NONE
    private var deviceAddress: String? = null
    private var gearEst = GearEstimator()

    private val frame = VehicleFrame()
    private val fuser = AltitudeFuser()
    private val demo = DemoWorld()
    private val obdVals = HashMap<String, Double?>()
    private val obdTs = HashMap<String, Double>()
    @Volatile private var obdSpeedTs = 0.0
    @Volatile private var lastFix: GpsFix? = null
    private var prevFix: GpsFix? = null
    private var elevTracker = ElevationTracker()
    private var tripDistM = 0.0
    private var lastTickT = 0.0
    private var altBuf = ArrayDeque<Pair<Double, Double>>()   // (mesafe m, yükseklik m)
    private var lastBearingRate = 0.0
    private var lastScoreT = 0.0
    private var lastScore: Int? = null
    private var tripStartMono = 0.0

    val PHONE_ITEMS = listOf(
        Item("gps_speed", "GPS Hızı", "km/s", "Telefon", 0.0, 200.0, decimals = 0),
        Item("alt", "Yükseklik (GPS+barometre)", "m", "Telefon", 0.0, 3000.0, decimals = 0),
        Item("grade", "Yol Eğimi", "%", "Telefon", -15.0, 15.0, decimals = 1),
        Item("g_long", "Boylamasına İvme", "g", "Telefon", -1.0, 1.0, decimals = 2),
        Item("g_lat", "Yanal İvme", "g", "Telefon", -1.0, 1.0, warnHi = 0.35, critHi = 0.6, decimals = 2),
        Item("asc", "Toplam Çıkış", "m", "Telefon", 0.0, 3000.0, decimals = 0),
        Item("desc", "Toplam İniş", "m", "Telefon", 0.0, 3000.0, decimals = 0),
    )

    fun init(ctx: Context) {
        if (inited) return
        inited = true
        app = ctx.applicationContext
        prefs = Prefs(app)
        store = TripStore(File(app.filesDir, "trips"))
        scanner = BleScanner(app)
        gearEst = GearEstimator.fromJson(prefs.gearJson)
        recorder = TripRecorder(store, TripCfg(autoStart = prefs.autoStart.value), gearEst)
        scope.launch { prefs.autoStart.collect { recorder.cfg.autoStart = it } }
        unfinished.value = recorder.findUnfinished()
    }

    private fun logLine(s: String) { synchronized(log) { log.addLast(s); while (log.size > 200) log.removeFirst() } }

    // ------------------------------------------------------------------ yaşam döngüsü
    fun running() = mode != Mode.NONE

    fun begin(newMode: Mode, address: String? = null): Boolean {
        if (running()) stop()
        mode = newMode
        deviceAddress = address
        conn.value = Conn(newMode, LinkState.CONNECTING, "Başlatılıyor")
        try {
            ContextCompat.startForegroundService(app, Intent(app, RecordingService::class.java))
        } catch (e: Exception) {
            mode = Mode.NONE
            conn.value = Conn()
            message.value = "Servis başlatılamadı: ${e.message}"
            return false
        }
        return true
    }

    /** Servis ön plana alınınca çağrılır. */
    fun onServiceStarted() {
        if (mainJob?.isActive == true) return
        mainJob = scope.launch { runSources() }
    }

    fun stop() {
        mainJob?.cancel(); obdJob?.cancel(); tickJob?.cancel()
        mainJob = null; obdJob = null; tickJob = null
        runCatching { elm?.disconnect() }
        elm = null
        hub?.stop(); hub = null
        savePrefsGear()
        if (recorder.active && !recorder.paused) recorder.pause()
        if (recorder.active) recorder.flush()
        mode = Mode.NONE
        conn.value = Conn()
        values.value = emptyMap()
        live.value = Live()
        app.stopService(Intent(app, RecordingService::class.java))
    }

    private fun savePrefsGear() { prefs.gearJson = gearEst.toJson() }

    private suspend fun runSources() = coroutineScope {
        obdVals.clear(); obdTs.clear(); lastFix = null; prevFix = null
        lastTickT = 0.0
        if (recorder.active) rebuildTripState(recorder.samples)
        when (mode) {
            Mode.DEMO -> { conn.value = Conn(Mode.DEMO, LinkState.CONNECTED, "Simülatör", obdOk = true, gpsOk = true); obdJob = launch(obdDispatcher) { demoObdLoop() } }
            Mode.OBD -> { startHub(); obdJob = launch(obdDispatcher) { obdLoop() } }
            Mode.PHONE -> { startHub(); conn.value = Conn(Mode.PHONE, LinkState.CONNECTED, "GPS hızı kullanılıyor") }
            else -> {}
        }
        tickJob = launch { tickLoop() }
    }

    private fun startHub() {
        val h = SensorHub(app, frame, fuser) { fix -> lastFix = fix }
        hub = h
        h.start()
    }

    // ------------------------------------------------------------------ OBD
    private val demoKeys = PIDS.map { it.key }

    private fun demoObdLoop() {
        // Demo: gerçek ELM istemcisi + simülatör taşıması (masaüstündeki gibi)
        val e = Elm327(SimTransport(), ::logLine)
        elm = e
        try {
            e.connect()
            pollLoop(e)
        } catch (ex: Exception) {
            if (mode == Mode.DEMO && obdJob?.isActive != false) message.value = "Demo hatası: ${ex.message}"
        }
    }

    private suspend fun obdLoop() {
        val btm = app.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        var first = true
        while (currentCoroutineContext().isActive) {
            val addr = deviceAddress ?: return
            conn.update { it.copy(state = if (first) LinkState.CONNECTING else LinkState.RECONNECTING, detail = if (first) "Bağlanıyor" else "Yeniden bağlanıyor", obdOk = false) }
            val e = try {
                @SuppressLint("MissingPermission")
                val dev = btm.adapter.getRemoteDevice(addr)
                Elm327(BleTransport(app, dev, ::logLine), ::logLine).also { elm = it }
            } catch (ex: Exception) { message.value = "Bluetooth hatası: ${ex.message}"; delay(4000); continue }
            try {
                val info = withContext(NonCancellable) { e.connect() }
                first = false
                conn.update { it.copy(state = LinkState.CONNECTED, obdOk = true,
                    detail = info.version + (if (info.protocol.isNotEmpty()) " · ${info.protocol}" else "") + (if (!info.ecuOk) " · ECU yanıt vermiyor" else "")) }
                pollLoop(e)
            } catch (ex: CancellationException) { throw ex
            } catch (ex: Exception) {
                logLine("! ${ex.message}")
            }
            runCatching { e.disconnect() }
            // OBD verisini duraklat; iz kaydı (GPS) sürer
            synchronized(obdVals) { obdVals.clear(); obdTs.clear() }
            values.value = emptyMap()
            conn.update { it.copy(state = LinkState.RECONNECTING, obdOk = false, detail = "Bağlantı koptu, yeniden deneniyor") }
            first = false
            delay(3000)
        }
    }

    /** Sürekli okuma: hızlılar (devir, hız) her turda; diğerleri dönüşümlü. Bağlantı hatasında istisna fırlatır. */
    private fun pollLoop(e: Elm327) {
        var cycle = 0
        var cursor = 0
        val recording = listOf("load", "coolant", "map", "maf", "frp")
        while (e.connected && (obdJob?.isActive != false)) {
            val sup = if (e.supported.isEmpty()) null else e.supported
            val want = wanted.value
            val slow = (recording + want).distinct().filter { k ->
                k != "rpm" && k != "speed" && (BY_KEY[k]?.let { sup == null || it.pid in sup } ?: (k == BATTERY_KEY && cycle % 15 == 0))
            }
            val plan = ArrayList<String>()
            if (sup == null || 0x0C in sup) plan.add("rpm")
            if (sup == null || 0x0D in sup) plan.add("speed")
            if (slow.isNotEmpty()) repeat(minOf(3, slow.size)) { plan.add(slow[cursor % slow.size]); cursor++ }
            if (BATTERY_KEY in want && cycle % 15 == 0 && BATTERY_KEY !in plan) plan.add(BATTERY_KEY)
            for (k in plan) {
                val v = if (k == BATTERY_KEY) e.voltage() else BY_KEY[k]?.let { e.readPid(it) }
                val now = System.currentTimeMillis() / 1000.0
                synchronized(obdVals) { obdVals[k] = v; if (v != null) obdTs[k] = now }
                if (k == "speed" && v != null) obdSpeedTs = now
            }
            publishValues()
            cycle++
            if (plan.isEmpty()) Thread.sleep(100)
        }
        throw ElmError("Bağlantı kesildi")
    }

    private fun fresh(k: String, now: Double, maxAge: Double = 4.0): Double? = synchronized(obdVals) {
        val ts = obdTs[k] ?: return null
        if (now - ts > maxAge) null else obdVals[k]
    }

    private var lastPublish = 0L
    private fun publishValues() {
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastPublish < 150) return
        lastPublish = nowMs
        val now = nowMs / 1000.0
        val m = HashMap<String, Double?>()
        synchronized(obdVals) { for ((k, _) in obdVals) m[k] = fresh(k, now, 6.0) }
        values.update { old -> old.filterKeys { it in PHONE_ITEMS.map { p -> p.key } } + m }
    }

    // ------------------------------------------------------------------ DTC
    suspend fun readDtcs(): Map<Int, List<String>> = withContext(Dispatchers.IO) {
        val e = elm ?: throw ElmError("OBD bağlı değil")
        mapOf(0x03 to e.readDtcs(0x03), 0x07 to e.readDtcs(0x07), 0x0A to e.readDtcs(0x0A))
    }

    suspend fun clearDtcs(): Boolean = withContext(Dispatchers.IO) {
        val e = elm ?: throw ElmError("OBD bağlı değil")
        e.clearDtcs()
    }

    fun obdAvailable() = elm?.connected == true && (mode == Mode.OBD || mode == Mode.DEMO)

    // ------------------------------------------------------------------ örnekleme (2 Hz)
    private suspend fun tickLoop() {
        var demoT0 = System.currentTimeMillis() / 1000.0
        val cyc = DriveCycle()
        while (currentCoroutineContext().isActive) {
            delay(500)
            val now = System.currentTimeMillis() / 1000.0
            try { tick(now, if (mode == Mode.DEMO) now - demoT0 else 0.0, cyc) } catch (e: Exception) { logLine("tick: $e") }
        }
    }

    private fun tick(now: Double, demoT: Double, cyc: DriveCycle) {
        val dt = if (lastTickT == 0.0) 0.5 else (now - lastTickT).coerceIn(0.0, 3.0)
        lastTickT = now
        val s = Sample(now)
        var gpsOk = false
        var hasAccel = false
        var hasBaro = false
        var obdOk = false

        if (mode == Mode.DEMO) {
            val p = cyc.at(demoT)
            s.rpm = fresh("rpm", now) ?: p.rpm; s.speed = fresh("speed", now) ?: p.speed
            s.load = fresh("load", now); s.coolant = fresh("coolant", now); s.map = fresh("map", now)
            s.maf = fresh("maf", now); s.frp = fresh("frp", now)
            demo.step(demoT, s.speed ?: 0.0)
            s.lat = demo.lat; s.lon = demo.lon; s.gpsSpeed = s.speed; s.bearing = demo.bearing; s.alt = demo.alt
            s.acc = 4.0; s.gLong = demo.gLong; s.gLat = demo.gLat
            gpsOk = true; obdOk = true; hasAccel = true
        } else {
            val fix = lastFix
            gpsOk = fix != null && now - fix.t < 4.0
            s.rpm = fresh("rpm", now); s.load = fresh("load", now); s.coolant = fresh("coolant", now)
            s.map = fresh("map", now); s.maf = fresh("maf", now); s.frp = fresh("frp", now)
            val obdSpeed = fresh("speed", now, 2.5)
            obdOk = obdSpeed != null || s.rpm != null
            if (gpsOk && fix != null) {
                s.lat = fix.lat; s.lon = fix.lon; s.gpsSpeed = fix.speedKmh?.let { if (it < 1.0) 0.0 else it }
                s.bearing = fix.bearing; s.acc = fix.accuracy
            }
            s.speed = obdSpeed ?: s.gpsSpeed
            hub?.let { h ->
                hasAccel = h.hasAccel; hasBaro = h.hasBaro
                synchronized(h.lock) {
                    fuser.alt?.let { s.alt = it }
                    s.baroAlt = fuser.baroAlt
                    val (l, la) = frame.takeTick()
                    s.gLong = l; s.gLat = la
                }
            }
            if (s.gLat == null && gpsOk && fix != null) {
                // kalibrasyon yokken yanal ivme için GPS yön değişiminden tahmin
                val pf = prevFix
                if (pf != null && fix !== pf && fix.t > pf.t && fix.bearing != null && pf.bearing != null && fix.speedKmh != null) {
                    val rate = (((fix.bearing - pf.bearing + 540) % 360) - 180) / (fix.t - pf.t)
                    lastBearingRate = rate
                    s.gLat = lateralFromBearing(fix.speedKmh / 3.6, rate)
                }
                prevFix = fix
            }
            if (!gpsOk) { s.alt = null }
        }

        // vites tahmini (OBD hızı + devir)
        val gearSpeed = if (mode == Mode.PHONE) null else s.speed
        s.gear = gearEst.update(now, s.rpm, gearSpeed)

        // canlı eğim ve mesafe
        val v = s.speed ?: 0.0
        if (recorder.active || mode != Mode.NONE) tripDistM += v / 3.6 * dt
        var grade: Double? = null
        s.alt?.let { a ->
            altBuf.addLast(tripDistM to a)
            while (altBuf.size > 2 && tripDistM - altBuf.first().first > 150) altBuf.removeFirst()
            val ref = altBuf.firstOrNull { tripDistM - it.first >= 50 }
            if (ref != null) {
                val last = altBuf.last { tripDistM - it.first >= 50 }
                val dd = tripDistM - last.first
                if (dd >= 50) grade = ((a - last.second) / dd * 100).coerceIn(-30.0, 30.0)
            }
        }

        // kayıt
        val wasActive = recorder.active
        val hasData = s.speed != null || s.rpm != null
        if (hasData) {
            val ev = recorder.feed(s)
            if (ev == "started") { tripStartMono = now; rebuildTripState(recorder.samples) }
        } else if (recorder.stalled(now)) recorder.pause(now)
        if (!wasActive && recorder.active) { /* otomatik başladı */ }
        if (recorder.active) s.alt?.let { elevTracker.update(it) }

        // paylaşılan değerler
        val phone = HashMap<String, Double?>()
        phone["gps_speed"] = s.gpsSpeed; phone["alt"] = s.alt; phone["grade"] = grade
        phone["g_long"] = s.gLong; phone["g_lat"] = s.gLat
        phone["asc"] = if (recorder.active) elevTracker.ascent else null
        phone["desc"] = if (recorder.active) elevTracker.descent else null
        publishValues()
        values.update { it + phone }

        live.value = Live(s.speed, s.rpm, s.gear, s.load, s.coolant, s.alt, grade, s.gLong, s.gLat,
            frame.calibrated || mode == Mode.DEMO, s.acc, hasBaro, hasAccel)
        conn.update { c -> if (c.gpsOk != gpsOk) c.copy(gpsOk = gpsOk) else c }

        // gelişmiş skor (15 sn'de bir) ve durum
        if (recorder.active && now - lastScoreT > 15) {
            lastScoreT = now
            val snap = ArrayList(recorder.samples)
            scope.launch(Dispatchers.Default) { lastScore = runCatching { analyze(snap.map { it.copy() }).total }.getOrNull() }
        }
        val dur = if (recorder.samples.size >= 2) recorder.samples.last().t - recorder.samples.first().t else 0.0
        trip.value = TripUi(recorder.active, recorder.paused, tripDistM / 1000, dur, elevTracker.ascent, elevTracker.descent,
            lastScore, recorder.samples.size)
        if (gearEst.dirty && now.toLong() % 20 == 0L) { savePrefsGear(); gearEst.dirty = false }
    }

    private fun rebuildTripState(samples: List<Sample>) {
        elevTracker = ElevationTracker()
        samples.forEach { it.alt?.let { a -> elevTracker.update(a) } }
        tripDistM = distanceKm(samples) * 1000
        altBuf.clear(); lastScore = null
    }

    // ------------------------------------------------------------------ sürüş kontrolü
    fun startTrip() {
        if (recorder.active) return
        elevTracker = ElevationTracker(); tripDistM = 0.0; altBuf.clear(); lastScore = null
        recorder.start(System.currentTimeMillis() / 1000.0, withPrebuffer = false)
    }

    /** "Sürüşü bitir": kaydı kaydeder. */
    fun finishTrip() {
        scope.launch(Dispatchers.IO) {
            val sum = recorder.stop()
            savePrefsGear()
            elevTracker = ElevationTracker(); tripDistM = 0.0; altBuf.clear(); lastScore = null
            trip.value = TripUi()
            if (sum != null) finishedTripId.value = sum.getString("id")
            else message.value = "Sürüş çok kısa olduğu için kaydedilmedi"
        }
    }

    fun resumeUnfinished() {
        unfinished.value?.let { recorder.resumeUnfinished(it); rebuildTripState(recorder.samples) }
        unfinished.value = null
        message.value = "Yarım kalan sürüş açıldı; veri gelince devam eder"
    }

    fun finishUnfinished() {
        unfinished.value?.let { recorder.resumeUnfinished(it) }
        unfinished.value = null
        finishTrip()
    }

    fun discardUnfinished() { recorder.discardUnfinished(); unfinished.value = null }

    fun resetGear() { gearEst.reset(); savePrefsGear() }
    fun gearRatios(): DoubleArray = gearEst.ratios
    fun gearCounts(): IntArray = gearEst.counts

}
