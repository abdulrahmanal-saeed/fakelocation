package com.abdulrahman.fakelocation.data

data class LatLon(val lat: Double, val lon: Double)

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
