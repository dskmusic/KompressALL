package com.dskmusic.kompressall.ui

import android.content.Context
import android.content.Intent
import android.os.Environment
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.dskmusic.kompressall.R
import com.dskmusic.kompressall.data.Settings as AppSettings
import com.dskmusic.kompressall.model.EngineState
import com.dskmusic.kompressall.model.ItemResult
import com.dskmusic.kompressall.model.formatSize
import com.dskmusic.kompressall.update.UpdateChecker
import com.dskmusic.kompressall.update.UpdateInfo
import java.io.File

// ── Inicio ────────────────────────────────────────────────────────────────────

@Composable
fun HomeScreen(
    onPick: () -> Unit,
    onSettings: () -> Unit,
    onRequestAccess: () -> Unit
) {
    var hasAccess by remember { mutableStateOf(Environment.isExternalStorageManager()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasAccess = Environment.isExternalStorageManager()
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    val totalSaved by AppSettings.totalSavedFlow.collectAsState()
    var showHelp by remember { mutableStateOf(false) }
    val context = LocalContext.current
    var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }
    LaunchedEffect(Unit) { updateInfo = UpdateChecker.check() }

    Box(Modifier.fillMaxSize().systemBarsPadding()) {
        Row(
            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { showHelp = true }) {
                Icon(Icons.Default.Info, contentDescription = stringResource(R.string.help))
            }
            IconButton(onClick = onSettings) {
                Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings))
            }
        }
        if (showHelp) {
            AlertDialog(
                onDismissRequest = { showHelp = false },
                title = { Text(stringResource(R.string.help_title)) },
                text = { Text(stringResource(R.string.help_text)) },
                confirmButton = {
                    TextButton(onClick = { showHelp = false }) { Text(stringResource(R.string.help_got_it)) }
                }
            )
        }
        updateInfo?.let { info ->
            AlertDialog(
                onDismissRequest = { updateInfo = null },
                title = { Text(stringResource(R.string.update_available_title)) },
                text = { Text(stringResource(R.string.update_available_text, info.versionName)) },
                confirmButton = {
                    TextButton(onClick = {
                        UpdateChecker.downloadAndInstall(context, info)
                        updateInfo = null
                    }) { Text(stringResource(R.string.update_download)) }
                },
                dismissButton = {
                    TextButton(onClick = { updateInfo = null }) { Text(stringResource(R.string.update_later)) }
                }
            )
        }
        Column(
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(colorResource(R.color.ic_launcher_background)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_launcher_foreground),
                    contentDescription = null,
                    tint = Color.Unspecified,
                    modifier = Modifier.size(96.dp)
                )
            }
            Row {
                Text(
                    "Kompress",
                    style = MaterialTheme.typography.headlineLarge,
                    fontFamily = FontFamily.Default,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "ALL",
                    style = MaterialTheme.typography.headlineLarge,
                    fontFamily = FontFamily.Default,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Text(
                stringResource(R.string.home_tagline),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            if (!hasAccess) {
                SectionCard(title = stringResource(R.string.grant_access_title)) {
                    Text(
                        stringResource(R.string.grant_access_text),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(onClick = onRequestAccess, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.grant_access_button))
                    }
                }
            }
            Button(
                onClick = onPick,
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) {
                Text(stringResource(R.string.select_media), style = MaterialTheme.typography.titleMedium)
            }
            if (totalSaved > 0) {
                Text(
                    stringResource(R.string.total_saved_fmt, formatSize(totalSaved)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Footer(Modifier.align(Alignment.BottomCenter))
    }
}

// ── Progreso ──────────────────────────────────────────────────────────────────

@Composable
fun ProgressScreen(state: EngineState, onCancel: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().systemBarsPadding().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            stringResource(R.string.compressing_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(24.dp))
        Text(
            "${(state.overallProgress * 100).toInt()}%",
            style = MaterialTheme.typography.displayMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(16.dp))
        LinearProgressIndicator(
            progress = { state.overallProgress },
            modifier = Modifier.fillMaxWidth().height(8.dp)
        )
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.file_x_of_y, state.currentIndex + 1, state.items.size),
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            state.currentName,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (state.isProbePass) {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.probe_pass),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary
            )
        }
        Spacer(Modifier.height(32.dp))
        OutlinedButton(onClick = onCancel) {
            Text(stringResource(R.string.cancel))
        }
    }
}

// ── Resultado ─────────────────────────────────────────────────────────────────

@Composable
fun ResultScreen(state: EngineState, onDone: () -> Unit) {
    val context = LocalContext.current
    val shareable = remember(state.results) {
        state.results.filter { it.success && it.outputPath != null }
    }
    val totalOriginal = state.results.sumOf { it.originalSize }
    val totalFinal = state.results.filter { it.success }.sumOf { it.finalSize } +
            state.results.filter { !it.success }.sumOf { it.originalSize }
    val saved = (totalOriginal - totalFinal).coerceAtLeast(0)
    val pct = if (totalOriginal > 0) (saved * 100 / totalOriginal).toInt() else 0

    Column(Modifier.fillMaxSize().systemBarsPadding().padding(20.dp)) {
        Text(
            stringResource(if (state.cancelled) R.string.result_cancelled else R.string.result_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(16.dp))
        SectionCard {
            Text(stringResource(R.string.original_total, formatSize(totalOriginal)))
            Text(stringResource(R.string.final_total, formatSize(totalFinal)))
            Text(
                stringResource(R.string.saved_total, formatSize(saved), pct),
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.height(12.dp))
        LazyColumn(Modifier.weight(1f)) {
            items(state.results) { r ->
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Text(r.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                    when {
                        !r.success -> Text(
                            stringResource(R.string.item_failed) + (r.error?.let { ": $it" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        r.keptOriginal -> Text(
                            stringResource(R.string.item_kept_original),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        else -> Text(
                            "${formatSize(r.originalSize)} → ${formatSize(r.finalSize)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                HorizontalDivider()
            }
        }
        Spacer(Modifier.height(12.dp))
        if (shareable.isNotEmpty()) {
            OutlinedButton(
                onClick = { shareResults(context, shareable) },
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) {
                Text(stringResource(R.string.share_results))
            }
            Spacer(Modifier.height(8.dp))
        }
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text(stringResource(R.string.done_button))
        }
        Footer(Modifier.align(Alignment.CenterHorizontally))
    }
}

/** Comparte todos los archivos comprimidos con éxito vía el diálogo del sistema. */
private fun shareResults(context: Context, results: List<ItemResult>) {
    val uris = results.mapNotNull { r ->
        val path = r.outputPath ?: return@mapNotNull null
        val file = File(path)
        if (!file.exists()) return@mapNotNull null
        FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
    }
    if (uris.isEmpty()) return
    val intent = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_STREAM, uris[0])
        }
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
    }
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(intent, null))
}
