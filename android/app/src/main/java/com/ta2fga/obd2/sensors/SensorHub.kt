package com.ta2fga.obd2.sensors

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.ta2fga.obd2.core.AltitudeFuser
import com.ta2fga.obd2.core.VehicleFrame

class GpsFix(
    val t: Double, val lat: Double, val lon: Double, val speedKmh: Double?, val bearing: Double?,
    val alt: Double?, val accuracy: Double?, val vAccuracy: Double?,
)

/**
 * Konum (Fused varsa Fused, yoksa LocationManager; 1 Hz yüksek doğruluk), ivmeölçer (TYPE_LINEAR_ACCELERATION,
 * yedek: ivme - alçak geçirgen yerçekimi) ve barometre. Hepsi tek bir kilit ([lock]) altında VehicleFrame ve
 * AltitudeFuser'a beslenir.
 */
class SensorHub(private val ctx: Context, val frame: VehicleFrame, val fuser: AltitudeFuser,
                private val onFix: (GpsFix) -> Unit) {
    val lock = Any()
    private val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val thread = HandlerThread("sensors").apply { start() }
    private val handler = Handler(thread.looper)
    private var fused: FusedLocationProviderClient? = null
    private var lm: LocationManager? = null
    var usingFused = false; private set
    var hasAccel = false; private set
    var hasBaro = false; private set
    private val grav = DoubleArray(3)
    private var gravInit = false
    private var haveLinear = false
    private var lastBaroT = 0.0

    private val accelListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val v = e.values
            synchronized(lock) {
                when (e.sensor.type) {
                    Sensor.TYPE_ACCELEROMETER -> {
                        // yerçekimi: alçak geçirgen süzgeç
                        if (!gravInit) { grav[0] = v[0].toDouble(); grav[1] = v[1].toDouble(); grav[2] = v[2].toDouble(); gravInit = true }
                        else for (i in 0..2) grav[i] = 0.98 * grav[i] + 0.02 * v[i]
                        if (!haveLinear) frame.onAccel(v[0] - grav[0], v[1] - grav[1], v[2] - grav[2], grav[0], grav[1], grav[2])
                    }
                    Sensor.TYPE_LINEAR_ACCELERATION -> if (gravInit) frame.onAccel(v[0].toDouble(), v[1].toDouble(), v[2].toDouble(), grav[0], grav[1], grav[2])
                }
            }
        }
        override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    }

    private val baroListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val t = System.currentTimeMillis() / 1000.0
            if (t - lastBaroT < 0.5) return
            lastBaroT = t
            synchronized(lock) { fuser.onBaro(t, e.values[0].toDouble()) }
        }
        override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    }

    private fun handle(l: Location) {
        val t = System.currentTimeMillis() / 1000.0
        val fix = GpsFix(
            t, l.latitude, l.longitude,
            if (l.hasSpeed()) l.speed * 3.6 else null,
            if (l.hasBearing()) l.bearing.toDouble() else null,
            if (l.hasAltitude()) l.altitude else null,
            if (l.hasAccuracy()) l.accuracy.toDouble() else null,
            if (l.hasVerticalAccuracy()) l.verticalAccuracyMeters.toDouble() else null,
        )
        synchronized(lock) {
            if (fix.alt != null) fuser.onGps(t, fix.alt, fix.vAccuracy ?: fix.accuracy?.times(1.5))
            if (fix.speedKmh != null) frame.onSpeed(t, fix.speedKmh / 3.6, fix.bearing)
        }
        onFix(fix)
    }

    @SuppressLint("MissingPermission")
    fun start() {
        val acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val lin = sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        hasAccel = acc != null
        if (acc != null) sm.registerListener(accelListener, acc, SensorManager.SENSOR_DELAY_GAME, handler)
        if (lin != null && acc != null) { haveLinear = true; sm.registerListener(accelListener, lin, SensorManager.SENSOR_DELAY_GAME, handler) }
        val bar = sm.getDefaultSensor(Sensor.TYPE_PRESSURE)
        hasBaro = bar != null
        if (bar != null) sm.registerListener(baroListener, bar, SensorManager.SENSOR_DELAY_NORMAL, handler)

        val gpsOk = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(ctx) == ConnectionResult.SUCCESS
        try {
            if (gpsOk) {
                usingFused = true
                val c = LocationServices.getFusedLocationProviderClient(ctx)
                fused = c
                val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
                    .setMinUpdateIntervalMillis(500L).setWaitForAccurateLocation(false).build()
                c.requestLocationUpdates(req, fusedCb, Looper.getMainLooper())
            } else startLm()
        } catch (e: SecurityException) {
        } catch (e: Exception) { startLm() }
    }

    private val fusedCb = object : LocationCallback() {
        override fun onLocationResult(r: LocationResult) { r.locations.forEach { handle(it) } }
    }

    private val lmCb = LocationListener { handle(it) }

    @SuppressLint("MissingPermission")
    private fun startLm() {
        usingFused = false
        val m = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        lm = m
        try { m.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, lmCb, Looper.getMainLooper()) } catch (_: Exception) {}
    }

    fun stop() {
        sm.unregisterListener(accelListener)
        sm.unregisterListener(baroListener)
        try { fused?.removeLocationUpdates(fusedCb) } catch (_: Exception) {}
        try { lm?.removeUpdates(lmCb) } catch (_: Exception) {}
        thread.quitSafely()
    }
}
