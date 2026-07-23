package com.dskmusic.kompressall.ui

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.PowerManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.os.LocaleListCompat
import com.dskmusic.kompressall.CompressionService
import com.dskmusic.kompressall.R
import com.dskmusic.kompressall.backup.BackupDestination
import com.dskmusic.kompressall.data.Settings
import com.dskmusic.kompressall.model.formatSize
import com.dskmusic.kompressall.notif.NOTIFICATION_SOUND_OPTIONS
import com.dskmusic.kompressall.notif.NotificationSounds
import com.dskmusic.kompressall.notif.VIBRATION_OPTIONS
import com.dskmusic.kompressall.update.UpdateChecker
import com.dskmusic.kompressall.update.UpdateInfo
import com.dskmusic.kompressall.util.MediaUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val PREVIEW_NOTIF_ID = 999

fun applyLanguage(lang: String) {
    AppCompatDelegate.setApplicationLocales(
        if (lang == "auto") LocaleListCompat.getEmptyLocaleList()
        else LocaleListCompat.forLanguageTags(lang)
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val theme by Settings.themeFlow.collectAsState()
    var language by remember { mutableStateOf(Settings.language) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            val ok = try {
                context.contentResolver.openOutputStream(uri)?.use {
                    it.write(Settings.exportJson().toByteArray(Charsets.UTF_8))
                } != null
            } catch (_: Exception) {
                false
            }
            Toast.makeText(
                context,
                if (ok) R.string.config_exported else R.string.config_import_error,
                Toast.LENGTH_SHORT
            ).show()
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val text = try {
                context.contentResolver.openInputStream(uri)?.use { String(it.readBytes()) }
            } catch (_: Exception) {
                null
            }
            if (text != null && Settings.importJson(text)) {
                language = Settings.language
                applyLanguage(language)
                Toast.makeText(context, R.string.config_imported, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, R.string.config_import_error, Toast.LENGTH_SHORT).show()
            }
        }
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
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
            }
            Text(
                stringResource(R.string.settings),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        }

        SectionCard(title = stringResource(R.string.theme)) {
            var showColorPicker by remember { mutableStateOf(false) }
            Row(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    RadioRow(stringResource(R.string.theme_system), selected = theme == "system") {
                        Settings.theme = "system"
                    }
                    RadioRow(stringResource(R.string.theme_dark), selected = theme == "dark") {
                        Settings.theme = "dark"
                    }
                    RadioRow(stringResource(R.string.theme_light), selected = theme == "light") {
                        Settings.theme = "light"
                    }
                }
                Column(Modifier.weight(1f)) {
                    RadioRow(stringResource(R.string.theme_amoled), selected = theme == "amoled") {
                        Settings.theme = "amoled"
                    }
                    RadioRow(stringResource(R.string.theme_custom), selected = theme == "custom") {
                        showColorPicker = true
                    }
                }
            }
            if (showColorPicker) {
                ColorPickerDialog(
                    initialColor = Settings.customThemeColor,
                    onDismiss = { showColorPicker = false },
                    onConfirm = { argb ->
                        Settings.customThemeColor = argb
                        Settings.theme = "custom"
                        showColorPicker = false
                    }
                )
            }
        }

        SectionCard(title = stringResource(R.string.accent_color)) {
            val accent by Settings.accentFlow.collectAsState()
            val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ACCENT_OPTIONS.forEach { name ->
                    val (pastel, deep) = accentPair(name)
                    val color = if (isDark) pastel else deep
                    val selected = accent == name
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(color)
                            .then(
                                if (selected) Modifier.border(
                                    3.dp, MaterialTheme.colorScheme.onSurface, CircleShape
                                ) else Modifier
                            )
                            .clickable { Settings.accent = name },
                        contentAlignment = Alignment.Center
                    ) {
                        if (selected) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = name,
                                tint = if (isDark) Color(0xFF10141A) else Color.White
                            )
                        }
                    }
                }
            }
        }

        SectionCard(title = stringResource(R.string.font)) {
            val font by Settings.fontFlow.collectAsState()
            val fontLabels = mapOf(
                "default" to stringResource(R.string.font_default),
                "sans" to stringResource(R.string.font_sans),
                "serif" to stringResource(R.string.font_serif),
                "monospace" to stringResource(R.string.font_monospace),
                "cursive" to stringResource(R.string.font_cursive),
                "sans_bold" to stringResource(R.string.font_sans_bold),
                "serif_light" to stringResource(R.string.font_serif_light),
                "monospace_medium" to stringResource(R.string.font_monospace_medium)
            )
            FONT_OPTIONS.forEach { key ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { Settings.font = key },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = font == key, onClick = { Settings.font = key })
                    Text(
                        fontLabels[key] ?: key,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        stringResource(R.string.font_preview_word),
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontFamily = fontFamilyFor(key),
                            fontWeight = fontWeightFor(key)
                        ),
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Clip,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        SectionCard(title = stringResource(R.string.language)) {
            RadioRow(stringResource(R.string.lang_auto), selected = language == "auto") {
                language = "auto"; Settings.language = "auto"; applyLanguage("auto")
            }
            RadioRow(stringResource(R.string.lang_es), selected = language == "es") {
                language = "es"; Settings.language = "es"; applyLanguage("es")
            }
            RadioRow(stringResource(R.string.lang_en), selected = language == "en") {
                language = "en"; Settings.language = "en"; applyLanguage("en")
            }
        }

        SectionCard(title = stringResource(R.string.videos_section)) {
            val twoPass by Settings.twoPassFlow.collectAsState()
            LabeledSwitch(
                stringResource(R.string.two_pass),
                stringResource(R.string.two_pass_desc),
                twoPass
            ) { Settings.twoPass = it }
        }

        SectionCard(title = stringResource(R.string.notifications)) {
            Text(
                stringResource(R.string.dnd_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(
                onClick = {
                    val intent = Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                        .putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, CompressionService.DONE_CHANNEL_ID)
                    try {
                        context.startActivity(intent)
                    } catch (_: Exception) {
                        context.startActivity(
                            Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.dnd_button), textAlign = TextAlign.Center) }

            val soundScope = rememberCoroutineScope()
            val currentSound by Settings.notificationSoundFlow.collectAsState()
            val currentVibration by Settings.notificationVibrationFlow.collectAsState()
            val vibrationLabels = mapOf(
                "default" to stringResource(R.string.vibration_default),
                "short" to stringResource(R.string.vibration_short),
                "long" to stringResource(R.string.vibration_long),
                "double" to stringResource(R.string.vibration_double),
                "pulse" to stringResource(R.string.vibration_pulse),
                "none" to stringResource(R.string.vibration_none)
            )
            val previewTitle = stringResource(R.string.notif_done_title)
            val previewText = stringResource(R.string.notif_done_text, 12, formatSize(1_500_000L))

            Text(stringResource(R.string.notif_sound_label), style = MaterialTheme.typography.labelLarge)
            NOTIFICATION_SOUND_OPTIONS.forEach { soundFile ->
                RadioRow(title = soundFile.removeSuffix(".mp3"), selected = currentSound == soundFile) {
                    NotificationSounds.playPreview(context, soundFile)
                    soundScope.launch(Dispatchers.IO) {
                        val uri = NotificationSounds.registerAsset(context, soundFile)
                        withContext(Dispatchers.Main) {
                            if (uri != null) {
                                Settings.notificationSound = soundFile
                                Settings.notificationSoundUri = uri.toString()
                            }
                        }
                    }
                }
            }
            OutlinedButton(
                onClick = {
                    soundScope.launch(Dispatchers.IO) {
                        val uri = NotificationSounds.registerAsset(context, Settings.DEFAULT_NOTIFICATION_SOUND)
                        withContext(Dispatchers.Main) {
                            if (uri != null) {
                                Settings.notificationSound = Settings.DEFAULT_NOTIFICATION_SOUND
                                Settings.notificationSoundUri = uri.toString()
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.notif_sound_reset), textAlign = TextAlign.Center) }

            Text(stringResource(R.string.notif_vibration_label), style = MaterialTheme.typography.labelLarge)
            VIBRATION_OPTIONS.forEach { key ->
                RadioRow(title = vibrationLabels[key] ?: key, selected = currentVibration == key) {
                    Settings.notificationVibration = key
                    NotificationSounds.vibratePreview(context, key)
                }
            }

            OutlinedButton(
                onClick = {
                    NotificationSounds.rebuildDoneChannel(context)
                    try {
                        NotificationManagerCompat.from(context).notify(
                            PREVIEW_NOTIF_ID,
                            NotificationCompat.Builder(context, CompressionService.DONE_CHANNEL_ID)
                                .setSmallIcon(R.drawable.ic_notif)
                                .setContentTitle(previewTitle)
                                .setContentText(previewText)
                                .setAutoCancel(true)
                                .build()
                        )
                    } catch (_: SecurityException) {
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.notif_preview_button), textAlign = TextAlign.Center) }
        }

        SectionCard(title = stringResource(R.string.battery_section)) {
            Text(
                stringResource(R.string.battery_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(
                onClick = {
                    try {
                        context.startActivity(
                            Intent().setComponent(
                                ComponentName(
                                    "com.miui.securitycenter",
                                    "com.miui.permcenter.autostart.AutoStartManagementActivity"
                                )
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    } catch (_: Exception) {
                        val powerManager = context.getSystemService(PowerManager::class.java)
                        try {
                            if (powerManager?.isIgnoringBatteryOptimizations(context.packageName) != true) {
                                context.startActivity(
                                    Intent(
                                        android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                        Uri.parse("package:${context.packageName}")
                                    )
                                )
                            } else {
                                context.startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                            }
                        } catch (_: Exception) {
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.battery_button), textAlign = TextAlign.Center) }
        }

        SectionCard(title = stringResource(R.string.backup_section)) {
                val destinations by Settings.destinationsFlow.collectAsState()
                var editingNew by remember { mutableStateOf(false) }
                var editingExisting by remember { mutableStateOf<BackupDestination?>(null) }
                var pendingDelete by remember { mutableStateOf<BackupDestination?>(null) }

                if (destinations.isEmpty()) {
                    Text(
                        stringResource(R.string.backup_no_destinations),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    destinations.forEach { dest ->
                        DestinationRow(
                            destination = dest,
                            onEdit = { editingExisting = dest },
                            onDelete = { pendingDelete = dest }
                        )
                    }
                }
                OutlinedButton(
                    onClick = { editingNew = true },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.dest_add_title)) }

                if (editingNew) {
                    DestinationEditDialog(
                        existing = null,
                        initialPassword = "",
                        onDismiss = { editingNew = false },
                        onSave = { dest, password ->
                            Settings.addOrUpdateDestination(dest)
                            Settings.setDestinationPassword(dest.id, password)
                            editingNew = false
                        }
                    )
                }
                editingExisting?.let { dest ->
                    DestinationEditDialog(
                        existing = dest,
                        initialPassword = Settings.destinationPassword(dest.id),
                        onDismiss = { editingExisting = null },
                        onSave = { updated, password ->
                            Settings.addOrUpdateDestination(updated)
                            Settings.setDestinationPassword(updated.id, password)
                            editingExisting = null
                        }
                    )
                }
                pendingDelete?.let { dest ->
                    AlertDialog(
                        onDismissRequest = { pendingDelete = null },
                        title = { Text(stringResource(R.string.dest_delete_title)) },
                        text = { Text(stringResource(R.string.dest_delete_text, dest.name)) },
                        confirmButton = {
                            TextButton(onClick = {
                                Settings.removeDestination(dest.id)
                                pendingDelete = null
                            }) { Text(stringResource(R.string.delete)) }
                        },
                        dismissButton = {
                            TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.cancel)) }
                        }
                    )
                }
            }

        SectionCard(title = stringResource(R.string.config_section)) {
            OutlinedButton(
                onClick = { exportLauncher.launch("kompressall_config.json") },
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.export_config), textAlign = TextAlign.Center) }
            OutlinedButton(
                onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.import_config), textAlign = TextAlign.Center) }
            var confirmReset by remember { mutableStateOf(false) }
            OutlinedButton(
                onClick = { confirmReset = true },
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.reset_stats), textAlign = TextAlign.Center) }
            if (confirmReset) {
                AlertDialog(
                    onDismissRequest = { confirmReset = false },
                    title = { Text(stringResource(R.string.reset_stats_title)) },
                    text = { Text(stringResource(R.string.reset_stats_text)) },
                    confirmButton = {
                        OutlinedButton(
                            onClick = {
                                Settings.totalSaved = 0
                                confirmReset = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(stringResource(R.string.reset_stats), textAlign = TextAlign.Center) }
                    },
                    dismissButton = {
                        TextButton(onClick = { confirmReset = false }) { Text(stringResource(R.string.cancel)) }
                    }
                )
            }

            var confirmDeleteEmpty by remember { mutableStateOf(false) }
            val emptyDirsScope = rememberCoroutineScope()
            OutlinedButton(
                onClick = { confirmDeleteEmpty = true },
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.delete_empty_folders), textAlign = TextAlign.Center) }
            if (confirmDeleteEmpty) {
                AlertDialog(
                    onDismissRequest = { confirmDeleteEmpty = false },
                    title = { Text(stringResource(R.string.delete_empty_folders_title)) },
                    text = { Text(stringResource(R.string.delete_empty_folders_text)) },
                    confirmButton = {
                        OutlinedButton(
                            onClick = {
                                confirmDeleteEmpty = false
                                emptyDirsScope.launch(Dispatchers.IO) {
                                    val root = File(Environment.getExternalStorageDirectory(), "KompressALL")
                                    val count = MediaUtils.deleteEmptyDirs(root)
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.delete_empty_folders_done, count),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(stringResource(R.string.delete_empty_folders_confirm), textAlign = TextAlign.Center) }
                    },
                    dismissButton = {
                        TextButton(onClick = { confirmDeleteEmpty = false }) { Text(stringResource(R.string.cancel)) }
                    }
                )
            }
        }

        SectionCard(title = stringResource(R.string.about)) {
            val version = remember {
                try {
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
                } catch (_: Exception) {
                    ""
                }
            }
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text("KompressALL", fontWeight = FontWeight.Bold)
                Text(
                    stringResource(R.string.version_fmt, version),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                var checkingUpdate by remember { mutableStateOf(false) }
                var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }
                val scope = rememberCoroutineScope()
                OutlinedButton(
                    enabled = !checkingUpdate,
                    onClick = {
                        checkingUpdate = true
                        scope.launch {
                            val result = UpdateChecker.check()
                            checkingUpdate = false
                            if (result != null) {
                                updateInfo = result
                            } else {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.update_none),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                ) {
                    Text(stringResource(if (checkingUpdate) R.string.update_checking else R.string.update_check))
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
                Footer()
            }
        }
    }
}

/**
 * Android no tiene un selector de color del sistema invocable por Intent (a
 * diferencia del selector de archivos); este es un selector propio con
 * deslizadores RGB y una vista previa en vivo.
 */
@Composable
private fun ColorPickerDialog(initialColor: Int, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    var r by remember { mutableStateOf((initialColor shr 16) and 0xFF) }
    var g by remember { mutableStateOf((initialColor shr 8) and 0xFF) }
    var b by remember { mutableStateOf(initialColor and 0xFF) }
    val preview = Color(0xFF000000.toInt() or (r shl 16) or (g shl 8) or b)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.theme_custom)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(preview)
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
                )
                Text("R: $r", style = MaterialTheme.typography.bodySmall)
                Slider(value = r.toFloat(), onValueChange = { r = it.toInt() }, valueRange = 0f..255f)
                Text("G: $g", style = MaterialTheme.typography.bodySmall)
                Slider(value = g.toFloat(), onValueChange = { g = it.toInt() }, valueRange = 0f..255f)
                Text("B: $b", style = MaterialTheme.typography.bodySmall)
                Slider(value = b.toFloat(), onValueChange = { b = it.toInt() }, valueRange = 0f..255f)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(0xFF000000.toInt() or (r shl 16) or (g shl 8) or b)
            }) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}
