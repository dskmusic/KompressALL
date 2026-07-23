package com.dskmusic.kompressall.backup

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Un destino de respaldo por SFTP. La contraseña NO vive aquí (se guarda
 * aparte, cifrada, en Settings.destinationPassword) para que nunca acabe en
 * el JSON de exportar/importar configuración.
 */
data class BackupDestination(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val host: String,
    val port: Int = 22,
    val username: String,
    val remotePath: String
)

fun List<BackupDestination>.toJson(): String {
    val arr = JSONArray()
    forEach { d ->
        arr.put(
            JSONObject()
                .put("id", d.id)
                .put("name", d.name)
                .put("host", d.host)
                .put("port", d.port)
                .put("username", d.username)
                .put("remotePath", d.remotePath)
        )
    }
    return arr.toString()
}

fun parseDestinations(json: String?): List<BackupDestination> {
    if (json.isNullOrBlank()) return emptyList()
    return try {
        val arr = JSONArray(json)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            BackupDestination(
                id = o.getString("id"),
                name = o.getString("name"),
                host = o.getString("host"),
                port = o.optInt("port", 22),
                username = o.getString("username"),
                remotePath = o.getString("remotePath")
            )
        }
    } catch (_: Exception) {
        emptyList()
    }
}
