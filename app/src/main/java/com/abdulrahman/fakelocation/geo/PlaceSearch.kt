package com.abdulrahman.fakelocation.geo

import android.content.Context
import android.util.Log
import com.abdulrahman.fakelocation.data.LatLon
import com.abdulrahman.fakelocation.data.Place
import com.abdulrahman.fakelocation.data.Prefs
import com.abdulrahman.fakelocation.geo.google.GoogleApi
import com.abdulrahman.fakelocation.geo.google.GooglePlaces
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Search and address lookup: Google when chosen in Settings (falls back to OpenStreetMap if it fails). */
object PlaceSearch {

    suspend fun search(context: Context, query: String, near: LatLon?): List<Place> {
        if (useGoogle(context)) {
            runCatching { withContext(Dispatchers.IO) { GooglePlaces.search(GoogleApi(context), query, near) } }
                .onSuccess { return it }
                .onFailure { Log.w(TAG, "Google search failed, using OpenStreetMap", it) }
        }
        return Nominatim.search(query, near)
    }

    suspend fun reverse(context: Context, position: LatLon): Place? {
        if (useGoogle(context)) {
            runCatching { withContext(Dispatchers.IO) { GooglePlaces.reverse(GoogleApi(context), position) } }
                .onSuccess { if (it != null) return it }
                .onFailure { Log.w(TAG, "Google address lookup failed, using OpenStreetMap", it) }
        }
        return Nominatim.reverse(position)
    }

    private fun useGoogle(context: Context) = Prefs(context).let { it.useGoogleSearch && it.hasGoogleKey }

    private const val TAG = "PlaceSearch"
}
