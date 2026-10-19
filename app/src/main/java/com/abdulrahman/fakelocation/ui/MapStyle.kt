package com.abdulrahman.fakelocation.ui

import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.LifecycleCoroutineScope
import com.abdulrahman.fakelocation.R
import com.abdulrahman.fakelocation.data.MapProvider
import com.abdulrahman.fakelocation.data.Prefs
import com.abdulrahman.fakelocation.geo.google.GoogleTiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.views.MapView

/** Puts the chosen map (OpenStreetMap or Google) into a map view. Returns the provider it applied. */
object MapStyle {

    fun apply(map: MapView, scope: LifecycleCoroutineScope, credit: TextView? = null): MapProvider {
        val context = map.context
        val prefs = Prefs(context)
        val provider = if (prefs.hasGoogleKey) prefs.mapProvider else MapProvider.OSM
        if (!provider.isGoogle) {
            map.setTileSource(TileSourceFactory.MAPNIK)
            credit?.setText(R.string.map_credit)
            return MapProvider.OSM
        }
        scope.launch {
            val source = runCatching { withContext(Dispatchers.IO) { GoogleTiles.tileSource(context, provider) } }
            source.onSuccess {
                map.setTileSource(it)
                credit?.setText(R.string.map_credit_google)
            }.onFailure {
                map.setTileSource(TileSourceFactory.MAPNIK)
                credit?.setText(R.string.map_credit)
                Toast.makeText(context, context.getString(R.string.google_map_failed, it.message), Toast.LENGTH_LONG).show()
            }
        }
        return provider
    }
}
