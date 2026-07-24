package com.dskmusic.kompressall.autosync

import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Environment
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.dskmusic.kompressall.CompressionService
import com.dskmusic.kompressall.R
import com.dskmusic.kompressall.data.Settings
import com.dskmusic.kompressall.engine.CompressionEngine
import com.dskmusic.kompressall.model.JobConfig
import com.dskmusic.kompressall.model.Phase
import com.dskmusic.kompressall.model.formatSize
import com.dskmusic.kompressall.notif.NotificationSounds
import com.dskmusic.kompressall.util.MediaUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Comprueba las carpetas vigiladas y comprime lo nuevo que encuentre. Se apoya
 * en WorkManager para la periodicidad y para que se reprograme solo tras
 * reiniciar el dispositivo (su propio receptor de arranque, no hace falta uno
 * nuestro). Si hay un trabajo manual en curso, se aparta y lo reintenta en la
 * siguiente vuelta en vez de competir con él.
 */
class AutoSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (!Settings.autoSyncEnabled || Settings.autoSyncFolders.isEmpty()) return@withContext Result.success()
        if (CompressionEngine.state.value.phase != Phase.IDLE) return@withContext Result.retry()

        try {
            setForeground(createForegroundInfo())
        } catch (_: Exception) {
        }

        val ctx = applicationContext
        val since = Settings.autoSyncLastCheckedMillis
        val outputRoot = File(Environment.getExternalStorageDirectory(), "KompressALL").absolutePath

        val candidateFiles = Settings.autoSyncFolders.flatMap { folderPath ->
            File(folderPath).listFiles()?.filter { f ->
                f.isFile && f.lastModified() > since &&
                    !f.absolutePath.startsWith(outputRoot) &&
                    isSupportedMedia(f.name)
            }.orEmpty()
        }

        Settings.autoSyncLastCheckedMillis = System.currentTimeMillis()
        if (candidateFiles.isEmpty()) return@withContext Result.success()

        val items = candidateFiles.mapNotNull { MediaUtils.loadEntry(ctx, Uri.fromFile(it)) }
        val cfg = JobConfig(
            imagePreset = Settings.autoSyncImagePreset,
            videoPreset = Settings.autoSyncVideoPreset,
            replaceOriginals = false,
            backupOriginals = false,
            deleteOriginals = false
        )

        val dirName = "AutoSync/" + SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        val root = File(Environment.getExternalStorageDirectory(), "KompressALL")
        val destDir = File(root, dirName)
        val videosDir = File(destDir, "Videos")
        val backupDir = File(root, "Backups/$dirName")

        var savedTotal = 0L
        var processedCount = 0
        for (item in items) {
            if (!Settings.autoSyncEnabled) break
            val result = try {
                if (item.isVideo) CompressionEngine.processVideo(ctx, item, cfg, destDir, videosDir, backupDir)
                else CompressionEngine.processImage(ctx, item, cfg, destDir, backupDir)
            } catch (_: Exception) {
                null
            }
            if (result?.success == true) {
                savedTotal += result.savedBytes
                processedCount++
            }
        }
        if (savedTotal > 0) Settings.totalSaved += savedTotal
        if (processedCount > 0) notifyDone(ctx, processedCount, savedTotal)

        Result.success()
    }

    private fun createForegroundInfo(): ForegroundInfo {
        val notification = NotificationCompat.Builder(applicationContext, CompressionService.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(applicationContext.getString(R.string.auto_sync_notif_title))
            .setOngoing(true)
            .setSilent(true)
            .build()
        return ForegroundInfo(AUTO_SYNC_NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private fun notifyDone(ctx: Context, count: Int, saved: Long) {
        try {
            NotificationSounds.rebuildDoneChannel(ctx)
            NotificationManagerCompat.from(ctx).notify(
                AUTO_SYNC_DONE_NOTIF_ID,
                NotificationCompat.Builder(ctx, NotificationSounds.doneChannelId())
                    .setSmallIcon(R.drawable.ic_notif)
                    .setContentTitle(ctx.getString(R.string.auto_sync_done_title))
                    .setContentText(ctx.getString(R.string.notif_done_text, count, formatSize(saved)))
                    .setAutoCancel(true)
                    .build()
            )
        } catch (_: SecurityException) {
        }
    }

    private fun isSupportedMedia(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in IMAGE_EXTS || ext in VIDEO_EXTS
    }

    companion object {
        private const val AUTO_SYNC_NOTIF_ID = 5
        private const val AUTO_SYNC_DONE_NOTIF_ID = 6
        private val IMAGE_EXTS = listOf("jpg", "jpeg", "png", "webp", "heic", "heif")
        private val VIDEO_EXTS = listOf("mp4", "mkv", "webm", "3gp", "mov", "avi", "m4v", "ts")
    }
}

/** Programa/cancela el trabajo periódico según los ajustes actuales. Idempotente:
 *  se puede llamar en cada arranque de la app sin miedo a duplicar nada. */
object AutoSyncScheduler {
    private const val WORK_NAME = "kompressall_auto_sync"

    fun reschedule(context: Context) {
        val workManager = WorkManager.getInstance(context.applicationContext)
        if (!Settings.autoSyncEnabled || Settings.autoSyncFolders.isEmpty()) {
            workManager.cancelUniqueWork(WORK_NAME)
            return
        }
        val intervalMinutes = Settings.autoSyncFrequencyMinutes.coerceAtLeast(15).toLong()
        val request = PeriodicWorkRequestBuilder<AutoSyncWorker>(intervalMinutes, TimeUnit.MINUTES).build()
        workManager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}
