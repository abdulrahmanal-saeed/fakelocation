package com.abdulrahman.fakelocation.data

import android.content.Context
import androidx.core.content.edit
import com.abdulrahman.fakelocation.drive.FuelType
import com.abdulrahman.fakelocation.drive.Parking

/** Small key/value store for things that must survive the app being closed. */
class Prefs(context: Context) {
    private val prefs = context.getSharedPreferences("fake_location", Context.MODE_PRIVATE)

    var lastRealLocation: LatLon?
        get() = readLatLon("real")
        set(value) = writeLatLon("real", value)

    var setupDone: Boolean
        get() = prefs.getBoolean("setup_done", false)
        set(value) = prefs.edit { putBoolean("setup_done", value) }

    /** The session the service should restore if Android restarts it. */
    var activeSession: FakeSession?
        get() {
            val position = readLatLon("fake") ?: return null
            return FakeSession(
                position,
                prefs.getString("fake_name", null).orEmpty(),
                prefs.getLong("fake_started", System.currentTimeMillis())
            )
        }
        set(value) {
            writeLatLon("fake", value?.position)
            prefs.edit {
                if (value == null) {
                    remove("fake_name")
                    remove("fake_started")
                } else {
                    putString("fake_name", value.name)
                    putLong("fake_started", value.startedAt)
                }
            }
        }

    // Alerts and movement settings
    var alertBeforeArriveMinutes: Int
        get() = prefs.getInt("alert_arrive", 2)
        set(value) = prefs.edit { putInt("alert_arrive", value) }

    var alertBeforeLeaveMinutes: Int
        get() = prefs.getInt("alert_leave", 1)
        set(value) = prefs.edit { putInt("alert_leave", value) }

    var ringInSilent: Boolean
        get() = prefs.getBoolean("ring_silent", true)
        set(value) = prefs.edit { putBoolean("ring_silent", value) }

    var vibrate: Boolean
        get() = prefs.getBoolean("vibrate", true)
        set(value) = prefs.edit { putBoolean("vibrate", value) }

    var naturalMovement: Boolean
        get() = prefs.getBoolean("natural", true)
        set(value) = prefs.edit { putBoolean("natural", value) }

    /** Extra time added to car trips for traffic, in percent. */
    var trafficPercent: Int
        get() = prefs.getInt("traffic", 0)
        set(value) = prefs.edit { putInt("traffic", value) }

    /** How much space map tiles may take on the phone, in MB. */
    var mapCacheMb: Int
        get() = prefs.getInt("map_cache_mb", 1000)
        set(value) = prefs.edit { putInt("map_cache_mb", value) }

    /** Own OSRM server for car routes, tried before the public ones ("" = public servers only). */
    var osrmServer: String
        get() = prefs.getString("osrm_server", "").orEmpty()
        set(value) = prefs.edit { putString("osrm_server", value.trim()) }

    /** "Home" and "Work" for one-tap navigation (Navigate screen, car, launcher shortcuts). */
    var home: Place?
        get() = readPlace(KEY_HOME)
        set(value) = writePlace(KEY_HOME, value)

    var work: Place?
        get() = readPlace(KEY_WORK)
        set(value) = writePlace(KEY_WORK, value)

    /** App colours: follow the phone ("system"), or always "light" / "dark". */
    var theme: String
        get() = prefs.getString("theme", THEME_SYSTEM) ?: THEME_SYSTEM
        set(value) = prefs.edit { putString("theme", value) }

    var nightMap: com.abdulrahman.fakelocation.nav.NightMapMode
        get() = com.abdulrahman.fakelocation.nav.NightMapMode.entries.firstOrNull { it.name == prefs.getString("night_map", null) }
            ?: com.abdulrahman.fakelocation.nav.NightMapMode.AUTO
        set(value) = prefs.edit { putString("night_map", value.name) }

    private fun readPlace(key: String): Place? = prefs.getString(key, null)?.let { text ->
        runCatching {
            val j = org.json.JSONObject(text)
            Place(LatLon(j.getDouble("lat"), j.getDouble("lon")), j.optString("name").ifBlank { null }, j.optString("address").ifBlank { null })
        }.getOrNull()
    }

    private fun writePlace(key: String, place: Place?) = prefs.edit {
        if (place == null) remove(key)
        else putString(key, org.json.JSONObject().put("lat", place.position.lat).put("lon", place.position.lon)
            .put("name", place.name).put("address", place.address).toString())
    }

    /** Routes that have an alarm set for their next weekly run (so alarms of routes that stop repeating are cleared). */
    var scheduledRouteIds: Set<Long>
        get() = prefs.getStringSet("scheduled_routes", emptySet()).orEmpty().mapNotNull { it.toLongOrNull() }.toSet()
        set(value) = prefs.edit { putStringSet("scheduled_routes", value.map { it.toString() }.toSet()) }

    /** Own Valhalla server, tried before the public one ("" = public server only). */
    var valhallaServer: String
        get() = prefs.getString("valhalla_server", "").orEmpty()
        set(value) = prefs.edit { putString("valhalla_server", value.trim()) }

    // Google Maps (optional). The key is typed in by the user in Settings.
    var googleApiKey: String
        get() = prefs.getString(KEY_GOOGLE, "").orEmpty()
        set(value) = prefs.edit { putString(KEY_GOOGLE, value.trim()) }

    val hasGoogleKey get() = googleApiKey.isNotBlank()

    var mapProvider: MapProvider
        get() = MapProvider.entries.firstOrNull { it.name == prefs.getString("map_provider", null) } ?: MapProvider.OSM
        set(value) = prefs.edit { putString("map_provider", value.name) }

    var useGoogleRoutes: Boolean
        get() = prefs.getBoolean("google_routes", false)
        set(value) = prefs.edit { putBoolean("google_routes", value) }

    var useGoogleSearch: Boolean
        get() = prefs.getBoolean("google_search", false)
        set(value) = prefs.edit { putBoolean("google_search", value) }

    /** Cached Google map tile session: "<provider>|<session>|<expiry millis>". */
    var googleTileSession: String?
        get() = prefs.getString("google_tile_session", null)
        set(value) = prefs.edit { if (value == null) remove("google_tile_session") else putString("google_tile_session", value) }

    /** Cached Google traffic-overlay session, same format. */
    var googleTrafficSession: String?
        get() = prefs.getString("google_traffic_session", null)
        set(value) = prefs.edit { if (value == null) remove("google_traffic_session") else putString("google_traffic_session", value) }

    /** How many times a second a moving fake location is sent (1, 2 or 5). */
    var fakeUpdatesPerSecond: Int
        get() = prefs.getInt("fake_updates_per_second", 1)
        set(value) = prefs.edit { putInt("fake_updates_per_second", value) }

    /** Live traffic drawn on the navigation map (with a Google key). */
    var trafficOnMap: Boolean
        get() = prefs.getBoolean("traffic_on_map", true)
        set(value) = prefs.edit { putBoolean("traffic_on_map", value) }

    /** True while in-app navigation needs the real GPS chip (see LocationMocker.fakeGpsChip). */
    var realGpsForNav: Boolean
        get() = prefs.getBoolean("real_gps_nav", false)
        set(value) = prefs.edit { putBoolean("real_gps_nav", value) }

    var navMuted: Boolean
        get() = prefs.getBoolean("nav_muted", false)
        set(value) = prefs.edit { putBoolean("nav_muted", value) }

    /** The running or scheduled trip, so the service can pick it up again after a restart. */
    var activeTrip: String?
        get() = prefs.getString("trip", null)
        set(value) = prefs.edit { if (value == null) remove("trip") else putString("trip", value) }

    var activeHistoryId: Long
        get() = prefs.getLong("history_id", -1)
        set(value) = prefs.edit { putLong("history_id", value) }

    // region Lanes and voice

    var laneGuidance: Boolean
        get() = prefs.getBoolean("lane_guidance", true)
        set(value) = prefs.edit { putBoolean("lane_guidance", value) }

    var laneCamera: Boolean
        get() = prefs.getBoolean("lane_camera", false)
        set(value) = prefs.edit { putBoolean("lane_camera", value) }

    /** This phone's GPS has been seen using a second frequency (L5). */
    var seenDualFrequency: Boolean
        get() = prefs.getBoolean("dual_freq", false)
        set(value) = prefs.edit { putBoolean("dual_freq", value) }

    /** Chosen text-to-speech voice name ("" = the best one found). */
    var navVoice: String
        get() = prefs.getString("nav_voice", "").orEmpty()
        set(value) = prefs.edit { putString("nav_voice", value) }

    var voiceLevel: com.abdulrahman.fakelocation.nav.VoiceOutput.Level
        get() = runCatching { com.abdulrahman.fakelocation.nav.VoiceOutput.Level.valueOf(prefs.getString("voice_level", null)!!) }
            .getOrDefault(com.abdulrahman.fakelocation.nav.VoiceOutput.Level.LOUD)
        set(value) = prefs.edit { putString("voice_level", value.name) }

    /** Pause the music while the voice talks (true) or only lower it (false). */
    var pauseMusicForVoice: Boolean
        get() = prefs.getBoolean("pause_music", true)
        set(value) = prefs.edit { putBoolean("pause_music", value) }

    /** Speak over the Bluetooth call channel, which makes most car stereos cut the radio. */
    var voiceOverCall: Boolean
        get() = prefs.getBoolean("voice_call", false)
        set(value) = prefs.edit { putBoolean("voice_call", value) }

    var navSpeechRate: Float
        get() = prefs.getFloat("nav_speech_rate", 0.95f)
        set(value) = prefs.edit { putFloat("nav_speech_rate", value) }

    // endregion

    // region Driving costs and parking (UAE)

    var avoidTolls: Boolean
        get() = prefs.getBoolean("avoid_tolls", false)
        set(value) = prefs.edit { putBoolean("avoid_tolls", value) }

    var avoidHighways: Boolean
        get() = prefs.getBoolean("avoid_highways", false)
        set(value) = prefs.edit { putBoolean("avoid_highways", value) }

    var fuelType: FuelType
        get() = runCatching { FuelType.valueOf(prefs.getString("fuel_type", null)!!) }.getOrDefault(FuelType.SPECIAL_95)
        set(value) = prefs.edit { putString("fuel_type", value.name) }

    fun fuelPrice(type: FuelType = fuelType): Double =
        prefs.getFloat("fuel_price_${type.name}", 0f).takeIf { it > 0f }?.toDouble() ?: type.defaultPrice

    fun setFuelPrice(type: FuelType, price: Double?) = prefs.edit {
        if (price == null) remove("fuel_price_${type.name}") else putFloat("fuel_price_${type.name}", price.toFloat())
    }

    /** Litres per 100 km. */
    var fuelConsumption: Double
        get() = prefs.getFloat("fuel_consumption", 8f).toDouble()
        set(value) = prefs.edit { putFloat("fuel_consumption", value.toFloat()) }

    /** Plate as typed in the parking SMS, e.g. "A12345". */
    var plateNumber: String
        get() = prefs.getString("plate", "").orEmpty()
        set(value) = prefs.edit { putString("plate", value.trim()) }

    var parkingAlertMinutes: Int
        get() = prefs.getInt("parking_alert_min", 10)
        set(value) = prefs.edit { putInt("parking_alert_min", value) }

    var parkingHours: Int
        get() = prefs.getInt("parking_hours", 1)
        set(value) = prefs.edit { putInt("parking_hours", value) }

    /** Zone codes used before, newest first. */
    var recentZones: List<String>
        get() = prefs.getString("recent_zones", "").orEmpty().split(",").filter { it.isNotBlank() }
        set(value) = prefs.edit { putString("recent_zones", value.take(6).joinToString(",")) }

    var activeParking: Parking?
        get() = prefs.getString("parking", null)?.let { Parking.fromJson(it) }
        set(value) = prefs.edit { if (value == null) remove("parking") else putString("parking", value.toJson()) }

    // endregion

    // region Backup and reset

    /** This phone's user settings for a backup (see [BACKUP_SETTINGS]); the Google key only when asked for. */
    fun settingsForBackup(includeGoogleKey: Boolean): Map<String, Any> {
        val out = LinkedHashMap<String, Any>()
        for ((key, value) in prefs.all) {
            if (value == null || key !in BACKUP_SETTINGS || (key == KEY_GOOGLE && !includeGoogleKey)) continue
            out[key] = value
        }
        return out
    }

    /**
     * Puts settings from a backup in place. With [replace], settings the backup doesn't have go back to their
     * defaults too, except the Google key, which is only replaced by a backup that has one.
     */
    fun restoreSettings(values: Map<String, Any>, replace: Boolean) = prefs.edit {
        if (replace) for (key in BACKUP_SETTINGS.keys) if (key != KEY_GOOGLE || KEY_GOOGLE in values) remove(key)
        for ((key, value) in values) {
            when (value) {
                is Boolean -> putBoolean(key, value)
                is Int -> putInt(key, value)
                is Float -> putFloat(key, value)
                is String -> putString(key, value)
            }
        }
        remove("google_tile_session")
    }

    /** Back to the defaults for these settings (one of the RESET_ lists). */
    fun reset(keys: Collection<String>) = prefs.edit { keys.forEach { remove(it) } }

    // endregion

    companion object {
        const val KEY_GOOGLE = "google_key"
        const val THEME_SYSTEM = "system"
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"
        private const val KEY_HOME = "home_place"
        private const val KEY_WORK = "work_place"

        private val FUEL_PRICES = FuelType.entries.map { "fuel_price_${it.name}" }

        /**
         * Settings that go into a backup, with their type. Running state (trip, fake position, last real position…)
         * stays out, and so does the lane camera, which needs the camera permission on the new phone first.
         */
        val BACKUP_SETTINGS: Map<String, SettingType> = buildMap {
            for (key in listOf("alert_arrive", "alert_leave", "traffic", "map_cache_mb", "parking_alert_min", "parking_hours", "fake_updates_per_second")) {
                put(key, SettingType.INT)
            }
            for (key in listOf(
                "ring_silent", "vibrate", "natural", "google_routes", "google_search", "nav_muted", "lane_guidance",
                "pause_music", "voice_call", "avoid_tolls", "avoid_highways", "traffic_on_map",
            )) put(key, SettingType.BOOLEAN)
            for (key in listOf("nav_speech_rate", "fuel_consumption") + FUEL_PRICES) put(key, SettingType.FLOAT)
            for (key in listOf(
                KEY_GOOGLE, "map_provider", "nav_voice", "voice_level", "fuel_type", "plate", "recent_zones",
                "osrm_server", "valhalla_server", KEY_HOME, KEY_WORK, "night_map", "theme",
            )) put(key, SettingType.STRING)
        }

        // What "Reset to defaults" clears on each Settings page. The plate and the Google key are kept.
        val RESET_ALERTS = listOf("alert_arrive", "alert_leave", "ring_silent", "vibrate")
        val RESET_MOVEMENT = listOf("natural", "traffic", "fake_updates_per_second")
        val RESET_LANES = listOf("lane_guidance", "lane_camera", "nav_voice", "voice_level", "pause_music", "voice_call", "nav_speech_rate")
        val RESET_COSTS = listOf("fuel_type", "fuel_consumption", "parking_alert_min") + FUEL_PRICES
        val RESET_GOOGLE = listOf("map_provider", "google_routes", "google_search", "google_tile_session")
        val RESET_MAP = listOf("map_cache_mb", "osrm_server", "valhalla_server", "night_map")
    }

    private fun readLatLon(key: String): LatLon? {
        if (!prefs.contains("${key}_lat")) return null
        return LatLon(
            java.lang.Double.longBitsToDouble(prefs.getLong("${key}_lat", 0)),
            java.lang.Double.longBitsToDouble(prefs.getLong("${key}_lon", 0))
        )
    }

    private fun writeLatLon(key: String, value: LatLon?) = prefs.edit {
        if (value == null) {
            remove("${key}_lat")
            remove("${key}_lon")
        } else {
            putLong("${key}_lat", java.lang.Double.doubleToRawLongBits(value.lat))
            putLong("${key}_lon", java.lang.Double.doubleToRawLongBits(value.lon))
        }
    }
}
