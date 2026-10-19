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
