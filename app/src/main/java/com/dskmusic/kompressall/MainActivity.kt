package com.dskmusic.kompressall

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import com.dskmusic.kompressall.data.Settings
import com.dskmusic.kompressall.engine.CompressionEngine
import com.dskmusic.kompressall.model.Phase
import com.dskmusic.kompressall.ui.ConfigScreen
import com.dskmusic.kompressall.ui.HomeScreen
import com.dskmusic.kompressall.ui.KompressAllTheme
import com.dskmusic.kompressall.ui.ProgressScreen
import com.dskmusic.kompressall.ui.ResultScreen
import com.dskmusic.kompressall.ui.SettingsScreen

class MainActivity : AppCompatActivity() {

    private val pickMedia = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(100)
    ) { uris ->
        if (uris.isNotEmpty()) CompressionEngine.load(this, uris)
    }

    private val addMoreMedia = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(100)
    ) { uris ->
        if (uris.isNotEmpty()) CompressionEngine.load(this, uris, append = true)
    }

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        maybeAutoOpenPicker(intent)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            val theme by Settings.themeFlow.collectAsState()
            val accent by Settings.accentFlow.collectAsState()
            val font by Settings.fontFlow.collectAsState()
            val customColor by Settings.customThemeColorFlow.collectAsState()
            KompressAllTheme(theme, accent, font, customColor) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Root(
                        onPick = {
                            pickMedia.launch(
                                PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageAndVideo
                                )
                            )
                        },
                        onAddMore = {
                            addMoreMedia.launch(
                                PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageAndVideo
                                )
                            )
                        },
                        onRequestAccess = { requestAllFilesAccess() }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
        maybeAutoOpenPicker(intent)
    }

    /** Atajo de icono (mantener pulsado): abre el selector directamente. */
    private fun maybeAutoOpenPicker(intent: Intent?) {
        if (intent?.getBooleanExtra("open_picker", false) == true) {
            pickMedia.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
            )
        }
    }

    /** Archivos recibidos por Compartir / Abrir con. */
    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            )
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                    ?: emptyList()
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            else -> emptyList()
        }
        if (uris.isNotEmpty()) CompressionEngine.offerExternal(this, uris)
    }

    private fun requestAllFilesAccess() {
        try {
            startActivity(
                Intent(
                    android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (_: Exception) {
            startActivity(Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    }
}

@Composable
private fun Root(onPick: () -> Unit, onAddMore: () -> Unit, onRequestAccess: () -> Unit) {
    val context = LocalContext.current
    val state by CompressionEngine.state.collectAsState()
    val pendingExternal by CompressionEngine.pendingExternal.collectAsState()
    val isLoading by CompressionEngine.isLoading.collectAsState()
    var showSettings by rememberSaveable { mutableStateOf(false) }

    BackHandler(enabled = showSettings || state.phase == Phase.CONFIG) {
        if (showSettings) showSettings = false else CompressionEngine.discard()
    }

    if (pendingExternal != null) {
        AlertDialog(
            onDismissRequest = { CompressionEngine.dismissPendingExternal() },
            title = { Text(stringResource(R.string.incoming_batch_title)) },
            text = { Text(stringResource(R.string.incoming_batch_text)) },
            confirmButton = {
                TextButton(onClick = { CompressionEngine.resolvePendingExternal(context, append = true) }) {
                    Text(stringResource(R.string.incoming_add_to_batch))
                }
            },
            dismissButton = {
                TextButton(onClick = { CompressionEngine.resolvePendingExternal(context, append = false) }) {
                    Text(stringResource(R.string.incoming_new_batch))
                }
            }
        )
    }

    when {
        showSettings -> SettingsScreen(onBack = { showSettings = false })
        state.phase == Phase.CONFIG -> ConfigScreen(
            state = state,
            onStart = { cfg, folderName -> CompressionEngine.start(context, cfg, folderName) },
            onDiscard = { CompressionEngine.discard() },
            onAddMore = onAddMore
        )
        state.phase == Phase.RUNNING -> ProgressScreen(state, onCancel = { CompressionEngine.cancel() })
        state.phase == Phase.DONE -> ResultScreen(state, onDone = { CompressionEngine.discard() })
        else -> HomeScreen(
            onPick = onPick,
            onSettings = { showSettings = true },
            onRequestAccess = onRequestAccess
        )
    }

    if (isLoading) {
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(color = Color.White)
                Text(stringResource(R.string.loading_files), color = Color.White)
            }
        }
    }
}
