package com.dskmusic.kompressall.ui

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.dskmusic.kompressall.R
import com.dskmusic.kompressall.engine.VideoCompressor
import com.dskmusic.kompressall.model.MediaEntry
import com.dskmusic.kompressall.model.VideoEdit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Recorte y giro de un vídeo del lote, con un fotograma de la marca de entrada y otro
 * de la de salida. No hay reproductor: solo para ver dónde caen las marcas, ExoPlayer
 * costaría más de lo que aporta, y los fotogramas se ven mientras se arrastra.
 */
@OptIn(ExperimentalMaterial3Api::class)
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun VideoEditDialog(
    entry: MediaEntry,
    onDismiss: () -> Unit,
    onConfirm: (VideoEdit) -> Unit
) {
    val context = LocalContext.current
    val durationMs by produceState(initialValue = -1L, entry.uri) {
        value = withContext(Dispatchers.IO) {
            VideoCompressor.readMeta(context, entry.uri)?.durationMs ?: 0L
        }
    }
    var edit by remember { mutableStateOf(entry.edit) }
    var startText by remember { mutableStateOf("") }
    var endText by remember { mutableStateOf("") }

    // Posiciones efectivas: endMs 0 significa "hasta el final".
    val endMs = if (edit.endMs in 1..durationMs) edit.endMs else durationMs.coerceAtLeast(0)
    val startMs = edit.startMs.coerceIn(0, endMs.coerceAtLeast(0))

    // Los cuadros de texto se reescriben cuando el cambio viene del slider o de la carga
    // inicial; al teclear se actualiza `edit` pero no el texto, o se pelearían.
    LaunchedEffect(durationMs) {
        if (durationMs > 0) {
            startText = formatPrecise(startMs)
            endText = formatPrecise(endMs)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.video_edit_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(entry.name, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                when {
                    durationMs < 0 -> Box(
                        Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) { CircularProgressIndicator() }

                    durationMs == 0L -> Text(stringResource(R.string.video_edit_no_duration))

                    else -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FramePreview(
                                entry.uri, startMs, edit.rotationDegrees, edit.mirrored,
                                stringResource(R.string.trim_start),
                                Modifier.weight(1f)
                            )
                            FramePreview(
                                entry.uri, endMs, edit.rotationDegrees, edit.mirrored,
                                stringResource(R.string.trim_end),
                                Modifier.weight(1f)
                            )
                        }
                        RangeSlider(
                            value = startMs.toFloat()..endMs.toFloat(),
                            onValueChange = { r ->
                                val s = r.start.toLong().coerceIn(0, durationMs)
                                val e = r.endInclusive.toLong().coerceIn(0, durationMs)
                                edit = edit.copy(
                                    startMs = s,
                                    // 0 = hasta el final: así el recorte sobrevive aunque
                                    // el vídeo real dure un pelo más de lo medido.
                                    endMs = if (e >= durationMs - 1) 0 else e
                                )
                                startText = formatPrecise(s)
                                endText = formatPrecise(e)
                            },
                            valueRange = 0f..durationMs.toFloat(),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = startText,
                                onValueChange = { t ->
                                    startText = t
                                    parseTime(t)?.coerceIn(0, durationMs)?.let { ms ->
                                        if (ms < endMs) edit = edit.copy(startMs = ms)
                                    }
                                },
                                label = { Text(stringResource(R.string.trim_start)) },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = endText,
                                onValueChange = { t ->
                                    endText = t
                                    parseTime(t)?.coerceIn(0, durationMs)?.let { ms ->
                                        if (ms > startMs) edit = edit.copy(
                                            endMs = if (ms >= durationMs) 0 else ms
                                        )
                                    }
                                },
                                label = { Text(stringResource(R.string.trim_end)) },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Text(
                            stringResource(
                                R.string.video_edit_range_fmt,
                                formatPrecise(startMs), formatPrecise(endMs)
                            ) + "\n" + stringResource(
                                R.string.trim_duration_fmt,
                                formatPrecise((endMs - startMs).coerceAtLeast(0)),
                                formatPrecise(durationMs)
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Text(
                    stringResource(R.string.rotation_label),
                    style = MaterialTheme.typography.labelLarge
                )
                ChoiceChips(
                    listOf("0°" to 0, "90°" to 90, "180°" to 180, "270°" to 270),
                    edit.rotationDegrees
                ) { edit = edit.copy(rotationDegrees = it) }
                LabeledSwitch(
                    title = stringResource(R.string.mirror_label),
                    description = stringResource(R.string.mirror_desc),
                    checked = edit.mirrored
                ) { edit = edit.copy(mirrored = it) }
                TextButton(onClick = {
                    edit = VideoEdit()
                    startText = formatPrecise(0)
                    endText = formatPrecise(durationMs.coerceAtLeast(0))
                }) { Text(stringResource(R.string.reset_defaults)) }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(edit) }) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

/**
 * Fotograma en [positionMs]. Se extrae con un retriever de usar y tirar: mantener uno
 * abierto obligaría a sincronizar su cierre con el arrastre, y el retardo de abajo ya
 * evita que se extraiga un fotograma por cada píxel movido.
 */
@Composable
private fun FramePreview(
    uri: Uri,
    positionMs: Long,
    rotationDegrees: Int,
    mirrored: Boolean,
    label: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    // produceState no reinicia el valor al cambiar la clave: el fotograma anterior sigue
    // visible mientras se extrae el nuevo, en vez de parpadear en negro.
    val frame by produceState<Bitmap?>(initialValue = null, uri, positionMs) {
        delay(180)
        value = withContext(Dispatchers.IO) {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(context, uri)
                // OPTION_CLOSEST y no _SYNC: el fotograma clave más cercano puede caer
                // segundos antes, y entonces la vista previa no serviría para lo único
                // que se le pide, que es ver dónde cae exactamente la marca.
                r.getScaledFrameAtTime(
                    positionMs * 1000,
                    MediaMetadataRetriever.OPTION_CLOSEST,
                    360, 360
                )
            } catch (_: Exception) {
                null
            } finally {
                try { r.release() } catch (_: Exception) {}
            }
        }
    }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            frame?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = label,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            // El volteo va antes que el giro, igual que en la exportación.
                            scaleX = if (mirrored) -1f else 1f
                            rotationZ = rotationDegrees.toFloat()
                        }
                )
            } ?: CircularProgressIndicator()
        }
        Text(
            "$label · ${formatPrecise(positionMs)}",
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
}

/** m:ss.d — las décimas importan para ajustar una marca a mano. */
internal fun formatPrecise(millis: Long): String {
    val ms = millis.coerceAtLeast(0)
    val totalSec = ms / 1000
    val tenths = (ms % 1000) / 100
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d.%d".format(h, m, s, tenths)
    else "%d:%02d.%d".format(m, s, tenths)
}

/**
 * Acepta "12", "1:23", "1:23.4" y "1:02:03.4". Devuelve null si aún no es un tiempo
 * válido, para no pisar la marca mientras el usuario está a medio teclear.
 */
internal fun parseTime(text: String): Long? {
    val t = text.trim().replace(',', '.')
    if (t.isEmpty()) return null
    val parts = t.split(':')
    if (parts.size > 3) return null
    var total = 0.0
    for (part in parts) {
        val v = part.toDoubleOrNull() ?: return null
        if (v < 0) return null
        total = total * 60 + v
    }
    return (total * 1000).toLong()
}
