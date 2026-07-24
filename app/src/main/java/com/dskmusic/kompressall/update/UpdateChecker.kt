package com.dskmusic.kompressall.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.dskmusic.kompressall.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

private const val REPO = "dskmusic/KompressALL"
private const val APK_FILE_NAME = "kompressall_update.apk"

data class UpdateInfo(val versionName: String, val apkUrl: String)

/** Comprueba la última Release en GitHub y descarga/instala el APK si hay una versión mas nueva. */
object UpdateChecker {

    // El tag de la Release es "v1.0.<versionCode>" (ver .github/workflows/build-release.yml),
    // asi que el numero tras el ultimo "." es directamente comparable con BuildConfig.VERSION_CODE.
    suspend fun check(): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val connection = URL("https://api.github.com/repos/$REPO/releases/latest")
                .openConnection() as HttpURLConnection
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            val json = JSONObject(connection.inputStream.bufferedReader().readText())
            val tag = json.getString("tag_name")
            val remoteVersionCode = tag.substringAfterLast('.').toIntOrNull() ?: return@withContext null
            if (remoteVersionCode <= BuildConfig.VERSION_CODE) return@withContext null
            val versionName = tag.removePrefix("v")
            val apkUrl = json.getJSONArray("assets").getJSONObject(0).getString("browser_download_url")
            UpdateInfo(versionName, apkUrl)
        } catch (_: Exception) {
            null
        }
    }

    /** Descarga el APK dentro de la propia app reportando progreso (0f..1f) y lanza el instalador. */
    suspend fun downloadAndInstall(context: Context, info: UpdateInfo, onProgress: (Float) -> Unit) {
        val file = withContext(Dispatchers.IO) {
            val connection = URL(info.apkUrl).openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = true
            val total = connection.contentLength.toLong()
            val target = File(context.getExternalFilesDir(null), APK_FILE_NAME)
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var downloaded = 0L
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (total > 0) onProgress(downloaded.toFloat() / total)
                    }
                }
            }
            target
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        )
    }
}
