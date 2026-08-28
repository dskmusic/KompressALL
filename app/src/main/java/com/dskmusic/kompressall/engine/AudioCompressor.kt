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
import com.dskmusic.kompressall.model.MediaEdit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
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
        edit: MediaEdit,
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

                val mediaItemBuilder = MediaItem.Builder().setUri(uri)
                if (edit.startMs > 0 || edit.endMs > 0) {
                    val clipping = MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(edit.startMs)
                    // endMs == 0 significa "hasta el final": no se fija posición de fin.
                    if (edit.endMs > edit.startMs) clipping.setEndPositionMs(edit.endMs)
                    mediaItemBuilder.setClippingConfiguration(clipping.build())
                }
                val editedMediaItem = EditedMediaItem.Builder(mediaItemBuilder.build())
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
}
