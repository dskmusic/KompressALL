package com.dskmusic.kompressall.notif

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import com.dskmusic.kompressall.CompressionService
import com.dskmusic.kompressall.R
import com.dskmusic.kompressall.data.Settings

/** Sonidos disponibles: res/raw/sound_01.mp3 .. sound_10.mp3 (nombres de recurso,
 *  sin extension). Empaquetados como raw en vez de assets/MediaStore: la Uri
 *  android.resource:// es inmediata y fiable, no depende de copiar bytes ni de
 *  que MediaStore termine de indexarlos a tiempo. */
val NOTIFICATION_SOUND_OPTIONS = (1..10).map { "sound_%02d".format(it) }

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

    private fun rawId(context: Context, resourceName: String): Int =
        context.resources.getIdentifier(resourceName, "raw", context.packageName)

    private fun soundUri(context: Context, resourceName: String): Uri? {
        val id = rawId(context, resourceName)
        if (id == 0) return null
        return Uri.parse("android.resource://${context.packageName}/$id")
    }

    /** Recrea el canal de "terminado" con el sonido y patron de vibracion actuales.
     *  Android ignora los cambios en un canal ya creado, asi que hay que borrarlo y
     *  volver a crearlo cada vez que el usuario cambia estas opciones. */
    fun rebuildDoneChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.deleteNotificationChannel(CompressionService.DONE_CHANNEL_ID)
        val uri = soundUri(context, Settings.notificationSound)
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
            if (uri != null) {
                setSound(
                    uri,
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
            }
        }
        manager.createNotificationChannel(channel)
    }

    /** Reproduce un sonido de res/raw directamente, para la vista previa en Ajustes. */
    fun playPreview(context: Context, resourceName: String) {
        val id = rawId(context, resourceName)
        if (id == 0) return
        try {
            val player = MediaPlayer.create(context, id)
            player?.setOnCompletionListener { it.release() }
            player?.start()
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
