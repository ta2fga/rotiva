package com.ta2fga.rotiva.service

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
import com.ta2fga.rotiva.R
import com.ta2fga.rotiva.core.tr1
import com.ta2fga.rotiva.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** Bildirim metni: sürüş yoksa yalnızca bağlantı durumu (kayıt ibaresi YOK). */
fun notificationText(t: TripUi, c: Conn): String = if (t.active) {
    (if (t.paused) "Sürüş duraklatıldı · " else "Sürüş kaydediliyor · ") +
        "${tr1(t.distanceKm)} km · ${(t.durationS / 60).toInt()} dk · ↑${t.ascent.toInt()} m ↓${t.descent.toInt()} m"
} else "${c.mode.label} · ${c.detail.ifEmpty { "hazır" }}"

/**
 * Ön plan servisi (konum türü): ekran kapalıyken de kayıt sürer. Sürüş yalnızca kullanıcı bitirince sona erer.
 *
 * Bildirim güncellemeleri servise ait, ANA iş parçacığındaki kapsamda yapılır: önceden Engine.scope
 * (Dispatchers.Default) içinde nm.notify() çağrılıyordu; "Bağlantıyı kes" durumu değiştirip servisi
 * durdururken yarışta gelen son notify(), servis yok edildikten SONRA aynı kimlikle (ongoing) bildirim
 * yayınlıyor ve bildirim artık hiçbir servise bağlı olmadığı için süreç ölene dek kalıyordu.
 */
class RecordingService : Service() {
    private var wake: PowerManager.WakeLock? = null
    private var job: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @Volatile private var alive = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Engine.init(applicationContext)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CH, "Bağlantı ve sürüş kaydı", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_FINISH) { Engine.finishTrip(); return START_NOT_STICKY }
        if (intent?.action == ACTION_STOP) { Engine.stop(); shutdown(); return START_NOT_STICKY }
        if (!Engine.running()) { shutdown(); return START_NOT_STICKY }     // gecikmiş başlatma: motor zaten durdu
        alive = true
        try {
            ServiceCompat.startForeground(this, ID, build("Başlatılıyor"), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } catch (e: Exception) {
            Engine.message.value = "Ön plan servisi başlatılamadı (konum izni gerekli): ${e.message}"
            Engine.stop(); stopSelf(); return START_NOT_STICKY
        }
        if (wake == null) {
            wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "rotiva:rec").apply { setReferenceCounted(false); acquire() }
        }
        Engine.onServiceStarted()
        job?.cancel()
        job = scope.launch {
            combine(Engine.trip, Engine.conn) { t, c -> Triple(notificationText(t, c), t.active, c.mode) }
                .distinctUntilChanged().collect { (text, active, mode) ->
                    if (!alive) return@collect
                    if (mode == Mode.NONE) { shutdown(); return@collect }   // bağlantı kesildi: bildirim kalmasın
                    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    nm.notify(ID, build(text, active))
                }
        }
        return START_NOT_STICKY
    }

    private fun build(text: String, tripActive: Boolean = false): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = NotificationCompat.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_stat_rotiva)
            .setContentTitle("Rotiva")
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

    /** Ön planı bırakır, bildirimi kaldırır ve servisi durdurur. */
    private fun shutdown() {
        alive = false
        job?.cancel(); job = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(ID)
        stopSelf()
    }

    override fun onDestroy() {
        alive = false
        job?.cancel()
        scope.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(ID)
        wake?.let { if (it.isHeld) it.release() }
        wake = null
        super.onDestroy()
    }

    companion object {
        const val CH = "recording"
        const val ID = 41
        const val ACTION_FINISH = "com.ta2fga.rotiva.FINISH"
        const val ACTION_STOP = "com.ta2fga.rotiva.STOP"
    }
}
