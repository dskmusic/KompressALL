package com.dskmusic.kompressall.engine

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import kotlin.coroutines.resume

/**
 * Recodifica archivos de audio sueltos a AAC (contenedor .m4a) con Media3 Transformer.
 * Misma tubería que el vídeo, quitando la pista de vídeo: en un MP3 esa pista suele ser
 * la carátula incrustada, que a veces pesa más que la propia canción.
 */
@androidx.annotation.OptIn(UnstableApi::class)
object AudioCompressor {

    data class Meta(val durationMs: Long, val bitrate: Int)

    fun readMeta(context: Context, uri: Uri): Meta? {
        return try {
            val r = MediaMetadataRetriever()
            try {
                if (uri.scheme == "file") r.setDataSource(uri.path)
                else r.setDataSource(context, uri)
                val duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
                val bitrate = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                    ?.toIntOrNull() ?: 0
                Meta(duration, bitrate)
            } finally {
                try { r.release() } catch (_: Exception) {}
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Devuelve null si tuvo éxito, o el mensaje de error. Cancelable con la corrutina. */
    suspend fun transcode(
        context: Context,
        uri: Uri,
        outFile: File,
        bitrate: Int,
        onProgress: (Float) -> Unit
    ): String? = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine<String?> { cont ->
            try {
                val encoderFactory = DefaultEncoderFactory.Builder(context)
                    .setEnableFallback(true)
                    .setRequestedAudioEncoderSettings(
                        AudioEncoderSettings.Builder().setBitrate(bitrate).build()
                    )
                    .build()

                val transformer = Transformer.Builder(context)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .setEncoderFactory(encoderFactory)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            if (cont.isActive) cont.resume(null)
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exportException: ExportException
                        ) {
                            if (cont.isActive) cont.resume(
                                exportException.localizedMessage
                                    ?: "Export error ${exportException.errorCode}"
                            )
                        }
                    })
                    .build()

                val editedMediaItem = EditedMediaItem.Builder(MediaItem.fromUri(uri))
                    .setRemoveVideo(true)
                    .build()

                transformer.start(
                    Composition.Builder(listOf(EditedMediaItemSequence(editedMediaItem))).build(),
                    outFile.absolutePath
                )

                val handler = Handler(Looper.getMainLooper())
                val holder = ProgressHolder()
                val poll = object : Runnable {
                    override fun run() {
                        if (!cont.isActive) return
                        try {
                            if (transformer.getProgress(holder) != Transformer.PROGRESS_STATE_NOT_STARTED) {
                                onProgress((holder.progress / 100f).coerceIn(0f, 1f))
                            }
                        } catch (_: Exception) {
                        }
                        handler.postDelayed(this, 250)
                    }
                }
                handler.postDelayed(poll, 250)

                cont.invokeOnCancellation {
                    handler.post {
                        try {
                            transformer.cancel()
                        } catch (_: Exception) {
                        }
                    }
                }
            } catch (e: Exception) {
                if (cont.isActive) cont.resume(e.message ?: "Error")
            }
        }
    }

    // ── Etiquetas ───────────────────────────────────────────────────────────────

    private class Atom(val name: String, val type: Int, val payload: ByteArray)

    /**
     * Copia título, artista, álbum, número de pista y carátula del original al .m4a
     * recién creado. Transformer no arrastra metadatos, así que se inyectan a mano.
     *
     * MediaMuxer escribe el 'moov' al final del archivo, de modo que hacerlo crecer no
     * mueve el 'mdat' y las tablas de offsets de chunks siguen siendo válidas. Si el
     * archivo no tiene esa forma se deja tal cual: mejor sin etiquetas que corrupto.
     */
    fun copyTags(context: Context, source: Uri, target: File): Boolean {
        val atoms = try {
            readTags(context, source)
        } catch (_: Exception) {
            return false
        }
        if (atoms.isEmpty()) return false
        return try {
            injectMetadata(target, atoms)
        } catch (_: Exception) {
            false
        }
    }

    private fun readTags(context: Context, uri: Uri): List<Atom> {
        val r = MediaMetadataRetriever()
        try {
            if (uri.scheme == "file") r.setDataSource(uri.path) else r.setDataSource(context, uri)
            val atoms = mutableListOf<Atom>()

            fun meta(key: Int): String? =
                try { r.extractMetadata(key)?.trim()?.takeIf { it.isNotEmpty() } }
                catch (_: Exception) { null }

            fun text(key: Int, name: String) {
                meta(key)?.let { atoms += Atom(name, TYPE_UTF8, it.toByteArray(Charsets.UTF_8)) }
            }

            text(MediaMetadataRetriever.METADATA_KEY_TITLE, "\u00A9nam")
            text(MediaMetadataRetriever.METADATA_KEY_ARTIST, "\u00A9ART")
            text(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST, "aART")
            text(MediaMetadataRetriever.METADATA_KEY_ALBUM, "\u00A9alb")
            text(MediaMetadataRetriever.METADATA_KEY_GENRE, "\u00A9gen")
            text(MediaMetadataRetriever.METADATA_KEY_COMPOSER, "\u00A9wrt")
            // En MP3 el año suele llegar por DATE y no por YEAR
            val year = meta(MediaMetadataRetriever.METADATA_KEY_YEAR)
                ?: meta(MediaMetadataRetriever.METADATA_KEY_DATE)
            year?.let { atoms += Atom("\u00A9day", TYPE_UTF8, it.toByteArray(Charsets.UTF_8)) }

            numberPair(meta(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER))?.let {
                atoms += Atom("trkn", TYPE_BINARY, it)
            }
            numberPair(meta(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER))?.let {
                atoms += Atom("disk", TYPE_BINARY, it)
            }

            val picture = try { r.embeddedPicture } catch (_: Exception) { null }
            if (picture != null && picture.size > 8) {
                val type = when {
                    picture[0] == 0xFF.toByte() && picture[1] == 0xD8.toByte() -> TYPE_JPEG
                    picture[0] == 0x89.toByte() && picture[1] == 'P'.code.toByte() -> TYPE_PNG
                    else -> 0
                }
                if (type != 0) atoms += Atom("covr", type, picture)
            }
            return atoms
        } finally {
            try { r.release() } catch (_: Exception) {}
        }
    }

    /** "3/12" o "3" -> los 8 bytes que esperan 'trkn' y 'disk'. */
    private fun numberPair(raw: String?): ByteArray? {
        val parts = (raw ?: return null).split('/')
        val index = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: return null
        if (index <= 0) return null
        val total = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
        return byteArrayOf(
            0, 0,
            (index shr 8).toByte(), index.toByte(),
            (total shr 8).toByte(), total.toByte(),
            0, 0
        )
    }

    private fun injectMetadata(file: File, atoms: List<Atom>): Boolean {
        RandomAccessFile(file, "rw").use { raf ->
            val length = raf.length()
            var pos = 0L
            var moovPos = -1L
            var moovSize = 0L
            while (pos + 8 <= length) {
                raf.seek(pos)
                val size = raf.readInt().toLong() and 0xFFFFFFFFL
                val type = raf.readType()
                // Tamaño de 64 bits o "hasta el final": MediaMuxer no los usa, y sin
                // entenderlos no se puede tocar el archivo con seguridad.
                if (size < 8) return false
                if (type == "moov") {
                    moovPos = pos
                    moovSize = size
                }
                pos += size
            }
            // El moov tiene que ser la última caja: si no, crecerlo desplazaría el mdat
            // y todos los offsets de las tablas de chunks apuntarían a basura.
            if (moovPos < 0 || moovPos + moovSize != length) return false

            var child = moovPos + 8
            val moovEnd = moovPos + moovSize
            while (child + 8 <= moovEnd) {
                raf.seek(child)
                val size = raf.readInt().toLong() and 0xFFFFFFFFL
                val type = raf.readType()
                if (size < 8) return false
                // Ya trae metadatos propios: la spec solo admite un 'udta', no se mezclan.
                if (type == "udta") return false
                child += size
            }

            val udta = buildUdta(atoms)
            raf.seek(length)
            raf.write(udta)
            raf.seek(moovPos)
            raf.writeInt((moovSize + udta.size).toInt())
            return true
        }
    }

    private fun RandomAccessFile.readType(): String {
        val b = ByteArray(4)
        readFully(b)
        return String(b, Charsets.ISO_8859_1)
    }

    private fun buildUdta(atoms: List<Atom>): ByteArray {
        val ilst = ByteArrayOutputStream()
        for (a in atoms) {
            val data = ByteArrayOutputStream()
            data.write(intBytes(a.type))
            data.write(intBytes(0))          // locale
            data.write(a.payload)
            ilst.write(box(a.name, box("data", data.toByteArray())))
        }
        val meta = ByteArrayOutputStream()
        meta.write(intBytes(0))              // meta es FullBox: versión + flags
        meta.write(box("hdlr", HDLR_BODY))
        meta.write(box("ilst", ilst.toByteArray()))
        return box("udta", box("meta", meta.toByteArray()))
    }

    private fun box(type: String, body: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(body.size + 8)
        out.write(intBytes(body.size + 8))
        out.write(type.toByteArray(Charsets.ISO_8859_1))
        out.write(body)
        return out.toByteArray()
    }

    private fun intBytes(v: Int) = byteArrayOf(
        (v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte()
    )

    private const val TYPE_BINARY = 0
    private const val TYPE_UTF8 = 1
    private const val TYPE_JPEG = 13
    private const val TYPE_PNG = 14

    /** hdlr 'mdir'/'appl': es lo que hace que los reproductores lean el 'ilst'. */
    private val HDLR_BODY = byteArrayOf(
        0, 0, 0, 0,                                     // versión + flags
        0, 0, 0, 0,                                     // predefined
        'm'.code.toByte(), 'd'.code.toByte(), 'i'.code.toByte(), 'r'.code.toByte(),
        'a'.code.toByte(), 'p'.code.toByte(), 'p'.code.toByte(), 'l'.code.toByte(),
        0, 0, 0, 0, 0, 0, 0, 0,                         // reservado
        0                                               // nombre vacío
    )
}
