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
import org.json.JSONArray
import org.json.JSONObject

/** Preferencias de la app + exportar/importar configuración como JSON. */
object Settings {

    const val DSK_URL = "https://www.dskmusic.com/dsk_dev_redirect.php"
    const val DEFAULT_NOTIFICATION_SOUND = "sound_01"
    val DEFAULT_MIN_SIZE_BYTES = (0.8 * 1024 * 1024).toLong()
    private const val KEY_TOTAL_SAVED = "total_saved_bytes"

    private lateinit var prefs: SharedPreferences
    private lateinit var securePrefs: SharedPreferences

    val themeFlow = MutableStateFlow("dark")
    val accentFlow = MutableStateFlow("blue")
    val fontFlow = MutableStateFlow("default")
    val totalSavedFlow = MutableStateFlow(0L)
    val destinationsFlow = MutableStateFlow<List<BackupDestination>>(emptyList())
    val customThemeColorFlow = MutableStateFlow(0xFF2A2A2A.toInt())
    val twoPassFlow = MutableStateFlow(false)
    val notificationSoundFlow = MutableStateFlow(DEFAULT_NOTIFICATION_SOUND)
    val notificationVibrationFlow = MutableStateFlow("default")
    val minSizeToCompressBytesFlow = MutableStateFlow(DEFAULT_MIN_SIZE_BYTES)
    val autoSyncEnabledFlow = MutableStateFlow(false)
    val autoSyncFoldersFlow = MutableStateFlow<List<String>>(emptyList())
    val autoSyncFrequencyMinutesFlow = MutableStateFlow(60)
    val autoSyncImagePresetFlow = MutableStateFlow(Preset.MEDIUM)
    val autoSyncVideoPresetFlow = MutableStateFlow(Preset.MEDIUM)

    /** Lo fija App.kt: reconstruye el canal de notificación "terminado" con el
     *  sonido/vibración actuales (Android no permite cambiarlos en un canal ya creado). */
    var onNotificationSettingsChanged: (() -> Unit)? = null

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
        customThemeColorFlow.value = customThemeColor
        twoPassFlow.value = twoPass
        notificationSoundFlow.value = notificationSound
        notificationVibrationFlow.value = notificationVibration
        minSizeToCompressBytesFlow.value = minSizeToCompressBytes
        autoSyncEnabledFlow.value = autoSyncEnabled
        autoSyncFoldersFlow.value = autoSyncFolders
        autoSyncFrequencyMinutesFlow.value = autoSyncFrequencyMinutes
        autoSyncImagePresetFlow.value = autoSyncImagePreset
        autoSyncVideoPresetFlow.value = autoSyncVideoPreset
    }

    /** Color de fondo para el tema "custom" (ARGB). Gris neutro por defecto. */
    var customThemeColor: Int
        get() = prefs.getInt("custom_theme_color", 0xFF2A2A2A.toInt())
        set(value) {
            prefs.edit().putInt("custom_theme_color", value).apply()
            customThemeColorFlow.value = value
        }

    /** Default global de "dos pasadas". Independiente del JobConfig de cada sesión:
     *  cambiarlo aquí es lo único que cambia el default; el de Vídeo avanzado solo
     *  afecta a esa sesión concreta (ver ConfigScreen). */
    var twoPass: Boolean
        get() = prefs.getBoolean("two_pass_global", false)
        set(value) {
            prefs.edit().putBoolean("two_pass_global", value).apply()
            twoPassFlow.value = value
        }

    /** Nombre del recurso (res/raw/) elegido para el sonido de "terminado". */
    var notificationSound: String
        get() = prefs.getString("notification_sound", DEFAULT_NOTIFICATION_SOUND) ?: DEFAULT_NOTIFICATION_SOUND
        set(value) {
            prefs.edit().putString("notification_sound", value).apply()
            notificationSoundFlow.value = value
            onNotificationSettingsChanged?.invoke()
        }

    var notificationVibration: String
        get() = prefs.getString("notification_vibration", "default") ?: "default"
        set(value) {
            prefs.edit().putString("notification_vibration", value).apply()
            notificationVibrationFlow.value = value
            onNotificationSettingsChanged?.invoke()
        }

    /** Los archivos por debajo de este tamaño no se comprimen, se conservan tal cual.
     *  0 = desactivado (comprime siempre). */
    var minSizeToCompressBytes: Long
        get() = prefs.getLong("min_size_to_compress_bytes", DEFAULT_MIN_SIZE_BYTES)
        set(value) {
            prefs.edit().putLong("min_size_to_compress_bytes", value).apply()
            minSizeToCompressBytesFlow.value = value
        }

    /** Interruptor general de la sincronización automática (carpeta vigilada). */
    var autoSyncEnabled: Boolean
        get() = prefs.getBoolean("auto_sync_enabled", false)
        set(value) {
            prefs.edit().putBoolean("auto_sync_enabled", value).apply()
            autoSyncEnabledFlow.value = value
        }

    var autoSyncFolders: List<String>
        get() {
            val raw = prefs.getString("auto_sync_folders", null) ?: return emptyList()
            return try {
                val arr = JSONArray(raw)
                (0 until arr.length()).map { arr.getString(it) }
            } catch (_: Exception) {
                emptyList()
            }
        }
        set(value) {
            val arr = JSONArray()
            value.forEach { arr.put(it) }
            prefs.edit().putString("auto_sync_folders", arr.toString()).apply()
            autoSyncFoldersFlow.value = value
        }

    /** En minutos; WorkManager no acepta menos de 15. */
    var autoSyncFrequencyMinutes: Int
        get() = prefs.getInt("auto_sync_frequency_minutes", 60)
        set(value) {
            prefs.edit().putInt("auto_sync_frequency_minutes", value).apply()
            autoSyncFrequencyMinutesFlow.value = value
        }

    var autoSyncImagePreset: Preset
        get() = try {
            Preset.valueOf(prefs.getString("auto_sync_image_preset", null) ?: "MEDIUM")
        } catch (_: Exception) { Preset.MEDIUM }
        set(value) {
            prefs.edit().putString("auto_sync_image_preset", value.name).apply()
            autoSyncImagePresetFlow.value = value
        }

    var autoSyncVideoPreset: Preset
        get() = try {
            Preset.valueOf(prefs.getString("auto_sync_video_preset", null) ?: "MEDIUM")
        } catch (_: Exception) { Preset.MEDIUM }
        set(value) {
            prefs.edit().putString("auto_sync_video_preset", value.name).apply()
            autoSyncVideoPresetFlow.value = value
        }

    /** Marca de tiempo del último archivo ya considerado; solo se procesan los más nuevos. */
    var autoSyncLastCheckedMillis: Long
        get() = prefs.getLong("auto_sync_last_checked", 0L)
        set(value) = prefs.edit().putLong("auto_sync_last_checked", value).apply()

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
        // El default de dos pasadas viene de Settings.twoPass (independiente), no de
        // aqui: el switch de Video avanzado solo cambia esto para la sesion actual.
        twoPass = twoPass
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
            customThemeColorFlow.value = customThemeColor
            twoPassFlow.value = twoPass
            notificationSoundFlow.value = notificationSound
            notificationVibrationFlow.value = notificationVibration
        minSizeToCompressBytesFlow.value = minSizeToCompressBytes
        autoSyncEnabledFlow.value = autoSyncEnabled
        autoSyncFoldersFlow.value = autoSyncFolders
        autoSyncFrequencyMinutesFlow.value = autoSyncFrequencyMinutes
        autoSyncImagePresetFlow.value = autoSyncImagePreset
        autoSyncVideoPresetFlow.value = autoSyncVideoPreset
            onNotificationSettingsChanged?.invoke()
            true
        } catch (_: Exception) {
            false
        }
    }
}
