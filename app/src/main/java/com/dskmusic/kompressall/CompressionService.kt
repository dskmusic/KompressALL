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
import com.dskmusic.kompressall.engine.CompressionEngine
import com.dskmusic.kompressall.model.Phase
import com.dskmusic.kompressall.model.formatSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Foreground service: mantiene vivo el proceso mientras se comprime (la app
 * puede minimizarse) y muestra la notificación persistente con progreso y
 * botón de cancelar.
 */
class CompressionService : Service() {

    companion object {
        const val CHANNEL_ID = "kompressall_progress"
        const val DONE_CHANNEL_ID = "kompressall_done"
        private const val NOTIF_ID = 1
        private const val DONE_ID = 2
        private const val ACTION_CANCEL = "com.dskmusic.kompressall.CANCEL"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, CompressionService::class.java))
        }
    }

    private var observerJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // startForeground sin tipo explícito: el sistema toma los tipos declarados
        // en el manifest (dataSync|mediaProcessing). Pasar el tipo a mano provocaba
        // InvalidForegroundServiceTypeException en MIUI/HyperOS.
        startForeground(NOTIF_ID, buildProgress(0, 1, 1, getString(R.string.notif_preparing)))
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "KompressALL:compress")
            .apply { acquire(4 * 60 * 60 * 1000L) }

        observerJob = CoroutineScope(Dispatchers.Main).launch {
            CompressionEngine.state.collect { state ->
                when (state.phase) {
                    Phase.RUNNING -> {
                        val pct = (state.overallProgress * 100).toInt()
                        notifySafe(
                            NOTIF_ID,
                            buildProgress(pct, state.currentIndex + 1, state.items.size, state.currentName)
                        )
                    }
                    Phase.DONE -> {
                        val saved = state.results.sumOf { it.savedBytes }
                        if (!state.cancelled) {
                            notifySafe(
                                DONE_ID,
                                NotificationCompat.Builder(this@CompressionService, DONE_CHANNEL_ID)
                                    .setSmallIcon(R.drawable.ic_notif)
                                    .setContentTitle(getString(R.string.notif_done_title))
                                    .setContentText(
                                        getString(R.string.notif_done_text, state.results.size, formatSize(saved))
                                    )
                                    .setContentIntent(openAppIntent())
                                    .setAutoCancel(true)
                                    .build()
                            )
                        }
                        stopSelf()
                    }
                    Phase.IDLE -> stopSelf()
                    else -> Unit
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) CompressionEngine.cancel()
        return START_NOT_STICKY
    }

    private fun buildProgress(pct: Int, current: Int, total: Int, name: String): Notification {
        val cancelIntent = PendingIntent.getService(
            this, 1,
            Intent(this, CompressionService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("${getString(R.string.compressing_title)} (${getString(R.string.file_x_of_y, current, total)})")
            .setContentText(name)
            .setProgress(100, pct, pct <= 0)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openAppIntent())
            .addAction(0, getString(R.string.cancel), cancelIntent)
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
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
