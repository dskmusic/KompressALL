package com.dskmusic.kompressall.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
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
        resolutionScale: Float   // 0..1
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
