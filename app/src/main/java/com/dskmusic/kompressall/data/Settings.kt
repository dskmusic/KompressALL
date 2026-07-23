package com.dskmusic.kompressall.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.dskmusic.kompressall.backup.BackupDestination
import com.dskmusic.kompressall.backup.parseDestinations
import com.dskmusic.kompressall.backup.toJson
import com.dskmusic.kompressall.model.JobConfig
import com.dskmusic.kompressall.model.Preset
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject

/** Preferencias de la app + exportar/importar configuración como JSON. */
object Settings {

    const val DSK_URL = "https://www.dskmusic.com/dsk_dev_redirect.php"
    private const val KEY_TOTAL_SAVED = "total_saved_bytes"

    private lateinit var prefs: SharedPreferences
    private lateinit var securePrefs: SharedPreferences

    val themeFlow = MutableStateFlow("dark")
    val accentFlow = MutableStateFlow("blue")
    val fontFlow = MutableStateFlow("default")
    val totalSavedFlow = MutableStateFlow(0L)
    val destinationsFlow = MutableStateFlow<List<BackupDestination>>(emptyList())

    fun init(context: Context) {
        prefs = context.getSharedPreferences("kompressall_prefs", Context.MODE_PRIVATE)
        val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        securePrefs = EncryptedSharedPreferences.create(
            context,
            "kompressall_secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        themeFlow.value = theme
        accentFlow.value = accent
        fontFlow.value = font
        totalSavedFlow.value = totalSaved
        destinationsFlow.value = destinations
    }

    /** Destinos de respaldo por SFTP (sin contraseña, ver destinationPassword). */
    var destinations: List<BackupDestination>
        get() = parseDestinations(prefs.getString("backup_destinations", null))
        set(value) {
            prefs.edit().putString("backup_destinations", value.toJson()).apply()
            destinationsFlow.value = value
        }

    fun addOrUpdateDestination(destination: BackupDestination) {
        val current = destinations.toMutableList()
        val idx = current.indexOfFirst { it.id == destination.id }
        if (idx >= 0) current[idx] = destination else current += destination
        destinations = current
    }

    fun removeDestination(id: String) {
        destinations = destinations.filterNot { it.id == id }
        securePrefs.edit().remove("dest_pw_$id").apply()
    }

    /** Contraseña de un destino, guardada cifrada aparte (nunca en el JSON exportado). */
    fun destinationPassword(id: String): String = securePrefs.getString("dest_pw_$id", "") ?: ""

    fun setDestinationPassword(id: String, password: String) {
        securePrefs.edit().putString("dest_pw_$id", password).apply()
    }

    var theme: String
        get() = prefs.getString("theme", "dark") ?: "dark"
        set(value) {
            prefs.edit().putString("theme", value).apply()
            themeFlow.value = value
        }

    /** Color de acento, ver ACCENT_OPTIONS en Theme.kt. Azul por defecto. */
    var accent: String
        get() = prefs.getString("accent", "blue") ?: "blue"
        set(value) {
            prefs.edit().putString("accent", value).apply()
            accentFlow.value = value
        }

    /** Tipografía, ver FONT_OPTIONS en Theme.kt. Predeterminada por defecto. */
    var font: String
        get() = prefs.getString("font", "default") ?: "default"
        set(value) {
            prefs.edit().putString("font", value).apply()
            fontFlow.value = value
        }

    /** Si ya se mostró el aviso de "esta acción no se puede deshacer" al
     *  activar por primera vez "Borrar originales". */
    var deleteWarningShown: Boolean
        get() = prefs.getBoolean("delete_warning_shown", false)
        set(value) = prefs.edit().putBoolean("delete_warning_shown", value).apply()

    /** "auto" | "es" | "en" — el idioma real lo aplica AppCompatDelegate. */
    var language: String
        get() = prefs.getString("language", "auto") ?: "auto"
        set(value) = prefs.edit().putString("language", value).apply()

    var totalSaved: Long
        get() = try {
            prefs.getLong(KEY_TOTAL_SAVED, 0L)
        } catch (_: ClassCastException) {
            // Autorreparación: una importación antigua pudo guardar este valor
            // como Int (ver importJson), lo que rompe getLong en cada arranque.
            val recovered = prefs.getInt(KEY_TOTAL_SAVED, 0).toLong()
            prefs.edit().putLong(KEY_TOTAL_SAVED, recovered).apply()
            recovered
        }
        set(value) {
            prefs.edit().putLong(KEY_TOTAL_SAVED, value).apply()
            totalSavedFlow.value = value
        }

    /** Carga la config recordada. Los presets se proponen siempre en MEDIUM. */
    fun loadConfig(): JobConfig = JobConfig(
        imagePreset = Preset.MEDIUM,
        imageFormat = prefs.getString("image_format", "jpeg") ?: "jpeg",
        imageQuality = prefs.getInt("image_quality", 75),
        imageResolutionPct = prefs.getInt("image_resolution_pct", 80),
        videoPreset = Preset.MEDIUM,
        videoCodec = prefs.getString("video_codec", "auto") ?: "auto",
        videoShortSide = prefs.getInt("video_short_side", 0),
        videoFps = prefs.getInt("video_fps", 0),
        videoSizePct = prefs.getInt("video_size_pct", 18),
        audioKbps = prefs.getInt("audio_kbps", 192),
        replaceOriginals = prefs.getBoolean("replace_originals", false),
        backupOriginals = prefs.getBoolean("backup_originals", true),
        deleteOriginals = prefs.getBoolean("delete_originals", false),
        twoPass = prefs.getBoolean("two_pass", false)
    )

    fun saveConfig(c: JobConfig) {
        prefs.edit()
            .putString("image_format", c.imageFormat)
            .putInt("image_quality", c.imageQuality)
            .putInt("image_resolution_pct", c.imageResolutionPct)
            .putString("video_codec", c.videoCodec)
            .putInt("video_short_side", c.videoShortSide)
            .putInt("video_fps", c.videoFps)
            .putInt("video_size_pct", c.videoSizePct)
            .putInt("audio_kbps", c.audioKbps)
            .putBoolean("replace_originals", c.replaceOriginals)
            .putBoolean("backup_originals", c.backupOriginals)
            .putBoolean("delete_originals", c.deleteOriginals)
            .putBoolean("two_pass", c.twoPass)
            .apply()
    }

    fun exportJson(): String {
        val json = JSONObject()
        prefs.all.forEach { (key, value) -> json.put(key, value) }
        // Contraseñas de los destinos de respaldo, para que un restaurado no
        // obligue a volver a escribirlas a mano.
        val passwords = JSONObject()
        securePrefs.all.forEach { (key, value) ->
            if (key.startsWith("dest_pw_") && value is String) passwords.put(key, value)
        }
        json.put("_dest_passwords", passwords)
        json.put("_app", "KompressALL")
        return json.toString(2)
    }

    /** Importa un JSON exportado. Devuelve false si no es válido. */
    fun importJson(text: String): Boolean {
        return try {
            // Quita el BOM (U+FEFF) que algunos gestores de archivos añaden al guardar.
            val cleaned = text.trim().let { if (it.isNotEmpty() && it[0].code == 0xFEFF) it.substring(1) else it }
            val json = JSONObject(cleaned)
            if (json.optString("_app") != "KompressALL") return false
            val editor = prefs.edit()
            json.keys().forEach { key ->
                if (key == "_app" || key == "_dest_passwords") return@forEach
                val value = json.get(key)
                when {
                    // org.json puede devolver un Integer para números pequeños aunque
                    // se guardaran como Long; forzamos el tipo correcto para esta clave.
                    key == KEY_TOTAL_SAVED && value is Number -> editor.putLong(key, value.toLong())
                    value is Boolean -> editor.putBoolean(key, value)
                    value is Int -> editor.putInt(key, value)
                    value is Long -> editor.putLong(key, value)
                    value is Number -> editor.putLong(key, value.toLong())
                    value is String -> editor.putString(key, value)
                }
            }
            editor.apply()
            json.optJSONObject("_dest_passwords")?.let { passwords ->
                val secureEditor = securePrefs.edit()
                passwords.keys().forEach { key -> secureEditor.putString(key, passwords.getString(key)) }
                secureEditor.apply()
            }
            themeFlow.value = theme
            accentFlow.value = accent
            fontFlow.value = font
            totalSavedFlow.value = totalSaved
            destinationsFlow.value = destinations
            true
        } catch (_: Exception) {
            false
        }
    }
}
