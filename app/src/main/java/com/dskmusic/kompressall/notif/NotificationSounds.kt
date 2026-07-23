package com.dskmusic.kompressall.notif

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
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

    /**
     * Id del canal de "terminado" para la combinación actual de sonido+vibración.
     * Android (y sobre todo MIUI) puede "recordar" el sonido de un canal aunque se
     * borre y se recree con el mismo id — por eso el id cambia con cada combinación
     * en vez de reutilizar siempre uno fijo, así el sistema lo trata como un canal
     * realmente nuevo y no restaura ajustes antiguos.
     */
    fun doneChannelId(): String = "kompressall_done_${Settings.notificationSound}_${Settings.notificationVibration}"

    private fun rawId(context: Context, resourceName: String): Int =
        context.resources.getIdentifier(resourceName, "raw", context.packageName)

    /**
     * Registra el sonido en la colección de notificaciones de MediaStore (asi el
     * propio sistema/MIUI lo reconoce como un sonido de notificacion instalado, en
     * vez de una Uri "android.resource://" que muchos fabricantes no aceptan para
     * NotificationChannel.setSound). Si ya hay un registro completo (no a medias)
     * con este nombre lo reutiliza tal cual: la Uri se queda estable entre llamadas,
     * porque el canal puede seguir apuntando a ella.
     */
    private fun registerInMediaStore(context: Context, resourceName: String): Uri? {
        val rawResId = rawId(context, resourceName)
        if (rawResId == 0) return null
        return try {
            val resolver = context.contentResolver
            val displayName = "KompressALL_$resourceName.mp3"
            val existing = resolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.IS_PENDING),
                "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                arrayOf(displayName),
                null
            )?.use { c ->
                if (c.moveToFirst() && c.getInt(1) == 0) {
                    ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, c.getLong(0))
                } else null
            }
            if (existing != null) return existing

            // No existe o quedo a medias de un intento anterior: limpiar y escribir de cero.
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

    /** Crea (si hace falta) el canal de "terminado" para la combinación actual de
     *  sonido+vibración. */
    fun rebuildDoneChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val id = doneChannelId()
        val uri = registerInMediaStore(context, Settings.notificationSound)
        val pattern = vibrationPatternFor(Settings.notificationVibration)
        if (manager.getNotificationChannel(id) == null) {
            val channel = NotificationChannel(
                id,
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
