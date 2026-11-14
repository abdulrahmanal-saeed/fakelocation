package com.abdulrahman.fakelocation.nav

import android.content.Context
import androidx.annotation.StringRes
import com.abdulrahman.fakelocation.R
import com.abdulrahman.fakelocation.account.WayShiftAccount
import com.abdulrahman.fakelocation.data.LatLon
import com.abdulrahman.fakelocation.data.distanceMeters
import com.abdulrahman.fakelocation.geo.WayShiftApi
import org.json.JSONObject
import java.util.UUID
import kotlin.math.abs

internal object RoadReports {
    enum class Kind(val key: String, @StringRes val label: Int) {
        TRAFFIC("traffic", R.string.report_traffic), CRASH("crash", R.string.report_crash),
        HAZARD("hazard", R.string.report_hazard), CLOSURE("closure", R.string.report_closure),
    }

    data class Fix(val position: LatLon, val heading: Float, val road: String) {
        fun json() = JSONObject().put("lat", position.lat).put("lon", position.lon)
            .put("heading", heading.toDouble()).put("road", road)
    }
    data class Incident(val id: String, val kind: Kind, val position: LatLon, val heading: Double, val confirmed: Boolean)
    enum class Result { SENT, DRAFT, SIGN_IN }

    val configured get() = WayShiftApi.configured

    fun fix(state: NavSession.State): Fix? {
        val location = state.real ?: return null
        val progress = state.progress ?: return null
        val route = state.route ?: return null
        val road = route.steps.lastOrNull { it.pointIndex <= progress.segment }?.road?.trim()
            ?.takeIf { it.isNotBlank() } ?: return null
        if (!state.navigating || state.gpsLost || state.fixId == 0L || progress.offRouteMeters > 18) return null
        return Fix(location, (state.bearing + 360f) % 360f, road.lowercase().take(100))
    }

    suspend fun submit(context: Context, kind: Kind, fix: Fix?, id: String = UUID.randomUUID().toString()): Result {
        if (WayShiftAccount.auth(context)?.currentUser?.isEmailVerified != true) return Result.SIGN_IN
        val token = WayShiftAccount.token(context) ?: return Result.SIGN_IN
        if (fix == null) {
            context.getSharedPreferences("road_reports", Context.MODE_PRIVATE).edit().putString("draft", kind.key).apply()
            return Result.DRAFT
        }
        WayShiftApi.request("/v1/incidents", fix.json().put("id", id).put("kind", kind.key), token)
        return Result.SENT
    }

    fun draft(context: Context): Kind? {
        val key = context.getSharedPreferences("road_reports", Context.MODE_PRIVATE).getString("draft", null)
        return Kind.entries.firstOrNull { it.key == key }
    }

    fun clearDraft(context: Context) {
        context.getSharedPreferences("road_reports", Context.MODE_PRIVATE).edit().remove("draft").apply()
    }

    suspend fun nearby(context: Context, fix: Fix): List<Incident> {
        return fetch(context, fix, distance = 45.0)
    }

    suspend fun ahead(context: Context, fix: Fix, route: NavRoute, along: Double): Incident? =
        fetch(context, fix, distance = 900.0, confirmedOnly = true).mapNotNull { incident ->
            val position = RoadInfoFetcher.alongOf(route, incident.position, 18.0) ?: return@mapNotNull null
            if (position - along !in -15.0..900.0) return@mapNotNull null
            incident to position
        }.minByOrNull { it.second }?.first

    private suspend fun fetch(context: Context, fix: Fix, distance: Double, confirmedOnly: Boolean = false): List<Incident> {
        val p = fix.position
        val items = WayShiftApi.request("/v1/incidents?lat=${p.lat}&lon=${p.lon}", token = WayShiftAccount.token(context))
            .optJSONArray("incidents") ?: return emptyList()
        return (0 until items.length()).mapNotNull { index ->
            val item = items.getJSONObject(index)
            val kind = Kind.entries.firstOrNull { it.key == item.optString("kind") } ?: return@mapNotNull null
            val position = LatLon(item.getDouble("lat"), item.getDouble("lon"))
            val heading = item.getDouble("heading")
            val turn = abs((heading - fix.heading + 540) % 360 - 180)
            val confirmed = item.getBoolean("confirmed")
            if (distanceMeters(position, p) > distance || turn > 30 || item.optString("road") != fix.road ||
                confirmedOnly && !confirmed) return@mapNotNull null
            Incident(item.getString("id"), kind, position, heading, confirmed)
        }
    }

    suspend fun vote(context: Context, fix: Fix, id: String, vote: String) {
        val token = WayShiftAccount.token(context) ?: error("Sign-in required")
        val path = if (vote == "abuse") "/v1/incidents/abuse" else "/v1/incidents/vote"
        WayShiftApi.request(path, fix.json().put("id", id).put("vote", vote), token)
    }
}
