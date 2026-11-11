package com.abdulrahman.fakelocation.data

import com.abdulrahman.fakelocation.geo.Router
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupTest {

    private val path = Router.parse(javaClass.classLoader!!.getResource("osrm_car.json")!!.readText())
    private val route = Route(
        7, "School run", TravelMode.CAR,
        listOf(Stop(1, "Home", LatLon(25.07, 55.14), 5), Stop(2, "School", LatLon(25.11, 55.20), 0)),
        path, Timing(TimingMode.ARRIVE_BY, 1_700_000_000_000L), updatedAt = 2_000L,
    )
    private val contents = Backup.Contents(
        favorites = listOf(Favorite(1, "Home", LatLon(25.0700, 55.1400)), Favorite(2, "Office", LatLon(25.2048, 55.2708))),
        history = listOf(
            HistoryEntry(10, "Mall", LatLon(25.1985, 55.2796), startedAt = 5_000L), // still running at backup time
            HistoryEntry(11, "Beach", LatLon(25.14, 55.18), startedAt = 1_000L, endedAt = 2_000L),
        ),
        routes = listOf(route),
        settings = mapOf(
            "alert_arrive" to 3, "ring_silent" to false, "fuel_consumption" to 8.0f, "nav_speech_rate" to 0.95f,
            "fuel_type" to "SUPER_98", "osrm_server" to "https://osrm.example.com",
        ),
        createdAt = 9_000L,
        appVersion = "1.9.0",
    )

    @Test
    fun roundTrip() {
        val back = Backup.read(Backup.write(contents), Prefs.BACKUP_SETTINGS)
        assertEquals(contents.favorites, back.favorites)
        assertEquals(contents.routes.map { it.copy(path = null) }, back.routes.map { it.copy(path = null) })
        assertEquals(path.points.size, back.routes[0].path!!.points.size)
        assertEquals(path.legs, back.routes[0].path!!.legs)
        assertEquals(9_000L, back.createdAt)
        assertEquals("1.9.0", back.appVersion)
        // A visit that was running when the backup was made has ended by then.
        assertEquals(9_000L, back.history[0].endedAt)
        assertEquals(contents.history[1], back.history[1])
    }

    @Test
    fun settingsKeepTheirTypes() {
        val back = Backup.read(Backup.write(contents), Prefs.BACKUP_SETTINGS).settings
        assertEquals(contents.settings, back)
        // 8.0f is written as a plain 8 in JSON, but must come back as a Float, not an Int.
        assertTrue(back["fuel_consumption"] is Float)
        assertTrue(back["alert_arrive"] is Int)
    }

    @Test
    fun unknownOrWrongSettingsAreDropped() {
        val json = JSONObject()
            .put("alert_arrive", "three") // wrong type
            .put("trip", "{}") // running state, never restored
            .put("lane_camera", true) // needs the camera permission first
            .put("traffic", 15.0) // number of another kind is fine
            .put("vibrate", true)
        assertEquals(mapOf("traffic" to 15, "vibrate" to true), Backup.typedSettings(json, Prefs.BACKUP_SETTINGS))
    }

    @Test(expected = Backup.NotABackupException::class)
    fun otherJsonIsRejected() {
        Backup.read("""{"favorites":[]}""", Prefs.BACKUP_SETTINGS)
    }

    @Test(expected = Backup.NotABackupException::class)
    fun textIsRejected() {
        Backup.read("hello", Prefs.BACKUP_SETTINGS)
    }

    @Test(expected = Backup.NotABackupException::class)
    fun newerVersionIsRejected() {
        val newer = JSONObject(Backup.write(contents)).put("version", Backup.VERSION + 1).toString()
        Backup.read(newer, Prefs.BACKUP_SETTINGS)
    }

    @Test
    fun mergeAddsWithoutDoubling() {
        val phone = Backup.Contents(
            favorites = listOf(Favorite(1, "Home", LatLon(25.0700, 55.1400)), Favorite(5, "Gym", LatLon(25.10, 55.17))),
            history = listOf(HistoryEntry(11, "Beach", LatLon(25.14, 55.18), 1_000L, 2_000L), HistoryEntry(20, "Park", LatLon(25.0, 55.0), 8_000L, 8_500L)),
            routes = listOf(route.copy(name = "School run (old)", updatedAt = 1_000L), route.copy(id = 8, name = "Gym", updatedAt = 3_000L)),
            settings = mapOf("alert_arrive" to 1),
        )
        val incoming = contents.copy(
            favorites = contents.favorites + Favorite(3, "Home", LatLon(25.07001, 55.14001)) + Favorite(4, "Home 2", LatLon(25.07001, 55.14001)),
        )
        val merged = Backup.merge(phone, incoming)
        // Same id, or same name a few metres away = the same favorite; a different name there is a new one.
        assertEquals(listOf("Home", "Gym", "Office", "Home 2"), merged.favorites.map { it.name })
        // History joined by visit, newest first.
        assertEquals(listOf(20L, 10L, 11L), merged.history.map { it.id })
        // The newer copy of route 7 (from the backup) wins; route 8 stays.
        assertEquals(listOf("Gym", "School run"), merged.routes.map { it.name })
        // This phone's settings stay.
        assertEquals(mapOf("alert_arrive" to 1), merged.settings)
    }

    @Test
    fun backupAndResetListsAgree() {
        val keys = Prefs.BACKUP_SETTINGS.keys
        for (state in listOf("trip", "fake_lat", "real_lat", "setup_done", "history_id", "parking", "real_gps_nav", "google_tile_session", "lane_camera")) {
            assertFalse(state, state in keys)
        }
        val resettable = Prefs.RESET_ALERTS + Prefs.RESET_MOVEMENT + Prefs.RESET_LANES + Prefs.RESET_COSTS + Prefs.RESET_GOOGLE + Prefs.RESET_MAP
        for (key in resettable - setOf("google_tile_session", "lane_camera")) assertTrue(key, key in keys)
        // Reset keeps the plate and the Google key.
        assertFalse("plate" in resettable)
        assertFalse(Prefs.KEY_GOOGLE in resettable)
    }
}
