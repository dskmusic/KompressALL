package com.dskmusic.kompressall.backup

import android.content.Context
import com.dskmusic.kompressall.BackupService
import com.dskmusic.kompressall.data.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/**
 * Orquestador del respaldo por SFTP. Vive fuera de la Activity (singleton),
 * igual que CompressionEngine: BackupService lo mantiene vivo con una
 * notificacion aunque la app se minimice o se cierre desde recientes.
 */
object BackupEngine {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(BackupState())
    val state = _state.asStateFlow()
    private var job: Job? = null

    fun start(context: Context, backupJob: BackupJob) {
        if (_state.value.running) return
        val appCtx = context.applicationContext
        val totalPerDest = backupJob.photoPaths.size + backupJob.videoPaths.size
        val bytesPerDest = (backupJob.photoPaths + backupJob.videoPaths).sumOf { File(it).length() }
        val overallTotalBytes = bytesPerDest * backupJob.destinations.size
        val startTime = System.currentTimeMillis()
        _state.value = BackupState(
            running = true,
            totalFiles = totalPerDest * backupJob.destinations.size,
            overallBytesTotal = overallTotalBytes
        )
        BackupService.start(appCtx)

        job = scope.launch {
            val results = mutableListOf<BackupDestResult>()
            var fileCounter = 0
            var bytesDoneBase = 0L

            fun onFileProgress(transferred: Long) {
                val overallDone = bytesDoneBase + transferred
                val elapsedSec = ((System.currentTimeMillis() - startTime) / 1000.0).coerceAtLeast(0.5)
                _state.update {
                    it.copy(overallBytesDone = overallDone, speedBytesPerSec = overallDone / elapsedSec)
                }
            }

            for (dest in backupJob.destinations) {
                _state.update { it.copy(currentDestination = dest.name) }
                val password = Settings.destinationPassword(dest.id)
                val sessionResult = SftpClient.openSession(dest.host, dest.port, dest.username, password)
                if (sessionResult.isFailure) {
                    results += BackupDestResult(dest.name, false, sessionResult.exceptionOrNull()?.message)
                    fileCounter += totalPerDest
                    bytesDoneBase += bytesPerDest
                    continue
                }
                val session = sessionResult.getOrThrow()
                try {
                    val baseDir = joinRemotePath(dest.remotePath, backupJob.folderName)
                    session.mkdirs(baseDir)
                    var ok = true
                    var lastError: String? = null

                    for (path in backupJob.photoPaths) {
                        fileCounter++
                        val file = File(path)
                        _state.update { it.copy(currentFileIndex = fileCounter, currentFileName = file.name) }
                        try {
                            session.upload(file, baseDir) { transferred, _ -> onFileProgress(transferred) }
                        } catch (e: Exception) {
                            ok = false; lastError = e.message
                        }
                        bytesDoneBase += file.length()
                    }
                    if (backupJob.videoPaths.isNotEmpty()) {
                        val videosDir = "$baseDir/Videos"
                        session.mkdirs(videosDir)
                        for (path in backupJob.videoPaths) {
                            fileCounter++
                            val file = File(path)
                            _state.update { it.copy(currentFileIndex = fileCounter, currentFileName = file.name) }
                            try {
                                session.upload(file, videosDir) { transferred, _ -> onFileProgress(transferred) }
                            } catch (e: Exception) {
                                ok = false; lastError = e.message
                            }
                            bytesDoneBase += file.length()
                        }
                    }
                    results += BackupDestResult(dest.name, ok, lastError)
                } finally {
                    session.close()
                }
            }

            // Mover: solo se borran los originales locales si TODOS los destinos salieron bien.
            if (backupJob.mode == BackupMode.MOVE && results.isNotEmpty() && results.all { it.success }) {
                (backupJob.photoPaths + backupJob.videoPaths).forEach { runCatching { File(it).delete() } }
            }

            _state.update { it.copy(running = false, done = true, results = results) }
        }
    }

    fun reset() {
        _state.value = BackupState()
    }

    private fun joinRemotePath(base: String, folder: String): String {
        val b = base.trim().trimEnd('/')
        return if (b.isEmpty()) folder else "$b/$folder"
    }
}
