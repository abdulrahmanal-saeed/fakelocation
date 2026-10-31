package com.abdulrahman.fakelocation.ui

import android.os.Bundle
import android.view.View
import com.abdulrahman.fakelocation.data.Place
import com.abdulrahman.fakelocation.data.TravelMode
import com.abdulrahman.fakelocation.nav.NavSession

/**
 * The Navigate screen's map. Two kinds: [ClassicNavMap] (map pictures, osmdroid) and [VectorNavMap] (vector map,
 * MapLibre: smooth turning, upright names, tilted 3D view). The screen talks to it only through this.
 */
interface NavMap {
    /** The map's view (the screen watches touches on it). */
    val view: View

    /** Driving: the map follows the car until it's moved by hand. */
    var follow: Boolean

    /** Route, other ways, cameras, turn arrow, lanes and the car, for the latest state. */
    fun draw(s: NavSession.State)

    fun setStops(stops: List<Place>)

    /** Driving: follow the car again. Before starting: the whole route, or where you are. */
    fun recenter(s: NavSession.State)

    /** Navigation started (heading up, close in) or ended (north up). */
    fun navigationChanged(navigating: Boolean, mode: TravelMode)

    fun setNight(night: Boolean)

    /** Google's live traffic over the map. */
    fun setTraffic(on: Boolean)

    fun onStart() = Unit
    fun onResume()
    fun onPause()
    fun onStop() = Unit
    fun onDestroy() = Unit
    fun onSaveInstanceState(out: Bundle) = Unit
    fun onLowMemory() = Unit
}
