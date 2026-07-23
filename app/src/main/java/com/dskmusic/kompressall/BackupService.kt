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
        private const val ACTION_CANCEL = "com.dskmusic.kompressall.BACKUP_CANCEL"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, BackupService::class.java))
        }

        /** Publica la notificacion de progreso al instante, antes incluso de que el
         *  servicio arranque (startForegroundService tarda un poco en llamar a
         *  onCreate; con lotes pequeños el respaldo puede terminar antes de eso y
         *  el usuario nunca llega a ver el progreso). */
        fun postInitialNotification(context: Context) {
            try {
                NotificationManagerCompat.from(context).notify(
                    NOTIF_ID,
                    buildProgress(context, 0, 1, "", context.getString(R.string.notif_preparing), 0, 0, 0.0)
                )
            } catch (_: SecurityException) {
            }
        }

        private fun buildProgress(
            context: Context, current: Int, total: Int, destination: String, name: String,
            bytesDone: Long, bytesTotal: Long, speedBps: Double
        ): Notification {
            val pct = if (bytesTotal > 0) (bytesDone * 100 / bytesTotal).toInt() else 0
            val fileLine = context.getString(R.string.file_x_of_y, current, total)
            val speedLine = if (bytesTotal > 0 && speedBps > 0) {
                val speedText = formatSize(speedBps.toLong()) + "/s"
                val remainingBytes = (bytesTotal - bytesDone).coerceAtLeast(0)
                val etaSec = (remainingBytes / speedBps).toLong()
                val etaText = if (etaSec >= 60) "${etaSec / 60}m ${etaSec % 60}s" else "${etaSec}s"
                "$speedText · $etaText"
            } else {
                ""
            }
            val bigText = listOf(fileLine, name, speedLine).filter { it.isNotBlank() }.joinToString("\n")

            val cancelIntent = PendingIntent.getService(
                context, 2,
                Intent(context, BackupService::class.java).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val openAppIntent = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE
            )

            return NotificationCompat.Builder(context, CompressionService.CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle(context.getString(R.string.backup_notif_title, destination))
                .setContentText(fileLine)
                .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
                .setProgress(100, pct, bytesTotal <= 0)
                .setOngoing(true)
                .setSilent(true)
                .setOnlyAlertOnce(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setContentIntent(openAppIntent)
                .addAction(0, context.getString(R.string.cancel), cancelIntent)
                .build()
        }
    }

    private var observerJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildProgress(this, 0, 1, "", getString(R.string.notif_preparing), 0, 0, 0.0))
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
                            this@BackupService,
                            state.currentFileIndex, state.totalFiles, state.currentDestination, state.currentFileName,
                            state.overallBytesDone, state.overallBytesTotal, state.speedBytesPerSec
                        )
                    )
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) BackupEngine.cancel()
        return START_NOT_STICKY
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
