package com.abdulrahman.fakelocation.ui

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
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

    /**
     * Turns a light street map into a dark one: brightness flipped, colours kept (water stays blue, parks green),
     * and a little softer so it doesn't glare at night.
     */
    val NIGHT_FILTER: ColorMatrixColorFilter by lazy {
        val invert = ColorMatrix(floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
            0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f,
        ))
        val hueHalfTurn = ColorMatrix(floatArrayOf(
            -0.574f, 1.430f, 0.144f, 0f, 0f,
            0.426f, 0.430f, 0.144f, 0f, 0f,
            0.426f, 1.430f, -0.856f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        ))
        val soften = ColorMatrix(floatArrayOf(
            0.82f, 0f, 0f, 0f, 24f,
            0f, 0.82f, 0f, 0f, 28f,
            0f, 0f, 0.82f, 0f, 36f,
            0f, 0f, 0f, 1f, 0f,
        ))
        val matrix = ColorMatrix()
        matrix.setConcat(hueHalfTurn, invert)
        matrix.postConcat(soften)
        ColorMatrixColorFilter(matrix)
    }

    /** Satellite pictures are dark enough already and look wrong flipped. */
    fun nightFilterFor(provider: MapProvider) =
        if (provider == MapProvider.GOOGLE_SATELLITE || provider == MapProvider.GOOGLE_HYBRID) null else NIGHT_FILTER

    fun apply(map: MapView, scope: LifecycleCoroutineScope, credit: TextView? = null, night: Boolean = false): MapProvider {
        val context = map.context
        val prefs = Prefs(context)
        val provider = if (prefs.hasGoogleKey) prefs.mapProvider else MapProvider.OSM
        if (night) {
            val tiles = map.overlayManager.tilesOverlay
            tiles.setColorFilter(nightFilterFor(provider))
            tiles.loadingBackgroundColor = 0xFF1A2331.toInt()
            tiles.loadingLineColor = 0xFF263143.toInt()
        }
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
                if (night) map.overlayManager.tilesOverlay.setColorFilter(NIGHT_FILTER)
                map.setTileSource(TileSourceFactory.MAPNIK)
                credit?.setText(R.string.map_credit)
                Toast.makeText(context, context.getString(R.string.google_map_failed, it.message), Toast.LENGTH_LONG).show()
            }
        }
        return provider
    }
}
