package com.dskmusic.kompressall.util

import android.content.ContentUris
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.dskmusic.kompressall.model.MediaEntry
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * URI a usar para leer el archivo: la ruta real en disco si se conoce (no depende del
 * permiso temporal del content:// que el picker concede solo mientras la Activity que
 * lo recibio sigue viva, y que se puede revocar al cerrar la app desde recientes aunque
 * el proceso siga activo por el foreground service) o, si no, el content:// original.
 */
fun MediaEntry.sourceUri(): Uri =
    realPath?.let { path -> File(path).takeIf { it.canRead() }?.let { Uri.fromFile(it) } } ?: uri

object MediaUtils {

    /** Carga nombre, tamaño, tipo, fecha y ruta real de un URI. */
    fun loadEntry(context: Context, uri: Uri): MediaEntry? {
        try {
            if (uri.scheme == "file") {
                val f = File(uri.path ?: return null)
                if (!f.exists()) return null
                return MediaEntry(
                    uri, f.name, f.length(), isVideoName(f.name, null),
                    bestDate(0L, f.name, f), f.absolutePath
                )
            }

            var name = ""
            var size = 0L
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIdx >= 0) name = c.getString(nameIdx) ?: ""
                    if (sizeIdx >= 0) size = c.getLong(sizeIdx)
                }
            }

            val mime = context.contentResolver.getType(uri)
            var dateTaken = 0L
            var realPath: String? = null

            // El Photo Picker devuelve DATA como una ruta virtual FUSE
            // (/.transforms/synthetic/picker/...) que no es escribible ni con
            // MANAGE_EXTERNAL_STORAGE. La ruta física real solo se obtiene
            // consultando las colecciones canónicas (Images/Video) por ID;
            // MediaStore.Files puede devolver la misma ruta sintética, así que
            // se prueba en último lugar.
            val id = uri.pathSegments.lastOrNull { seg -> seg.isNotEmpty() && seg.all { it.isDigit() } }
                ?.toLongOrNull()
            val candidates = buildList {
                if (id != null) {
                    add(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id))
                    add(ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id))
                }
                add(uri)
                if (id != null) add(ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"), id))
            }
            for (candidate in candidates) {
                if (realPath != null) break
                try {
                    context.contentResolver.query(
                        candidate,
                        arrayOf(
                            MediaStore.MediaColumns.DATA,
                            MediaStore.MediaColumns.DATE_TAKEN,
                            MediaStore.MediaColumns.DISPLAY_NAME,
                            MediaStore.MediaColumns.SIZE
                        ),
                        null, null, null
                    )?.use { c ->
                        if (c.moveToFirst()) {
                            val data = c.getString(0)?.takeIf { it.isNotBlank() }
                            if (data != null && !data.contains("/.transforms/")) realPath = data
                            if (c.getLong(1) > 0) dateTaken = c.getLong(1)
                            // El Photo Picker inventa nombres numéricos: el DISPLAY_NAME
                            // de MediaStore es el nombre original real, siempre preferido.
                            c.getString(2)?.takeIf { it.isNotBlank() }?.let { name = it }
                            if (size <= 0) size = c.getLong(3)
                        }
                    }
                } catch (_: Exception) {
                }
            }

            if (name.isBlank()) name = "media_${System.currentTimeMillis()}"
            if (size <= 0) {
                size = try {
                    context.contentResolver.openInputStream(uri)?.use { s ->
                        var total = 0L
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = s.read(buf); if (n < 0) break; total += n
                        }
                        total
                    } ?: 0L
                } catch (_: Exception) { 0L }
            }
            if (size <= 0) return null

            val file = realPath?.let { File(it) }?.takeIf { it.exists() }
            // La ruta física es la fuente más fiable del nombre original
            if (file != null) name = file.name
            return MediaEntry(
                uri, name, size, isVideoName(name, mime),
                bestDate(dateTaken, name, file), file?.absolutePath
            )
        } catch (_: Exception) {
            return null
        }
    }

    private fun isVideoName(name: String, mime: String?): Boolean {
        if (mime != null) return mime.startsWith("video/")
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in listOf("mp4", "mkv", "webm", "3gp", "mov", "avi", "m4v", "ts")
    }

    private fun bestDate(dateTaken: Long, name: String, file: File?): Long {
        if (dateTaken > 0) return dateTaken
        val fromName = parseDateFromFilename(name)
        if (fromName > 0) return fromName
        val mod = file?.lastModified() ?: 0L
        return if (mod > 0) mod else System.currentTimeMillis()
    }

    /** Cubre patrones como IMG_20260430_160034.jpg o 20260429_video.mp4 */
    fun parseDateFromFilename(filename: String): Long {
        if (filename.isBlank()) return 0L
        val patterns = listOf(
            Regex("""(\d{4})(\d{2})(\d{2})[_\-T](\d{2})(\d{2})(\d{2})"""),
            Regex("""(\d{4})(\d{2})(\d{2})""")
        )
        for (pattern in patterns) {
            val g = (pattern.find(filename) ?: continue).groupValues
            val dateStr = if (g.size == 7)
                "${g[1]}-${g[2]}-${g[3]} ${g[4]}:${g[5]}:${g[6]}"
            else
                "${g[1]}-${g[2]}-${g[3]} 12:00:00"
            val ms = try {
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).parse(dateStr)?.time ?: 0L
            } catch (_: Exception) { 0L }
            if (ms > 0L) return ms
        }
        return 0L
    }

    /** Prefijo de carpeta sugerido: la fecha (yyyyMMdd) más frecuente del lote + espacio. */
    fun folderSuggestion(items: List<MediaEntry>): String {
        if (items.isEmpty()) return ""
        val fmt = SimpleDateFormat("yyyyMMdd", Locale.US)
        val topDay = items.groupingBy { fmt.format(Date(it.dateMillis)) }
            .eachCount().maxByOrNull { it.value }?.key
            ?: fmt.format(Date())
        return "$topDay "
    }

    /** Evita colisiones: nombre.jpg → nombre (1).jpg */
    fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val base = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "")
        var i = 1
        while (f.exists()) {
            f = File(dir, if (ext.isEmpty()) "$base ($i)" else "$base ($i).$ext")
            i++
        }
        return f
    }

    fun scan(context: Context, paths: List<String>) {
        if (paths.isEmpty()) return
        try {
            MediaScannerConnection.scanFile(context, paths.toTypedArray(), null, null)
        } catch (_: Exception) {
        }
    }

    /** Borra recursivamente las subcarpetas vacías dentro de [root] (root en sí no se
     *  borra). Devuelve cuántas carpetas se eliminaron. */
    fun deleteEmptyDirs(root: File): Int {
        var deleted = 0
        root.listFiles()?.forEach { child ->
            if (child.isDirectory) {
                deleted += deleteEmptyDirs(child)
                if (child.listFiles()?.isEmpty() == true && child.delete()) deleted++
            }
        }
        return deleted
    }
}
