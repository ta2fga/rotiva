package com.ta2fga.obd2.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.ta2fga.obd2.R
import com.ta2fga.obd2.core.tr1
import com.ta2fga.obd2.ui.MainActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Ön plan servisi (konum türü): ekran kapalıyken de kayıt sürer. Sürüş yalnızca kullanıcı bitirince sona erer. */
class RecordingService : Service() {
    private var wake: PowerManager.WakeLock? = null
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Engine.init(applicationContext)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CH, "Sürüş kaydı", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_FINISH) { Engine.finishTrip(); return START_NOT_STICKY }
        if (intent?.action == ACTION_STOP) { Engine.stop(); return START_NOT_STICKY }
        try {
            ServiceCompat.startForeground(this, ID, build("Başlatılıyor"), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } catch (e: Exception) {
            Engine.message.value = "Ön plan servisi başlatılamadı (konum izni gerekli): ${e.message}"
            Engine.stop(); stopSelf(); return START_NOT_STICKY
        }
        if (wake == null) {
            wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "obd2:rec").apply { setReferenceCounted(false); acquire() }
        }
        Engine.onServiceStarted()
        job?.cancel()
        job = Engine.scope.launch {
            combine(Engine.trip, Engine.conn) { t, c -> t to c }.collect { (t, c) ->
                val text = if (t.active) {
                    (if (t.paused) "Duraklatıldı · " else "Sürüş kaydediliyor · ") +
                        "${tr1(t.distanceKm)} km · ${(t.durationS / 60).toInt()} dk · ↑${t.ascent.toInt()} m ↓${t.descent.toInt()} m"
                } else "${c.mode.label} · ${c.detail.ifEmpty { "hazır" }}"
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(ID, build(text, t.active))
            }
        }
        return START_NOT_STICKY
    }

    private fun build(text: String, tripActive: Boolean = false): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = NotificationCompat.Builder(this, CH)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("PSA OBD2 Tanı")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
        if (tripActive) {
            b.addAction(0, "Sürüşü bitir", PendingIntent.getService(this, 1,
                Intent(this, RecordingService::class.java).setAction(ACTION_FINISH), PendingIntent.FLAG_IMMUTABLE))
        }
        b.addAction(0, "Bağlantıyı kes", PendingIntent.getService(this, 2,
            Intent(this, RecordingService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE))
        return b.build()
    }

    override fun onDestroy() {
        job?.cancel()
        wake?.let { if (it.isHeld) it.release() }
        wake = null
        super.onDestroy()
    }

    companion object {
        const val CH = "recording"
        const val ID = 41
        const val ACTION_FINISH = "com.ta2fga.obd2.FINISH"
        const val ACTION_STOP = "com.ta2fga.obd2.STOP"
    }
}
