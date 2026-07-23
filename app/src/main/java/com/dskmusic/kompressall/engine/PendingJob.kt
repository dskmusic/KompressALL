package com.dskmusic.kompressall.engine

import android.net.Uri
import com.dskmusic.kompressall.model.JobConfig
import com.dskmusic.kompressall.model.MediaEntry
import com.dskmusic.kompressall.model.Preset
import org.json.JSONArray
import org.json.JSONObject

/** Lote a medio procesar, guardado en disco por si la app se cierra a mitad
 *  (fallo, la mata el sistema, cierre accidental...) para poder retomarlo. */
data class PendingJob(
    val items: List<MediaEntry>,
    val config: JobConfig,
    val folderName: String
)

fun PendingJob.toJson(): String {
    val itemsArr = JSONArray()
    items.forEach { item ->
        itemsArr.put(
            JSONObject()
                .put("uri", item.uri.toString())
                .put("name", item.name)
                .put("size", item.size)
                .put("isVideo", item.isVideo)
                .put("dateMillis", item.dateMillis)
                .put("realPath", item.realPath ?: JSONObject.NULL)
        )
    }
    val c = config
    val configObj = JSONObject()
        .put("imagePreset", c.imagePreset.name)
        .put("imageFormat", c.imageFormat)
        .put("imageQuality", c.imageQuality)
        .put("imageResolutionPct", c.imageResolutionPct)
        .put("videoPreset", c.videoPreset.name)
        .put("videoCodec", c.videoCodec)
        .put("videoShortSide", c.videoShortSide)
        .put("videoFps", c.videoFps)
        .put("videoSizePct", c.videoSizePct)
        .put("audioKbps", c.audioKbps)
        .put("replaceOriginals", c.replaceOriginals)
        .put("backupOriginals", c.backupOriginals)
        .put("deleteOriginals", c.deleteOriginals)
        .put("twoPass", c.twoPass)
    return JSONObject()
        .put("folderName", folderName)
        .put("items", itemsArr)
        .put("config", configObj)
        .toString()
}

fun parsePendingJob(text: String): PendingJob? {
    return try {
        val json = JSONObject(text)
        val itemsArr = json.getJSONArray("items")
        val items = (0 until itemsArr.length()).map { i ->
            val o = itemsArr.getJSONObject(i)
            MediaEntry(
                uri = Uri.parse(o.getString("uri")),
                name = o.getString("name"),
                size = o.getLong("size"),
                isVideo = o.getBoolean("isVideo"),
                dateMillis = o.getLong("dateMillis"),
                realPath = if (o.isNull("realPath")) null else o.getString("realPath")
            )
        }
        if (items.isEmpty()) return null
        val co = json.getJSONObject("config")
        val config = JobConfig(
            imagePreset = Preset.valueOf(co.getString("imagePreset")),
            imageFormat = co.getString("imageFormat"),
            imageQuality = co.getInt("imageQuality"),
            imageResolutionPct = co.getInt("imageResolutionPct"),
            videoPreset = Preset.valueOf(co.getString("videoPreset")),
            videoCodec = co.getString("videoCodec"),
            videoShortSide = co.getInt("videoShortSide"),
            videoFps = co.getInt("videoFps"),
            videoSizePct = co.getInt("videoSizePct"),
            audioKbps = co.getInt("audioKbps"),
            replaceOriginals = co.getBoolean("replaceOriginals"),
            backupOriginals = co.getBoolean("backupOriginals"),
            deleteOriginals = co.getBoolean("deleteOriginals"),
            twoPass = co.getBoolean("twoPass")
        )
        PendingJob(items, config, json.getString("folderName"))
    } catch (_: Exception) {
        null
    }
}
