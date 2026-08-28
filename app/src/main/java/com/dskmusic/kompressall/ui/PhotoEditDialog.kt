package com.dskmusic.kompressall.ui

import android.graphics.Bitmap
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
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dskmusic.kompressall.R
import com.dskmusic.kompressall.engine.ImageCompressor
import com.dskmusic.kompressall.model.MediaEdit
import com.dskmusic.kompressall.model.MediaEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Recorte, giro, volteo y ajustes de una foto del lote. La vista previa se genera con el
 * mismo [ImageCompressor.applyEdit] que usa la compresión, sobre una copia reducida: lo
 * que se ve aquí es exactamente lo que se guarda, sin una segunda implementación que
 * mantener en sincronía.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoEditDialog(
    entry: MediaEntry,
    onDismiss: () -> Unit,
    onConfirm: (MediaEdit) -> Unit
) {
    val context = LocalContext.current
    var edit by remember { mutableStateOf(entry.edit) }

    val source by produceState<ImageCompressor.Preview?>(initialValue = null, entry.uri) {
        value = withContext(Dispatchers.IO) {
            ImageCompressor.decodePreview(context, entry.uri, PREVIEW_PX)
        }
    }
    // produceState no reinicia el valor al cambiar la clave: la vista anterior sigue
    // puesta mientras se calcula la nueva, en vez de parpadear con cada roce del slider.
    val preview by produceState<Bitmap?>(initialValue = null, source, edit) {
        val src = source ?: return@produceState
        delay(120)
        value = withContext(Dispatchers.IO) { ImageCompressor.applyEdit(src.bitmap, edit) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.photo_edit_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(entry.name, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    preview?.let {
                        Image(
                            bitmap = it.asImageBitmap(),
                            contentDescription = entry.name,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )
                    } ?: CircularProgressIndicator()
                }

                source?.let { src ->
                    val w = if (edit.swapsSides) src.height else src.width
                    val h = if (edit.swapsSides) src.width else src.height
                    Text(
                        stringResource(
                            R.string.crop_size_fmt,
                            ((edit.cropRight - edit.cropLeft) * w).roundToInt().coerceAtLeast(1),
                            ((edit.cropBottom - edit.cropTop) * h).roundToInt().coerceAtLeast(1)
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Text(stringResource(R.string.crop_h_label), style = MaterialTheme.typography.labelLarge)
                RangeSlider(
                    value = edit.cropLeft..edit.cropRight,
                    onValueChange = { r ->
                        // Un margen mínimo entre asas: un recorte de ancho cero no daría
                        // ningún bitmap y el diálogo se quedaría sin vista previa.
                        val l = r.start.coerceIn(0f, 1f - MIN_CROP)
                        edit = edit.copy(
                            cropLeft = l,
                            cropRight = r.endInclusive.coerceIn(l + MIN_CROP, 1f)
                        )
                    },
                    valueRange = 0f..1f,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(stringResource(R.string.crop_v_label), style = MaterialTheme.typography.labelLarge)
                RangeSlider(
                    value = edit.cropTop..edit.cropBottom,
                    onValueChange = { r ->
                        val t = r.start.coerceIn(0f, 1f - MIN_CROP)
                        edit = edit.copy(
                            cropTop = t,
                            cropBottom = r.endInclusive.coerceIn(t + MIN_CROP, 1f)
                        )
                    },
                    valueRange = 0f..1f,
                    modifier = Modifier.fillMaxWidth()
                )

                Text(stringResource(R.string.rotation_label), style = MaterialTheme.typography.labelLarge)
                ChoiceChips(
                    listOf("0°" to 0, "90°" to 90, "180°" to 180, "270°" to 270),
                    edit.rotationDegrees
                ) { edit = edit.copy(rotationDegrees = it) }
                LabeledSwitch(
                    title = stringResource(R.string.mirror_label),
                    description = stringResource(R.string.mirror_desc),
                    checked = edit.mirrored
                ) { edit = edit.copy(mirrored = it) }

                AdjustSlider(stringResource(R.string.brightness_label), edit.brightness) {
                    edit = edit.copy(brightness = it)
                }
                AdjustSlider(stringResource(R.string.contrast_label), edit.contrast) {
                    edit = edit.copy(contrast = it)
                }
                AdjustSlider(stringResource(R.string.saturation_label), edit.saturation) {
                    edit = edit.copy(saturation = it)
                }

                TextButton(onClick = { edit = MediaEdit() }) {
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

/** Ajuste de -100 a 100 con el valor a la vista: a ojo no se sabe cuánto se ha movido. */
@Composable
private fun AdjustSlider(label: String, value: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        Text(
            if (value > 0) "+$value" else "$value",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    Slider(
        value = value.toFloat(),
        onValueChange = { onChange(it.roundToInt()) },
        valueRange = -100f..100f,
        modifier = Modifier.fillMaxWidth()
    )
}

private const val PREVIEW_PX = 480
private const val MIN_CROP = 0.05f
