package com.abdulrahman.fakelocation.system

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.abdulrahman.fakelocation.R
import com.abdulrahman.fakelocation.data.Prefs
import com.abdulrahman.fakelocation.mock.MockLocationService

/** Quick Settings tile: on = stop the fake location, off = teleport to the first favorite. */
class QuickTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        render()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        if (QuickActions.isActive(this)) {
            MockLocationService.stop(this)
            render(forceActive = false)
            return
        }
        val intent = QuickActionActivity.intent(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun render(forceActive: Boolean? = null) {
        val tile = qsTile ?: return
        val prefs = Prefs(this)
        val active = forceActive ?: QuickActions.isActive(this)
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.app_name)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when {
                !active -> QuickActions.defaultPlace(this)?.name?.let { getString(R.string.tile_off_go, it) } ?: getString(R.string.tile_off)
                prefs.activeTrip != null -> getString(R.string.tile_trip)
                else -> prefs.activeSession?.name ?: getString(R.string.tile_on)
            }
        }
        tile.updateTile()
    }

    companion object {
        /** Asks the system to refresh the tile after the fake location changes. */
        fun refresh(context: Context) {
            runCatching { requestListeningState(context, ComponentName(context, QuickTileService::class.java)) }
        }
    }
}
