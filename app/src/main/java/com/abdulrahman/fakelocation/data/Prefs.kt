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
