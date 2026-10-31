package com.abdulrahman.fakelocation.system

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import com.abdulrahman.fakelocation.R
import com.abdulrahman.fakelocation.data.LatLon
import com.abdulrahman.fakelocation.data.RoutesRepository
import com.abdulrahman.fakelocation.data.format
import com.abdulrahman.fakelocation.mock.LocationMocker
import com.abdulrahman.fakelocation.mock.MockLocationService
import com.abdulrahman.fakelocation.ui.MainActivity
import com.abdulrahman.fakelocation.ui.hasLocationPermission

/**
 * Invisible activity used by the tile, the widget, app shortcuts and repeating routes to turn the fake location on.
 * Starting it from the foreground is what lets Android start the location service.
 */
class QuickActionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A weekly route opened by its alarm with the phone locked: be in front (over the lock screen for the blink
        // this takes), since that's what lets Android start the location service.
        if (intent.action == ACTION_START_ROUTE && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) setShowWhenLocked(true)
        when (intent.action) {
            ACTION_STOP -> MockLocationService.stop(this)
            ACTION_TELEPORT -> if (ready()) {
                val target = LatLon(intent.getDoubleExtra(EXTRA_LAT, 0.0), intent.getDoubleExtra(EXTRA_LON, 0.0))
                val name = intent.getStringExtra(EXTRA_NAME) ?: target.format()
                MockLocationService.teleport(this, target, name)
                Toast.makeText(this, getString(R.string.notification_title, name), Toast.LENGTH_SHORT).show()
            }
            ACTION_START_ROUTE -> startRoute()
            else -> {
                val place = QuickActions.defaultPlace(this)
                if (place != null && ready()) {
                    MockLocationService.teleport(this, place.position, place.name ?: place.position.format())
                    Toast.makeText(this, getString(R.string.notification_title, place.name), Toast.LENGTH_SHORT).show()
                } else if (place == null) {
                    openApp()
                }
            }
        }
        finish()
    }

    /** A repeating route is due (from its notification, or on its own with the screen off). */
    private fun startRoute() {
        val id = intent.getLongExtra(EXTRA_ROUTE_ID, -1)
        TripScheduler.dismiss(this, id)
        val route = RoutesRepository.get(this).get(id) ?: return
        if (route.path == null || !ready()) return
        val now = System.currentTimeMillis()
        val due = intent.getLongExtra(EXTRA_START_AT, now)
        // Opened a little late: the trip is where it would have been by now, so it still arrives on time.
        val startAt = if (now - due in 0..LATE_LIMIT_MS) due else now
        MockLocationService.startTrip(this, route, startAt)
        Toast.makeText(this, getString(R.string.schedule_started, route.name), Toast.LENGTH_SHORT).show()
    }

    /** Can fake right now; otherwise the app opens so the user can fix it. */
    private fun ready(): Boolean {
        if (hasLocationPermission() && LocationMocker.isSelectedMockApp(this)) return true
        openApp()
        return false
    }

    private fun openApp() = startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    companion object {
        const val ACTION_TELEPORT = "com.abdulrahman.fakelocation.QUICK_TELEPORT"
        const val ACTION_STOP = "com.abdulrahman.fakelocation.QUICK_STOP"
        private const val ACTION_START_ROUTE = "com.abdulrahman.fakelocation.QUICK_START_ROUTE"
        private const val EXTRA_LAT = "lat"
        private const val EXTRA_LON = "lon"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_ROUTE_ID = "route_id"
        private const val EXTRA_START_AT = "start_at"
        private const val LATE_LIMIT_MS = 30 * 60_000L

        fun intent(context: Context) =
            Intent(context, QuickActionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)

        fun teleport(context: Context, target: LatLon, name: String) = intent(context)
            .setAction(ACTION_TELEPORT).putExtra(EXTRA_LAT, target.lat).putExtra(EXTRA_LON, target.lon).putExtra(EXTRA_NAME, name)

        fun stop(context: Context) = intent(context).setAction(ACTION_STOP)

        fun startRoute(context: Context, routeId: Long, startAt: Long) = intent(context)
            .setAction(ACTION_START_ROUTE).putExtra(EXTRA_ROUTE_ID, routeId).putExtra(EXTRA_START_AT, startAt)
    }
}
