package com.abdulrahman.fakelocation.data

import org.json.JSONArray
import org.json.JSONObject

/** How a setting is stored, so a backup puts it back with the same type (JSON alone can't tell 8 from 8f). */
enum class SettingType { BOOLEAN, INT, FLOAT, STRING }

/**
 * One JSON file with the favorites, history, routes and settings, so nothing is lost when the app is reinstalled or
 * the phone changes (Android's own backup is off for this app). Pure Kotlin + org.json so it can be unit tested.
 */
object Backup {
    private const val FORMAT = "fake-location-backup"
    const val VERSION = 1

    data class Contents(
        val favorites: List<Favorite>,
        val history: List<HistoryEntry>,
        val routes: List<Route>,
        /** Setting key → Boolean / Int / Float / String, as [Prefs.settingsForBackup] gives them. */
        val settings: Map<String, Any>,
        val createdAt: Long = System.currentTimeMillis(),
        val appVersion: String = "",
    )

    /** The file isn't a backup from this app, or is from a newer version of it. */
    class NotABackupException(message: String) : IllegalArgumentException(message)

    fun write(contents: Contents): String = JSONObject()
        .put("format", FORMAT)
        .put("version", VERSION)
        .put("createdAt", contents.createdAt)
        .put("appVersion", contents.appVersion)
        .put("favorites", JSONArray(contents.favorites.map {
            JSONObject().put("id", it.id).put("name", it.name).put("lat", it.position.lat).put("lon", it.position.lon)
        }))
        .put("history", JSONArray(contents.history.map {
            JSONObject().put("id", it.id).put("name", it.name).put("lat", it.position.lat).put("lon", it.position.lon)
                .put("startedAt", it.startedAt).apply { it.endedAt?.let { end -> put("endedAt", end) } }
        }))
        .put("routes", JSONArray(contents.routes.map(RouteJson::write)))
        .put("settings", JSONObject().apply { for ((key, value) in contents.settings) put(key, value) })
        .toString(2)

    /** Reads a backup; settings are typed by [settingTypes] and unknown ones are dropped. */
    fun read(text: String, settingTypes: Map<String, SettingType>): Contents {
        val root = runCatching { JSONObject(text) }.getOrNull()
        if (root == null || root.optString("format") != FORMAT) throw NotABackupException("Not a Fake Location backup")
        if (root.optInt("version") > VERSION) throw NotABackupException("Made by a newer version of the app")
        val createdAt = root.optLong("createdAt", System.currentTimeMillis())
        return Contents(
            favorites = root.optJSONArray("favorites").objects().map {
                Favorite(it.getLong("id"), it.getString("name"), LatLon(it.getDouble("lat"), it.getDouble("lon")))
            },
            history = root.optJSONArray("history").objects().map {
                HistoryEntry(
                    it.getLong("id"), it.getString("name"), LatLon(it.getDouble("lat"), it.getDouble("lon")),
                    it.getLong("startedAt"),
                    // A visit that was still running when the backup was made ended then.
                    if (it.has("endedAt")) it.getLong("endedAt") else createdAt,
                )
            },
            routes = root.optJSONArray("routes").objects().map(RouteJson::read),
            settings = typedSettings(root.optJSONObject("settings") ?: JSONObject(), settingTypes),
            createdAt = createdAt,
            appVersion = root.optString("appVersion"),
        )
    }

    /** Each known setting with its proper type; anything unknown or of the wrong kind is left out. */
    fun typedSettings(json: JSONObject, types: Map<String, SettingType>): Map<String, Any> {
        val out = LinkedHashMap<String, Any>()
        for (key in json.keys()) {
            val value = json.opt(key)
            val typed: Any? = when (types[key]) {
                SettingType.BOOLEAN -> value as? Boolean
                SettingType.INT -> (value as? Number)?.toInt()
                SettingType.FLOAT -> (value as? Number)?.toFloat()
                SettingType.STRING -> value as? String
                null -> null
            }
            if (typed != null) out[key] = typed
        }
        return out
    }

    /**
     * [incoming] added to [current] without losing or doubling anything: a favorite already saved at (almost) the
     * same spot is kept once, history is joined by visit, and of two copies of a route the newer one wins.
     * This phone's settings stay as they are.
     */
    fun merge(current: Contents, incoming: Contents): Contents {
        val favorites = current.favorites + incoming.favorites.filter { new ->
            current.favorites.none { it.id == new.id || (distanceMeters(it.position, new.position) < 15.0 && it.name == new.name) }
        }
        val history = (current.history + incoming.history.filter { new -> current.history.none { it.id == new.id } })
            .sortedByDescending { it.startedAt }
            .take(MAX_HISTORY)
        val routes = (current.routes + incoming.routes)
            .groupBy { it.id }
            .map { (_, copies) -> copies.maxBy { it.updatedAt } }
            .sortedByDescending { it.updatedAt }
        return current.copy(favorites = favorites, history = history, routes = routes)
    }

    /** Same limit as the history list on the phone. */
    const val MAX_HISTORY = 100
}

private fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }
