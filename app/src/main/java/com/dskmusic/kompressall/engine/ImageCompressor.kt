package com.dskmusic.kompressall.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.dskmusic.kompressall.model.MediaEdit
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/**
 * Compresión de imágenes 100% en Kotlin (portado de Kompress).
 * Preserva metadatos EXIF copiándolos del original al resultado (solo JPEG).
 */
object ImageCompressor {

    /** Devuelve los bytes comprimidos, o null si falla. */
    fun compress(
        context: Context,
        uri: Uri,
        format: String,          // "jpeg" | "webp" | "png"
        quality: Int,            // 1..100
        resolutionScale: Float,  // 0..1
        edit: MediaEdit = MediaEdit()
    ): ByteArray? {
        return try {
            val originalBytes = context.contentResolver
                .openInputStream(uri)?.use { it.readBytes() } ?: return null

            val exifData = readExif(context, uri)

            // Decodificar con muestreo para evitar OOM en fotos de decenas de MP
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(originalBytes, 0, originalBytes.size, bounds)

            val scale = resolutionScale.coerceIn(0.05f, 1f)
            val sampleSize = if (scale < 0.999f && bounds.outWidth > 0 && bounds.outHeight > 0) {
                val targetW = (bounds.outWidth * scale).toInt().coerceAtLeast(1)
                val targetH = (bounds.outHeight * scale).toInt().coerceAtLeast(1)
                calculateInSampleSize(bounds.outWidth, bounds.outHeight, targetW * 2, targetH * 2)
            } else 1

            var bitmap = decodeWithFallback(originalBytes, sampleSize) ?: return null
            bitmap = fixOrientation(exifData, bitmap)
            // Las ediciones van antes de escalar: así el porcentaje de resolución se
            // aplica sobre lo que el usuario ve recortado, no sobre la foto entera.
            val edited = applyEdit(bitmap, edit)
            if (edited !== bitmap) { bitmap.recycle(); bitmap = edited }

            if (scale < 0.999f) {
                val newW = (bitmap.width * scale).toInt().coerceAtLeast(1)
                val newH = (bitmap.height * scale).toInt().coerceAtLeast(1)
                val scaled = Bitmap.createScaledBitmap(bitmap, newW, newH, true)
                if (scaled != bitmap) { bitmap.recycle(); bitmap = scaled }
            }

            val compressFormat = when (format) {
                "png"  -> Bitmap.CompressFormat.PNG
                "webp" -> Bitmap.CompressFormat.WEBP_LOSSY
                else   -> Bitmap.CompressFormat.JPEG
            }

            val outStream = ByteArrayOutputStream()
            bitmap.compress(compressFormat, quality.coerceIn(1, 100), outStream)
            bitmap.recycle()
            var compressedBytes = outStream.toByteArray()

            // PNG y WEBP no soportan EXIF de forma fiable via ExifInterface
            if (format == "jpeg") {
                compressedBytes = writeExif(context, compressedBytes, exifData)
            }
            compressedBytes
        } catch (e: Exception) {
            null
        }
    }

    /** Bitmap ya orientado y reducido, con el tamaño real del original detrás. */
    internal class Preview(val bitmap: Bitmap, val width: Int, val height: Int)

    /**
     * Carga para la vista previa del editor: reducida a [maxSize] y con la orientación
     * EXIF ya aplicada, que es el punto exacto en el que arranca [applyEdit] al comprimir.
     */
    internal fun decodePreview(context: Context, uri: Uri, maxSize: Int): Preview? = try {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        if (bytes == null) null else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val exifData = readExif(context, uri)
            val sample = calculateInSampleSize(bounds.outWidth, bounds.outHeight, maxSize, maxSize)
            decodeWithFallback(bytes, sample)?.let { raw ->
                val fixed = fixOrientation(exifData, raw)
                // El giro EXIF puede intercambiar los lados, y el diálogo enseña el tamaño
                // que tendrá el archivo, no el que dicen las cabeceras.
                val swap = fixed.width > fixed.height != bounds.outWidth > bounds.outHeight
                Preview(
                    fixed,
                    if (swap) bounds.outHeight else bounds.outWidth,
                    if (swap) bounds.outWidth else bounds.outHeight
                )
            }
        }
    } catch (_: Exception) {
        null
    }

    /**
     * Giro, volteo, recorte y ajustes elegidos en el diálogo de edición. Nunca recicla
     * [source] (la vista previa reutiliza el mismo bitmap en cada cambio); sí los pasos
     * intermedios que crea por el camino.
     */
    internal fun applyEdit(source: Bitmap, edit: MediaEdit): Bitmap {
        var bmp = source
        fun swap(next: Bitmap) {
            if (next === bmp) return
            if (bmp !== source) bmp.recycle()
            bmp = next
        }
        if (edit.rotationDegrees != 0 || edit.mirrored) {
            val m = Matrix()
            // Volteo antes que giro, el mismo orden que en el vídeo y en la vista previa.
            if (edit.mirrored) m.postScale(-1f, 1f)
            if (edit.rotationDegrees != 0) m.postRotate(edit.rotationDegrees.toFloat())
            swap(Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true))
        }
        if (edit.isCropped) {
            val x = (edit.cropLeft * bmp.width).toInt().coerceIn(0, bmp.width - 1)
            val y = (edit.cropTop * bmp.height).toInt().coerceIn(0, bmp.height - 1)
            val w = ((edit.cropRight - edit.cropLeft) * bmp.width).toInt().coerceIn(1, bmp.width - x)
            val h = ((edit.cropBottom - edit.cropTop) * bmp.height).toInt().coerceIn(1, bmp.height - y)
            swap(Bitmap.createBitmap(bmp, x, y, w, h))
        }
        if (edit.isAdjusted) {
            val cm = ColorMatrix()
            if (edit.saturation != 0) cm.setSaturation(1f + edit.saturation / 100f)
            if (edit.contrast != 0 || edit.brightness != 0) {
                val c = 1f + edit.contrast / 100f
                // El contraste pivota sobre el gris medio; si no, subirlo aclara la foto.
                val t = 127.5f * (1f - c) + edit.brightness * 1.27f
                cm.postConcat(ColorMatrix(floatArrayOf(
                    c, 0f, 0f, 0f, t,
                    0f, c, 0f, 0f, t,
                    0f, 0f, c, 0f, t,
                    0f, 0f, 0f, 1f, 0f
                )))
            }
            val out = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
            Canvas(out).drawBitmap(bmp, 0f, 0f, Paint().apply {
                isFilterBitmap = true
                colorFilter = ColorMatrixColorFilter(cm)
            })
            swap(out)
        }
        return bmp
    }

    private fun readExif(context: Context, uri: Uri): Map<String, String> {
        val attrs = mutableMapOf<String, String>()
        try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val exif = ExifInterface(stream)
                EXIF_TAGS.forEach { tag ->
                    exif.getAttribute(tag)?.let { attrs[tag] = it }
                }
            }
        } catch (_: IOException) {
        }
        return attrs
    }

    private fun writeExif(context: Context, jpegBytes: ByteArray, exifData: Map<String, String>): ByteArray {
        if (exifData.isEmpty()) return jpegBytes
        return try {
            val tmp = File.createTempFile("ka_exif", ".jpg", context.cacheDir)
            tmp.writeBytes(jpegBytes)
            val exif = ExifInterface(tmp.absolutePath)
            exifData.forEach { (tag, value) ->
                // La orientación ya está corregida en los píxeles
                if (tag != ExifInterface.TAG_ORIENTATION) exif.setAttribute(tag, value)
            }
            exif.setAttribute(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL.toString()
            )
            exif.saveAttributes()
            val result = tmp.readBytes()
            tmp.delete()
            result
        } catch (_: Exception) {
            jpegBytes
        }
    }

    private fun fixOrientation(exifData: Map<String, String>, bitmap: Bitmap): Bitmap {
        val orientation = exifData[ExifInterface.TAG_ORIENTATION]?.toIntOrNull()
            ?: ExifInterface.ORIENTATION_NORMAL
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90  -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL   -> matrix.preScale(1f, -1f)
            else -> return bitmap
        }
        return try {
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated != bitmap) bitmap.recycle()
            rotated
        } catch (_: Exception) {
            bitmap
        }
    }

    private fun calculateInSampleSize(srcW: Int, srcH: Int, reqW: Int, reqH: Int): Int {
        var inSampleSize = 1
        if (srcH > reqH || srcW > reqW) {
            val halfH = srcH / 2
            val halfW = srcW / 2
            while ((halfH / inSampleSize) >= reqH && (halfW / inSampleSize) >= reqW) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    private fun decodeWithFallback(bytes: ByteArray, initialSampleSize: Int): Bitmap? {
        var sampleSize = initialSampleSize.coerceAtLeast(1)
        repeat(6) {
            try {
                val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
                return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            } catch (_: OutOfMemoryError) {
                sampleSize *= 2
            }
        }
        return null
    }

    private val EXIF_TAGS = listOf(
        ExifInterface.TAG_MAKE,
        ExifInterface.TAG_MODEL,
        ExifInterface.TAG_DATETIME,
        ExifInterface.TAG_DATETIME_ORIGINAL,
        ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_GPS_LATITUDE,
        ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE,
        ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE,
        ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_TIMESTAMP,
        ExifInterface.TAG_GPS_DATESTAMP,
        ExifInterface.TAG_EXPOSURE_TIME,
        ExifInterface.TAG_F_NUMBER,
        ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
        ExifInterface.TAG_FOCAL_LENGTH,
        ExifInterface.TAG_FLASH,
        ExifInterface.TAG_WHITE_BALANCE,
        ExifInterface.TAG_IMAGE_WIDTH,
        ExifInterface.TAG_IMAGE_LENGTH,
        ExifInterface.TAG_PIXEL_X_DIMENSION,
        ExifInterface.TAG_PIXEL_Y_DIMENSION,
        ExifInterface.TAG_ORIENTATION
    )
}
