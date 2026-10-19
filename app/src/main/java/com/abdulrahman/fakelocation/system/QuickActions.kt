package com.abdulrahman.fakelocation.system

import android.content.Context
import com.abdulrahman.fakelocation.data.Place
import com.abdulrahman.fakelocation.data.PlacesRepository
import com.abdulrahman.fakelocation.data.Prefs

/** Shared helpers for the Quick Settings tile and the home-screen widget. */
object QuickActions {

    /** Where "turn on" from the tile / widget goes: the first favorite, else the last place used. */
    fun defaultPlace(context: Context): Place? {
        val places = PlacesRepository.get(context)
        places.favorites.value.firstOrNull()?.let { return Place(it.position, it.name) }
        return places.history.value.firstOrNull()?.let { Place(it.position, it.name) }
    }

    /** True while a fixed fake location or a trip (running or scheduled) is active. */
    fun isActive(context: Context): Boolean {
        val prefs = Prefs(context)
        return prefs.activeSession != null || prefs.activeTrip != null
    }
}
