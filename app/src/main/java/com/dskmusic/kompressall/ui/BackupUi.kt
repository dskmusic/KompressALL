package com.dskmusic.kompressall.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.dskmusic.kompressall.R
import com.dskmusic.kompressall.backup.BackupDestination
import com.dskmusic.kompressall.backup.BackupMode
import com.dskmusic.kompressall.backup.RemoteDir
import com.dskmusic.kompressall.backup.SftpClient
import kotlinx.coroutines.launch
import java.util.UUID

@Composable
fun DestinationRow(destination: BackupDestination, onEdit: () -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(destination.name, fontWeight = FontWeight.Bold)
            Text(
                "${destination.username}@${destination.host}:${destination.port}${destination.remotePath.let { if (it.isNotBlank()) " · $it" else "" }}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.edit)) }
        IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete)) }
    }
}

@Composable
fun DestinationEditDialog(
    existing: BackupDestination?,
    initialPassword: String,
    onDismiss: () -> Unit,
    onSave: (BackupDestination, String) -> Unit
) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var host by remember { mutableStateOf(existing?.host ?: "") }
    var port by remember { mutableStateOf((existing?.port ?: 22).toString()) }
    var username by remember { mutableStateOf(existing?.username ?: "") }
    var password by remember { mutableStateOf(initialPassword) }
    var remotePath by remember { mutableStateOf(existing?.remotePath ?: "") }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<Result<Unit>?>(null) }
    var showBrowser by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (existing == null) R.string.dest_add_title else R.string.dest_edit_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text(stringResource(R.string.dest_name)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = host, onValueChange = { host = it },
                    label = { Text(stringResource(R.string.dest_host)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = port, onValueChange = { port = it.filter(Char::isDigit) },
                    label = { Text(stringResource(R.string.dest_port)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = username, onValueChange = { username = it },
                    label = { Text(stringResource(R.string.dest_user)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = password, onValueChange = { password = it },
                    label = { Text(stringResource(R.string.dest_password)) },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = remotePath, onValueChange = { remotePath = it },
                        label = { Text(stringResource(R.string.dest_path)) }, singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        enabled = host.isNotBlank() && username.isNotBlank(),
                        onClick = { showBrowser = true }
                    ) { Text(stringResource(R.string.dest_browse)) }
                }
                OutlinedButton(
                    enabled = !testing && host.isNotBlank() && username.isNotBlank(),
                    onClick = {
                        testing = true; testResult = null
                        scope.launch {
                            testResult = SftpClient.test(host.trim(), port.toIntOrNull() ?: 22, username.trim(), password, remotePath.trim())
                            testing = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(if (testing) R.string.dest_testing else R.string.dest_test)) }
                testResult?.let { result ->
                    Text(
                        if (result.isSuccess) stringResource(R.string.dest_test_ok)
                        else stringResource(R.string.dest_test_fail, result.exceptionOrNull()?.message ?: "?"),
                        color = if (result.isSuccess) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && host.isNotBlank() && username.isNotBlank(),
                onClick = {
                    onSave(
                        BackupDestination(
                            id = existing?.id ?: UUID.randomUUID().toString(),
                            name = name.trim(),
                            host = host.trim(),
                            port = port.toIntOrNull() ?: 22,
                            username = username.trim(),
                            remotePath = remotePath.trim()
                        ),
                        password
                    )
                }
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )

    if (showBrowser) {
        RemoteBrowserDialog(
            host = host.trim(), port = port.toIntOrNull() ?: 22, username = username.trim(), password = password,
            startPath = remotePath.trim(),
            onDismiss = { showBrowser = false },
            onPicked = { path -> remotePath = path; showBrowser = false }
        )
    }
}

@Composable
fun RemoteBrowserDialog(
    host: String, port: Int, username: String, password: String, startPath: String,
    onDismiss: () -> Unit, onPicked: (String) -> Unit
) {
    // El "." inicial del SFTP suele resolver a la home del usuario, no a la raiz del
    // sistema; se resuelve primero a una ruta absoluta real para poder subir de nivel
    // más allá de esa carpeta (root/administrador incluido).
    var currentPath by remember { mutableStateOf<String?>(null) }
    var dirs by remember { mutableStateOf<List<RemoteDir>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        currentPath = SftpClient.canonicalize(host, port, username, password, startPath).getOrDefault("/")
    }

    LaunchedEffect(currentPath) {
        val path = currentPath ?: return@LaunchedEffect
        loading = true
        error = false
        SftpClient.listDirs(host, port, username, password, path)
            .onSuccess { dirs = it }
            .onFailure { error = true }
        loading = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(currentPath ?: "…") },
        text = {
            Box(Modifier.height(280.dp).fillMaxWidth()) {
                when {
                    loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    error -> Text(
                        stringResource(R.string.dest_browse_error),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.align(Alignment.Center)
                    )
                    else -> LazyColumn {
                        val path = currentPath
                        if (path != null && path != "/") {
                            item {
                                Text(
                                    stringResource(R.string.dest_browse_up),
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { currentPath = path.substringBeforeLast('/').ifEmpty { "/" } }
                                        .padding(vertical = 12.dp)
                                )
                            }
                        }
                        items(dirs) { dir ->
                            Text(
                                dir.name,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { currentPath = dir.path }
                                    .padding(vertical = 12.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { currentPath?.let(onPicked) }) { Text(stringResource(R.string.dest_browse_use_here)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

@Composable
fun BackupDialog(
    destinations: List<BackupDestination>,
    folderSuggestion: String,
    onDismiss: () -> Unit,
    onAddDestination: () -> Unit,
    onConfirm: (selected: List<BackupDestination>, mode: BackupMode, folderName: String, extraInfo: String) -> Unit
) {
    var selected by remember { mutableStateOf(destinations.map { it.id }.toSet()) }
    var mode by remember { mutableStateOf(BackupMode.COPY) }
    var folderName by remember { mutableStateOf(folderSuggestion) }
    var extraInfo by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_dialog_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (destinations.isEmpty()) {
                    Text(stringResource(R.string.backup_no_destinations), style = MaterialTheme.typography.bodySmall)
                } else {
                    Text(stringResource(R.string.backup_choose_destinations), style = MaterialTheme.typography.labelLarge)
                    destinations.forEach { dest ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selected = if (dest.id in selected) selected - dest.id else selected + dest.id
                                },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = dest.id in selected,
                                onCheckedChange = { checked ->
                                    selected = if (checked) selected + dest.id else selected - dest.id
                                }
                            )
                            Text(dest.name)
                        }
                    }
                }
                TextButton(onClick = onAddDestination) { Text(stringResource(R.string.dest_add_title)) }

                HorizontalDivider()

                Text(stringResource(R.string.backup_mode_title), style = MaterialTheme.typography.labelLarge)
                RadioRow(stringResource(R.string.backup_mode_copy), selected = mode == BackupMode.COPY) { mode = BackupMode.COPY }
                RadioRow(stringResource(R.string.backup_mode_move), selected = mode == BackupMode.MOVE) { mode = BackupMode.MOVE }

                OutlinedTextField(
                    value = folderName, onValueChange = { folderName = it },
                    label = { Text(stringResource(R.string.folder_name_label)) },
                    placeholder = { Text(stringResource(R.string.folder_name_hint)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = extraInfo, onValueChange = { extraInfo = it },
                    label = { Text(stringResource(R.string.backup_extra_info)) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = destinations.isNotEmpty() && selected.isNotEmpty() && folderName.isNotBlank(),
                onClick = {
                    onConfirm(destinations.filter { it.id in selected }, mode, folderName.trim(), extraInfo.trim())
                }
            ) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}
