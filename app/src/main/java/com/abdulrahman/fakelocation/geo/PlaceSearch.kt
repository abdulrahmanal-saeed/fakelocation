package com.abdulrahman.fakelocation.geo

import android.content.Context
import android.util.Log
import com.abdulrahman.fakelocation.BuildConfig
import kotlinx.coroutines.CancellationException
import com.abdulrahman.fakelocation.data.LatLon
import com.abdulrahman.fakelocation.data.Place
import com.abdulrahman.fakelocation.data.Prefs
import com.abdulrahman.fakelocation.geo.google.GoogleApi
import com.abdulrahman.fakelocation.geo.google.GooglePlaces
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Search and address lookup: Google when chosen in Settings (falls back to OpenStreetMap if it fails). */
object PlaceSearch {
    private val photonEndpoint get() = BuildConfig.PHOTON_URL
    fun supportsLiveSearch(context: Context): Boolean = useGoogle(context) || photonEndpoint.isNotBlank()


    suspend fun search(context: Context, query: String, near: LatLon?, submitted: Boolean = true): List<Place> {
        if (useGoogle(context)) {
            runCatching { withContext(Dispatchers.IO) { GooglePlaces.search(GoogleApi(context), query, near) } }
                .onSuccess { return it }
                .onFailure { if (it is CancellationException) throw it; Log.w(TAG, "Google search unavailable", it) }
        }
        val locale = MapLocale.from(context)
        if (photonEndpoint.isNotBlank()) return withContext(Dispatchers.IO) {
            Photon.search(photonEndpoint, query, near, locale)
        }
        return if (submitted) Nominatim.search(query, near, locale) else emptyList()
    }

    suspend fun reverse(context: Context, position: LatLon): Place? {
        if (useGoogle(context)) {
            runCatching { withContext(Dispatchers.IO) { GooglePlaces.reverse(GoogleApi(context), position) } }
                .onSuccess { if (it != null) return it }
                .onFailure { if (it is CancellationException) throw it; Log.w(TAG, "Google address lookup unavailable", it) }
        }
        val locale = MapLocale.from(context)
        return if (photonEndpoint.isNotBlank()) withContext(Dispatchers.IO) {
            Photon.reverse(photonEndpoint, position, locale)
        } else Nominatim.reverse(position, locale)
    }

    private fun useGoogle(context: Context) = Prefs(context).let { it.useGoogleSearch && it.hasGoogleKey && it.mapProvider.isGoogle }

    private const val TAG = "PlaceSearch"
}
