package com.abdulrahman.fakelocation.nav

import android.annotation.SuppressLint
import android.content.Context
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.content.ContextCompat
import com.abdulrahman.fakelocation.R
import com.abdulrahman.fakelocation.data.Drive
import com.abdulrahman.fakelocation.data.DriveLog
import com.abdulrahman.fakelocation.data.LatLon
import com.abdulrahman.fakelocation.data.Place
import com.abdulrahman.fakelocation.data.Prefs
import com.abdulrahman.fakelocation.data.TravelMode
import com.abdulrahman.fakelocation.data.distanceMeters
import com.abdulrahman.fakelocation.drive.Fuel
import com.abdulrahman.fakelocation.drive.TollGate
import com.abdulrahman.fakelocation.drive.TollPass
import com.abdulrahman.fakelocation.drive.Tolls
import com.abdulrahman.fakelocation.geo.LocationKalman
import com.abdulrahman.fakelocation.geo.NearbySearch
import com.abdulrahman.fakelocation.geo.PlaceCategory
import com.abdulrahman.fakelocation.mock.MockLocationService
import com.abdulrahman.fakelocation.mock.MockState
import com.abdulrahman.fakelocation.trip.TripTimeline
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Real-position navigation, shared by the phone screen and Android Auto: one route, one GPS feed, one voice.
 * The fake location keeps running for other apps; while navigation is open the raw GPS chip is left real.
 */
@SuppressLint("StaticFieldLeak") // holds the application context only
object NavSession {

    private const val TAG = "NavSession"

    enum class Status { IDLE, ROUTING, PREVIEW, NAVIGATING, ERROR }

    data class State(
        val status: Status = Status.IDLE,
        val from: Place? = null, // null = my real location
        val to: Place? = null,
        val mode: TravelMode = TravelMode.CAR,
        val route: NavRoute? = null,
        val roadInfo: RoadInfo? = null,
        val progress: NavProgress? = null,
        val real: LatLon? = null,
        val bearing: Float = 0f,
        val speedKmh: Int = 0,
        val limitKmh: Int = 0,
        val cameraMeters: Double? = null,
        val speeding: Boolean = false,
        val rerouting: Boolean = false,
        /** Metres to the start of a route that begins elsewhere (0 when not relevant). */
        val toStartMeters: Double = 0.0,
        val muted: Boolean = false,
        /** Places to pass on the way, in order (the destination is [to]). */
        val stops: List<Place> = emptyList(),
        /** How many of [stops] are already behind us. */
        val stopsReached: Int = 0,
        val avoid: Avoid = Avoid(),
        /** Salik / Darb gates on the route and their price at the time you'll pass. */
        val tolls: List<TollPass> = emptyList(),
        val fuelCostAed: Double = 0.0,
        /** The next toll gate ahead while driving, and how far. */
        val nextToll: TollPass? = null,
        val tollMeters: Double? = null,
        /** Lanes here in our direction and which one we're in (1 = leftmost; 0 = unknown). */
        val laneCount: Int = 0,
        val lane: Int = 0,
        val laneSource: LaneTracker.Source? = null,
        /** Lanes to move for the next turn (+ right, − left, 0 = you're in a good lane, null = unknown). */
        val laneAdvice: Int? = null,
        /** The GPS uses a second frequency (L5/E5a): metre-level fixes. */
        val dualFrequency: Boolean = false,
        /** Ways to choose from before starting ([route] is one of them); empty while driving. */
        val routes: List<NavRoute> = emptyList(),
        /** Parking closest to where we arrived (cars), to drive on to it. */
        val parkingNearby: Place? = null,
        /** Counts position updates (real or estimated), so the map can tell a new fix from other changes. */
        val fixId: Long = 0,
        /** No GPS for a few seconds (a tunnel): the position is being estimated along the route. */
        val gpsLost: Boolean = false,
        /** Inside an average speed check: the zone, your average so far and how far it still goes. */
        val averageCheck: AverageSpeedCheck.Status? = null,
    ) {
        val navigating get() = status == Status.NAVIGATING

        /** Lane arrows before [step]: from the router, else from OpenStreetMap's turn:lanes. */
        fun lanesFor(step: NavStep?): List<Lane> {
            if (step == null) return emptyList()
            if (step.lanes.isNotEmpty()) return step.lanes
            val index = route?.steps?.indexOf(step) ?: return emptyList()
            return roadInfo?.stepLanes?.get(index).orEmpty()
        }
    }

    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private var clients = 0
    private var listening = false
    private var routingJob: Job? = null
    private var roadInfoJob: Job? = null
    private var tracker: NavTracker? = null
    private var lastFix: Location? = null
    private var lastFixEstimated = false
    /** When the last real GPS fix came in (uptime ms; 0 = none yet this drive). */
    private var lastRealFixAt = 0L
    private val deadReckoning = DeadReckoning()
    /**
     * Spots GPS glitches (a fix bouncing off a building) so they don't reach the route tracking. The fixes themselves
     * go on as the GPS gave them: the car is smoothed on screen, and smoothing here would only add lag at turns.
     */
    private var kalman = LocationKalman()
    /** Dead reckoning ran out (too long or too far): wait for the GPS. */
    private var deadReckoningDone = false
    private val handler = android.os.Handler(Looper.getMainLooper())
    private val gpsWatch = object : Runnable {
        override fun run() {
            checkGps()
            if (state.value.navigating) handler.postDelayed(this, 1000L)
        }
    }
    private val offRoute = OffRouteDetector()
    private val averageCheck = AverageSpeedCheck()
    private var lastReroute = 0L
    /** Stops already passed when the current route was made (it only contains the ones after). */
    private var routeStopBase = 0
    private val spoken = HashSet<String>()
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var beeper: ToneGenerator? = null
    private var roadInfoFailedAt = 0L
    /** The last posted limit seen, kept on screen a little while over unmapped bits of the same road. */
    private var heldLimit = 0
    private var heldLimitAlong = 0.0
    private val voice by lazy { VoiceOutput(app) }
    private val laneTracker = LaneTracker()
    private var laneSensors: LaneSensors? = null
    /** Index of the step we last drove past (−2: a new route just came in, don't count its start as a turn). */
    private var lastStepIndex = -1
    private val gnssCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var dual = false
            for (i in 0 until status.satelliteCount) {
                if (status.usedInFix(i) && status.hasCarrierFrequencyHz(i) && status.getCarrierFrequencyHz(i) in 1.16e9..1.19e9) dual = true
            }
            if (dual != state.value.dualFrequency) {
                _state.update { it.copy(dualFrequency = dual) }
                if (dual) Prefs(app).seenDualFrequency = true
            }
        }
    }

    private val locationManager get() = app.getSystemService(LocationManager::class.java)
    private val fused by lazy { LocationServices.getFusedLocationProviderClient(app) }
    private val gpsListener = LocationListener { onLocation(it) }
    private val fusedCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { onLocation(it) }
        }
    }

    fun init(context: Context) {
        app = context.applicationContext
        val prefs = Prefs(app)
        _state.update { it.copy(muted = prefs.navMuted, real = prefs.lastRealLocation, avoid = Avoid(prefs.avoidTolls, prefs.avoidHighways)) }
    }

    // region Who is watching

    /** A screen (phone or car) showing navigation: keep the real GPS running. */
    fun acquire() {
        clients++
        startListening()
    }

    fun release() {
        clients = (clients - 1).coerceAtLeast(0)
        if (clients == 0 && !state.value.navigating) stopListening()
    }

    // endregion

    // region Commands

    fun setDestination(place: Place) {
        _state.update { it.copy(to = place) }
        plan()
    }

    fun setStart(place: Place?) {
        _state.update { it.copy(from = place) }
        plan()
    }

    fun setMode(mode: TravelMode) {
        if (mode == state.value.mode) return
        _state.update { it.copy(mode = mode) }
        plan()
    }

    fun swap() {
        val s = state.value
        val newTo = s.from ?: s.real?.let { Place(it, app.getString(R.string.nav_my_location)) } ?: return
        _state.update { it.copy(from = s.to, to = newTo, stops = s.stops.reversed()) }
        plan()
    }

    /** Adds a place to pass on the way; while navigating it becomes the next stop. */
    fun addStop(place: Place) {
        _state.update { s ->
            if (s.to == null) return@update s.copy(to = place)
            if (s.navigating) {
                val list = s.stops.toMutableList().apply { add(s.stopsReached, place) }
                s.copy(stops = list, from = null)
            } else s.copy(stops = s.stops + place)
        }
        plan()
    }

    fun removeStop(index: Int) {
        val s = state.value
        if (index !in s.stops.indices || (s.navigating && index < s.stopsReached)) return
        _state.update { it.copy(stops = it.stops.filterIndexed { i, _ -> i != index }, from = if (it.navigating) null else it.from) }
        plan()
    }

    fun setAvoid(avoid: Avoid) {
        if (avoid == state.value.avoid) return
        Prefs(app).avoidTolls = avoid.tolls
        Prefs(app).avoidHighways = avoid.highways
        _state.update { it.copy(avoid = avoid) }
        plan()
    }

    fun plan() {
        val s = state.value
        val target = s.to ?: return
        val start = s.from?.position ?: s.real
        if (start == null) {
            _state.update { it.copy(status = Status.ROUTING, route = null) } // waits for the first GPS fix
            return
        }
        routingJob?.cancel()
        _state.update { it.copy(status = if (it.navigating) Status.NAVIGATING else Status.ROUTING, rerouting = it.navigating) }
        val base = if (s.navigating) s.stopsReached else 0
        val via = s.stops.drop(base).map { it.position }
        routingJob = scope.launch {
            // Before starting there's a choice of ways; while driving, just the best one from here.
            runCatching { NavRouter.routes(app, start, target.position, s.mode, via, s.avoid, alternatives = !s.navigating) }
                .onSuccess { routeStopBase = base; setRoute(it.first(), it) }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    Log.w(TAG, "route failed", e)
                    _state.update { st -> st.copy(status = if (st.navigating) Status.NAVIGATING else Status.ERROR, rerouting = false) }
                }
        }
    }

    /** Picks one of the ways shown before starting. */
    fun chooseRoute(index: Int) {
        val s = state.value
        val chosen = s.routes.getOrNull(index) ?: return
        if (s.navigating || chosen === s.route) return
        setRoute(chosen, s.routes)
    }

    fun start() {
        if (state.value.route == null) return
        routeStopBase = 0
        laneTracker.reset()
        lastStepIndex = -1
        kalman = LocationKalman(if (state.value.mode == TravelMode.CAR) LocationKalman.CAR_ACCELERATION else LocationKalman.WALK_ACCELERATION)
        drive = DriveRecorder(System.currentTimeMillis())
        _state.update { it.copy(status = Status.NAVIGATING, stopsReached = 0, routes = emptyList(), parkingNearby = null) }
        startListening()
        ContextCompat.startForegroundService(app, NavigationService.intent(app))
        speak(state.value.route?.steps?.firstOrNull()?.spoken.orEmpty())
        lastRealFixAt = if (lastFix != null) SystemClock.uptimeMillis() else 0L
        deadReckoning.stop()
        deadReckoningDone = false
        handler.removeCallbacks(gpsWatch)
        handler.postDelayed(gpsWatch, 1000L)
        lastFix?.let { handle(it, lastFixEstimated) }
    }

    fun stop() {
        if (!state.value.navigating) return
        recordDrive()
        handler.removeCallbacks(gpsWatch)
        deadReckoning.stop()
        _state.update { it.copy(status = if (it.route != null) Status.PREVIEW else Status.IDLE, progress = null, rerouting = false, parkingNearby = null, gpsLost = false) }
        app.stopService(NavigationService.intent(app))
        tts?.stop()
        if (clients == 0) stopListening()
    }

    /** Forget the destination (e.g. leaving the screen without navigating). */
    fun clear() {
        if (state.value.navigating) return
        routingJob?.cancel()
        roadInfoJob?.cancel()
        tracker = null
        _state.update { State(muted = it.muted, real = it.real, mode = it.mode, avoid = it.avoid) }
    }

    fun toggleMute() {
        val muted = !state.value.muted
        Prefs(app).navMuted = muted
        if (muted) tts?.stop()
        _state.update { it.copy(muted = muted) }
    }

    // endregion

    private fun setRoute(route: NavRoute, options: List<NavRoute> = emptyList()) {
        tracker = NavTracker(route)
        spoken.clear()
        offRoute.reset()
        averageCheck.reset()
        val car = state.value.mode == TravelMode.CAR
        val prefs = Prefs(app)
        _state.update {
            it.copy(route = route, roadInfo = null, progress = null, rerouting = false,
                status = if (it.navigating) Status.NAVIGATING else Status.PREVIEW,
                tolls = if (car) tollsOn(route, Tolls.GATES) else emptyList(),
                fuelCostAed = if (car) Fuel.cost(route.distanceMeters, prefs.fuelConsumption, prefs.fuelPrice()) else 0.0,
                routes = if (it.navigating) emptyList() else options)
        }
        roadInfoJob?.cancel()
        roadInfoFailedAt = 0L
        heldLimitAlong = 0.0
        if (state.value.navigating) lastStepIndex = -2
        fetchRoadInfo(route, 0.0)
        deadReckoning.stop()
        lastFix?.let { handle(it, lastFixEstimated) }
    }

    /** Speed limits, cameras and lanes for the next stretch of the route (20 km at a time, ahead of the car). */
    private fun fetchRoadInfo(route: NavRoute, from: Double) {
        if (roadInfoJob?.isActive == true) return
        val car = state.value.mode == TravelMode.CAR
        roadInfoJob = scope.launch {
            val info = runCatching { RoadInfoFetcher.fetch(route, from, from + RoadInfoFetcher.CHUNK_METERS) }
                .onFailure { Log.w(TAG, "speed limits / lanes didn't load", it) }
                .getOrNull()
            if (state.value.route !== route) return@launch
            if (info == null) {
                roadInfoFailedAt = System.currentTimeMillis()
                return@launch
            }
            _state.update {
                val merged = it.roadInfo?.merge(info) ?: info
                val extra = Tolls.extraGates(merged.gantries)
                it.copy(roadInfo = merged, tolls = if (car && extra.isNotEmpty()) tollsOn(route, Tolls.GATES + extra) else it.tolls)
            }
        }
    }

    private fun fetchMoreRoadInfo(route: NavRoute, along: Double) {
        val info = state.value.roadInfo
        val covered = info?.coveredTo ?: 0.0
        if (covered >= route.length || along < covered - 6000) return
        if (System.currentTimeMillis() - roadInfoFailedAt < 20_000) return
        fetchRoadInfo(route, if (info == null) (along - 500).coerceAtLeast(0.0) else covered)
    }

    /** Salik / Darb and fuel for one of the ways offered before starting (cars), for the choice list. */
    fun costOf(route: NavRoute): Pair<Double, Double> {
        if (state.value.mode != TravelMode.CAR) return 0.0 to 0.0
        val prefs = Prefs(app)
        val tolls = tollsOn(route, Tolls.GATES).sumOf { it.priceAed }
        return tolls to Fuel.cost(route.distanceMeters, prefs.fuelConsumption, prefs.fuelPrice())
    }

    // region Drive log

    /** What one navigation has driven so far: real GPS distance, gates passed. */
    private class DriveRecorder(val startedAt: Long) {
        var meters = 0.0
        var last: LatLon? = null
        val gates = HashSet<String>()
        var tollsAed = 0.0
        var recorded = false
    }

    private var drive: DriveRecorder? = null

    private fun trackDrive(location: Location, here: LatLon, progress: NavProgress?, s: State) {
        val d = drive ?: return
        if (location.hasAccuracy() && location.accuracy > 30f) return
        d.last?.let { prev ->
            val step = distanceMeters(prev, here)
            if (step < 300) d.meters += step // a bigger jump is the GPS catching up, not driving
        }
        d.last = here
        if (progress != null) {
            for (pass in s.tolls) {
                if (pass.alongMeters <= progress.alongMeters && d.gates.add("${pass.gate.name}:${pass.alongMeters.roundToInt()}")) d.tollsAed += pass.priceAed
            }
        }
    }

    /** Navigation ended (arrived, or stopped on the way): add it to the drive log once, if it went anywhere. */
    private fun recordDrive() {
        val d = drive ?: return
        if (d.recorded || d.meters < DriveLog.MIN_METERS) return
        d.recorded = true
        val s = state.value
        val prefs = Prefs(app)
        val car = s.mode == TravelMode.CAR
        DriveLog.get(app).add(
            Drive(
                id = System.nanoTime(), startedAt = d.startedAt, endedAt = System.currentTimeMillis(),
                destination = s.to?.name ?: app.getString(R.string.nav_choose_destination), mode = s.mode, meters = d.meters,
                tollsAed = d.tollsAed, tollGates = d.gates.size,
                fuelAed = if (car) Fuel.cost(d.meters, prefs.fuelConsumption, prefs.fuelPrice()) else 0.0,
            )
        )
    }

    // endregion

    /** Arrived by car: look up the nearest parking once, to offer driving on to it. */
    private fun findParking(at: LatLon) {
        scope.launch {
            val nearest = runCatching { NearbySearch.find(app, PlaceCategory.PARKING, at) }
                .onFailure { Log.w(TAG, "no parking nearby", it) }
                .getOrNull()
                ?.firstOrNull { distanceMeters(it.position, at) > 40 } // not the place we're already at
            if (state.value.progress?.arrived == true) _state.update { it.copy(parkingNearby = nearest) }
        }
    }

    private fun tollsOn(route: NavRoute, gates: List<TollGate>): List<TollPass> = Tolls.passes(
        gates, System.currentTimeMillis(),
        alongOf = { RoadInfoFetcher.alongOf(route, it, 25.0) },
        secondsTo = { along -> if (route.length > 0) route.durationSeconds * along / route.length else 0.0 },
    )

    // region Real location

    @SuppressLint("MissingPermission")
    private fun startListening() {
        if (listening) return
        if (ContextCompat.checkSelfPermission(app, android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        listening = true
        MockLocationService.setRealGpsForNavigation(app, true)
        if (tts == null) {
            tts = TextToSpeech(app) { status ->
                ttsReady = status == TextToSpeech.SUCCESS
                if (ttsReady) setUpVoice()
            }
        }
        if (beeper == null) beeper = runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90) }.getOrNull()
        if (Prefs(app).laneGuidance) {
            laneSensors = LaneSensors(app) { t, rate -> laneTracker.onYawRate(t, rate) }.also { it.start() }
        }
        runCatching { locationManager.registerGnssStatusCallback(gnssCallback, android.os.Handler(Looper.getMainLooper())) }
        // The GPS chip itself: real even while a fake location runs for other apps.
        runCatching { locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, gpsListener, Looper.getMainLooper()) }
            .onFailure { Log.w(TAG, "real GPS didn't start", it) }
        // Without a fake location, Google's location service is real too and gets a first fix faster.
        if (!MockState.isFaking) {
            fused.requestLocationUpdates(LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L).build(), fusedCallback, Looper.getMainLooper())
        }
    }

    private fun stopListening() {
        if (!listening) return
        listening = false
        runCatching { locationManager.removeUpdates(gpsListener) }
        runCatching { locationManager.unregisterGnssStatusCallback(gnssCallback) }
        laneSensors?.stop()
        laneSensors = null
        fused.removeLocationUpdates(fusedCallback)
        MockLocationService.setRealGpsForNavigation(app, false)
    }

    private fun onLocation(location: Location) {
        if (location.isMockCompat()) return
        val now = SystemClock.uptimeMillis()
        lastRealFixAt = now
        deadReckoning.stop()
        deadReckoningDone = false
        val smoothed = kalman.update(
            LatLon(location.latitude, location.longitude),
            if (location.hasAccuracy()) location.accuracy.toDouble() else 15.0,
            now,
            if (location.hasSpeed()) location.speed.toDouble() else null,
            if (location.hasBearing() && location.hasSpeed()) location.bearing else null,
        )
        if (smoothed.rejected) {
            Log.d(TAG, "GPS glitch ignored (${location.accuracy} m)")
            return
        }
        handle(location, estimated = false)
    }

    /**
     * Once a second while driving: when the GPS has gone quiet (a tunnel), carry on along the route at the speed we
     * had, so the car, the distances and the voice keep going until the signal is back.
     */
    private fun checkGps() {
        val s = state.value
        if (!s.navigating || lastRealFixAt == 0L) return
        val now = SystemClock.uptimeMillis()
        if (now - lastRealFixAt < DeadReckoning.AFTER_MS) return
        val route = s.route
        if (!deadReckoning.active && !deadReckoningDone) {
            val p = s.progress
            if (route != null && p != null && p.offRouteMeters < 30 && !p.arrived && s.toStartMeters == 0.0) {
                deadReckoning.start(p.alongMeters, s.speedKmh / 3.6, lastRealFixAt)
                estimatedRoute = route
            }
            if (!deadReckoning.active) deadReckoningDone = true
        }
        val along = if (route != null && route === estimatedRoute) deadReckoning.alongAt(now) else null
        if (along == null || route == null) {
            deadReckoning.stop()
            deadReckoningDone = true
            if (!s.gpsLost && now - lastRealFixAt > 5000) _state.update { it.copy(gpsLost = true) }
            return
        }
        val at = route.pointAt(along)
        val estimate = Location(LocationManager.GPS_PROVIDER).apply {
            latitude = at.lat
            longitude = at.lon
            speed = deadReckoning.speedAt(now).toFloat()
            bearing = TripTimeline.bearing(route.pointAt(along - 5), route.pointAt(along + 15))
            accuracy = 30f
            time = System.currentTimeMillis()
        }
        handle(estimate, estimated = true)
    }

    private var estimatedRoute: NavRoute? = null

    /** A position to navigate from: a real fix, or one estimated along the route while the GPS is lost. */
    private fun handle(location: Location, estimated: Boolean) {
        val here = LatLon(location.latitude, location.longitude)
        if (!estimated) Prefs(app).lastRealLocation = here
        val speed = speedKmh(location)
        lastFix = location
        lastFixEstimated = estimated
        val s = state.value
        if (s.real == null && s.route == null && s.to != null) {
            _state.update { it.copy(real = here) }
            plan()
        }
        val route = s.route
        val progress = tracker?.update(here)
        val bearing = when {
            location.hasBearing() && location.speed > 1.5f -> location.bearing
            route != null && progress != null -> TripTimeline.bearing(route.points[progress.segment], route.points[progress.segment + 1])
            else -> s.bearing
        }
        val limit = if (progress != null) heldLimit(s.roadInfo?.limitAt(progress.segment) ?: 0, progress.alongMeters) else 0
        if (route != null && progress != null) fetchMoreRoadInfo(route, progress.alongMeters)
        val over = limit > 0 && speed > limit + 5
        if (over && !s.speeding && s.navigating && !s.muted) beeper?.startTone(ToneGenerator.TONE_PROP_BEEP2, 400)
        val camera = progress?.let { p -> s.roadInfo?.nextCamera(p.alongMeters)?.takeIf { it in 0.0..600.0 && s.mode == TravelMode.CAR } }
        val toStart = if (route != null && s.from != null && (progress?.alongMeters ?: 0.0) < 1) distanceMeters(here, route.points.first()) else 0.0
        val toll = progress?.let { p -> s.tolls.firstOrNull { it.alongMeters > p.alongMeters - 10 } }
        val tollMeters = toll?.let { it.alongMeters - progress.alongMeters }?.takeIf { it < 1500 }
        val average = if (progress != null && s.mode == TravelMode.CAR) {
            averageCheck.update(s.roadInfo?.averageZones.orEmpty(), progress.alongMeters, System.currentTimeMillis())
        } else null
        val reached = if (route != null && progress != null && s.navigating) {
            routeStopBase + route.stopsAlong.count { it <= progress.alongMeters + 30 }
        } else s.stopsReached
        // Estimated positions don't say anything about lanes: keep what was shown.
        if (!estimated) {
            val lanes = if (s.navigating && route != null && progress != null && s.mode == TravelMode.CAR && Prefs(app).laneGuidance) {
                trackLane(location, route, progress, s)
            } else null
            _state.update {
                it.copy(laneCount = lanes?.first ?: 0, lane = lanes?.second ?: 0, laneAdvice = lanes?.third,
                    laneSource = if (lanes != null) laneTracker.source else null)
            }
        }
        _state.update {
            it.copy(real = here, bearing = bearing, speedKmh = speed, limitKmh = limit, progress = progress,
                fixId = it.fixId + 1, gpsLost = estimated, averageCheck = average,
                speeding = if (over) true else if (speed <= limit || limit == 0) false else it.speeding,
                cameraMeters = camera, toStartMeters = if (toStart > NavTracker.OFF_ROUTE_METERS) toStart else 0.0,
                nextToll = toll?.takeIf { tollMeters != null }, tollMeters = tollMeters, stopsReached = reached)
        }
        if (s.navigating) trackDrive(location, here, progress, s)
        if (s.navigating && route != null && progress != null) {
            // Estimated positions are on the route by definition; a route that starts elsewhere isn't "off" yet.
            val off = !estimated && state.value.toStartMeters == 0.0 && offRoute.update(
                progress.offRouteMeters,
                if (location.hasAccuracy()) location.accuracy else null,
                speed.toDouble(),
                if (location.hasBearing() && location.speed > 1.5f) location.bearing else null,
                TripTimeline.bearing(route.points[progress.segment], route.points[progress.segment + 1]),
            )
            guide(route, progress, camera, limit, off)
        }
    }

    private fun guide(route: NavRoute, p: NavProgress, camera: Double?, limit: Int, off: Boolean) {
        val s = state.value
        // Off the route (see OffRouteDetector): find a new way from here.
        if (off && System.currentTimeMillis() - lastReroute > OffRouteDetector.REROUTE_GAP_MS) {
            lastReroute = System.currentTimeMillis()
            _state.update { it.copy(from = null) } // from now on, routes start where you are
            plan()
            return
        }
        state.value.averageCheck?.takeIf { it.entered }?.let { check ->
            if (spoken.add("average:${check.zone.fromAlong.roundToInt()}")) {
                speak(app.getString(R.string.nav_average_voice, spokenDistance(check.zone.length)) +
                    if (check.zone.limitKmh > 0) " " + app.getString(R.string.nav_speed_limit_voice, check.zone.limitKmh) + "." else "")
            }
        }
        if (camera != null && camera < 400 && spoken.add("camera:${(p.alongMeters + camera).roundToInt() / 50}")) {
            speak(app.getString(R.string.nav_camera_ahead) + if (limit > 0) ". " + app.getString(R.string.nav_speed_limit_voice, limit) else "")
        }
        val toll = state.value.nextToll
        val tollMeters = state.value.tollMeters
        if (toll != null && tollMeters != null && tollMeters < 600 && spoken.add("toll:${toll.gate.name}:${toll.alongMeters.roundToInt() / 100}")) {
            speak(
                if (toll.priceAed > 0) app.getString(R.string.toll_voice, toll.gate.system.label, toll.priceAed.roundToInt())
                else app.getString(R.string.toll_voice_free, toll.gate.system.label)
            )
        }
        if (p.arrived) {
            if (spoken.add("arrived")) {
                speak(app.getString(R.string.nav_arrived))
                recordDrive()
                if (s.mode == TravelMode.CAR) s.to?.let { findParking(it.position) }
            }
            return
        }
        val step = p.nextStep ?: return
        val index = route.steps.indexOf(step)
        val d = p.metersToNextStep
        val car = s.mode == TravelMode.CAR
        // Announce by time, not fixed distances: earlier on fast roads (like Waze / Yango).
        val mps = (s.speedKmh / 3.6).coerceAtLeast(if (car) 8.0 else 1.2)
        val early = if (car) (mps * 35).coerceIn(500.0, 1500.0) else (mps * 60).coerceIn(80.0, 150.0)
        val prepare = if (car) (mps * 12).coerceIn(150.0, 450.0) else 35.0
        val now = if (car) (mps * 3.5).coerceIn(30.0, 90.0) else 10.0
        val previousAt = route.steps.getOrNull(index - 1)?.let { route.along[it.pointIndex] } ?: 0.0
        val sinceTurn = p.alongMeters - previousAt
        val then = p.thenStep?.takeIf { route.along[it.pointIndex] - route.along[step.pointIndex] < 120 && it.maneuver != Maneuver.DEPART }
        val thenText = then?.let { app.getString(R.string.nav_voice_then, it.spoken.lowercase(Locale.US)) }.orEmpty()
        val what = step.spoken.lowercase(Locale.US)
        val advice = state.value.laneAdvice
        if (advice != null && advice != 0 && d in now..early && sinceTurn > 30 && spoken.add("$index:lane")) {
            val n = kotlin.math.abs(advice)
            speak(app.getString(if (advice > 0) R.string.nav_voice_lanes_right else R.string.nav_voice_lanes_left,
                if (n == 1) "one lane" else "$n lanes"), queue = true)
        }
        when {
            d <= now && spoken.add("$index:now") ->
                speak(step.spoken + thenText)
            d in now..prepare && spoken.add("$index:near") ->
                speak(app.getString(R.string.nav_in, spokenDistance(d), what) + thenText)
            d in prepare..early && sinceTurn > 60 && spoken.add("$index:far") ->
                speak(app.getString(R.string.nav_in, spokenDistance(d), what))
            d > 2500 && sinceTurn in 30.0..400.0 && spoken.add("$index:continue") ->
                speak(app.getString(R.string.nav_voice_continue, spokenDistance(d)))
        }
    }

    /** Feeds the lane tracker with this fix; returns lane count, our lane and the advice for the next turn. */
    private fun trackLane(location: Location, route: NavRoute, p: NavProgress, s: State): Triple<Int, Int, Int?> {
        val road = TripTimeline.bearing(route.pointAt(p.alongMeters), route.pointAt(p.alongMeters + 20)).toDouble()
        val stepLanes = s.lanesFor(p.nextStep).takeIf { p.metersToNextStep < 600 }.orEmpty()
        val mapped = s.roadInfo?.laneCountAt(p.segment) ?: 0
        val count = if (stepLanes.size >= 2) stepLanes.size else mapped
        // Drove past a turn: you're on that side's edge lane now.
        val current = p.currentStep?.let { route.steps.indexOf(it) } ?: -1
        if (current != lastStepIndex) {
            if (lastStepIndex != -2 && current >= 0) laneTracker.onTurn(route.steps[current].maneuver)
            lastStepIndex = current
        }
        val mps = if (location.hasSpeed()) location.speed.toDouble() else speedKmh(location) / 3.6
        val gpsBearing = if (location.hasBearing() && location.speed > 1.5f) location.bearing.toDouble() else null
        laneTracker.onFix(System.currentTimeMillis(), mps, gpsBearing, road, count)
        // A metre-accurate fix (dual-frequency GPS) on a divided road says roughly which lane we're in.
        if (s.dualFrequency && location.hasAccuracy() && location.accuracy <= 2.0f && count >= 2 && p.offRouteMeters < 8) {
            val a = route.points[p.segment]
            val b = route.points[p.segment + 1]
            val cross = (b.lon - a.lon) * (location.latitude - a.lat) - (b.lat - a.lat) * (location.longitude - a.lon)
            val offset = if (cross > 0) -p.offRouteMeters else p.offRouteMeters // + = right of the line
            laneTracker.onPositionHint(((offset + count * 1.75) / 3.5).toInt().coerceIn(0, count - 1) + 1)
        }
        val lane = laneTracker.lane
        val targets = stepLanes.withIndex().filter { it.value.valid }.map { it.index + 1 }
        val advice = when {
            lane == 0 || targets.isEmpty() || laneTracker.count != stepLanes.size -> null
            lane in targets -> 0
            else -> targets.minByOrNull { kotlin.math.abs(it - lane) }!! - lane
        }
        return Triple(laneTracker.count, lane, advice)
    }

    /** What the lane camera saw (from the navigation screen). */
    fun onCameraEvent(event: LaneDetector.Event) {
        when (event) {
            is LaneDetector.Event.LaneChange -> laneTracker.onCameraLaneChange(System.currentTimeMillis(), event.direction)
            LaneDetector.Event.LeftEdge -> laneTracker.onLeftEdgeSeen()
        }
        _state.update { it.copy(lane = laneTracker.lane, laneSource = laneTracker.source) }
    }

    private fun heldLimit(mapped: Int, along: Double): Int {
        if (mapped > 0) {
            heldLimit = mapped
            heldLimitAlong = along
            return mapped
        }
        return if (heldLimit > 0 && along - heldLimitAlong in -50.0..700.0) heldLimit else 0
    }

    private fun speedKmh(location: Location): Int {
        if (location.hasSpeed()) return (location.speed * 3.6f).roundToInt()
        val previous = lastFix ?: return 0
        val seconds = (location.time - previous.time) / 1000.0
        if (seconds <= 0) return 0
        return (location.distanceTo(previous) / seconds * 3.6).roundToInt()
    }

    /** "400 meters", "1 kilometer", "1.5 kilometers": round numbers, as people say them. */
    private fun spokenDistance(meters: Double): String = when {
        meters < 100 -> "${((meters / 10).roundToInt() * 10).coerceAtLeast(10)} meters"
        meters < 1000 -> "${((meters / 50).roundToInt() * 50).coerceAtMost(950)} meters"
        meters < 1250 -> "1 kilometer"
        meters < 10_000 -> String.format(Locale.US, "%.1f kilometers", (meters / 500).roundToInt() / 2.0).replace(".0 ", " ")
        else -> "${(meters / 1000).roundToInt()} kilometers"
    }

    /** A clear offline English voice on the navigation audio channel. */
    private fun setUpVoice() {
        val engine = tts ?: return
        engine.setAudioAttributes(voice.attributes())
        val prefs = Prefs(app)
        val voices = englishVoices(engine)
        val chosen = voices.firstOrNull { it.name == prefs.navVoice } ?: voices.firstOrNull()
        if (chosen != null) engine.voice = chosen else engine.language = Locale.US
        engine.setSpeechRate(prefs.navSpeechRate)
        engine.setPitch(1.0f)
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = voiceDone()
            override fun onStop(utteranceId: String?, interrupted: Boolean) = voiceDone()
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = voiceDone()
        })
    }

    /** Installed English voices that work offline, best first. */
    private fun englishVoices(engine: TextToSpeech): List<android.speech.tts.Voice> =
        runCatching { engine.voices }.getOrNull().orEmpty()
            .filter { it.locale.language == "en" && !it.isNetworkConnectionRequired && "notinstalled" !in it.features.orEmpty() }
            .sortedByDescending { v -> v.quality * 10 + (if (v.locale.country == "US") 3 else if (v.locale.country == "GB") 2 else 0) - v.latency / 100 }

    /** For Settings: the voices to choose from (name, label). Empty until the voice engine has started. */
    fun voiceChoices(): List<Pair<String, String>> {
        val engine = tts
        if (engine == null) {
            tts = TextToSpeech(app) { status -> ttsReady = status == TextToSpeech.SUCCESS; if (ttsReady) setUpVoice() }
            return emptyList()
        }
        if (!ttsReady) return emptyList()
        return englishVoices(engine).mapIndexed { i, v ->
            val country = v.locale.displayCountry.ifBlank { v.locale.country }
            v.name to "Voice ${i + 1} · $country" + if (v.quality >= android.speech.tts.Voice.QUALITY_HIGH) " · HD" else ""
        }
    }

    /** Applies the voice settings and says a sample sentence. */
    fun previewVoice() {
        if (!ttsReady) return
        setUpVoice()
        val wasMuted = state.value.muted
        if (wasMuted) return
        speak("In 400 meters, turn right. Then keep left.")
    }

    private fun speak(text: String, queue: Boolean = false) {
        if (text.isBlank() || state.value.muted || !ttsReady) return
        // Music paused or lowered, volume up and the voice boosted for the sentence (see VoiceOutput).
        voice.begin {
            val params = android.os.Bundle().apply {
                putInt(TextToSpeech.Engine.KEY_PARAM_SESSION_ID, voice.sessionId)
                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1f)
            }
            val result = tts?.speak(text, if (queue) TextToSpeech.QUEUE_ADD else TextToSpeech.QUEUE_FLUSH, params, text + System.nanoTime())
            if (result != TextToSpeech.SUCCESS) voice.end()
        }
    }

    /** Callbacks come from the voice engine's thread. */
    private fun voiceDone() {
        android.os.Handler(Looper.getMainLooper()).post { voice.end() }
    }

    // endregion
}

private fun Location.isMockCompat(): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) isMock else @Suppress("DEPRECATION") isFromMockProvider
