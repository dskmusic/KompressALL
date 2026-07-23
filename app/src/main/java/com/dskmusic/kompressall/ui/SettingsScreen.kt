package com.dskmusic.kompressall.ui

import android.content.Intent
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import com.dskmusic.kompressall.CompressionService
import com.dskmusic.kompressall.R
import com.dskmusic.kompressall.data.Settings

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
    var twoPass by remember { mutableStateOf(Settings.loadConfig().twoPass) }

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
                twoPass = Settings.loadConfig().twoPass
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
            RadioRow(stringResource(R.string.theme_dark), selected = theme == "dark") {
                Settings.theme = "dark"
            }
            RadioRow(stringResource(R.string.theme_light), selected = theme == "light") {
                Settings.theme = "light"
            }
            RadioRow(stringResource(R.string.theme_system), selected = theme == "system") {
                Settings.theme = "system"
            }
        }

        SectionCard(title = stringResource(R.string.font)) {
            val font by Settings.fontFlow.collectAsState()
            val fontLabels = mapOf(
                "default" to stringResource(R.string.font_default),
                "sans" to stringResource(R.string.font_sans),
                "serif" to stringResource(R.string.font_serif),
                "monospace" to stringResource(R.string.font_monospace),
                "cursive" to stringResource(R.string.font_cursive)
            )
            FONT_OPTIONS.forEach { key ->
                RadioRow(
                    title = fontLabels[key] ?: key,
                    selected = font == key
                ) { Settings.font = key }
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
            LabeledSwitch(
                stringResource(R.string.two_pass),
                stringResource(R.string.two_pass_desc),
                twoPass
            ) {
                twoPass = it
                Settings.saveConfig(Settings.loadConfig().copy(twoPass = it))
            }
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
            OutlinedButton(
                onClick = { Settings.totalSaved = 0 },
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.reset_stats), textAlign = TextAlign.Center) }
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
                Footer()
            }
        }
    }
}
