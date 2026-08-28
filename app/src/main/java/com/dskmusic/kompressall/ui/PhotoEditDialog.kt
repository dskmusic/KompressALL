package com.dskmusic.kompressall.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dskmusic.kompressall.R
import com.dskmusic.kompressall.engine.ImageCompressor
import com.dskmusic.kompressall.model.MediaEdit
import com.dskmusic.kompressall.model.MediaEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Recorte, giro, volteo y ajustes de una foto del lote. La vista previa se genera con el
 * mismo [ImageCompressor.applyEdit] que usa la compresión, sobre una copia reducida: lo
 * que se ve aquí es exactamente lo que se guarda, sin una segunda implementación que
 * mantener en sincronía.
 */
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
    // La vista previa lleva el giro, el volteo y los ajustes, pero no el recorte: el
    // recuadro se dibuja encima de la foto entera, como en cualquier editor.
    val base = edit.copy(cropLeft = 0f, cropTop = 0f, cropRight = 1f, cropBottom = 1f)
    // produceState no reinicia el valor al cambiar la clave: la vista anterior sigue
    // puesta mientras se calcula la nueva, en vez de parpadear con cada roce del slider.
    val preview by produceState<Bitmap?>(initialValue = null, source, base) {
        val src = source ?: return@produceState
        delay(120)
        value = withContext(Dispatchers.IO) { ImageCompressor.applyEdit(src.bitmap, base) }
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
                        .height(300.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    val bmp = preview
                    if (bmp == null) {
                        CircularProgressIndicator()
                    } else {
                        CropBox(
                            bitmap = bmp,
                            edit = edit,
                            onChange = { edit = it },
                            modifier = Modifier
                                .fillMaxSize()
                                // La zona de gestos tiene que coincidir con la foto al
                                // píxel, o el recuadro no caería donde se toca.
                                .aspectRatio(
                                    bmp.width.toFloat() / bmp.height,
                                    matchHeightConstraintsFirst = bmp.height > bmp.width
                                )
                        )
                    }
                }
                Text(
                    stringResource(R.string.crop_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

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

                Text(stringResource(R.string.rotation_label), style = MaterialTheme.typography.labelLarge)
                ChoiceChips(
                    listOf("0°" to 0, "90°" to 90, "180°" to 180, "270°" to 270),
                    edit.rotationDegrees
                ) {
                    // Al girar, el recuadro dejaría de cuadrar con lo que se ve: se
                    // vuelve a la foto entera en vez de recortar por donde no toca.
                    edit = edit.copy(
                        rotationDegrees = it,
                        cropLeft = 0f, cropTop = 0f, cropRight = 1f, cropBottom = 1f
                    )
                }
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

private enum class Grab { NONE, TL, TR, BL, BR, MOVE }

/**
 * Foto con el recuadro de recorte encima: se arrastra por dentro para moverlo y por las
 * esquinas para redimensionarlo. Las coordenadas son fracciones del bitmap, que ocupa
 * exactamente esta caja, así que no hay conversión de escalas que se pueda desajustar.
 */
@Composable
private fun CropBox(
    bitmap: Bitmap,
    edit: MediaEdit,
    onChange: (MediaEdit) -> Unit,
    modifier: Modifier = Modifier
) {
    val handlePx = with(LocalDensity.current) { 28.dp.toPx() }
    // El gesto se registra una sola vez; sin esto se quedaría con el `edit` de la
    // primera composición y cada arrastre partiría del recuadro inicial.
    val current = rememberUpdatedState(edit)
    val change = rememberUpdatedState(onChange)
    var grab by remember { mutableStateOf(Grab.NONE) }

    Box(modifier) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = Modifier.fillMaxSize()
        )
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { p ->
                            val e = current.value
                            val l = e.cropLeft * size.width
                            val t = e.cropTop * size.height
                            val r = e.cropRight * size.width
                            val b = e.cropBottom * size.height
                            fun near(x: Float, y: Float) =
                                abs(p.x - x) < handlePx && abs(p.y - y) < handlePx
                            grab = when {
                                near(l, t) -> Grab.TL
                                near(r, t) -> Grab.TR
                                near(l, b) -> Grab.BL
                                near(r, b) -> Grab.BR
                                p.x in l..r && p.y in t..b -> Grab.MOVE
                                else -> Grab.NONE
                            }
                        },
                        onDragEnd = { grab = Grab.NONE },
                        onDragCancel = { grab = Grab.NONE },
                        onDrag = { event, drag ->
                            event.consume()
                            val e = current.value
                            val dx = drag.x / size.width
                            val dy = drag.y / size.height
                            change.value(
                                when (grab) {
                                    Grab.TL -> e.copy(
                                        cropLeft = (e.cropLeft + dx).coerceIn(0f, e.cropRight - MIN_CROP),
                                        cropTop = (e.cropTop + dy).coerceIn(0f, e.cropBottom - MIN_CROP)
                                    )
                                    Grab.TR -> e.copy(
                                        cropRight = (e.cropRight + dx).coerceIn(e.cropLeft + MIN_CROP, 1f),
                                        cropTop = (e.cropTop + dy).coerceIn(0f, e.cropBottom - MIN_CROP)
                                    )
                                    Grab.BL -> e.copy(
                                        cropLeft = (e.cropLeft + dx).coerceIn(0f, e.cropRight - MIN_CROP),
                                        cropBottom = (e.cropBottom + dy).coerceIn(e.cropTop + MIN_CROP, 1f)
                                    )
                                    Grab.BR -> e.copy(
                                        cropRight = (e.cropRight + dx).coerceIn(e.cropLeft + MIN_CROP, 1f),
                                        cropBottom = (e.cropBottom + dy).coerceIn(e.cropTop + MIN_CROP, 1f)
                                    )
                                    // Mover no puede deformar el recuadro: el
                                    // desplazamiento se recorta al hueco que queda.
                                    Grab.MOVE -> {
                                        val mx = dx.coerceIn(-e.cropLeft, 1f - e.cropRight)
                                        val my = dy.coerceIn(-e.cropTop, 1f - e.cropBottom)
                                        e.copy(
                                            cropLeft = e.cropLeft + mx, cropRight = e.cropRight + mx,
                                            cropTop = e.cropTop + my, cropBottom = e.cropBottom + my
                                        )
                                    }
                                    Grab.NONE -> e
                                }
                            )
                        }
                    )
                }
        ) {
            val l = edit.cropLeft * size.width
            val t = edit.cropTop * size.height
            val r = edit.cropRight * size.width
            val b = edit.cropBottom * size.height
            val dim = Color.Black.copy(alpha = 0.55f)
            drawRect(dim, size = Size(size.width, t))
            drawRect(dim, topLeft = Offset(0f, b), size = Size(size.width, size.height - b))
            drawRect(dim, topLeft = Offset(0f, t), size = Size(l, b - t))
            drawRect(dim, topLeft = Offset(r, t), size = Size(size.width - r, b - t))

            for (i in 1..2) {
                val x = l + (r - l) * i / 3f
                val y = t + (b - t) * i / 3f
                drawLine(Color.White.copy(alpha = 0.35f), Offset(x, t), Offset(x, b), 1.dp.toPx())
                drawLine(Color.White.copy(alpha = 0.35f), Offset(l, y), Offset(r, y), 1.dp.toPx())
            }
            drawRect(
                Color.White, topLeft = Offset(l, t), size = Size(r - l, b - t),
                style = Stroke(1.5.dp.toPx())
            )

            val arm = 20.dp.toPx()
            val thick = 4.dp.toPx()
            for ((corner, dir) in listOf(
                Offset(l, t) to Offset(1f, 1f),
                Offset(r, t) to Offset(-1f, 1f),
                Offset(l, b) to Offset(1f, -1f),
                Offset(r, b) to Offset(-1f, -1f)
            )) {
                drawLine(Color.White, corner, Offset(corner.x + arm * dir.x, corner.y), thick)
                drawLine(Color.White, corner, Offset(corner.x, corner.y + arm * dir.y), thick)
            }
        }
    }
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
