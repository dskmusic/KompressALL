package com.dskmusic.kompressall

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.dskmusic.kompressall.backup.BackupEngine
import com.dskmusic.kompressall.model.formatSize
import com.dskmusic.kompressall.notif.NotificationSounds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Foreground service para el respaldo por SFTP: mismo patron que
 * CompressionService, para que la subida siga aunque se cierre la app desde
 * recientes.
 */
class BackupService : Service() {

    companion object {
        private const val NOTIF_ID = 3
        private const val DONE_ID = 4

        fun start(context: Context) {
            context.startForegroundService(Intent(context, BackupService::class.java))
        }
    }

    private var observerJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildProgress(0, 1, "", getString(R.string.notif_preparing), 0, 0, 0.0))
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "KompressALL:backup")
            .apply { acquire(4 * 60 * 60 * 1000L) }

        observerJob = CoroutineScope(Dispatchers.Main).launch {
            BackupEngine.state.collect { state ->
                if (state.done) {
                    val ok = state.results.count { it.success }
                    val failed = state.results.size - ok
                    NotificationSounds.rebuildDoneChannel(this@BackupService)
                    notifySafe(
                        DONE_ID,
                        NotificationCompat.Builder(this@BackupService, NotificationSounds.doneChannelId())
                            .setSmallIcon(R.drawable.ic_notif)
                            .setContentTitle(getString(R.string.backup_notif_done_title))
                            .setContentText(getString(R.string.backup_notif_done_text, ok, failed))
                            .setContentIntent(openAppIntent())
                            .setAutoCancel(true)
                            .build()
                    )
                    stopSelf()
                } else if (state.running) {
                    notifySafe(
                        NOTIF_ID,
                        buildProgress(
                            state.currentFileIndex, state.totalFiles, state.currentDestination, state.currentFileName,
                            state.overallBytesDone, state.overallBytesTotal, state.speedBytesPerSec
                        )
                    )
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    private fun buildProgress(
        current: Int, total: Int, destination: String, name: String,
        bytesDone: Long, bytesTotal: Long, speedBps: Double
    ): Notification {
        val pct = if (bytesTotal > 0) (bytesDone * 100 / bytesTotal).toInt() else 0
        val detail = if (bytesTotal > 0 && speedBps > 0) {
            val speedText = formatSize(speedBps.toLong()) + "/s"
            val remainingBytes = (bytesTotal - bytesDone).coerceAtLeast(0)
            val etaSec = (remainingBytes / speedBps).toLong()
            val etaText = if (etaSec >= 60) "${etaSec / 60}m ${etaSec % 60}s" else "${etaSec}s"
            "$name · $speedText · $etaText"
        } else {
            name
        }
        return NotificationCompat.Builder(this, CompressionService.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("${getString(R.string.backup_notif_title, destination)} (${getString(R.string.file_x_of_y, current, total)})")
            .setContentText(detail)
            .setProgress(100, pct, bytesTotal <= 0)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openAppIntent())
            .build()
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE
    )

    private fun notifySafe(id: Int, notification: Notification) {
        try {
            NotificationManagerCompat.from(this).notify(id, notification)
        } catch (_: SecurityException) {
        }
    }

    override fun onDestroy() {
        observerJob?.cancel()
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {
        }
        BackupEngine.reset()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
