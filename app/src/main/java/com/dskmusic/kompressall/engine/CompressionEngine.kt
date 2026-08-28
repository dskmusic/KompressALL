package com.dskmusic.kompressall.engine

import android.content.Context
import android.media.MediaCodecInfo
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import com.dskmusic.kompressall.CompressionService
import com.dskmusic.kompressall.data.Settings
import com.dskmusic.kompressall.model.EngineState
import com.dskmusic.kompressall.model.ItemResult
import com.dskmusic.kompressall.model.JobConfig
import com.dskmusic.kompressall.model.MediaEntry
import com.dskmusic.kompressall.model.MediaKind
import com.dskmusic.kompressall.model.Phase
import com.dskmusic.kompressall.model.Preset
import com.dskmusic.kompressall.model.VideoEdit
import com.dskmusic.kompressall.util.MediaUtils
import com.dskmusic.kompressall.util.sourceUri
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

    /** True mientras se resuelven los archivos recibidos (nombre, tamaño, ruta real...);
     *  con lotes grandes puede tardar un poco, la UI muestra un overlay de carga. */
    val isLoading = MutableStateFlow(false)

    fun load(context: Context, uris: List<Uri>, append: Boolean = false) {
        val appCtx = context.applicationContext
        isLoading.value = true
        scope.launch {
            val newItems = try {
                uris.mapNotNull { MediaUtils.loadEntry(appCtx, it) }
            } finally {
                isLoading.value = false
            }
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

    /** Carga de golpe todos los medios que haya sueltos en [folderPath]. El listado
     *  del directorio también se hace fuera del hilo principal: una carpeta de cámara
     *  puede tener miles de archivos. */
    fun loadFolder(context: Context, folderPath: String, append: Boolean = false) {
        val appCtx = context.applicationContext
        isLoading.value = true
        scope.launch {
            val uris = try {
                MediaUtils.listMediaInFolder(File(folderPath))
            } catch (_: Exception) {
                emptyList()
            }
            isLoading.value = false
            if (uris.isNotEmpty()) load(appCtx, uris, append)
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

    /** Quita un archivo del lote antes de empezar. Si era el último, vuelve al inicio. */
    fun remove(uri: Uri) {
        _state.update { current ->
            if (current.phase != Phase.CONFIG) return@update current
            val items = current.items.filterNot { it.uri == uri }
            if (items.isEmpty()) EngineState() else current.copy(items = items)
        }
    }

    /** Guarda el recorte/giro elegido para un vídeo concreto del lote. */
    fun updateEdit(uri: Uri, edit: VideoEdit) {
        _state.update { current ->
            if (current.phase != Phase.CONFIG) return@update current
            current.copy(items = current.items.map { if (it.uri == uri) it.copy(edit = edit) else it })
        }
    }

    fun discard() {
        spaceWarning.value = null
        _state.value = EngineState()
    }

    fun cancel() {
        _state.update { it.copy(cancelled = true) }
        job?.cancel()
    }

    /** (bytes necesarios, bytes libres) cuando el lote no cabe. La UI decide si seguir. */
    val spaceWarning = MutableStateFlow<Pair<Long, Long>?>(null)

    fun start(context: Context, config: JobConfig, folderName: String, force: Boolean = false) {
        val snapshot = _state.value
        if (snapshot.items.isEmpty() || snapshot.phase != Phase.CONFIG) return
        if (!force) {
            val needed = spaceNeeded(snapshot.items, config)
            val free = freeSpace()
            if (free in 0 until needed) {
                spaceWarning.value = needed to free
                return
            }
        }
        spaceWarning.value = null
        runJob(context, snapshot.items, config, folderName.trim().ifBlank { snapshot.folderSuggestion })
    }

    /**
     * Peor caso: un archivo comprimido nunca acaba ocupando más que el original (si le
     * saliera mayor se conserva el original), así que el lote entero cabe en su propio
     * tamaño; más la copia de seguridad si toca, más el temporal más grande de la caché.
     */
    private fun spaceNeeded(items: List<MediaEntry>, cfg: JobConfig): Long {
        val total = items.sumOf { it.size }
        val backup = if (cfg.replaceOriginals && cfg.backupOriginals) total else 0L
        return total + backup + (items.maxOfOrNull { it.size } ?: 0L)
    }

    /** -1 si no se puede saber: en ese caso no se avisa, mejor que un falso positivo. */
    private fun freeSpace(): Long = try {
        StatFs(Environment.getExternalStorageDirectory().absolutePath).availableBytes
    } catch (_: Exception) {
        -1L
    }

    private fun pendingFile(context: Context) = File(context.applicationContext.filesDir, "pending_job.json")

    /** ¿Hay un lote que se quedó a medias en un cierre anterior de la app? */
    fun hasPendingJob(context: Context): Boolean = pendingFile(context).exists()

    fun loadPendingJob(context: Context): PendingJob? =
        pendingFile(context).takeIf { it.exists() }?.let { f ->
            parsePendingJob(f.readText())
        }

    fun discardPendingJob(context: Context) {
        pendingFile(context).delete()
    }

    fun resumePendingJob(context: Context) {
        val pending = loadPendingJob(context) ?: return
        runJob(context, pending.items, pending.config, pending.folderName, pending.savedSoFar)
    }

    /**
     * [savedBase] son los bytes ya ahorrados en intentos anteriores de este mismo lote:
     * el contador global solo se actualiza al terminar, así que al reanudar hay que
     * arrastrarlos o se pierden.
     */
    private fun runJob(
        context: Context,
        items: List<MediaEntry>,
        config: JobConfig,
        folderName: String,
        savedBase: Long = 0L
    ) {
        val appCtx = context.applicationContext
        if (items.isEmpty()) return

        val dirName = folderName.trim().ifBlank {
            SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        }

        CompressionService.start(appCtx)
        _state.update {
            it.copy(phase = Phase.RUNNING, items = items, currentIndex = 0, fileProgress = 0f,
                results = emptyList(), cancelled = false)
        }

        job = scope.launch {
            pendingFile(appCtx).writeText(PendingJob(items, config, dirName, savedBase).toJson())
            val root = File(Environment.getExternalStorageDirectory(), "KompressALL")
            val destDir = File(root, dirName)
            val videosDir = File(destDir, "Videos")
            val audioDir = File(destDir, "Audio")
            val backupDir = File(File(root, "Backups"), dirName)
            val results = mutableListOf<ItemResult>()
            try {
                for ((i, item) in items.withIndex()) {
                    if (!isActive || _state.value.cancelled) break
                    _state.update {
                        it.copy(currentIndex = i, fileProgress = 0f,
                            currentName = item.name, isProbePass = false)
                    }
                    val result = try {
                        when (item.kind) {
                            MediaKind.VIDEO -> processVideo(appCtx, item, config, destDir, videosDir, backupDir)
                            MediaKind.AUDIO -> processAudio(appCtx, item, config, audioDir, backupDir)
                            MediaKind.IMAGE -> processImage(appCtx, item, config, destDir, backupDir)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        ItemResult(item.name, item.isVideo, item.size, 0, false, error = e.message)
                    }
                    results += result
                    _state.update { it.copy(results = results.toList(), fileProgress = 1f) }
                    // Se reescribe con lo que queda, para poder retomar solo lo pendiente
                    // si la app se cierra a partir de aquí.
                    val remaining = items.drop(i + 1)
                    if (remaining.isEmpty()) pendingFile(appCtx).delete()
                    else pendingFile(appCtx).writeText(
                        PendingJob(
                            remaining, config, dirName,
                            savedBase + results.sumOf { r -> r.savedBytes }
                        ).toJson()
                    )
                }
            } finally {
                pendingFile(appCtx).delete()
                val saved = savedBase + results.sumOf { r -> r.savedBytes }
                if (saved > 0) Settings.totalSaved += saved
                _state.update { it.copy(phase = Phase.DONE, results = results.toList()) }
            }
        }
    }

    // ── Imagen ────────────────────────────────────────────────────────────────

    internal fun processImage(
        ctx: Context, item: MediaEntry, cfg: JobConfig, destDir: File, backupDir: File
    ): ItemResult {
        val minSize = Settings.minSizeToCompressBytes
        if (minSize > 0 && item.size < minSize) {
            return finishImage(ctx, item, cfg, destDir, backupDir, kept = true, outName = item.name, bytes = null)
        }
        val (format, quality, scale) = when (cfg.imagePreset) {
            Preset.HIGH   -> Triple("jpeg", 95, 1f)
            Preset.MEDIUM -> Triple("jpeg", 75, 0.8f)
            Preset.LOW    -> Triple("jpeg", 45, 0.5f)
            Preset.MANUAL -> Triple(cfg.imageFormat, cfg.imageQuality, cfg.imageResolutionPct / 100f)
        }
        val bytes = ImageCompressor.compress(ctx, item.sourceUri(), format, quality, scale)
            ?: return ItemResult(item.name, false, item.size, 0, false, error = "decode")

        // Si el resultado es mayor que el original, conservar el original
        val kept = bytes.size >= item.size
        val base = item.name.substringBeforeLast('.')
        val ext = if (kept) item.name.substringAfterLast('.', "jpg").lowercase()
        else if (format == "jpeg") "jpg" else format
        val outName = "$base.$ext"
        return finishImage(ctx, item, cfg, destDir, backupDir, kept, outName, if (kept) null else bytes)
    }

    private fun finishImage(
        ctx: Context, item: MediaEntry, cfg: JobConfig, destDir: File, backupDir: File,
        kept: Boolean, outName: String, bytes: ByteArray?
    ): ItemResult {
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
            if (kept) copyOriginal(ctx, item, out) else out.writeBytes(bytes!!)
            if (!out.absolutePath.equals(orig.absolutePath, ignoreCase = true) && orig.exists()) {
                orig.delete()
                touched += orig.absolutePath
            }
        } else {
            destDir.mkdirs()
            out = MediaUtils.uniqueFile(destDir, outName)
            if (kept) copyOriginal(ctx, item, out) else out.writeBytes(bytes!!)
            deleteOriginalIfWanted(cfg, item, out, touched)
        }
        if (item.dateMillis > 0) out.setLastModified(item.dateMillis)
        touched += out.absolutePath
        MediaUtils.scan(ctx, touched, out.absolutePath, item.dateMillis)
        return ItemResult(item.name, false, item.size, out.length(), true, keptOriginal = kept, outputPath = out.absolutePath)
    }

    // ── Audio ─────────────────────────────────────────────────────────────────

    internal suspend fun processAudio(
        ctx: Context, item: MediaEntry, cfg: JobConfig, audioDir: File, backupDir: File
    ): ItemResult {
        val minSize = Settings.minSizeToCompressBytes
        if (minSize > 0 && item.size < minSize) {
            return placeTranscoded(ctx, item, cfg, audioDir, backupDir, item.name, null)
        }
        val targetKbps = when (cfg.audioPreset) {
            Preset.HIGH -> 192; Preset.MEDIUM -> 128; Preset.LOW -> 96
            Preset.MANUAL -> cfg.audioOutKbps.coerceIn(32, 320)
        }
        // Recodificar un archivo que ya viene por debajo del objetivo solo pierde calidad
        // sin ganar espacio, así que se conserva tal cual.
        val meta = AudioCompressor.readMeta(ctx, item.sourceUri())
        if (meta != null && meta.bitrate in 1..(targetKbps * 1000)) {
            return placeTranscoded(ctx, item, cfg, audioDir, backupDir, item.name, null)
        }

        val cache = File(ctx.cacheDir, "ka_${System.currentTimeMillis()}.m4a")
        try {
            val err = AudioCompressor.transcode(ctx, item.sourceUri(), cache, targetKbps * 1000) { p ->
                _state.update { s -> s.copy(fileProgress = p) }
            }
            if (err != null) return ItemResult(item.name, false, item.size, 0, false, error = err)

            // Transformer no arrastra las etiquetas y la carátula puede pesar cientos de
            // KB: se copian antes de comparar tamaños para no decidir sobre un tamaño
            // que aún va a crecer.
            Mp4Metadata.copyTags(ctx, item.sourceUri(), cache, item.dateMillis)
            val kept = cache.length() >= item.size
            val outName = if (kept) item.name else "${item.name.substringBeforeLast('.')}.m4a"
            return placeTranscoded(ctx, item, cfg, audioDir, backupDir, outName, if (kept) null else cache)
        } finally {
            cache.delete()
        }
    }

    // ── Vídeo ─────────────────────────────────────────────────────────────────

    internal suspend fun processVideo(
        ctx: Context, item: MediaEntry, cfg: JobConfig,
        destDir: File, videosDir: File, backupDir: File
    ): ItemResult {
        val minSize = Settings.minSizeToCompressBytes
        // Un vídeo recortado o girado siempre pasa por el transcodificador, aunque sea
        // pequeño: si no, se perdería la edición que ha pedido el usuario.
        if (minSize > 0 && item.size < minSize && !item.edit.isSet) {
            return placeTranscoded(ctx, item, cfg, videosDir, backupDir, item.name, null)
        }
        val meta = VideoCompressor.readMeta(ctx, item.sourceUri())
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

        // Recorte: el trozo que se conserva marca tanto la duración a codificar como
        // la parte proporcional del tamaño original que sirve de objetivo.
        val fullDurationMs = meta.durationMs.coerceAtLeast(1)
        val clipEndMs = if (item.edit.endMs in 1..fullDurationMs) item.edit.endMs else fullDurationMs
        val clipStartMs = item.edit.startMs.coerceIn(0, (clipEndMs - 200).coerceAtLeast(0))
        val effDurationMs = (clipEndMs - clipStartMs).coerceAtLeast(200)
        val durationFraction = effDurationMs.toDouble() / fullDurationMs

        // Resolución de salida: el preset fija el lado corto (720p/1080p también en vertical).
        // El giro del usuario intercambia los lados antes de decidir nada.
        val dispW = (if (item.edit.swapsSides) meta.displayHeight else meta.displayWidth).coerceAtLeast(2)
        val dispH = (if (item.edit.swapsSides) meta.displayWidth else meta.displayHeight).coerceAtLeast(2)
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
        val durationSec = (effDurationMs / 1000.0).coerceAtLeast(0.1)
        val targetBytes = (item.size * fraction.toDouble() * durationFraction).coerceAtLeast(100_000.0)
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

        // Media3 le pide al encoder H.264 su nivel máximo (findHighestSupportedEncodingLevel,
        // que en móviles modernos es 6.0/6.2) y ese level_idc acaba en el SPS. Muchas TVs
        // rechazan el archivo solo por eso, aunque la resolución sea baja ("archivo no
        // compatible"). Pedimos el nivel más bajo que cubra la salida; 0 = que elija Media3.
        val avcLevel = if (mime != MimeTypes.VIDEO_H264) 0 else {
            val (outW, outH) = dims(outDisplayHeight)
            when {
                outW * outH > 1920 * 1088 -> 0
                effFps > 30f -> MediaCodecInfo.CodecProfileLevel.AVCLevel42
                else -> MediaCodecInfo.CodecProfileLevel.AVCLevel4
            }
        }

        val edit = item.edit.copy(startMs = clipStartMs, endMs = if (clipEndMs >= fullDurationMs) 0 else clipEndMs)
        val cache = File(ctx.cacheDir, "ka_${System.currentTimeMillis()}.mp4")
        try {
            if (cfg.twoPass) {
                // Pasada de análisis: mide el tamaño real y corrige el bitrate
                _state.update { it.copy(isProbePass = true) }
                val probe = File(ctx.cacheDir, "ka_probe_${System.currentTimeMillis()}.mp4")
                val probeErr = VideoCompressor.transcode(
                    ctx, item.sourceUri(), probe, mime, videoBitrate, outDisplayHeight,
                    outFps, srcFps, audioBps, audioPassthrough, avcLevel, edit
                ) { p -> _state.update { s -> s.copy(fileProgress = p * 0.5f) } }
                if (probeErr == null && probe.length() > 0) {
                    videoBitrate = (videoBitrate * (targetBytes / probe.length()))
                        .toLong().coerceIn(100_000L, cap)
                }
                probe.delete()
                _state.update { it.copy(isProbePass = false, fileProgress = 0f) }
            }

            val err = VideoCompressor.transcode(
                ctx, item.sourceUri(), cache, mime, videoBitrate, outDisplayHeight,
                outFps, srcFps, audioBps, audioPassthrough, avcLevel, edit
            ) { p -> _state.update { s -> s.copy(fileProgress = p) } }
            if (err != null) {
                return ItemResult(item.name, true, item.size, 0, false, error = err)
            }

            // Ubicación, fecha de grabación y etiquetas del contenedor: Transformer solo
            // conserva la rotación, el resto hay que reinyectarlo.
            Mp4Metadata.copyTags(ctx, item.sourceUri(), cache, item.dateMillis)

            // Si el resultado es mayor que el original, conservar el original. Con recorte
            // o giro no aplica: descartar la salida tiraría la edición del usuario.
            val kept = !item.edit.isSet && cache.length() >= item.size
            val outName = if (kept) item.name else "${item.name.substringBeforeLast('.')}.mp4"
            return placeTranscoded(ctx, item, cfg, videosDir, backupDir, outName, if (kept) null else cache)
        } finally {
            cache.delete()
        }
    }

    // ── Auxiliares ────────────────────────────────────────────────────────────

    /**
     * Coloca el archivo ya transcodificado en su destino final (carpeta nueva o
     * reemplazando el original), haciendo el backup previo si toca.
     * [cache] a null significa "conservar el original tal cual".
     */
    private fun placeTranscoded(
        ctx: Context, item: MediaEntry, cfg: JobConfig,
        outDir: File, backupDir: File, outName: String, cache: File?
    ): ItemResult {
        val kept = cache == null
        val touched = mutableListOf<String>()
        val out: File

        if (cfg.replaceOriginals && item.realPath != null) {
            val orig = File(item.realPath)
            if (kept) {
                return ItemResult(item.name, item.isVideo, item.size, item.size, true, keptOriginal = true, outputPath = orig.absolutePath)
            }
            if (cfg.backupOriginals) {
                backupDir.mkdirs()
                val backup = MediaUtils.uniqueFile(backupDir, item.name)
                orig.copyTo(backup)
                touched += backup.absolutePath
            }
            out = File(orig.parentFile ?: outDir, outName)
            cache!!.copyTo(out, overwrite = true)
            if (!out.absolutePath.equals(orig.absolutePath, ignoreCase = true) && orig.exists()) {
                orig.delete()
                touched += orig.absolutePath
            }
        } else {
            outDir.mkdirs()
            out = MediaUtils.uniqueFile(outDir, outName)
            if (kept) copyOriginal(ctx, item, out) else cache!!.copyTo(out)
            deleteOriginalIfWanted(cfg, item, out, touched)
        }
        if (item.dateMillis > 0) out.setLastModified(item.dateMillis)
        touched += out.absolutePath
        MediaUtils.scan(ctx, touched, out.absolutePath, item.dateMillis)
        return ItemResult(item.name, item.isVideo, item.size, out.length(), true, keptOriginal = kept, outputPath = out.absolutePath)
    }

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
        ctx.contentResolver.openInputStream(item.sourceUri())?.use { input ->
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
