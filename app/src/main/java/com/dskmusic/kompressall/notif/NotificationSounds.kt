package com.dskmusic.kompressall.notif

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.dskmusic.kompressall.CompressionService
import com.dskmusic.kompressall.R
import com.dskmusic.kompressall.data.Settings

/** Sonidos disponibles: res/raw/sound_01.mp3 .. sound_10.mp3 (nombres de recurso, sin extensión). */
val NOTIFICATION_SOUND_OPTIONS = (1..10).map { "sound_%02d".format(it) }

private val VIBRATION_PATTERNS: Map<String, LongArray> = linkedMapOf(
    "default" to longArrayOf(0, 250, 250, 250),
    "short" to longArrayOf(0, 150),
    "long" to longArrayOf(0, 800),
    "double" to longArrayOf(0, 150, 120, 150),
    // SOS en morse: ··· −−− ···
    "pulse" to longArrayOf(
        0,
        150, 100, 150, 100, 150, 300,
        450, 100, 450, 100, 450, 300,
        150, 100, 150, 100, 150
    ),
    "none" to longArrayOf(0)
)

val VIBRATION_OPTIONS = VIBRATION_PATTERNS.keys.toList()

fun vibrationPatternFor(key: String): LongArray = VIBRATION_PATTERNS[key] ?: VIBRATION_PATTERNS.getValue("default")

object NotificationSounds {

    private fun rawId(context: Context, resourceName: String): Int =
        context.resources.getIdentifier(resourceName, "raw", context.packageName)

    /**
     * Registra el sonido en la colección de notificaciones de MediaStore (asi el
     * propio sistema/MIUI lo reconoce como un sonido de notificacion instalado, en
     * vez de una Uri "android.resource://" que muchos fabricantes no aceptan para
     * NotificationChannel.setSound). Borra cualquier registro anterior con el mismo
     * nombre y vuelve a escribirlo entero cada vez, para no arrastrar una fila
     * a medio escribir de un intento previo.
     */
    private fun registerInMediaStore(context: Context, resourceName: String): Uri? {
        val rawResId = rawId(context, resourceName)
        if (rawResId == 0) return null
        return try {
            val resolver = context.contentResolver
            val displayName = "KompressALL_$resourceName.mp3"
            resolver.delete(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                arrayOf(displayName)
            )
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, "audio/mpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_NOTIFICATIONS)
                put(MediaStore.Audio.Media.IS_NOTIFICATION, 1)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values) ?: return null
            resolver.openOutputStream(uri)?.use { out ->
                context.resources.openRawResource(rawResId).use { it.copyTo(out) }
            } ?: return null
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } catch (_: Exception) {
            null
        }
    }

    /** Recrea el canal de "terminado" con el sonido y patrón de vibración actuales.
     *  Android ignora los cambios en un canal ya creado, así que hay que borrarlo y
     *  volver a crearlo cada vez que el usuario cambia estas opciones. */
    /** Devuelve un texto de diagnóstico: si la Uri se escribió bien en MediaStore y si el
     *  canal, ya releído del sistema, realmente se quedó con ese sonido (para poder ver
     *  si el fallo está en nuestro registro o en que el sistema/MIUI lo descarta). */
    fun rebuildDoneChannel(context: Context): String {
        val manager = context.getSystemService(NotificationManager::class.java)
            ?: return "sin NotificationManager"
        manager.deleteNotificationChannel(CompressionService.DONE_CHANNEL_ID)
        val uri = registerInMediaStore(context, Settings.notificationSound)
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
        val applied = manager.getNotificationChannel(CompressionService.DONE_CHANNEL_ID)
        return when {
            uri == null -> "registro en MediaStore falló (revisa permisos de almacenamiento)"
            applied == null -> "el canal no existe tras crearlo"
            applied.sound == null -> "el sistema descartó el sonido: el canal se quedó sin sonido"
            applied.sound != uri -> "el sistema aplicó otra uri: ${applied.sound}"
            else -> "OK, el canal tiene: $uri"
        }
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
