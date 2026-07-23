package com.dskmusic.kompressall.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
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

    fun downloadAndInstall(context: Context, info: UpdateInfo) {
        val request = DownloadManager.Request(Uri.parse(info.apkUrl))
            .setTitle("KompressALL ${info.versionName}")
            .setDestinationInExternalFilesDir(context, null, APK_FILE_NAME)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadId = downloadManager.enqueue(request)

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) != downloadId) return
                context.unregisterReceiver(this)
                val file = File(context.getExternalFilesDir(null), APK_FILE_NAME)
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
                context.startActivity(
                    Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "application/vnd.android.package-archive")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                )
            }
        }
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
    }
}
