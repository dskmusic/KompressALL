package com.dskmusic.kompressall.model

import android.net.Uri
import java.util.Locale

/** Un archivo del lote a comprimir. */
data class MediaEntry(
    val uri: Uri,
    val name: String,
    val size: Long,
    val isVideo: Boolean,
    val dateMillis: Long,
    /** Ruta física real (via MediaStore DATA). Necesaria para reemplazar/borrar originales. */
    val realPath: String?
)

enum class Preset { HIGH, MEDIUM, LOW, MANUAL }

/** Configuración del trabajo. Se persiste en prefs para recordarla entre sesiones,
 *  salvo los presets, que siempre se proponen en MEDIUM. */
data class JobConfig(
    val imagePreset: Preset = Preset.MEDIUM,
    val imageFormat: String = "jpeg",      // manual: jpeg | webp | png
    val imageQuality: Int = 75,            // manual: 1..100
    val imageResolutionPct: Int = 80,      // manual: 5..100
    val videoPreset: Preset = Preset.MEDIUM,
    val videoCodec: String = "auto",       // manual: auto | h264 | h265 | av1
    val videoShortSide: Int = 0,           // manual: 0 = original, 1080, 720, 480
    val videoFps: Int = 0,                 // manual: 0 = original, 60, 30, 24
    val videoSizePct: Int = 18,            // manual: % del tamaño original
    val audioKbps: Int = 192,              // manual
    val replaceOriginals: Boolean = false,
    val backupOriginals: Boolean = true,
    val deleteOriginals: Boolean = false,
    val twoPass: Boolean = false
)

data class ItemResult(
    val name: String,
    val isVideo: Boolean,
    val originalSize: Long,
    val finalSize: Long,
    val success: Boolean,
    val keptOriginal: Boolean = false,
    val error: String? = null,
    /** Ruta absoluta del archivo final, para poder compartirlo desde el resumen. */
    val outputPath: String? = null
) {
    val savedBytes: Long get() = (originalSize - finalSize).coerceAtLeast(0)
}

enum class Phase { IDLE, CONFIG, RUNNING, DONE }

data class EngineState(
    val phase: Phase = Phase.IDLE,
    val items: List<MediaEntry> = emptyList(),
    /** Prefijo sugerido para la carpeta destino, p.ej. "20270723 " */
    val folderSuggestion: String = "",
    val currentIndex: Int = 0,
    val fileProgress: Float = 0f,
    val isProbePass: Boolean = false,
    val currentName: String = "",
    val results: List<ItemResult> = emptyList(),
    val cancelled: Boolean = false
) {
    val imageCount: Int get() = items.count { !it.isVideo }
    val videoCount: Int get() = items.count { it.isVideo }
    val totalSize: Long get() = items.sumOf { it.size }
    val overallProgress: Float
        get() = if (items.isEmpty()) 0f
        else ((currentIndex + fileProgress) / items.size).coerceIn(0f, 1f)
}

fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val mb = bytes / (1024.0 * 1024.0)
    return when {
        mb >= 1000 -> String.format(Locale.US, "%.2f GB", mb / 1024.0)
        mb >= 1    -> String.format(Locale.US, "%.1f MB", mb)
        else       -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    }
}
