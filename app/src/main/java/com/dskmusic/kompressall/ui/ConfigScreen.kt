package com.dskmusic.kompressall.ui

import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import com.dskmusic.kompressall.R
import com.dskmusic.kompressall.data.Settings
import com.dskmusic.kompressall.engine.VideoCompressor
import com.dskmusic.kompressall.model.EngineState
import com.dskmusic.kompressall.model.JobConfig
import com.dskmusic.kompressall.model.Preset
import com.dskmusic.kompressall.model.formatSize

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun ConfigScreen(
    state: EngineState,
    onStart: (JobConfig, String) -> Unit,
    onDiscard: () -> Unit,
    onAddMore: () -> Unit
) {
    val context = LocalContext.current
    var cfg by remember { mutableStateOf(Settings.loadConfig()) }
    var showDeleteWarning by remember { mutableStateOf(false) }
    val update: (JobConfig) -> Unit = { cfg = it; Settings.saveConfig(it) }
    var folderName by remember(state.folderSuggestion) { mutableStateOf(state.folderSuggestion) }
    val av1Available = remember { VideoCompressor.hasEncoder(MimeTypes.VIDEO_AV1) }

    val presetOptions = listOf(
        stringResource(R.string.preset_high) to Preset.HIGH,
        stringResource(R.string.preset_medium) to Preset.MEDIUM,
        stringResource(R.string.preset_low) to Preset.LOW,
        "+" to Preset.MANUAL
    )

    // Estimación heurística del tamaño final del lote
    val estimate = remember(cfg, state.items) {
        val (q, s) = when (cfg.imagePreset) {
            Preset.HIGH -> 95 to 1f
            Preset.MEDIUM -> 75 to 0.8f
            Preset.LOW -> 45 to 0.5f
            Preset.MANUAL -> cfg.imageQuality to cfg.imageResolutionPct / 100f
        }
        val resFactor = if (s >= 0.999f) 1f else s * s * 0.9f
        val imgFactor = (q / 100f * 0.85f * resFactor).coerceIn(0.02f, 0.98f)
        val vidFraction = when (cfg.videoPreset) {
            Preset.HIGH -> 0.40f; Preset.MEDIUM -> 0.18f; Preset.LOW -> 0.08f
            Preset.MANUAL -> cfg.videoSizePct / 100f
        }
        val imgBytes = state.items.filter { !it.isVideo }.sumOf { it.size }
        val vidBytes = state.items.filter { it.isVideo }.sumOf { it.size }
        (imgBytes * imgFactor + vidBytes * vidFraction).toLong()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "KompressALL",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onDiscard) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.clear_selection))
            }
        }

        SectionCard {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.items) { MediaThumb(it) }
            }
            Text(
                stringResource(
                    R.string.batch_summary,
                    state.imageCount, state.videoCount, formatSize(state.totalSize)
                ),
                style = MaterialTheme.typography.bodyMedium
            )
            TextButton(onClick = onAddMore, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.add_more))
            }
            Text(
                stringResource(R.string.estimated_size_fmt, formatSize(estimate)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }

        if (state.imageCount > 0) {
            SectionCard(title = stringResource(R.string.images_section)) {
                ChoiceChips(presetOptions, cfg.imagePreset) { update(cfg.copy(imagePreset = it)) }
                if (cfg.imagePreset == Preset.MANUAL) {
                    Text(stringResource(R.string.format_label), style = MaterialTheme.typography.labelLarge)
                    ChoiceChips(
                        listOf("JPG" to "jpeg", "WEBP" to "webp", "PNG" to "png"),
                        cfg.imageFormat
                    ) { update(cfg.copy(imageFormat = it)) }
                    Text(
                        stringResource(R.string.quality_fmt, cfg.imageQuality),
                        style = MaterialTheme.typography.labelLarge
                    )
                    Slider(
                        value = cfg.imageQuality.toFloat(),
                        onValueChange = { update(cfg.copy(imageQuality = it.toInt())) },
                        valueRange = 5f..100f
                    )
                    Text(
                        stringResource(R.string.resolution_pct_fmt, cfg.imageResolutionPct),
                        style = MaterialTheme.typography.labelLarge
                    )
                    Slider(
                        value = cfg.imageResolutionPct.toFloat(),
                        onValueChange = { update(cfg.copy(imageResolutionPct = it.toInt())) },
                        valueRange = 5f..100f
                    )
                }
            }
        }

        if (state.videoCount > 0) {
            SectionCard(title = stringResource(R.string.videos_section)) {
                ChoiceChips(presetOptions, cfg.videoPreset) { update(cfg.copy(videoPreset = it)) }
                if (cfg.videoPreset == Preset.MANUAL) {
                    Text(stringResource(R.string.codec_label), style = MaterialTheme.typography.labelLarge)
                    val codecs = buildList {
                        add(stringResource(R.string.codec_auto) to "auto")
                        add("H.264" to "h264")
                        add("H.265" to "h265")
                        if (av1Available) add("AV1" to "av1")
                    }
                    ChoiceChips(codecs, cfg.videoCodec) { update(cfg.copy(videoCodec = it)) }
                    Text(stringResource(R.string.resolution_label), style = MaterialTheme.typography.labelLarge)
                    ChoiceChips(
                        listOf(
                            stringResource(R.string.original_label) to 0,
                            "1080p" to 1080, "720p" to 720, "480p" to 480
                        ),
                        cfg.videoShortSide
                    ) { update(cfg.copy(videoShortSide = it)) }
                    Text(stringResource(R.string.fps_label), style = MaterialTheme.typography.labelLarge)
                    ChoiceChips(
                        listOf(
                            stringResource(R.string.original_label) to 0,
                            "60" to 60, "30" to 30, "24" to 24
                        ),
                        cfg.videoFps
                    ) { update(cfg.copy(videoFps = it)) }
                    Text(
                        stringResource(R.string.target_pct_fmt, cfg.videoSizePct),
                        style = MaterialTheme.typography.labelLarge
                    )
                    Slider(
                        value = cfg.videoSizePct.toFloat(),
                        onValueChange = { update(cfg.copy(videoSizePct = it.toInt())) },
                        valueRange = 5f..90f
                    )
                    Text(stringResource(R.string.audio_label), style = MaterialTheme.typography.labelLarge)
                    ChoiceChips(
                        listOf("96" to 96, "128" to 128, "192" to 192, "256" to 256),
                        cfg.audioKbps
                    ) { update(cfg.copy(audioKbps = it)) }
                    LabeledSwitch(
                        stringResource(R.string.two_pass),
                        stringResource(R.string.two_pass_desc),
                        cfg.twoPass
                    ) { update(cfg.copy(twoPass = it)) }
                }
            }
        }

        SectionCard(title = stringResource(R.string.destination)) {
            RadioRow(
                stringResource(R.string.dest_new_folder),
                stringResource(R.string.dest_new_folder_desc),
                selected = !cfg.replaceOriginals
            ) { update(cfg.copy(replaceOriginals = false)) }
            if (!cfg.replaceOriginals) {
                OutlinedTextField(
                    value = folderName,
                    onValueChange = { folderName = it },
                    label = { Text(stringResource(R.string.folder_name_label)) },
                    placeholder = { Text(stringResource(R.string.folder_name_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                LabeledSwitch(
                    stringResource(R.string.delete_originals),
                    stringResource(R.string.delete_desc),
                    cfg.deleteOriginals
                ) { checked ->
                    if (checked && !Settings.deleteWarningShown) {
                        showDeleteWarning = true
                    } else {
                        update(cfg.copy(deleteOriginals = checked))
                    }
                }
            }
            RadioRow(
                stringResource(R.string.dest_replace),
                stringResource(R.string.dest_replace_desc),
                selected = cfg.replaceOriginals
            ) { update(cfg.copy(replaceOriginals = true)) }
            if (cfg.replaceOriginals) {
                LabeledSwitch(
                    stringResource(R.string.backup_originals),
                    stringResource(R.string.backup_desc),
                    cfg.backupOriginals
                ) { update(cfg.copy(backupOriginals = it)) }
            }
        }

        Button(
            onClick = {
                if (!Environment.isExternalStorageManager()) {
                    Toast.makeText(context, R.string.error_no_access, Toast.LENGTH_LONG).show()
                    try {
                        context.startActivity(
                            Intent(
                                android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            )
                        )
                    } catch (_: Exception) {
                        context.startActivity(
                            Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                        )
                    }
                } else {
                    onStart(cfg, folderName)
                }
            },
            modifier = Modifier.fillMaxWidth().height(56.dp)
        ) {
            Text(stringResource(R.string.start_compression), style = MaterialTheme.typography.titleMedium)
        }
        TextButton(onClick = onDiscard, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text(stringResource(R.string.clear_selection))
        }
        Spacer(Modifier.height(4.dp))
        Footer(Modifier.align(Alignment.CenterHorizontally))
    }

    if (showDeleteWarning) {
        AlertDialog(
            onDismissRequest = { showDeleteWarning = false },
            title = { Text(stringResource(R.string.delete_warning_title)) },
            text = { Text(stringResource(R.string.delete_warning_text)) },
            confirmButton = {
                TextButton(onClick = {
                    Settings.deleteWarningShown = true
                    update(cfg.copy(deleteOriginals = true))
                    showDeleteWarning = false
                }) { Text(stringResource(R.string.delete_warning_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteWarning = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}
