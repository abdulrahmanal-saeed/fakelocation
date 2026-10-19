package com.abdulrahman.fakelocation.nav

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Looper
import android.speech.tts.TextToSpeech
import androidx.core.content.ContextCompat
import com.abdulrahman.fakelocation.R
import com.abdulrahman.fakelocation.data.LatLon
import com.abdulrahman.fakelocation.data.Place
import com.abdulrahman.fakelocation.data.Prefs
import com.abdulrahman.fakelocation.data.TravelMode
import com.abdulrahman.fakelocation.data.distanceMeters
import com.abdulrahman.fakelocation.drive.Fuel
import com.abdulrahman.fakelocation.drive.TollGate
import com.abdulrahman.fakelocation.drive.TollPass
import com.abdulrahman.fakelocation.drive.Tolls
import com.abdulrahman.fakelocation.mock.MockLocationService
import com.abdulrahman.fakelocation.mock.MockState
import com.abdulrahman.fakelocation.trip.TripTimeline
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
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
    ) {
        val navigating get() = status == Status.NAVIGATING
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
    private var offRouteCount = 0
    private var lastReroute = 0L
    /** Stops already passed when the current route was made (it only contains the ones after). */
    private var routeStopBase = 0
    private val spoken = HashSet<String>()
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var beeper: ToneGenerator? = null

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
            runCatching { NavRouter.route(app, start, target.position, s.mode, via, s.avoid) }
                .onSuccess { routeStopBase = base; setRoute(it) }
                .onFailure { _state.update { st -> st.copy(status = if (st.navigating) Status.NAVIGATING else Status.ERROR, rerouting = false) } }
        }
    }

    fun start() {
        if (state.value.route == null) return
        routeStopBase = 0
        _state.update { it.copy(status = Status.NAVIGATING, stopsReached = 0) }
        startListening()
        ContextCompat.startForegroundService(app, NavigationService.intent(app))
        speak(state.value.route?.steps?.firstOrNull()?.spoken.orEmpty())
        lastFix?.let { onLocation(it) }
    }

    fun stop() {
        if (!state.value.navigating) return
        _state.update { it.copy(status = if (it.route != null) Status.PREVIEW else Status.IDLE, progress = null, rerouting = false) }
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

    private fun setRoute(route: NavRoute) {
        tracker = NavTracker(route)
        spoken.clear()
        offRouteCount = 0
        val car = state.value.mode == TravelMode.CAR
        val prefs = Prefs(app)
        _state.update {
            it.copy(route = route, roadInfo = null, progress = null, rerouting = false,
                status = if (it.navigating) Status.NAVIGATING else Status.PREVIEW,
                tolls = if (car) tollsOn(route, Tolls.GATES) else emptyList(),
                fuelCostAed = if (car) Fuel.cost(route.distanceMeters, prefs.fuelConsumption, prefs.fuelPrice()) else 0.0)
        }
        roadInfoJob?.cancel()
        roadInfoJob = scope.launch {
            val info = runCatching { RoadInfoFetcher.fetch(route) }.getOrNull() ?: return@launch
            if (state.value.route !== route) return@launch
            val extra = Tolls.extraGates(info.gantries)
            _state.update { it.copy(roadInfo = info, tolls = if (car && extra.isNotEmpty()) tollsOn(route, Tolls.GATES + extra) else it.tolls) }
        }
        lastFix?.let { onLocation(it) }
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
                if (ttsReady) tts?.language = Locale.US
            }
        }
        if (beeper == null) beeper = runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90) }.getOrNull()
        // The GPS chip itself: real even while a fake location runs for other apps.
        runCatching { locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, gpsListener, Looper.getMainLooper()) }
        // Without a fake location, Google's location service is real too and gets a first fix faster.
        if (!MockState.isFaking) {
            fused.requestLocationUpdates(LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L).build(), fusedCallback, Looper.getMainLooper())
        }
    }

    private fun stopListening() {
        if (!listening) return
        listening = false
        runCatching { locationManager.removeUpdates(gpsListener) }
        fused.removeLocationUpdates(fusedCallback)
        MockLocationService.setRealGpsForNavigation(app, false)
    }

    private fun onLocation(location: Location) {
        if (location.isMockCompat()) return
        val here = LatLon(location.latitude, location.longitude)
        Prefs(app).lastRealLocation = here
        val speed = speedKmh(location)
        lastFix = location
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
        val limit = if (progress != null) s.roadInfo?.limitAt(progress.segment) ?: 0 else 0
        val over = limit > 0 && speed > limit + 5
        if (over && !s.speeding && s.navigating && !s.muted) beeper?.startTone(ToneGenerator.TONE_PROP_BEEP2, 400)
        val camera = progress?.let { p -> s.roadInfo?.nextCamera(p.alongMeters)?.takeIf { it in 0.0..600.0 && s.mode == TravelMode.CAR } }
        val toStart = if (route != null && s.from != null && (progress?.alongMeters ?: 0.0) < 1) distanceMeters(here, route.points.first()) else 0.0
        val toll = progress?.let { p -> s.tolls.firstOrNull { it.alongMeters > p.alongMeters - 10 } }
        val tollMeters = toll?.let { it.alongMeters - progress.alongMeters }?.takeIf { it < 1500 }
        val reached = if (route != null && progress != null && s.navigating) {
            routeStopBase + route.stopsAlong.count { it <= progress.alongMeters + 30 }
        } else s.stopsReached
        _state.update {
            it.copy(real = here, bearing = bearing, speedKmh = speed, limitKmh = limit, progress = progress,
                speeding = if (over) true else if (speed <= limit || limit == 0) false else it.speeding,
                cameraMeters = camera, toStartMeters = if (toStart > NavTracker.OFF_ROUTE_METERS) toStart else 0.0,
                nextToll = toll?.takeIf { tollMeters != null }, tollMeters = tollMeters, stopsReached = reached)
        }
        if (s.navigating && route != null && progress != null) guide(route, progress, camera, limit)
    }

    private fun guide(route: NavRoute, p: NavProgress, camera: Double?, limit: Int) {
        val s = state.value
        // Off the route: wait for a few fixes (GPS jumps), then find a new way from here.
        if (p.offRouteMeters > NavTracker.OFF_ROUTE_METERS && s.toStartMeters == 0.0) offRouteCount++ else offRouteCount = 0
        if (offRouteCount >= 3 && System.currentTimeMillis() - lastReroute > 15_000) {
            lastReroute = System.currentTimeMillis()
            _state.update { it.copy(from = null) } // from now on, routes start where you are
            plan()
            return
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
            if (spoken.add("arrived")) speak(app.getString(R.string.nav_arrived))
            return
        }
        val step = p.nextStep ?: return
        val index = route.steps.indexOf(step)
        val d = p.metersToNextStep
        val car = s.mode == TravelMode.CAR
        val far = if (car) 400.0 else 120.0
        val near = if (car) 120.0 else 30.0
        val now = if (car) 35.0 else 10.0
        when {
            d <= now && spoken.add("$index:now") ->
                speak(if (step.maneuver == Maneuver.ARRIVE) step.spoken else app.getString(R.string.nav_now, step.spoken))
            d in now..near && spoken.add("$index:near") -> speak(app.getString(R.string.nav_in, spokenDistance(d), step.spoken.lowercase(Locale.US)))
            d in near..far && spoken.add("$index:far") -> speak(app.getString(R.string.nav_in, spokenDistance(d), step.spoken.lowercase(Locale.US)))
        }
    }

    private fun speedKmh(location: Location): Int {
        if (location.hasSpeed()) return (location.speed * 3.6f).roundToInt()
        val previous = lastFix ?: return 0
        val seconds = (location.time - previous.time) / 1000.0
        if (seconds <= 0) return 0
        return (location.distanceTo(previous) / seconds * 3.6).roundToInt()
    }

    private fun spokenDistance(meters: Double): String {
        val rounded = if (meters < 100) (meters / 10).roundToInt() * 10 else (meters / 50).roundToInt() * 50
        return if (rounded >= 1000) String.format(Locale.US, "%.1f kilometers", rounded / 1000.0) else "$rounded meters"
    }

    private fun speak(text: String) {
        if (text.isBlank() || state.value.muted || !ttsReady) return
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, text)
    }

    // endregion
}

private fun Location.isMockCompat(): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) isMock else @Suppress("DEPRECATION") isFromMockProvider
