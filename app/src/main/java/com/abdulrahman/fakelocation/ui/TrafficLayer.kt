package com.abdulrahman.fakelocation.ui

import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.LifecycleCoroutineScope
import com.abdulrahman.fakelocation.geo.google.GoogleTiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.tileprovider.modules.IFilesystemCache
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.TilesOverlay
import java.io.InputStream

/**
 * Google's live traffic over the whole map (not just the route), when there's a Google key. Tiles are only kept in
 * memory and fetched again every few minutes so the colours stay current.
 */
class TrafficLayer(private val map: MapView, private val scope: LifecycleCoroutineScope) {

    private var overlay: TilesOverlay? = null
    private var provider: MapTileProviderBasic? = null
    private var job: Job? = null
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            provider?.clearTileCache()
            map.invalidate()
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    val shown get() = overlay != null

    fun show(on: Boolean) {
        if (on) start() else stop()
    }

    private fun start() {
        if (overlay != null || job?.isActive == true) return
        val context = map.context.applicationContext
        job = scope.launch {
            val source = runCatching { withContext(Dispatchers.IO) { GoogleTiles.trafficSource(context) } }
                .onFailure { Log.w(TAG, "traffic layer didn't start", it) }
                .getOrNull() ?: return@launch
            val tiles = MapTileProviderBasic(context, source, NoDiskCache)
            val layer = TilesOverlay(tiles, context).apply {
                loadingBackgroundColor = Color.TRANSPARENT
                loadingLineColor = Color.TRANSPARENT
            }
            tiles.setTileRequestCompleteHandler(map.tileRequestCompleteHandler)
            provider = tiles
            overlay = layer
            // Under the route and markers, over the map.
            map.overlays.add(0, layer)
            map.invalidate()
            handler.postDelayed(refresh, REFRESH_MS)
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        handler.removeCallbacks(refresh)
        overlay?.let {
            map.overlays.remove(it)
            it.onDetach(map)
        }
        overlay = null
        provider = null
        map.invalidate()
    }

    /** Traffic goes stale in minutes: never written to the phone's map cache. */
    private object NoDiskCache : IFilesystemCache {
        override fun saveFile(source: ITileSource?, index: Long, stream: InputStream?, expiry: Long?) = false
        override fun exists(source: ITileSource?, index: Long) = false
        override fun onDetach() = Unit
        override fun remove(source: ITileSource?, index: Long) = false
        override fun getExpirationTimestamp(source: ITileSource?, index: Long): Long? = null
        override fun loadTile(source: ITileSource?, index: Long): Drawable? = null
    }

    private companion object {
        const val TAG = "TrafficLayer"
        const val REFRESH_MS = 3 * 60_000L
    }
}
