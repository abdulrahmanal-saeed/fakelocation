package com.abdulrahman.fakelocation.mock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
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
import com.abdulrahman.fakelocation.data.TravelMode
import com.abdulrahman.fakelocation.data.format
import com.abdulrahman.fakelocation.data.formatClock
import com.abdulrahman.fakelocation.data.formatDistance
import com.abdulrahman.fakelocation.data.formatDuration
import com.abdulrahman.fakelocation.trip.Alert
import com.abdulrahman.fakelocation.trip.AlertKind
import com.abdulrahman.fakelocation.trip.TripAlarms
import com.abdulrahman.fakelocation.trip.TripClock
import com.abdulrahman.fakelocation.trip.TripPhase
import com.abdulrahman.fakelocation.trip.TripPoint
import com.abdulrahman.fakelocation.trip.TripRun
import com.abdulrahman.fakelocation.system.QuickTileService
import com.abdulrahman.fakelocation.system.StatusWidget
import com.abdulrahman.fakelocation.ui.AlarmActivity
import com.abdulrahman.fakelocation.ui.MainActivity
import com.abdulrahman.fakelocation.ui.clockText
import com.abdulrahman.fakelocation.ui.stopLetter
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Foreground service that feeds the fake position to the phone every second: either a fixed spot,
 * or a trip moving along a route, waiting at stops and ringing alarms on the way.
 */
class MockLocationService : LifecycleService() {

    private lateinit var mocker: LocationMocker
    private lateinit var prefs: Prefs
    private lateinit var places: PlacesRepository
    private lateinit var notifications: NotificationManager
    private val handler = Handler(Looper.getMainLooper())
    private var session: FakeSession? = null
    private var trip: TripRun? = null
    private var mocking = false
    private var wakeLock: PowerManager.WakeLock? = null
    private val drift = Drift()

    private val tick = object : Runnable {
        override fun run() {
            try {
                if (trip != null) tickTrip() else session?.let { push(it.position, 0f, 0f, natural = false) }
            } catch (e: Exception) {
                Log.w(TAG, "tick failed", e)
            }
            if (trip != null || session != null) handler.postDelayed(this, PUSH_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        mocker = LocationMocker(this)
        prefs = Prefs(this)
        places = PlacesRepository.get(this)
        notifications = getSystemService(NotificationManager::class.java)
        createChannels()
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
            ACTION_DISMISS_ALARM -> {
                dismissAlarm()
                if (trip == null && session == null) stopSelf()
            }
            // Restarted by the system after being killed: pick up where we were.
            null -> {
                val savedTrip = prefs.activeTrip?.let { runCatching { TripRun.fromJson(it) }.getOrNull() }
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
        goForeground(fixedNotification(next))
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
            route = route,
            clock = TripClock.startingAt(startAt),
            fired = mutableSetOf(),
            natural = prefs.naturalMovement,
            trafficPercent = prefs.trafficPercent,
        )
        resumeTrip(run)
    }

    private fun resumeTrip(run: TripRun) {
        trip = run
        saveTrip()
        goForeground(tripNotification(run, run.timeline.at(run.clock.at(System.currentTimeMillis()))))
        acquireWakeLock()
        restartTicks()
    }

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
            if (!ensureMocking()) return
            push(point.position, point.bearing, point.speedMps, run.natural)
        }

        trackStopHistory(run, point, now)

        val due = TripAlarms.due(
            run.timeline, t, run.fired,
            prefs.alertBeforeArriveMinutes * 60_000L, prefs.alertBeforeLeaveMinutes * 60_000L
        )
        if (!run.clock.paused) {
            for (alert in due) {
                run.fired += alert.key
                fire(alert, run, t, now)
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
                prefs.alertBeforeArriveMinutes * 60_000L, prefs.alertBeforeLeaveMinutes * 60_000L
            )
        )
        notifications.notify(NOTIFICATION_ID, tripNotification(run, point))
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
        notifications.notify(NOTIFICATION_ID, fixedNotification(fixed))
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
                dismissAlarm()
            }
        }
        saveTrip()
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
        dismissAlarm()
    }

    private fun saveTrip() {
        prefs.activeTrip = trip?.toJson()
    }

    // endregion

    // region Alarms

    private fun fire(alert: Alert, run: TripRun, t: Long, now: Long) {
        val stops = run.route.stops
        val i = alert.stopIndex
        val stop = stops[i]
        val wallOf = { tripMs: Long -> now + (tripMs - t) }
        when (alert.kind) {
            AlertKind.ARRIVING -> {
                val target = wallOf(run.timeline.arrive[i])
                ring(
                    AlarmActivity.Kind.ARRIVING, i, stops.size, stop.name, target,
                    getString(R.string.alarm_arriving_title, stopLetter(i), stop.name),
                    getString(R.string.alarm_arriving_text, formatDuration(target - now + 999)),
                    note = if (i < stops.lastIndex && stop.waitMinutes > 0) getString(R.string.wait_minutes, stop.waitMinutes) else null,
                )
            }
            AlertKind.LEAVING -> {
                val target = wallOf(run.timeline.leave[i])
                val next = stops.getOrNull(i + 1)
                ring(
                    AlarmActivity.Kind.LEAVING, i, stops.size, stop.name, target,
                    getString(R.string.alarm_leaving_title, stop.name, formatDuration(target - now + 999)),
                    next?.let { getString(R.string.alarm_leaving_text, stopLetter(i + 1), it.name) }.orEmpty(),
                    note = next?.let { "${stopLetter(i + 1)} · ${it.name}" },
                )
            }
            AlertKind.ARRIVED -> info(
                getString(R.string.arrived_title, stopLetter(i), stop.name),
                getString(R.string.arrived_text, formatDuration(stop.waitMinutes * 60_000L), clockText(wallOf(run.timeline.leave[i]))),
            )
            AlertKind.FINISHED -> info(getString(R.string.finished_title), getString(R.string.finished_text, stop.name))
        }
    }

    private fun ring(
        kind: AlarmActivity.Kind, stopIndex: Int, stopCount: Int, name: String, target: Long,
        title: String, text: String, note: String?,
    ) {
        val loud = prefs.ringInSilent
        if (loud) AlarmPlayer.start(this, sound = true, vibrate = prefs.vibrate)
        val screen = AlarmActivity.intent(this, kind, stopIndex, stopCount, name, target, note)
        val screenIntent = PendingIntent.getActivity(this, 10, screen, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val dismiss = PendingIntent.getService(
            this, 11, Intent(this, MockLocationService::class.java).setAction(ACTION_DISMISS_ALARM), PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, if (loud) CHANNEL_ALARMS else CHANNEL_ALERTS_SOUND)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(this, R.color.fake))
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setContentIntent(screenIntent)
            .setDeleteIntent(dismiss)
            .addAction(R.drawable.ic_close, getString(R.string.dismiss), dismiss)
            .setAutoCancel(true)
            .setTimeoutAfter(10 * 60_000L)
        if (loud) builder.setFullScreenIntent(screenIntent, true)
        notifications.notify(ALERT_NOTIFICATION_ID, builder.build())
    }

    private fun info(title: String, text: String) {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        notifications.notify(
            INFO_NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_TRIP_INFO)
                .setSmallIcon(R.drawable.ic_notification)
                .setColor(ContextCompat.getColor(this, R.color.fake))
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
        )
    }

    private fun dismissAlarm() {
        AlarmPlayer.stop()
        notifications.cancel(ALERT_NOTIFICATION_ID)
        sendBroadcast(Intent(AlarmActivity.ACTION_CLOSE).setPackage(packageName))
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
            info(getString(R.string.app_name), getString(R.string.mock_failed))
            stopEverything()
            false
        }
    }

    private fun push(position: LatLon, bearing: Float, speed: Float, natural: Boolean) {
        if (!natural) {
            mocker.push(position.lat, position.lon, bearing, speed)
            return
        }
        val (lat, lon, accuracy) = drift.apply(position)
        mocker.push(lat, lon, bearing, speed, accuracy)
    }

    private fun restartTicks() {
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
            this, NOTIFICATION_ID, notification,
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

    private fun createChannels() {
        val status = NotificationChannel(CHANNEL_STATUS, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW)
        // The app plays the alarm sound itself (on the alarm stream), so this channel stays quiet.
        val alarms = NotificationChannel(CHANNEL_ALARMS, getString(R.string.channel_alarms), NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null)
            enableVibration(false)
            setBypassDnd(true)
        }
        val alerts = NotificationChannel(CHANNEL_ALERTS_SOUND, getString(R.string.channel_alerts_sound), NotificationManager.IMPORTANCE_HIGH)
        val info = NotificationChannel(CHANNEL_TRIP_INFO, getString(R.string.channel_trip_info), NotificationManager.IMPORTANCE_DEFAULT)
        notifications.createNotificationChannels(listOf(status, alarms, alerts, info))
    }

    private fun openAppIntent() =
        PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)

    private fun serviceIntent(action: String, code: Int) =
        PendingIntent.getService(this, code, Intent(this, MockLocationService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE)

    private fun fixedNotification(session: FakeSession): Notification =
        NotificationCompat.Builder(this, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(this, R.color.fake))
            .setContentTitle(getString(R.string.notification_title, session.name))
            .setContentText(session.position.format())
            .setContentIntent(openAppIntent())
            .addAction(R.drawable.ic_stop, getString(R.string.stop), serviceIntent(ACTION_STOP, 1))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setUsesChronometer(true)
            .setWhen(session.startedAt)
            .build()

    private fun tripNotification(run: TripRun, point: TripPoint): Notification {
        val stops = run.route.stops
        val now = System.currentTimeMillis()
        val t = run.clock.at(now)
        val builder = NotificationCompat.Builder(this, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(this, R.color.fake))
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
        var title: String
        val text: String
        when (point.phase) {
            TripPhase.SCHEDULED -> {
                title = getString(R.string.trip_starts_at, clockText(now - t))
                text = run.route.name
                builder.addAction(R.drawable.ic_route, getString(R.string.start_now_action), serviceIntent(ACTION_SKIP, 3))
                builder.addAction(R.drawable.ic_stop, getString(R.string.cancel_trip), serviceIntent(ACTION_STOP, 1))
            }
            TripPhase.MOVING, TripPhase.FINISHED -> {
                val next = (point.stopIndex + 1).coerceAtMost(stops.lastIndex)
                val toNext = run.timeline.arrive[next] - t
                title = getString(
                    if (run.route.mode == TravelMode.CAR) R.string.trip_driving_to else R.string.trip_walking_to,
                    stopLetter(next), stops[next].name
                )
                text = getString(
                    R.string.trip_moving_text, formatDuration(toNext.coerceAtLeast(0) + 999),
                    formatDistance(point.distanceToNextStop), (point.speedMps * 3.6f).roundToInt()
                )
                val total = run.timeline.totalDistance.coerceAtLeast(1.0)
                builder.setProgress(1000, (point.distanceDone / total * 1000).toInt(), false)
                builder.addAction(
                    R.drawable.ic_stop,
                    getString(if (run.clock.paused) R.string.resume else R.string.pause),
                    serviceIntent(if (run.clock.paused) ACTION_RESUME else ACTION_PAUSE, 4)
                )
                builder.addAction(R.drawable.ic_route, getString(R.string.skip), serviceIntent(ACTION_SKIP, 3))
                builder.addAction(R.drawable.ic_stop, getString(R.string.stop), serviceIntent(ACTION_STOP, 1))
            }
            TripPhase.WAITING -> {
                val i = point.stopIndex
                title = getString(R.string.trip_waiting_at, stopLetter(i), stops[i].name)
                val next = stops.getOrNull(i + 1)
                text = if (next == null) getString(R.string.trip_waiting_last)
                else getString(R.string.trip_waiting_text, formatClock(run.timeline.leave[i] - t + 999).removePrefix("00:"), "${stopLetter(i + 1)} · ${next.name}")
                builder.addAction(R.drawable.ic_plus, getString(R.string.plus_five), serviceIntent(ACTION_ADD_WAIT, 5))
                builder.addAction(R.drawable.ic_route, getString(R.string.leave_now), serviceIntent(ACTION_SKIP, 3))
                builder.addAction(R.drawable.ic_stop, getString(R.string.stop), serviceIntent(ACTION_STOP, 1))
            }
        }
        if (run.clock.paused) title = getString(R.string.trip_paused_prefix, title)
        return builder.setContentTitle(title).setContentText(text).build()
    }

    /** Tiny random wander (a few metres) like a real GPS fix. */
    private class Drift {
        private var north = 0.0
        private var east = 0.0

        fun apply(position: LatLon): Triple<Double, Double, Float> {
            north = (north + Random.nextDouble(-0.6, 0.6)).coerceIn(-4.0, 4.0)
            east = (east + Random.nextDouble(-0.6, 0.6)).coerceIn(-4.0, 4.0)
            val lat = position.lat + north / 111_320.0
            val lon = position.lon + east / (111_320.0 * cos(Math.toRadians(position.lat)))
            return Triple(lat, lon, Random.nextDouble(3.0, 8.0).toFloat())
        }
    }

    // endregion

    companion object {
        private const val TAG = "MockLocationService"
        private const val CHANNEL_STATUS = "status"
        private const val CHANNEL_ALARMS = "alarms"
        private const val CHANNEL_ALERTS_SOUND = "alerts"
        private const val CHANNEL_TRIP_INFO = "trip_info"
        private const val NOTIFICATION_ID = 1
        private const val ALERT_NOTIFICATION_ID = 2
        private const val INFO_NOTIFICATION_ID = 3
        private const val PUSH_INTERVAL_MS = 1000L
        private const val SURFACE_REFRESH_MS = 30_000L
        private const val ACTION_TELEPORT = "teleport"
        const val ACTION_STOP = "stop"
        private const val ACTION_START_TRIP = "start_trip"
        const val ACTION_PAUSE = "pause"
        const val ACTION_RESUME = "resume"
        const val ACTION_SKIP = "skip"
        const val ACTION_ADD_WAIT = "add_wait"
        private const val ACTION_DISMISS_ALARM = "dismiss_alarm"
        private const val EXTRA_LAT = "lat"
        private const val EXTRA_LON = "lon"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_ROUTE = "route"
        private const val EXTRA_START_AT = "start_at"

        fun teleport(context: Context, target: LatLon, name: String?) {
            val intent = Intent(context, MockLocationService::class.java)
                .setAction(ACTION_TELEPORT)
                .putExtra(EXTRA_LAT, target.lat)
                .putExtra(EXTRA_LON, target.lon)
                .putExtra(EXTRA_NAME, name)
            ContextCompat.startForegroundService(context, intent)
        }

        fun startTrip(context: Context, route: Route, startAt: Long) {
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
        fun dismissAlarm(context: Context) = send(context, ACTION_DISMISS_ALARM)

        fun actionIntent(context: Context, action: String): Intent =
            Intent(context, MockLocationService::class.java).setAction(action)

        private fun send(context: Context, action: String) {
            context.startService(actionIntent(context, action))
        }
    }
}
