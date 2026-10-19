package com.abdulrahman.fakelocation.data

data class LatLon(val lat: Double, val lon: Double) {
    companion object {
        /** Where the map opens before any location is known: Dubai. */
        val HOME_REGION = LatLon(25.2048, 55.2708)
    }
}

/** A point the user picked: from the map, search, a link, a favorite or history. */
data class Place(val position: LatLon, val name: String? = null, val address: String? = null)

data class Favorite(val id: Long, val name: String, val position: LatLon)

data class HistoryEntry(
    val id: Long,
    val name: String,
    val position: LatLon,
    val startedAt: Long,
    val endedAt: Long? = null,
)

/** The fake location currently being sent to the phone. */
data class FakeSession(val position: LatLon, val name: String, val startedAt: Long)

enum class MapProvider { OSM, GOOGLE_ROADMAP, GOOGLE_SATELLITE, GOOGLE_HYBRID;

    val isGoogle get() = this != OSM
}
