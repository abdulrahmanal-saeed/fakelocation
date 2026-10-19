package com.abdulrahman.fakelocation

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class LatLon(val lat: Double, val lon: Double)

/** Shared state between the map screen and the background service. */
object MockState {
    private val _fakePosition = MutableStateFlow<LatLon?>(null)
    val fakePosition: StateFlow<LatLon?> = _fakePosition

    fun setFakePosition(position: LatLon?) {
        _fakePosition.value = position
    }
}

/** Small key/value store for things that must survive the app being closed. */
class Prefs(context: Context) {
    private val prefs = context.getSharedPreferences("fake_location", Context.MODE_PRIVATE)

    var lastRealLocation: LatLon?
        get() = readLatLon("real")
        set(value) = writeLatLon("real", value)

    /** The fake position the service should restore if Android restarts it. */
    var activeFakeLocation: LatLon?
        get() = readLatLon("fake")
        set(value) = writeLatLon("fake", value)

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
