package com.abdulrahman.fakelocation.mock

import com.abdulrahman.fakelocation.nav.NavSession
import com.abdulrahman.fakelocation.plus.PlusAccess
import android.app.Notification
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.abdulrahman.fakelocation.R
import com.abdulrahman.fakelocation.data.FakeSession
import com.abdulrahman.fakelocation.data.LatLon
import com.abdulrahman.fakelocation.data.PlacesRepository
import com.abdulrahman.fakelocation.data.Prefs
import com.abdulrahman.fakelocation.data.Route
import com.abdulrahman.fakelocation.data.RouteJson
import com.abdulrahman.fakelocation.data.format
import com.abdulrahman.fakelocation.data.moved
import com.abdulrahman.fakelocation.trip.TripAlarms
import com.abdulrahman.fakelocation.trip.TripClock
import com.abdulrahman.fakelocation.trip.TripPhase
import com.abdulrahman.fakelocation.trip.TripPoint
import com.abdulrahman.fakelocation.trip.TripRun
import com.abdulrahman.fakelocation.system.QuickTileService
import com.abdulrahman.fakelocation.system.StatusWidget

/**
 * Foreground service that feeds the fake position to the phone every second: either a fixed spot,
 * or a trip moving along a route, waiting at stops and ringing alarms on the way.
 */
class MockLocationService : LifecycleService() {

    private lateinit var mocker: LocationMocker
    private lateinit var prefs: Prefs
    private lateinit var places: PlacesRepository
    private lateinit var notes: ServiceNotifications
    private val handler = Handler(Looper.getMainLooper())
    private var session: FakeSession? = null
    private var trip: TripRun? = null
    private var mocking = false
    private var wakeLock: PowerManager.WakeLock? = null
    private val drift = Drift()

    private val tick = object : Runnable {
        override fun run() {
            try {
                if (trip != null) tickTrip() else session?.let(::tickFixed)
            } catch (e: Exception) {
                Log.w(TAG, "tick failed", e)
            }
            // Moving (a trip or the joystick): as often as chosen in Settings → Fake movement; a still spot once a second.
            val moving = trip != null || MockState.joystick.value != null
            val every = if (!moving) PUSH_INTERVAL_MS else {
                val chosen = 1000L / prefs.fakeUpdatesPerSecond.coerceIn(1, 10)
                if (trip == null) minOf(chosen, JOYSTICK_INTERVAL_MS) else chosen
            }
            if (trip != null || session != null) handler.postDelayed(this, every)
        }
    }

    private var lastFixedTick = 0L
    private var lastSessionSave = 0L

    /** A fixed spot, or one the joystick on the map is moving. */
    private fun tickFixed(current: FakeSession) {
        val now = SystemClock.elapsedRealtime()
        val dt = if (lastFixedTick == 0L) 0.0 else (now - lastFixedTick).coerceIn(0, 2_000) / 1000.0
        lastFixedTick = now
        val stick = MockState.joystick.value
        if (stick == null || stick.speedMps <= 0.0) {
            push(current.position, stick?.bearing?.toFloat() ?: 0f, 0f, natural = false)
            return
        }
        val moved = current.copy(position = current.position.moved(stick.bearing, stick.speedMps * dt))
        session = moved
        MockState.set(moved)
        // Written now and then, so a restart picks up close to where the joystick left it.
        if (now - lastSessionSave > SESSION_SAVE_MS) {
            prefs.activeSession = moved
            lastSessionSave = now
        }
        push(moved.position, stick.bearing.toFloat(), stick.speedMps.toFloat(), natural = false)
    }

    override fun onCreate() {
        super.onCreate()
        mocker = LocationMocker(this)
        prefs = Prefs(this)
        mocker.setFakeGpsChip(!prefs.realGpsForNav)
        places = PlacesRepository.get(this)
        notes = ServiceNotifications(this)
        notes.createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> {
                stopEverything()
                return START_NOT_STICKY
            }
            ACTION_TELEPORT -> {
                val target = LatLon(intent.getDoubleExtra(EXTRA_LAT, 0.0), intent.getDoubleExtra(EXTRA_LON, 0.0))
                val name = intent.getStringExtra(EXTRA_NAME) ?: target.format()
                endTrip()
                startFixed(FakeSession(target, name, System.currentTimeMillis()), newVisit = true)
            }
            ACTION_START_TRIP -> {
                val route = RouteJson.read(org.json.JSONObject(intent.getStringExtra(EXTRA_ROUTE)!!))
                startTrip(route, intent.getLongExtra(EXTRA_START_AT, System.currentTimeMillis()))
            }
            ACTION_PAUSE, ACTION_RESUME, ACTION_SKIP, ACTION_ADD_WAIT -> tripControl(intent.action!!)
            ACTION_SET_SPEED -> trip?.let { run ->
                run.changeSpeed(intent.getDoubleExtra(EXTRA_SPEED, 1.0).coerceIn(0.25, 5.0), System.currentTimeMillis())
                saveTrip()
                restartTicks()
                lastSurfaceKey = null
            }
            ACTION_REAL_GPS -> {
                val real = intent.getBooleanExtra(EXTRA_REAL, false)
                prefs.realGpsForNav = real
                mocker.setFakeGpsChip(!real)
                if (trip == null && session == null) stopSelf()
            }
            ACTION_DISMISS_ALARM -> {
                notes.dismissAlarm()
                if (trip == null && session == null) stopSelf()
            }
            // Restarted by the system after being killed: pick up where we were.
            null -> {
                val savedTrip = prefs.activeTrip?.let {
                    runCatching { TripRun.fromJson(it) }.onFailure { e -> Log.w(TAG, "saved trip unreadable", e) }.getOrNull()
                }
                when {
                    savedTrip != null -> resumeTrip(savedTrip)
                    prefs.activeSession != null -> startFixed(prefs.activeSession!!, newVisit = false)
                    else -> stopSelf()
                }
            }
        }
        return START_STICKY
    }

    // region Fixed location

    private fun startFixed(next: FakeSession, newVisit: Boolean) {
        goForeground(notes.fixed(next))
        if (!ensureMocking()) return
        if (newVisit) {
            closeHistoryEntry()
            prefs.activeHistoryId = places.startHistory(next.name, next.position, next.startedAt)
        }
        session = next
        prefs.activeSession = next
        MockState.set(next)
        restartTicks()
        refreshSurfaces(force = true)
    }

    // endregion

    // region Trip

    private fun startTrip(route: Route, startAt: Long) {
        // A new trip replaces whatever was running.
        session = null
        prefs.activeSession = null
        MockState.set(null)
        closeHistoryEntry()
        trip = null
        val run = TripRun(
            // The way back and the laps become plain stops, so the rest of the trip engine needs nothing special.
            route = route.expanded(),
            clock = TripClock.startingAt(startAt),
            fired = mutableSetOf(),
            natural = prefs.naturalMovement,
            trafficPercent = prefs.trafficPercent,
            redLightPercent = prefs.redLightPercent,
        )
        resumeTrip(run)
    }

    private fun resumeTrip(run: TripRun) {
        trip = run
        saveTrip()
        goForeground(notes.trip(run, run.timeline.at(run.clock.at(System.currentTimeMillis()))))
        acquireWakeLock()
        restartTicks()
    }

    /** When the notification, alarms and map were last brought up to date (they only need it once a second). */
    private var lastFullTick = 0L

    private fun tickTrip() {
        val run = trip ?: return
        val now = System.currentTimeMillis()
        val t = run.clock.at(now)
        val point = run.timeline.at(t)

        if (point.phase == TripPhase.SCHEDULED) {
            if (mocking) {
                mocker.stop()
                mocking = false
            }
        } else {
            if (!allowSimulation(this)) {
                stopEverything()
                return
            }
            if (!ensureMocking()) return
            push(point.position, point.bearing, point.speedMps, run.natural)
        }
        // Between once-a-second updates only the position is sent.
        if (now - lastFullTick < FULL_TICK_MS && point.phase != TripPhase.FINISHED) return
        lastFullTick = now

        trackStopHistory(run, point, now)

        val due = TripAlarms.due(
            run.timeline, t, run.fired,
            prefs.alertBeforeArriveMinutes * 60_000L, prefs.alertBeforeLeaveMinutes * 60_000L
        )
        if (!run.clock.paused) {
            for (alert in due) {
                run.fired += alert.key
                notes.fire(alert, run, t, now)
            }
            if (due.isNotEmpty()) saveTrip()
        }

        if (point.phase == TripPhase.FINISHED) {
            finishTrip(run)
            return
        }
        MockState.setTrip(
            TripUi(
                run.route.name, run.route.mode, run.timeline, t, point, run.clock.paused, now,
                prefs.alertBeforeArriveMinutes * 60_000L, prefs.alertBeforeLeaveMinutes * 60_000L, run.route.speed,
            )
        )
        notes.showOngoing(notes.trip(run, point))
        refreshSurfaces()
    }

    /** Each stop we wait at shows up in the place history. */
    private fun trackStopHistory(run: TripRun, point: TripPoint, now: Long) {
        val waitingAt = if (point.phase == TripPhase.WAITING || point.phase == TripPhase.FINISHED) point.stopIndex else -1
        if (waitingAt == run.lastWaitingStop) return
        if (run.historyId >= 0) places.finishHistory(run.historyId, now)
        run.historyId = -1
        if (waitingAt >= 0) {
            val stop = run.route.stops[waitingAt]
            run.historyId = places.startHistory(stop.name, stop.position, now)
        }
        run.lastWaitingStop = waitingAt
        saveTrip()
    }

    /** At the end of the trip, stay at the last stop like a normal fixed location. */
    private fun finishTrip(run: TripRun) {
        val last = run.route.stops.last()
        trip = null
        prefs.activeTrip = null
        MockState.setTrip(null)
        releaseWakeLock()
        prefs.activeHistoryId = run.historyId
        val fixed = FakeSession(last.position, last.name, System.currentTimeMillis())
        session = fixed
        prefs.activeSession = fixed
        MockState.set(fixed)
        notes.showOngoing(notes.fixed(fixed))
        refreshSurfaces(force = true)
    }

    private fun tripControl(action: String) {
        val run = trip ?: return
        val now = System.currentTimeMillis()
        val t = run.clock.at(now)
        val point = run.timeline.at(t)
        when (action) {
            ACTION_PAUSE -> run.clock = run.clock.pause(now)
            ACTION_RESUME -> run.clock = run.clock.resume(now)
            ACTION_ADD_WAIT -> if (point.phase == TripPhase.WAITING) run.addWait(point.stopIndex, 5)
            ACTION_SKIP -> {
                val target = when (point.phase) {
                    TripPhase.SCHEDULED -> 0L
                    TripPhase.WAITING -> run.timeline.leave[point.stopIndex]
                    TripPhase.MOVING -> run.timeline.arrive[point.stopIndex + 1]
                    TripPhase.FINISHED -> t
                }
                run.clock = run.clock.jumpTo(target, now).let { if (point.phase == TripPhase.SCHEDULED) it.resume(now) else it }
                notes.dismissAlarm()
            }
        }
        saveTrip()
        lastFullTick = 0L
        handler.removeCallbacks(tick)
        handler.post(tick)
        lastSurfaceKey = null
    }

    private fun endTrip() {
        val run = trip ?: return
        if (run.historyId >= 0) places.finishHistory(run.historyId, System.currentTimeMillis())
        trip = null
        prefs.activeTrip = null
        MockState.setTrip(null)
        releaseWakeLock()
        notes.dismissAlarm()
    }

    private fun saveTrip() {
        prefs.activeTrip = trip?.toJson()
    }

    // endregion

    // region Plumbing

    private fun ensureMocking(): Boolean {
        if (mocking) return true
        return try {
            mocker.start()
            mocking = true
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "not the selected mock app", e)
            notes.info(getString(R.string.app_name), getString(R.string.mock_failed))
            stopEverything()
            false
        }
    }

    private var lastPushAt = 0L

    private fun push(position: LatLon, bearing: Float, speed: Float, natural: Boolean) {
        val now = SystemClock.elapsedRealtime()
        val seconds = if (lastPushAt == 0L) 1.0 else (now - lastPushAt) / 1000.0
        lastPushAt = now
        if (!natural) {
            mocker.push(position.lat, position.lon, bearing, speed)
            return
        }
        val (lat, lon, accuracy) = drift.apply(position, seconds)
        mocker.push(lat, lon, bearing, speed, accuracy)
    }

    private fun restartTicks() {
        lastFullTick = 0L
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    private var lastSurfaceKey: String? = null
    private var lastSurfaceUpdate = 0L

    /** Keeps the Quick Settings tile and the widget in step with what is running. */
    private fun refreshSurfaces(force: Boolean = false) {
        val run = trip
        val now = System.currentTimeMillis()
        val key = when {
            run != null -> run.timeline.at(run.clock.at(now)).let { "${it.phase}:${it.stopIndex}:${run.clock.paused}:${run.timeline.totalMs}" }
            session != null -> "fixed:${session?.position}"
            else -> "off"
        }
        if (!force && key == lastSurfaceKey && now - lastSurfaceUpdate < SURFACE_REFRESH_MS) return
        lastSurfaceKey = key
        lastSurfaceUpdate = now
        StatusWidget.update(this)
        QuickTileService.refresh(this)
    }

    private fun goForeground(notification: Notification) {
        ServiceCompat.startForeground(
            this, ServiceNotifications.NOTIFICATION_ID, notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        )
    }

    private fun stopEverything() {
        handler.removeCallbacks(tick)
        endTrip()
        if (mocking) mocker.stop()
        mocking = false
        closeHistoryEntry()
        session = null
        prefs.activeSession = null
        MockState.set(null)
        PlusAccess.endWhenInactive(this)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        refreshSurfaces(force = true)
        stopSelf()
    }

    private fun closeHistoryEntry() {
        val id = prefs.activeHistoryId
        if (id >= 0) places.finishHistory(id, System.currentTimeMillis())
        prefs.activeHistoryId = -1
    }

    /** Keeps the CPU awake during a trip so it keeps moving and alarms go off with the screen off. */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FakeLocation:trip")
            .apply { acquire(24 * 60 * 60_000L) }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        AlarmPlayer.stop()
        releaseWakeLock()
        if (mocking) mocker.stop()
        mocking = false
        super.onDestroy()
    }

    // endregion

    companion object {
        private const val TAG = "MockLocationService"
        private const val PUSH_INTERVAL_MS = 1000L
        private const val JOYSTICK_INTERVAL_MS = 500L
        private const val FULL_TICK_MS = 950L
        private const val SESSION_SAVE_MS = 5_000L
        private const val SURFACE_REFRESH_MS = 30_000L
        private const val ACTION_TELEPORT = "teleport"
        const val ACTION_STOP = "stop"
        private const val ACTION_START_TRIP = "start_trip"
        const val ACTION_PAUSE = "pause"
        const val ACTION_RESUME = "resume"
        const val ACTION_SKIP = "skip"
        const val ACTION_ADD_WAIT = "add_wait"
        const val ACTION_DISMISS_ALARM = "dismiss_alarm"
        private const val ACTION_REAL_GPS = "real_gps"
        private const val EXTRA_REAL = "real"
        private const val EXTRA_LAT = "lat"
        private const val EXTRA_LON = "lon"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_ROUTE = "route"
        private const val EXTRA_START_AT = "start_at"
        private const val ACTION_SET_SPEED = "set_speed"
        private const val EXTRA_SPEED = "speed"

        private fun allowSimulation(context: Context): Boolean {
            val nav = NavSession.state.value
            if (!nav.navigating || nav.progress?.arrived == true || PlusAccess.mayUseBoth(context)) return true
            android.widget.Toast.makeText(context, R.string.plus_simulation_blocked, android.widget.Toast.LENGTH_LONG).show()
            return false
        }

        fun teleport(context: Context, target: LatLon, name: String?) {
            if (!allowSimulation(context)) return
            val intent = Intent(context, MockLocationService::class.java)
                .setAction(ACTION_TELEPORT)
                .putExtra(EXTRA_LAT, target.lat)
                .putExtra(EXTRA_LON, target.lon)
                .putExtra(EXTRA_NAME, name)
            ContextCompat.startForegroundService(context, intent)
        }

        fun startTrip(context: Context, route: Route, startAt: Long) {
            if (!allowSimulation(context)) return
            val intent = Intent(context, MockLocationService::class.java)
                .setAction(ACTION_START_TRIP)
                .putExtra(EXTRA_ROUTE, RouteJson.write(route).toString())
                .putExtra(EXTRA_START_AT, startAt)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) = send(context, ACTION_STOP)
        fun pause(context: Context) = send(context, ACTION_PAUSE)
        fun resume(context: Context) = send(context, ACTION_RESUME)

        /** Moving: jump to the next stop. Waiting: leave now. Scheduled: start now. */
        fun skip(context: Context) = send(context, ACTION_SKIP)
        fun addWait(context: Context) = send(context, ACTION_ADD_WAIT)

        /** Trip speed from now on (1.0 = each street's speed). */
        fun setSpeed(context: Context, speed: Double) =
            context.startService(actionIntent(context, ACTION_SET_SPEED).putExtra(EXTRA_SPEED, speed))
        fun dismissAlarm(context: Context) = send(context, ACTION_DISMISS_ALARM)

        /** Navigation on: the app reads the real GPS chip while the fake location keeps running for other apps. */
        fun setRealGpsForNavigation(context: Context, real: Boolean) {
            Prefs(context).realGpsForNav = real
            context.startService(actionIntent(context, ACTION_REAL_GPS).putExtra(EXTRA_REAL, real))
        }

        fun actionIntent(context: Context, action: String): Intent =
            Intent(context, MockLocationService::class.java).setAction(action)

        private fun send(context: Context, action: String) {
            context.startService(actionIntent(context, action))
        }
    }
}
