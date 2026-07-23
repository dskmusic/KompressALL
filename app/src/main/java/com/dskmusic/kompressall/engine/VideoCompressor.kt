package com.dskmusic.kompressall.engine

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.Presentation
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultAssetLoaderFactory
import androidx.media3.transformer.DefaultDecoderFactory
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/** Transcodificación de vídeo con Media3 Transformer (portado de Kompressor). */
@androidx.annotation.OptIn(UnstableApi::class)
object VideoCompressor {

    data class Meta(
        val width: Int,
        val height: Int,
        val rotation: Int,
        val fps: Float,
        val durationMs: Long,
        val totalBitrate: Long,
        val audioMime: String?,
        val audioBitrate: Int
    ) {
        val displayWidth: Int get() = if (rotation == 90 || rotation == 270) height else width
        val displayHeight: Int get() = if (rotation == 90 || rotation == 270) width else height
    }

    fun readMeta(context: Context, uri: Uri): Meta? {
        var width = 0; var height = 0; var rotation = 0
        var durationMs = 0L; var totalBitrate = 0L
        try {
            val r = MediaMetadataRetriever()
            r.setDataSource(context, uri)
            width = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            height = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            rotation = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            durationMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            totalBitrate = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull() ?: 0L
            r.release()
        } catch (_: Exception) {
        }
        if (width <= 0 || height <= 0 || durationMs <= 0) return null

        var fps = 0f
        var audioMime: String? = null
        var audioBitrate = 0
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") && fps <= 0f && f.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                    fps = f.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat()
                }
                if (mime.startsWith("audio/") && audioMime == null) {
                    audioMime = mime
                    audioBitrate = if (f.containsKey(MediaFormat.KEY_BIT_RATE))
                        f.getInteger(MediaFormat.KEY_BIT_RATE) else 0
                }
            }
        } catch (_: Exception) {
        } finally {
            extractor.release()
        }
        return Meta(width, height, rotation, fps, durationMs, totalBitrate, audioMime, audioBitrate)
    }

    fun hasEncoder(mimeType: String): Boolean = try {
        MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.any { info ->
            info.isEncoder && info.isHardwareAccelerated &&
                    info.supportedTypes.any { it.equals(mimeType, ignoreCase = true) }
        }
    } catch (_: Exception) {
        false
    }

    fun isConfigSupported(mimeType: String, width: Int, height: Int, fps: Float, encoder: Boolean): Boolean {
        return try {
            val safeFps = if (fps > 0f) fps else 30f
            MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.asSequence()
                .filter { it.isEncoder == encoder }
                .filter { info -> info.supportedTypes.any { it.equals(mimeType, ignoreCase = true) } }
                .any { info ->
                    try {
                        val vc = info.getCapabilitiesForType(mimeType).videoCapabilities ?: return@any false
                        vc.areSizeAndRateSupported(width, height, safeFps.toDouble()) ||
                                vc.areSizeAndRateSupported(height, width, safeFps.toDouble())
                    } catch (_: Exception) {
                        false
                    }
                }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Transcodifica [uri] a [outFile]. Devuelve null si tuvo éxito o un mensaje de error.
     * Cancelable: al cancelar la corrutina se cancela el Transformer.
     */
    suspend fun transcode(
        context: Context,
        uri: Uri,
        outFile: File,
        videoMime: String,
        videoBitrate: Long,
        outDisplayHeight: Int,   // 0 = mantener resolución
        outFps: Int,             // 0 = mantener fps
        srcFps: Float,
        audioBitrate: Int,       // bps
        audioPassthrough: Boolean,
        onProgress: (Float) -> Unit
    ): String? = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine<String?> { cont ->
            try {
                // CBR: muchos encoders hardware ignoran el bitrate solicitado en VBR.
                // GOP de 2s: ~5-10% menos tamaño a igual calidad.
                val videoSettings = VideoEncoderSettings.Builder()
                    .setBitrate(videoBitrate.toInt())
                    .setBitrateMode(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
                    .setiFrameIntervalSeconds(2f)
                    .build()
                val encoderFactoryBuilder = DefaultEncoderFactory.Builder(context)
                    .setEnableFallback(true)
                    .setRequestedVideoEncoderSettings(videoSettings)
                if (!audioPassthrough) {
                    encoderFactoryBuilder.setRequestedAudioEncoderSettings(
                        AudioEncoderSettings.Builder().setBitrate(audioBitrate).build()
                    )
                }
                val decoderFactory = DefaultDecoderFactory.Builder(context)
                    .setEnableDecoderFallback(true).build()

                val transformerBuilder = Transformer.Builder(context).setVideoMimeType(videoMime)
                if (!audioPassthrough) transformerBuilder.setAudioMimeType(MimeTypes.AUDIO_AAC)
                val transformer = transformerBuilder
                    .setAssetLoaderFactory(DefaultAssetLoaderFactory(context, decoderFactory, Clock.DEFAULT))
                    .setEncoderFactory(encoderFactoryBuilder.build())
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
                                exportException.localizedMessage ?: "Export error ${exportException.errorCode}"
                            )
                        }
                    })
                    .build()

                val effectsList = mutableListOf<Effect>()
                if (outDisplayHeight > 0) {
                    var h = outDisplayHeight
                    if (h % 2 != 0) h--
                    if (h > 0) effectsList.add(Presentation.createForHeight(h))
                }
                if (outFps > 0 && srcFps > outFps) {
                    effectsList.add(FrameDropEffect.createSimpleFrameDropEffect(srcFps, outFps.toFloat()))
                }
                val editedMediaItem = EditedMediaItem.Builder(MediaItem.fromUri(uri))
                    .setEffects(Effects(emptyList(), effectsList))
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
