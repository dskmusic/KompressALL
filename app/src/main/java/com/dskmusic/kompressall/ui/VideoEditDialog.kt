package com.dskmusic.kompressall.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.dskmusic.kompressall.R
import com.dskmusic.kompressall.engine.VideoCompressor
import com.dskmusic.kompressall.model.MediaEntry
import com.dskmusic.kompressall.model.VideoEdit
import com.dskmusic.kompressall.model.formatDuration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Recorte y giro de un vídeo del lote. Sin previsualización: el reproductor añadiría
 * ExoPlayer entero por unos segundos de vídeo, y el rango en texto ya orienta.
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

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.video_edit_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(entry.name, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                when {
                    durationMs < 0 -> CircularProgressIndicator()
                    durationMs == 0L ->
                        Text(stringResource(R.string.video_edit_no_duration))
                    else -> {
                        val endMs = if (edit.endMs in 1..durationMs) edit.endMs else durationMs
                        val startMs = edit.startMs.coerceIn(0, endMs)
                        Text(
                            stringResource(
                                R.string.video_edit_range_fmt,
                                formatDuration(startMs), formatDuration(endMs)
                            ),
                            style = MaterialTheme.typography.labelLarge
                        )
                        RangeSlider(
                            value = startMs.toFloat()..endMs.toFloat(),
                            onValueChange = { r ->
                                edit = edit.copy(
                                    startMs = r.start.toLong(),
                                    // endMs 0 = "hasta el final": así el recorte sobrevive
                                    // aunque el vídeo real dure un pelo más de lo medido.
                                    endMs = if (r.endInclusive >= durationMs - 1) 0
                                            else r.endInclusive.toLong()
                                )
                            },
                            valueRange = 0f..durationMs.toFloat(),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                Text(
                    stringResource(R.string.rotation_label),
                    style = MaterialTheme.typography.labelLarge
                )
                ChoiceChips(
                    listOf(
                        stringResource(R.string.original_label) to 0,
                        "90°" to 90, "180°" to 180, "270°" to 270
                    ),
                    edit.rotationDegrees
                ) { edit = edit.copy(rotationDegrees = it) }
                TextButton(onClick = { edit = VideoEdit() }) {
                    Text(stringResource(R.string.reset_defaults))
                }
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
