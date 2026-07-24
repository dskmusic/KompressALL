package com.dskmusic.kompressall.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dskmusic.kompressall.R
import java.io.File

/** Selector de carpetas locales (para las carpetas vigiladas de sincronización
 *  automática). Como la app ya tiene acceso a todo el almacenamiento, es un
 *  explorador propio en vez de pasar por el selector de documentos del sistema. */
@Composable
fun LocalFolderBrowserDialog(startPath: String, onDismiss: () -> Unit, onPicked: (String) -> Unit) {
    var currentPath by remember { mutableStateOf(startPath) }
    val current = File(currentPath)
    val dirs = remember(currentPath) {
        current.listFiles()
            ?.filter { it.isDirectory && !it.isHidden }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(currentPath) },
        text = {
            Box(Modifier.height(280.dp).fillMaxWidth()) {
                LazyColumn {
                    val parent = current.parentFile
                    if (parent != null) {
                        item {
                            Text(
                                "..",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { currentPath = parent.absolutePath }
                                    .padding(vertical = 12.dp)
                            )
                        }
                    }
                    items(dirs) { dir ->
                        Text(
                            dir.name,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { currentPath = dir.absolutePath }
                                .padding(vertical = 12.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onPicked(currentPath) }) { Text(stringResource(R.string.dest_browse_use_here)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}
