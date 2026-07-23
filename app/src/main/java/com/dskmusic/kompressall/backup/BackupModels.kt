package com.dskmusic.kompressall.backup

enum class BackupMode { COPY, MOVE }

data class BackupJob(
    val destinations: List<BackupDestination>,
    val folderName: String,
    val mode: BackupMode,
    val photoPaths: List<String>,
    val videoPaths: List<String>
)

data class BackupDestResult(val destinationName: String, val success: Boolean, val error: String? = null)

data class BackupState(
    val running: Boolean = false,
    val currentDestination: String = "",
    val currentFileIndex: Int = 0,
    val totalFiles: Int = 0,
    val currentFileName: String = "",
    val overallBytesDone: Long = 0,
    val overallBytesTotal: Long = 0,
    val speedBytesPerSec: Double = 0.0,
    val done: Boolean = false,
    val results: List<BackupDestResult> = emptyList()
)
