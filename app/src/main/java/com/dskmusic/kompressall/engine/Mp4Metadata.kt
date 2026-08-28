package com.dskmusic.kompressall.engine

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile

/**
 * Copia etiquetas, carátula, ubicación y fecha de grabación del archivo original al MP4
 * o M4A recién creado: ni Transformer ni MediaMuxer arrastran nada de esto.
 *
 * MediaMuxer escribe el 'moov' al final del archivo, de modo que hacerlo crecer no mueve
 * el 'mdat' y las tablas de offsets de chunks siguen siendo válidas. Si el archivo no
 * tiene esa forma se deja tal cual: mejor sin metadatos que corrupto.
 */
object Mp4Metadata {

    /**
     * [dateMillis] es la fecha de captura que ya usa el resto de la app (DATE_TAKEN de
     * MediaStore, o la deducida del nombre); 0 para no tocar las fechas internas.
     *
     * Devuelve true si llegó a escribir algo.
     */
    fun copyTags(context: Context, source: Uri, target: File, dateMillis: Long = 0L): Boolean {
        val atoms = try {
            readTags(context, source)
        } catch (_: Exception) {
            return false
        }
        val seconds = mp4Seconds(dateMillis)
        if (atoms.isEmpty() && seconds == null) return false
        return try {
            inject(target, atoms, seconds)
        } catch (_: Exception) {
            false
        }
    }

    // ── Lectura ───────────────────────────────────────────────────────────────

    /** [raw] va suelto dentro del 'udta'; el resto va envuelto en 'ilst' > 'data'. */
    private class Atom(
        val name: String,
        val type: Int,
        val payload: ByteArray,
        val raw: Boolean = false
    )

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

            text(MediaMetadataRetriever.METADATA_KEY_TITLE, "©nam")
            text(MediaMetadataRetriever.METADATA_KEY_ARTIST, "©ART")
            text(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST, "aART")
            text(MediaMetadataRetriever.METADATA_KEY_ALBUM, "©alb")
            text(MediaMetadataRetriever.METADATA_KEY_GENRE, "©gen")
            text(MediaMetadataRetriever.METADATA_KEY_COMPOSER, "©wrt")
            // En MP3 el año suele llegar por DATE y no por YEAR
            val year = meta(MediaMetadataRetriever.METADATA_KEY_YEAR)
                ?: meta(MediaMetadataRetriever.METADATA_KEY_DATE)
            year?.let { atoms += Atom("©day", TYPE_UTF8, it.toByteArray(Charsets.UTF_8)) }

            numberPair(meta(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER))?.let {
                atoms += Atom("trkn", TYPE_BINARY, it)
            }
            numberPair(meta(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER))?.let {
                atoms += Atom("disk", TYPE_BINARY, it)
            }

            // Las coordenadas de la cámara: es la caja que escribe MediaMuxer.setLocation
            // y la que leen la galería y los mapas, y va suelta bajo 'udta', no en 'ilst'.
            meta(MediaMetadataRetriever.METADATA_KEY_LOCATION)?.let { loc ->
                atoms += Atom("©xyz", 0, iso6709(loc), raw = true)
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

    /** Cuerpo de '©xyz': longitud de la cadena, idioma y la cadena ISO 6709 tal cual. */
    private fun iso6709(value: String): ByteArray {
        val text = value.toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream(text.size + 4)
        out.write(text.size shr 8)
        out.write(text.size and 0xFF)
        out.write(0x15)                      // 'und', igual que MediaMuxer
        out.write(0xC7)
        out.write(text)
        return out.toByteArray()
    }

    /** Segundos desde 1904-01-01 UTC, o null si no cabe en los 32 bits del formato. */
    private fun mp4Seconds(dateMillis: Long): Long? {
        if (dateMillis <= 0) return null
        val seconds = dateMillis / 1000 + EPOCH_1904_OFFSET
        return seconds.takeIf { it in 1..0xFFFFFFFFL }
    }

    // ── Escritura ─────────────────────────────────────────────────────────────

    private fun inject(file: File, atoms: List<Atom>, seconds: Long?): Boolean {
        RandomAccessFile(file, "rw").use { raf ->
            val length = raf.length()
            var moovPos = -1L
            var moovSize = 0L
            if (!forEachChild(raf, 0L, length) { pos, size, type ->
                    if (type == "moov") {
                        moovPos = pos
                        moovSize = size
                    }
                }
            ) return false
            // El moov tiene que ser la última caja: si no, crecerlo desplazaría el mdat y
            // todos los offsets de las tablas de chunks apuntarían a basura.
            if (moovPos < 0 || moovPos + moovSize != length) return false

            var hasUdta = false
            val moovEnd = moovPos + moovSize
            if (!forEachChild(raf, moovPos + 8, moovEnd) { pos, size, type ->
                    when (type) {
                        // La spec solo admite un 'udta' por 'moov': no se mezclan.
                        "udta" -> hasUdta = true
                        "mvhd" -> if (seconds != null) writeTimes(raf, pos, seconds)
                        "trak" -> if (seconds != null) {
                            forEachChild(raf, pos + 8, pos + size) { p, _, t ->
                                if (t == "tkhd") writeTimes(raf, p, seconds)
                            }
                        }
                    }
                }
            ) return false

            if (atoms.isEmpty() || hasUdta) return seconds != null

            val udta = buildUdta(atoms)
            raf.seek(length)
            raf.write(udta)
            raf.seek(moovPos)
            raf.writeInt((moovSize + udta.size).toInt())
            return true
        }
    }

    /** Recorre las cajas de [start] a [end]; false si alguna tiene un tamaño imposible. */
    private inline fun forEachChild(
        raf: RandomAccessFile,
        start: Long,
        end: Long,
        action: (pos: Long, size: Long, type: String) -> Unit
    ): Boolean {
        var pos = start
        while (pos + 8 <= end) {
            raf.seek(pos)
            val size = raf.readInt().toLong() and 0xFFFFFFFFL
            val type = raf.readType()
            // Tamaño de 64 bits o "hasta el final": MediaMuxer no los usa, y sin
            // entenderlos no se puede tocar el archivo con seguridad.
            if (size < 8 || pos + size > end) return false
            action(pos, size, type)
            pos += size
        }
        return pos == end
    }

    /** mvhd/tkhd: cabecera(8) + versión y flags(4) + creación(4) + modificación(4). */
    private fun writeTimes(raf: RandomAccessFile, boxPos: Long, seconds: Long) {
        raf.seek(boxPos + 8)
        // Versión 1 usa campos de 64 bits en otras posiciones; MediaMuxer no la escribe.
        if (raf.readInt() ushr 24 != 0) return
        raf.writeInt(seconds.toInt())
        raf.writeInt(seconds.toInt())
    }

    private fun RandomAccessFile.readType(): String {
        val b = ByteArray(4)
        readFully(b)
        return String(b, Charsets.ISO_8859_1)
    }

    private fun buildUdta(atoms: List<Atom>): ByteArray {
        val udta = ByteArrayOutputStream()
        val tagged = atoms.filter { !it.raw }
        if (tagged.isNotEmpty()) {
            val ilst = ByteArrayOutputStream()
            for (a in tagged) {
                val data = ByteArrayOutputStream()
                data.write(intBytes(a.type))
                data.write(intBytes(0))      // locale
                data.write(a.payload)
                ilst.write(box(a.name, box("data", data.toByteArray())))
            }
            val meta = ByteArrayOutputStream()
            meta.write(intBytes(0))          // meta es FullBox: versión + flags
            meta.write(box("hdlr", HDLR_BODY))
            meta.write(box("ilst", ilst.toByteArray()))
            udta.write(box("meta", meta.toByteArray()))
        }
        for (a in atoms) if (a.raw) udta.write(box(a.name, a.payload))
        return box("udta", udta.toByteArray())
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
    private const val EPOCH_1904_OFFSET = 2_082_844_800L

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
