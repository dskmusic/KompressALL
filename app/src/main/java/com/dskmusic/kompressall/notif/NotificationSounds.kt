package com.dskmusic.kompressall.notif

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.net.toUri
import com.dskmusic.kompressall.CompressionService
import com.dskmusic.kompressall.R
import com.dskmusic.kompressall.data.Settings

/** Sonidos disponibles: assets/notifications/Sound01.mp3 .. Sound10.mp3. */
val NOTIFICATION_SOUND_OPTIONS = (1..10).map { "Sound%02d.mp3".format(it) }

private val VIBRATION_PATTERNS: Map<String, LongArray> = linkedMapOf(
    "default" to longArrayOf(0, 250, 250, 250),
    "short" to longArrayOf(0, 150),
    "long" to longArrayOf(0, 800),
    "double" to longArrayOf(0, 150, 120, 150),
    "pulse" to longArrayOf(0, 100, 100, 100, 100, 100),
    "none" to longArrayOf(0)
)

val VIBRATION_OPTIONS = VIBRATION_PATTERNS.keys.toList()

fun vibrationPatternFor(key: String): LongArray = VIBRATION_PATTERNS[key] ?: VIBRATION_PATTERNS.getValue("default")

object NotificationSounds {

    /** Copia el asset elegido a la coleccion de sonidos de notificacion de MediaStore
     *  (asi el sistema puede reproducirlo desde el canal sin permisos especiales) y
     *  devuelve su Uri. Si ya se habia registrado antes, reutiliza esa entrada. */
    fun registerAsset(context: Context, assetName: String): Uri? {
        return try {
            val resolver = context.contentResolver
            val displayName = "KompressALL_$assetName"
            val existingId = resolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                arrayOf(displayName),
                null
            )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
            if (existingId != null) {
                return ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, existingId)
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, "audio/mpeg")
                if (Build.VERSION.SDK_INT >= 29) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_NOTIFICATIONS)
                    put(MediaStore.Audio.Media.IS_NOTIFICATION, 1)
                }
            }
            val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values) ?: return null
            resolver.openOutputStream(uri)?.use { out ->
                context.assets.open("notifications/$assetName").use { it.copyTo(out) }
            }
            uri
        } catch (_: Exception) {
            null
        }
    }

    /** Recrea el canal de "terminado" con el sonido y patron de vibracion actuales.
     *  Android ignora los cambios en un canal ya creado, asi que hay que borrarlo y
     *  volver a crearlo cada vez que el usuario cambia estas opciones. */
    fun rebuildDoneChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.deleteNotificationChannel(CompressionService.DONE_CHANNEL_ID)
        val soundUri = Settings.notificationSoundUri.takeIf { it.isNotBlank() }?.toUri()
        val pattern = vibrationPatternFor(Settings.notificationVibration)
        val channel = NotificationChannel(
            CompressionService.DONE_CHANNEL_ID,
            context.getString(R.string.notif_done_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            setBypassDnd(true)
            if (pattern.size > 1) {
                enableVibration(true)
                vibrationPattern = pattern
            } else {
                enableVibration(false)
            }
            if (soundUri != null) {
                setSound(
                    soundUri,
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
            }
        }
        manager.createNotificationChannel(channel)
    }

    /** Reproduce un sonido directamente desde assets, para la vista previa en Ajustes. */
    fun playPreview(context: Context, assetName: String) {
        try {
            context.assets.openFd("notifications/$assetName").use { afd ->
                val player = android.media.MediaPlayer()
                player.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                player.setOnCompletionListener { it.release() }
                player.prepare()
                player.start()
            }
        } catch (_: Exception) {
        }
    }

    /** Reproduce un patrón de vibración directamente, para la vista previa en Ajustes. */
    fun vibratePreview(context: Context, key: String) {
        val pattern = vibrationPatternFor(key)
        if (pattern.size <= 1) return
        try {
            val vibrator = context.getSystemService(android.os.Vibrator::class.java) ?: return
            vibrator.vibrate(android.os.VibrationEffect.createWaveform(pattern, -1))
        } catch (_: Exception) {
        }
    }
}
