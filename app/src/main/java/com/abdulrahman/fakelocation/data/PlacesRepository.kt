package com.abdulrahman.fakelocation.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Favorites and teleport history, kept in a small JSON file. */
class PlacesRepository private constructor(private val file: File) {

    private val _favorites = MutableStateFlow<List<Favorite>>(emptyList())
    val favorites: StateFlow<List<Favorite>> = _favorites

    private val _history = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val history: StateFlow<List<HistoryEntry>> = _history

    init {
        load()
    }

    @Synchronized
    fun addFavorite(name: String, position: LatLon): Favorite {
        val favorite = Favorite(newId(), name, position)
        _favorites.value = _favorites.value + favorite
        save()
        return favorite
    }

    @Synchronized
    fun renameFavorite(id: Long, name: String) {
        _favorites.value = _favorites.value.map { if (it.id == id) it.copy(name = name) else it }
        save()
    }

    @Synchronized
    fun deleteFavorite(id: Long) {
        _favorites.value = _favorites.value.filterNot { it.id == id }
        save()
    }

    /** The favorite at (almost) the same spot, if any. */
    fun favoriteAt(position: LatLon): Favorite? =
        _favorites.value.firstOrNull { distanceMeters(it.position, position) < 15.0 }

    @Synchronized
    fun startHistory(name: String, position: LatLon, startedAt: Long): Long {
        val entry = HistoryEntry(newId(), name, position, startedAt)
        _history.value = (listOf(entry) + _history.value).take(MAX_HISTORY)
        save()
        return entry.id
    }

    @Synchronized
    fun finishHistory(id: Long, endedAt: Long) {
        _history.value = _history.value.map { if (it.id == id && it.endedAt == null) it.copy(endedAt = endedAt) else it }
        save()
    }

    @Synchronized
    fun clearHistory() {
        _history.value = _history.value.filter { it.endedAt == null }
        save()
    }

    private fun newId(): Long = System.nanoTime()

    private fun load() {
        if (!file.exists()) return
        try {
            val root = JSONObject(file.readText())
            _favorites.value = root.optJSONArray("favorites").objects().map {
                Favorite(it.getLong("id"), it.getString("name"), LatLon(it.getDouble("lat"), it.getDouble("lon")))
            }
            _history.value = root.optJSONArray("history").objects().map {
                HistoryEntry(
                    it.getLong("id"), it.getString("name"), LatLon(it.getDouble("lat"), it.getDouble("lon")),
                    it.getLong("startedAt"), if (it.has("endedAt")) it.getLong("endedAt") else null
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "could not read places", e)
        }
    }

    private fun save() {
        val root = JSONObject()
            .put("favorites", JSONArray(_favorites.value.map {
                JSONObject().put("id", it.id).put("name", it.name).put("lat", it.position.lat).put("lon", it.position.lon)
            }))
            .put("history", JSONArray(_history.value.map {
                JSONObject().put("id", it.id).put("name", it.name).put("lat", it.position.lat).put("lon", it.position.lon)
                    .put("startedAt", it.startedAt).apply { it.endedAt?.let { end -> put("endedAt", end) } }
            }))
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(root.toString())
        tmp.renameTo(file)
    }

    companion object {
        private const val TAG = "PlacesRepository"
        private const val MAX_HISTORY = 100

        @Volatile
        private var instance: PlacesRepository? = null

        fun get(context: Context): PlacesRepository =
            instance ?: synchronized(this) {
                instance ?: PlacesRepository(File(context.applicationContext.filesDir, "places.json")).also { instance = it }
            }
    }
}

private fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }
