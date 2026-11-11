package com.abdulrahman.fakelocation.geo.google

import android.content.Context
import com.abdulrahman.fakelocation.data.MapProvider
import com.abdulrahman.fakelocation.data.Prefs
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourcePolicy
import org.osmdroid.util.MapTileIndex

/**
 * Google map tiles (Map Tiles API) shown in the same map view as OpenStreetMap.
 * Tiles need a session token, created once and reused until it expires (about two weeks).
 */
object GoogleTiles {

    /** Returns a tile source for [provider], creating a session if needed. Network call: not on the main thread. */
    fun tileSource(context: Context, provider: MapProvider): OnlineTileSourceBase {
        val api = GoogleApi(context)
        val session = session(context, api, provider)
        val key = api.key
        val name = "Google-" + provider.name.lowercase()
        return object : OnlineTileSourceBase(
            name, 0, 21, 256, ".png", arrayOf("https://tile.googleapis.com/v1/2dtiles/"), "© Google",
            TileSourcePolicy(2, TileSourcePolicy.FLAG_NO_BULK or TileSourcePolicy.FLAG_NO_PREVENTIVE)
        ) {
            override fun getTileURLString(index: Long): String =
                baseUrl + "${MapTileIndex.getZoom(index)}/${MapTileIndex.getX(index)}/${MapTileIndex.getY(index)}?session=$session&key=$key"
        }
    }

    /**
     * Live traffic only (green / orange / red lines on a transparent background), to lay over any map. Its own session
     * (the traffic layer as an overlay); tiles aren't kept on the phone, they're fetched fresh. Network call.
     */
    fun trafficSource(context: Context): OnlineTileSourceBase {
        val api = GoogleApi(context)
        val session = trafficSession(context, api)
        val key = api.key
        return object : OnlineTileSourceBase(
            "Google-traffic", 0, 21, 256, ".png", arrayOf("https://tile.googleapis.com/v1/2dtiles/"), "© Google",
            TileSourcePolicy(2, TileSourcePolicy.FLAG_NO_BULK or TileSourcePolicy.FLAG_NO_PREVENTIVE)
        ) {
            override fun getTileURLString(index: Long): String =
                baseUrl + "${MapTileIndex.getZoom(index)}/${MapTileIndex.getX(index)}/${MapTileIndex.getY(index)}?session=$session&key=$key"
        }
    }

    fun trafficSession(context: Context, api: GoogleApi): String {
        val prefs = Prefs(context)
        val cachedKey = "traffic:${api.key.takeLast(6)}"
        prefs.googleTrafficSession?.split('|')?.takeIf { it.size == 3 }?.let { (who, session, expiry) ->
            if (who == cachedKey && (expiry.toLongOrNull() ?: 0) > System.currentTimeMillis() + 3_600_000) return session
        }
        val body = JSONObject().put("language", "en-US").put("region", "AE").put("mapType", "roadmap")
            .put("layerTypes", JSONArray().put("layerTraffic")).put("overlay", true)
        val json = api.post("https://tile.googleapis.com/v1/createSession", body)
        val session = json.getString("session")
        val expiry = (json.optString("expiry").toLongOrNull() ?: (System.currentTimeMillis() / 1000 + 86_400)) * 1000
        prefs.googleTrafficSession = "$cachedKey|$session|$expiry"
        return session
    }

    fun session(context: Context, api: GoogleApi, provider: MapProvider): String {
        val prefs = Prefs(context)
        val cachedKey = "${provider.name}:${api.key.takeLast(6)}"
        prefs.googleTileSession?.split('|')?.takeIf { it.size == 3 }?.let { (who, session, expiry) ->
            if (who == cachedKey && (expiry.toLongOrNull() ?: 0) > System.currentTimeMillis() + 3_600_000) return session
        }
        val body = JSONObject().put("language", "en-US").put("region", "AE")
        when (provider) {
            MapProvider.GOOGLE_SATELLITE -> body.put("mapType", "satellite")
            MapProvider.GOOGLE_HYBRID -> body.put("mapType", "satellite").put("layerTypes", JSONArray().put("layerRoadmap"))
            else -> body.put("mapType", "roadmap")
        }
        val json = api.post("https://tile.googleapis.com/v1/createSession", body)
        val session = json.getString("session")
        val expiry = (json.optString("expiry").toLongOrNull() ?: (System.currentTimeMillis() / 1000 + 86_400)) * 1000
        prefs.googleTileSession = "$cachedKey|$session|$expiry"
        return session
    }
}
