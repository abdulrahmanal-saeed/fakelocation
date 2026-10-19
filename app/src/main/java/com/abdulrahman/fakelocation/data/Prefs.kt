package com.abdulrahman.fakelocation.data

import android.content.Context
import androidx.core.content.edit

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

    // Google Maps (optional). The key is typed in by the user in Settings.
    var googleApiKey: String
        get() = prefs.getString("google_key", "").orEmpty()
        set(value) = prefs.edit { putString("google_key", value.trim()) }

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
