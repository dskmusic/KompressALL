package com.dskmusic.kompressall.engine

import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import com.dskmusic.kompressall.CompressionService
import com.dskmusic.kompressall.data.Settings
import com.dskmusic.kompressall.model.EngineState
import com.dskmusic.kompressall.model.ItemResult
import com.dskmusic.kompressall.model.JobConfig
import com.dskmusic.kompressall.model.MediaEntry
import com.dskmusic.kompressall.model.Phase
import com.dskmusic.kompressall.model.Preset
import com.dskmusic.kompressall.util.MediaUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Orquestador del lote. Vive fuera de la Activity (singleton) para que la
 * compresión siga aunque la app se minimice; el CompressionService mantiene
 * vivo el proceso y muestra la notificación.
 */
@androidx.annotation.OptIn(UnstableApi::class)
object CompressionEngine {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(EngineState())
    val state = _state.asStateFlow()
    private var job: Job? = null

    /** Archivos recibidos desde fuera (Compartir/Abrir con) mientras ya hay un
     *  lote pendiente: la UI pregunta si añadirlos o empezar de nuevo. */
    val pendingExternal = MutableStateFlow<List<Uri>?>(null)

    fun load(context: Context, uris: List<Uri>, append: Boolean = false) {
        val appCtx = context.applicationContext
        scope.launch {
            val newItems = uris.mapNotNull { MediaUtils.loadEntry(appCtx, it) }
            if (newItems.isEmpty()) return@launch
            val current = _state.value
            val merging = append && current.phase == Phase.CONFIG
            val items = (if (merging) current.items + newItems else newItems)
                .distinctBy { it.uri }
                .sortedBy { it.dateMillis }
            // Al añadir a un lote existente se conserva el nombre de carpeta
            // ya sugerido/editado en vez de recalcularlo.
            val suggestion = if (merging) current.folderSuggestion
            else MediaUtils.folderSuggestion(items)
            _state.value = EngineState(
                phase = Phase.CONFIG,
                items = items,
                folderSuggestion = suggestion
            )
        }
    }

    /** Punto de entrada para contenido externo (Compartir/Abrir con). Si ya
     *  hay un lote pendiente de configurar, deja la decisión en manos de la UI. */
    fun offerExternal(context: Context, uris: List<Uri>) {
        if (uris.isEmpty()) return
        val current = _state.value
        if (current.phase == Phase.CONFIG && current.items.isNotEmpty()) {
            pendingExternal.value = uris
        } else {
            load(context, uris)
        }
    }

    fun resolvePendingExternal(context: Context, append: Boolean) {
        val uris = pendingExternal.value ?: return
        pendingExternal.value = null
        load(context, uris, append = append)
    }

    fun dismissPendingExternal() {
        pendingExternal.value = null
    }

    fun discard() {
        _state.value = EngineState()
    }

    fun cancel() {
        _state.update { it.copy(cancelled = true) }
        job?.cancel()
    }

    fun start(context: Context, config: JobConfig, folderName: String) {
        val appCtx = context.applicationContext
        val snapshot = _state.value
        if (snapshot.items.isEmpty() || snapshot.phase != Phase.CONFIG) return

        CompressionService.start(appCtx)
        _state.update {
            it.copy(phase = Phase.RUNNING, currentIndex = 0, fileProgress = 0f,
                results = emptyList(), cancelled = false)
        }

        job = scope.launch {
            val root = File(Environment.getExternalStorageDirectory(), "KompressALL")
            val dirName = folderName.trim().ifBlank {
                snapshot.folderSuggestion.trim().ifBlank {
                    SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
                }
            }
            val destDir = File(root, dirName)
            val videosDir = File(destDir, "Videos")
            val backupDir = File(File(root, "Backups"), dirName)
            val results = mutableListOf<ItemResult>()
            try {
                for ((i, item) in snapshot.items.withIndex()) {
                    if (!isActive || _state.value.cancelled) break
                    _state.update {
                        it.copy(currentIndex = i, fileProgress = 0f,
                            currentName = item.name, isProbePass = false)
                    }
                    val result = try {
                        if (item.isVideo) processVideo(appCtx, item, config, destDir, videosDir, backupDir)
                        else processImage(appCtx, item, config, destDir, backupDir)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        ItemResult(item.name, item.isVideo, item.size, 0, false, error = e.message)
                    }
                    results += result
                    _state.update { it.copy(results = results.toList(), fileProgress = 1f) }
                }
            } finally {
                val saved = results.sumOf { r -> r.savedBytes }
                if (saved > 0) Settings.totalSaved += saved
                _state.update { it.copy(phase = Phase.DONE, results = results.toList()) }
            }
        }
    }

    // ── Imagen ────────────────────────────────────────────────────────────────

    private fun processImage(
        ctx: Context, item: MediaEntry, cfg: JobConfig, destDir: File, backupDir: File
    ): ItemResult {
        val (format, quality, scale) = when (cfg.imagePreset) {
            Preset.HIGH   -> Triple("jpeg", 95, 1f)
            Preset.MEDIUM -> Triple("jpeg", 75, 0.8f)
            Preset.LOW    -> Triple("jpeg", 45, 0.5f)
            Preset.MANUAL -> Triple(cfg.imageFormat, cfg.imageQuality, cfg.imageResolutionPct / 100f)
        }
        val bytes = ImageCompressor.compress(ctx, item.uri, format, quality, scale)
            ?: return ItemResult(item.name, false, item.size, 0, false, error = "decode")

        // Si el resultado es mayor que el original, conservar el original
        val kept = bytes.size >= item.size
        val base = item.name.substringBeforeLast('.')
        val ext = if (kept) item.name.substringAfterLast('.', "jpg").lowercase()
        else if (format == "jpeg") "jpg" else format
        val outName = "$base.$ext"
        val touched = mutableListOf<String>()
        val out: File

        if (cfg.replaceOriginals && item.realPath != null) {
            val orig = File(item.realPath)
            if (kept && orig.name.equals(outName, ignoreCase = true)) {
                return ItemResult(item.name, false, item.size, item.size, true, keptOriginal = true, outputPath = orig.absolutePath)
            }
            if (cfg.backupOriginals) {
                backupDir.mkdirs()
                val backup = MediaUtils.uniqueFile(backupDir, item.name)
                orig.copyTo(backup)
                touched += backup.absolutePath
            }
            out = File(orig.parentFile ?: destDir, outName)
            if (kept) copyOriginal(ctx, item, out) else out.writeBytes(bytes)
            if (!out.absolutePath.equals(orig.absolutePath, ignoreCase = true) && orig.exists()) {
                orig.delete()
                touched += orig.absolutePath
            }
        } else {
            destDir.mkdirs()
            out = MediaUtils.uniqueFile(destDir, outName)
            if (kept) copyOriginal(ctx, item, out) else out.writeBytes(bytes)
            deleteOriginalIfWanted(cfg, item, out, touched)
        }
        if (item.dateMillis > 0) out.setLastModified(item.dateMillis)
        touched += out.absolutePath
        MediaUtils.scan(ctx, touched)
        return ItemResult(item.name, false, item.size, out.length(), true, keptOriginal = kept, outputPath = out.absolutePath)
    }

    // ── Vídeo ─────────────────────────────────────────────────────────────────

    private suspend fun processVideo(
        ctx: Context, item: MediaEntry, cfg: JobConfig,
        destDir: File, videosDir: File, backupDir: File
    ): ItemResult {
        val meta = VideoCompressor.readMeta(ctx, item.uri)
            ?: return ItemResult(item.name, true, item.size, 0, false, error = "metadata")

        val fraction = when (cfg.videoPreset) {
            Preset.HIGH -> 0.40f; Preset.MEDIUM -> 0.18f; Preset.LOW -> 0.08f
            Preset.MANUAL -> (cfg.videoSizePct.coerceIn(5, 95)) / 100f
        }
        val shortSideTarget = when (cfg.videoPreset) {
            Preset.HIGH -> 0; Preset.MEDIUM -> 1080; Preset.LOW -> 720
            Preset.MANUAL -> cfg.videoShortSide
        }
        val fpsWanted = when (cfg.videoPreset) {
            Preset.HIGH -> 0; Preset.MEDIUM, Preset.LOW -> 30
            Preset.MANUAL -> cfg.videoFps
        }
        val audioBps = 1000 * when (cfg.videoPreset) {
            Preset.HIGH -> 320; Preset.MEDIUM -> 192; Preset.LOW -> 128
            Preset.MANUAL -> cfg.audioKbps
        }

        var mime = when (if (cfg.videoPreset == Preset.MANUAL) cfg.videoCodec else "auto") {
            "h264" -> MimeTypes.VIDEO_H264
            "h265" -> MimeTypes.VIDEO_H265
            "av1"  -> MimeTypes.VIDEO_AV1
            else -> if (VideoCompressor.hasEncoder(MimeTypes.VIDEO_H265)) MimeTypes.VIDEO_H265
            else MimeTypes.VIDEO_H264
        }
        if (!VideoCompressor.hasEncoder(mime)) mime = MimeTypes.VIDEO_H264

        // Resolución de salida: el preset fija el lado corto (720p/1080p también en vertical)
        val dispW = meta.displayWidth.coerceAtLeast(2)
        val dispH = meta.displayHeight.coerceAtLeast(2)
        val srcShort = minOf(dispW, dispH)
        var effShort = srcShort
        var outDisplayHeight = 0
        if (shortSideTarget in 1 until srcShort) {
            effShort = shortSideTarget
            outDisplayHeight = if (dispH > dispW)
                (shortSideTarget.toDouble() * dispH / dispW).toInt()
            else shortSideTarget
        }
        val srcFps = if (meta.fps > 0f) meta.fps else 30f
        var outFps = if (fpsWanted in 1 until srcFps.toInt()) fpsWanted else 0
        var effFps = if (outFps > 0) outFps.toFloat() else srcFps

        // Bitrate objetivo a partir del tamaño deseado (matemática de Kompressor)
        val durationSec = (meta.durationMs / 1000.0).coerceAtLeast(0.1)
        val targetBytes = (item.size * fraction.toDouble()).coerceAtLeast(100_000.0)
        val audioPassthrough = meta.audioMime == MimeTypes.AUDIO_AAC &&
                meta.audioBitrate in 1..audioBps
        val effAudioBps = when {
            meta.audioMime == null -> 0
            audioPassthrough -> meta.audioBitrate
            else -> audioBps
        }
        val targetBits = targetBytes * 8
        val available = (targetBits * 0.98 - effAudioBps * durationSec).coerceAtLeast(targetBits * 0.1)
        val cap = if (meta.totalBitrate > 0) meta.totalBitrate else Long.MAX_VALUE
        var videoBitrate = (available / durationSec).toLong()
            .coerceAtLeast(minVideoBitrate(effShort, mime, effFps))
            .coerceAtMost(cap)

        // Comprobación de soporte del encoder con degradación (H.264 → 720p/30)
        fun dims(displayHeight: Int): Pair<Int, Int> {
            val h = if (displayHeight > 0) displayHeight else dispH
            var w = (h.toDouble() * dispW / dispH).toInt().coerceAtLeast(2)
            var hh = h
            if (w % 2 != 0) w--
            if (hh % 2 != 0) hh--
            return w to hh
        }
        run {
            val (w, h) = dims(outDisplayHeight)
            if (!VideoCompressor.isConfigSupported(mime, w, h, effFps, encoder = true)) {
                if (mime != MimeTypes.VIDEO_H264 &&
                    VideoCompressor.isConfigSupported(MimeTypes.VIDEO_H264, w, h, effFps, encoder = true)
                ) {
                    mime = MimeTypes.VIDEO_H264
                } else {
                    val fbShort = 720.coerceAtMost(srcShort)
                    val fbHeight = if (dispH > dispW)
                        (fbShort.toDouble() * dispH / dispW).toInt() else fbShort
                    val (fw, fh) = dims(fbHeight)
                    if (VideoCompressor.isConfigSupported(MimeTypes.VIDEO_H264, fw, fh, 30f, encoder = true)) {
                        mime = MimeTypes.VIDEO_H264
                        outDisplayHeight = fbHeight
                        effShort = fbShort
                        outFps = if (srcFps > 30f) 30 else 0
                        effFps = if (outFps > 0) 30f else srcFps
                        videoBitrate = videoBitrate.coerceAtLeast(minVideoBitrate(effShort, mime, effFps))
                    } else {
                        return ItemResult(item.name, true, item.size, 0, false, error = "encoder")
                    }
                }
            }
        }

        val cache = File(ctx.cacheDir, "ka_${System.currentTimeMillis()}.mp4")
        try {
            if (cfg.twoPass) {
                // Pasada de análisis: mide el tamaño real y corrige el bitrate
                _state.update { it.copy(isProbePass = true) }
                val probe = File(ctx.cacheDir, "ka_probe_${System.currentTimeMillis()}.mp4")
                val probeErr = VideoCompressor.transcode(
                    ctx, item.uri, probe, mime, videoBitrate, outDisplayHeight,
                    outFps, srcFps, audioBps, audioPassthrough
                ) { p -> _state.update { s -> s.copy(fileProgress = p * 0.5f) } }
                if (probeErr == null && probe.length() > 0) {
                    videoBitrate = (videoBitrate * (targetBytes / probe.length()))
                        .toLong().coerceIn(100_000L, cap)
                }
                probe.delete()
                _state.update { it.copy(isProbePass = false, fileProgress = 0f) }
            }

            val err = VideoCompressor.transcode(
                ctx, item.uri, cache, mime, videoBitrate, outDisplayHeight,
                outFps, srcFps, audioBps, audioPassthrough
            ) { p -> _state.update { s -> s.copy(fileProgress = p) } }
            if (err != null) {
                return ItemResult(item.name, true, item.size, 0, false, error = err)
            }

            // Si el resultado es mayor que el original, conservar el original
            val kept = cache.length() >= item.size
            val base = item.name.substringBeforeLast('.')
            val outName = if (kept) item.name else "$base.mp4"
            val touched = mutableListOf<String>()
            val out: File

            if (cfg.replaceOriginals && item.realPath != null) {
                val orig = File(item.realPath)
                if (kept) {
                    return ItemResult(item.name, true, item.size, item.size, true, keptOriginal = true, outputPath = orig.absolutePath)
                }
                if (cfg.backupOriginals) {
                    backupDir.mkdirs()
                    val backup = MediaUtils.uniqueFile(backupDir, item.name)
                    orig.copyTo(backup)
                    touched += backup.absolutePath
                }
                out = File(orig.parentFile ?: videosDir, outName)
                cache.copyTo(out, overwrite = true)
                if (!out.absolutePath.equals(orig.absolutePath, ignoreCase = true) && orig.exists()) {
                    orig.delete()
                    touched += orig.absolutePath
                }
            } else {
                videosDir.mkdirs()
                out = MediaUtils.uniqueFile(videosDir, outName)
                if (kept) copyOriginal(ctx, item, out) else cache.copyTo(out)
                deleteOriginalIfWanted(cfg, item, out, touched)
            }
            if (item.dateMillis > 0) out.setLastModified(item.dateMillis)
            touched += out.absolutePath
            MediaUtils.scan(ctx, touched)
            return ItemResult(item.name, true, item.size, out.length(), true, keptOriginal = kept, outputPath = out.absolutePath)
        } finally {
            cache.delete()
        }
    }

    // ── Auxiliares ────────────────────────────────────────────────────────────

    private fun minVideoBitrate(shortSide: Int, mime: String, fps: Float): Long {
        var base = when {
            shortSide >= 2160 -> 2_500_000L
            shortSide >= 1440 -> 1_400_000L
            shortSide >= 1080 -> 900_000L
            shortSide >= 720  -> 550_000L
            shortSide >= 480  -> 320_000L
            shortSide >= 360  -> 200_000L
            else -> 120_000L
        }
        if (mime == MimeTypes.VIDEO_H265) base = (base * 0.7).toLong()
        else if (mime == MimeTypes.VIDEO_AV1) base = (base * 0.6).toLong()
        return (base * (if (fps > 45f) 1.5f else 1.0f)).toLong()
    }

    private fun copyOriginal(ctx: Context, item: MediaEntry, dest: File) {
        ctx.contentResolver.openInputStream(item.uri)?.use { input ->
            dest.outputStream().use { input.copyTo(it) }
        } ?: throw IOException("No se pudo leer el original")
    }

    private fun deleteOriginalIfWanted(
        cfg: JobConfig, item: MediaEntry, out: File, touched: MutableList<String>
    ) {
        if (!cfg.deleteOriginals || item.realPath == null) return
        val orig = File(item.realPath)
        if (orig.exists() && !orig.absolutePath.equals(out.absolutePath, ignoreCase = true)) {
            orig.delete()
            touched += orig.absolutePath
        }
    }
}
