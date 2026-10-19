package com.abdulrahman.fakelocation.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.media.AudioManager
import android.media.ToneGenerator
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.abdulrahman.fakelocation.R
import com.abdulrahman.fakelocation.data.LatLon
import com.abdulrahman.fakelocation.data.Place
import com.abdulrahman.fakelocation.data.Prefs
import com.abdulrahman.fakelocation.data.TravelMode
import com.abdulrahman.fakelocation.data.distanceMeters
import com.abdulrahman.fakelocation.data.formatDistance
import com.abdulrahman.fakelocation.data.formatDuration
import com.abdulrahman.fakelocation.databinding.ActivityNavigateBinding
import com.abdulrahman.fakelocation.mock.MockLocationService
import com.abdulrahman.fakelocation.mock.MockState
import com.abdulrahman.fakelocation.nav.Maneuver
import com.abdulrahman.fakelocation.nav.NavProgress
import com.abdulrahman.fakelocation.nav.NavRoute
import com.abdulrahman.fakelocation.nav.NavRouter
import com.abdulrahman.fakelocation.nav.NavTracker
import com.abdulrahman.fakelocation.nav.RoadInfo
import com.abdulrahman.fakelocation.nav.RoadInfoFetcher
import com.abdulrahman.fakelocation.trip.TripTimeline
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Real turn-by-turn navigation (like Waze) that follows the phone's REAL position, while any fake location
 * or fake trip keeps running in the background for the other apps.
 */
class NavigateActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNavigateBinding
    private lateinit var prefs: Prefs
    private val locationManager by lazy { getSystemService(LocationManager::class.java) }
    private val fusedClient by lazy { LocationServices.getFusedLocationProviderClient(this) }

    private var from: Place? = null // null = my real location
    private var to: Place? = null
    private var mode = TravelMode.CAR
    private var route: NavRoute? = null
    private var tracker: NavTracker? = null
    private var navigating = false
    private var real: Location? = null
    private var follow = true
    private var routingJob: Job? = null
    private var offRouteCount = 0
    private var lastReroute = 0L
    private val spoken = HashSet<String>()
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var arrowCache = HashMap<Int, Drawable>()
    private var carCache = HashMap<Int, Drawable>()
    private var roadInfo: RoadInfo? = null
    private var roadInfoJob: Job? = null
    private var lastFix: Location? = null
    private var speeding = false
    private val beeper by lazy { runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90) }.getOrNull() }
    private val cameraMarkers = mutableListOf<Marker>()

    private val routeCasing by lazy { line(R.color.route_green_dark, 12f) }
    private val routeLine by lazy { line(R.color.route_green, 8f) }
    private val passedLine by lazy { line(R.color.idle_dot, 7f) }
    private val me by lazy {
        Marker(binding.map).apply {
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            setInfoWindow(null)
            setOnMarkerClickListener { _, _ -> true }
        }
    }
    private val destination by lazy {
        Marker(binding.map).apply {
            icon = ContextCompat.getDrawable(this@NavigateActivity, R.drawable.marker_fake)?.mutate()?.apply { setTint(ContextCompat.getColor(this@NavigateActivity, R.color.ink)) }
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            setInfoWindow(null)
            setOnMarkerClickListener { _, _ -> true }
        }
    }

    private val pickFrom = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        SearchActivity.placeFrom(result.data)?.let { from = it; planRoute() }
    }
    private val pickTo = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        SearchActivity.placeFrom(result.data)?.let { to = it; planRoute() }
    }

    private val gpsListener = LocationListener { onRealLocation(it) }
    private val fusedCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { onRealLocation(it) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityNavigateBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)
        intent.getStringExtra(EXTRA_TO_NAME)?.let { name ->
            to = Place(LatLon(intent.getDoubleExtra(EXTRA_TO_LAT, 0.0), intent.getDoubleExtra(EXTRA_TO_LON, 0.0)), name)
        }
        binding.driveProgress.max = 1000
        applyInsets()
        setupMap()
        setupControls()
        tts = TextToSpeech(this) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) tts?.language = Locale.US
        }
        // Read the real GPS chip even while a fake location is running for other apps.
        MockLocationService.setRealGpsForNavigation(this, true)
        render()
        planRoute()
    }

    override fun onResume() {
        super.onResume()
        binding.map.onResume()
        startRealLocation()
    }

    override fun onPause() {
        binding.map.onPause()
        if (!navigating) stopRealLocation()
        super.onPause()
    }

    override fun onDestroy() {
        stopRealLocation()
        tts?.shutdown()
        beeper?.release()
        MockLocationService.setRealGpsForNavigation(this, false)
        super.onDestroy()
    }

    private fun applyInsets() {
        val top = (binding.top.layoutParams as ViewGroup.MarginLayoutParams).topMargin
        val bottom = binding.bottom.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            binding.top.updateLayoutParams<ViewGroup.MarginLayoutParams> { topMargin = top + bars.top }
            binding.bottom.updatePadding(bottom = bottom + bars.bottom)
            insets
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupMap() = with(binding.map) {
        MapStyle.apply(this, lifecycleScope)
        setMultiTouchControls(true)
        zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        isTilesScaledToDpi = true
        controller.setZoom(16.0)
        val start = prefs.lastRealLocation ?: LatLon(30.0444, 31.2357)
        controller.setCenter(GeoPoint(start.lat, start.lon))
        overlays.addAll(listOf(routeCasing, passedLine, routeLine))
        setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_MOVE) follow = false
            false
        }
    }

    private fun setupControls() {
        binding.closeButton.setOnClickListener { finish() }
        binding.fromRow.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.nav_choose_start)
                .setItems(arrayOf(getString(R.string.nav_from_choice_real), getString(R.string.nav_from_choice_search))) { _, which ->
                    if (which == 0) {
                        from = null
                        planRoute()
                    } else {
                        pickFrom.launch(Intent(this, SearchActivity::class.java))
                    }
                }
                .show()
        }
        binding.toRow.setOnClickListener { pickTo.launch(Intent(this, SearchActivity::class.java)) }
        binding.swapButton.setOnClickListener {
            val oldTo = to
            to = from ?: realPlace()
            from = oldTo
            planRoute()
        }
        binding.modeGroup.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            mode = if (id == R.id.modeWalk) TravelMode.WALK else TravelMode.CAR
            planRoute()
        }
        binding.recenterButton.setOnClickListener {
            follow = true
            real?.let { onRealLocation(it) } ?: route?.let { fit(it) }
        }
        binding.muteButton.setOnClickListener {
            prefs.navMuted = !prefs.navMuted
            if (prefs.navMuted) tts?.stop()
            render()
        }
        binding.mainButton.setOnClickListener { if (navigating) finish() else startNavigation() }
        binding.endButton.setOnClickListener { finish() }
        binding.muteFab.setOnClickListener { binding.muteButton.performClick() }
    }

    private fun realPlace(): Place? = real?.let { Place(LatLon(it.latitude, it.longitude), getString(R.string.nav_my_location)) }

    // region Route

    private fun planRoute() {
        render()
        val target = to ?: return
        val start = from?.position ?: real?.let { LatLon(it.latitude, it.longitude) }
        if (start == null) {
            binding.summaryText.setText(R.string.nav_waiting_gps)
            return
        }
        routingJob?.cancel()
        binding.etaText.setText(R.string.nav_routing)
        binding.summaryText.text = ""
        binding.mainButton.isEnabled = false
        routingJob = lifecycleScope.launch {
            val result = runCatching { NavRouter.route(this@NavigateActivity, start, target.position, mode) }
            result.onSuccess { setRoute(it, fitMap = !navigating) }
                .onFailure {
                    binding.etaText.setText(R.string.nav_route_failed)
                    binding.mainButton.isEnabled = false
                }
        }
    }

    private fun setRoute(newRoute: NavRoute, fitMap: Boolean) {
        route = newRoute
        tracker = NavTracker(newRoute)
        spoken.clear()
        offRouteCount = 0
        val points = newRoute.points.map { GeoPoint(it.lat, it.lon) }
        routeCasing.setPoints(points)
        routeLine.setPoints(points)
        passedLine.setPoints(emptyList())
        destination.position = points.last()
        if (!binding.map.overlays.contains(destination)) binding.map.overlays.add(destination)
        if (fitMap) fit(newRoute)
        loadRoadInfo(newRoute)
        binding.mainButton.isEnabled = true
        render()
        real?.let { onRealLocation(it) }
    }

    /** Speed limits and cameras along the route, from OpenStreetMap. Missing data just means no sign. */
    private fun loadRoadInfo(r: NavRoute) {
        roadInfoJob?.cancel()
        roadInfo = null
        cameraMarkers.forEach { binding.map.overlays.remove(it) }
        cameraMarkers.clear()
        roadInfoJob = lifecycleScope.launch {
            val info = runCatching { RoadInfoFetcher.fetch(r) }.getOrNull() ?: return@launch
            if (route !== r) return@launch
            roadInfo = info
            info.camerasAlong.forEach { along ->
                val index = r.along.indexOfFirst { it >= along }.coerceAtLeast(0)
                val p = r.points[index]
                cameraMarkers += Marker(binding.map).apply {
                    position = GeoPoint(p.lat, p.lon)
                    icon = ContextCompat.getDrawable(this@NavigateActivity, R.drawable.ic_camera)?.mutate()?.apply {
                        setTint(ContextCompat.getColor(this@NavigateActivity, R.color.limit_red))
                    }
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    setInfoWindow(null)
                    setOnMarkerClickListener { _, _ -> true }
                }
            }
            binding.map.overlays.addAll(cameraMarkers)
            binding.map.invalidate()
        }
    }

    private fun fit(r: NavRoute) {
        binding.map.post {
            val box = BoundingBox.fromGeoPoints(r.points.map { GeoPoint(it.lat, it.lon) })
            binding.map.mapOrientation = 0f
            binding.map.zoomToBoundingBox(box, true, (64 * resources.displayMetrics.density).toInt())
        }
    }

    // endregion

    // region Navigation

    private fun startNavigation() {
        if (route == null) return
        navigating = true
        follow = true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding.map.controller.setZoom(if (mode == TravelMode.CAR) 17.5 else 18.5)
        render()
        speak(route!!.steps.firstOrNull()?.spoken ?: "")
        real?.let { onRealLocation(it) }
    }

    private fun onRealLocation(location: Location) {
        if (location.isMockCompat()) return
        val firstFix = real == null
        real = location
        val here = LatLon(location.latitude, location.longitude)
        prefs.lastRealLocation = here
        if (firstFix && route == null && to != null) planRoute()

        val r = route
        val progress = tracker?.update(here)
        val bearing = when {
            location.hasBearing() && location.speed > 1.5f -> location.bearing
            r != null && progress != null -> TripTimeline.bearing(r.points[progress.segment], r.points[progress.segment + 1])
            else -> 0f
        }
        val shown = if (navigating && progress != null && progress.offRouteMeters < 25) progress.snapped else here
        me.position = GeoPoint(shown.lat, shown.lon)
        if (navigating && follow) {
            binding.map.mapOrientation = -bearing
            binding.map.controller.animateTo(me.position)
        }
        me.icon = if (navigating && mode == TravelMode.CAR) car(bearing + binding.map.mapOrientation) else arrow(bearing + binding.map.mapOrientation)
        val kmh = speedKmh(location)
        lastFix = location
        binding.map.overlays.remove(me)
        binding.map.overlays.add(me)

        if (r != null && progress != null) {
            passedLine.setPoints(r.points.subList(0, progress.segment + 1).map { GeoPoint(it.lat, it.lon) } + GeoPoint(progress.snapped.lat, progress.snapped.lon))
            if (navigating) guide(r, progress, here)
            renderProgress(progress)
            renderSpeed(kmh, roadInfo?.limitAt(progress.segment) ?: 0, progress)
        }
        binding.map.invalidate()
    }

    private fun guide(r: NavRoute, p: NavProgress, here: LatLon) {
        // Off the route: wait for a few fixes (GPS jumps), then find a new way from here.
        if (p.offRouteMeters > NavTracker.OFF_ROUTE_METERS) offRouteCount++ else offRouteCount = 0
        val startDistance = distanceMeters(here, r.points.first())
        if (p.alongMeters < 1 && startDistance > NavTracker.OFF_ROUTE_METERS && from != null) {
            binding.banner.isVisible = true
            binding.banner.text = getString(R.string.nav_go_to_start, formatDistance(startDistance))
        } else if (offRouteCount >= 3 && System.currentTimeMillis() - lastReroute > 15_000) {
            lastReroute = System.currentTimeMillis()
            binding.banner.isVisible = true
            binding.banner.setText(R.string.nav_rerouting)
            from = null // from now on, routes start where you are
            planRoute()
            return
        } else if (offRouteCount == 0) {
            binding.banner.isVisible = false
        }

        if (p.arrived) {
            if (spoken.add("arrived")) speak(getString(R.string.nav_arrived))
            return
        }
        val step = p.nextStep ?: return
        val index = r.steps.indexOf(step)
        val d = p.metersToNextStep
        val far = if (mode == TravelMode.CAR) 400.0 else 120.0
        val near = if (mode == TravelMode.CAR) 120.0 else 30.0
        val now = if (mode == TravelMode.CAR) 35.0 else 10.0
        when {
            d <= now && spoken.add("$index:now") -> speak(if (step.maneuver == Maneuver.ARRIVE) step.spoken else getString(R.string.nav_now, step.spoken))
            d in now..near && spoken.add("$index:near") -> speak(getString(R.string.nav_in, spokenDistance(d), step.spoken.lowercase(Locale.US)))
            d in near..far && spoken.add("$index:far") -> speak(getString(R.string.nav_in, spokenDistance(d), step.spoken.lowercase(Locale.US)))
        }
    }

    private fun spokenDistance(meters: Double): String {
        val rounded = if (meters < 100) (meters / 10).roundToInt() * 10 else (meters / 50).roundToInt() * 50
        return if (rounded >= 1000) String.format(Locale.US, "%.1f kilometers", rounded / 1000.0) else "$rounded meters"
    }

    private fun speak(text: String) {
        if (text.isBlank() || prefs.navMuted || !ttsReady) return
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, text)
    }

    // endregion

    // region Real location

    @SuppressLint("MissingPermission")
    private fun startRealLocation() {
        if (!hasLocationPermission()) return
        // The GPS chip itself: real even while a fake location runs (navigation turned the chip back to real).
        runCatching { locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, gpsListener, Looper.getMainLooper()) }
        // Without a fake location, Google's location service is real too and gets a first fix faster.
        if (!MockState.isFaking) {
            fusedClient.requestLocationUpdates(
                LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L).build(), fusedCallback, Looper.getMainLooper()
            )
        }
    }

    private fun stopRealLocation() {
        runCatching { locationManager.removeUpdates(gpsListener) }
        fusedClient.removeLocationUpdates(fusedCallback)
    }

    // endregion

    // region Drawing

    private fun render() {
        binding.fromText.text = from?.name ?: getString(R.string.nav_my_location)
        binding.toText.text = to?.name ?: getString(R.string.nav_choose_destination)
        binding.planCard.isVisible = !navigating
        binding.instructionCard.isVisible = navigating
        binding.muteButton.isVisible = false
        binding.muteButton.setIconResource(if (prefs.navMuted) R.drawable.ic_volume_off else R.drawable.ic_volume)
        binding.muteFab.isVisible = navigating
        binding.muteFab.setIconResource(if (prefs.navMuted) R.drawable.ic_volume_off else R.drawable.ic_volume)
        binding.bottom.isVisible = !navigating
        binding.driveBar.isVisible = navigating
        if (!navigating) {
            binding.speedRow.isVisible = false
            binding.cameraChip.isVisible = false
            binding.streetPill.isVisible = false
        }
        binding.mainButton.setText(if (navigating) R.string.nav_end else R.string.nav_start)
        binding.mainButton.setIconResource(if (navigating) R.drawable.ic_stop else R.drawable.ic_navigate)
        binding.mainButton.backgroundTintList = ContextCompat.getColorStateList(this, if (navigating) R.color.ink else R.color.real)
        val session = MockState.session.value
        val trip = MockState.trip.value
        binding.fakeChip.isVisible = session != null || trip != null
        binding.fakeChip.text = when {
            trip != null -> getString(R.string.nav_fake_trip, trip.routeName)
            session != null -> getString(R.string.nav_fake_on, session.name)
            else -> ""
        }
        val r = route
        if (to == null) {
            binding.etaText.setText(R.string.nav_pick_destination)
            binding.summaryText.text = if (MockState.isFaking) getString(R.string.nav_real_gps_note) else ""
            binding.mainButton.isEnabled = false
        } else if (r != null && !navigating) {
            binding.etaText.text = getString(R.string.nav_eta, formatDuration((r.durationSeconds * 1000).toLong()), clockText(System.currentTimeMillis() + (r.durationSeconds * 1000).toLong()))
            val distance = formatDistance(r.distanceMeters)
            binding.summaryText.text = if (r.trafficAware) getString(R.string.nav_traffic, distance) else distance
        }
    }

    private fun renderProgress(p: NavProgress) {
        if (!navigating) return
        val step = p.nextStep
        binding.maneuverIcon.setImageResource(iconFor(step?.maneuver ?: Maneuver.ARRIVE))
        binding.maneuverDistance.text = if (p.arrived) "" else formatDistance(p.metersToNextStep)
        binding.maneuverText.text = if (p.arrived) getString(R.string.nav_arrived) else step?.text.orEmpty()
        binding.maneuverDistance.isVisible = !p.arrived
        val then = p.thenStep
        binding.thenText.isVisible = then != null && !p.arrived && p.metersToNextStep < 600
        binding.thenText.text = then?.let { getString(R.string.nav_then, it.text) }
        val secs = p.remainingSeconds
        binding.driveDistance.text = formatDistance(p.remainingMeters)
        binding.driveArrival.text = clockText(System.currentTimeMillis() + (secs * 1000).toLong())
        binding.driveMinutes.text = getString(R.string.nav_minutes, ((secs + 59) / 60).toInt())
        val length = route?.length?.takeIf { it > 0 } ?: 1.0
        binding.driveProgress.progress = (p.alongMeters / length * 1000).toInt()
        val road = p.currentStep?.road.orEmpty()
        binding.streetPill.isVisible = road.isNotBlank()
        binding.streetPill.text = road
        renderLanes(p)
    }

    private fun iconFor(m: Maneuver) = when (m) {
        Maneuver.LEFT -> R.drawable.ic_m_left
        Maneuver.RIGHT -> R.drawable.ic_m_right
        Maneuver.SLIGHT_LEFT -> R.drawable.ic_m_slight_left
        Maneuver.SLIGHT_RIGHT -> R.drawable.ic_m_slight_right
        Maneuver.SHARP_LEFT -> R.drawable.ic_m_sharp_left
        Maneuver.SHARP_RIGHT -> R.drawable.ic_m_sharp_right
        Maneuver.UTURN -> R.drawable.ic_m_uturn
        Maneuver.ROUNDABOUT -> R.drawable.ic_m_roundabout
        Maneuver.ARRIVE -> R.drawable.ic_m_arrive
        else -> R.drawable.ic_m_straight
    }

    private fun line(color: Int, widthDp: Float) = Polyline(binding.map).apply {
        outlinePaint.color = ContextCompat.getColor(this@NavigateActivity, color)
        outlinePaint.strokeWidth = widthDp * resources.displayMetrics.density
        outlinePaint.strokeCap = Paint.Cap.ROUND
        outlinePaint.strokeJoin = Paint.Join.ROUND
        setOnClickListener { _, _, _ -> false }
    }

    private fun speedKmh(location: Location): Int {
        if (location.hasSpeed()) return (location.speed * 3.6f).roundToInt()
        val previous = lastFix ?: return 0
        val seconds = (location.time - previous.time) / 1000.0
        if (seconds <= 0) return 0
        return (location.distanceTo(previous) / seconds * 3.6).roundToInt()
    }

    /** Your speed next to the road's limit (red sign). Speed turns red and beeps once when you go over. */
    private fun renderSpeed(kmh: Int, limit: Int, p: NavProgress) {
        if (!navigating) return
        binding.speedRow.isVisible = mode == TravelMode.CAR
        binding.mySpeed.text = kmh.toString()
        binding.speedLimit.isVisible = limit > 0
        binding.speedLimit.text = limit.toString()
        val over = limit > 0 && kmh > limit + 5
        binding.mySpeed.setTextColor(ContextCompat.getColor(this, if (over) R.color.limit_red else R.color.ink))
        if (over && !speeding) {
            speeding = true
            if (!prefs.navMuted) beeper?.startTone(ToneGenerator.TONE_PROP_BEEP2, 400)
        } else if (!over && kmh <= limit) {
            speeding = false
        }
        // Speed camera coming up.
        val camera = roadInfo?.nextCamera(p.alongMeters)?.takeIf { it in 0.0..600.0 && mode == TravelMode.CAR }
        binding.cameraChip.isVisible = camera != null
        if (camera != null) {
            binding.cameraChip.text = formatDistance(camera)
            val key = "camera:${(p.alongMeters + camera).roundToInt()}"
            if (camera < 400 && spoken.add(key)) {
                speak(getString(R.string.nav_camera_ahead) + if (limit > 0) ". " + getString(R.string.nav_speed_limit_voice, limit) else "")
            }
        }
    }

    /** Lane arrows for the next junction, the ones to use bright and the others faded. */
    private fun renderLanes(p: NavProgress) {
        val step = p.nextStep
        val lanes = step?.lanes.orEmpty()
        val show = lanes.isNotEmpty() && p.metersToNextStep < 800
        binding.lanesRow.isVisible = show
        if (!show) return
        binding.lanesRow.removeAllViews()
        val size = (26 * resources.displayMetrics.density).toInt()
        for (lane in lanes) {
            val direction = lane.directions.firstOrNull { lane.valid && matches(it, step!!.maneuver) } ?: lane.directions.firstOrNull() ?: "straight"
            val view = android.widget.ImageView(this).apply {
                setImageResource(laneIcon(direction))
                imageTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this@NavigateActivity, R.color.surface))
                alpha = if (lane.valid) 1f else 0.35f
            }
            binding.lanesRow.addView(view, android.widget.LinearLayout.LayoutParams(size, size))
        }
    }

    private fun matches(direction: String, m: Maneuver) = when (m) {
        Maneuver.LEFT, Maneuver.SHARP_LEFT -> "left" in direction
        Maneuver.RIGHT, Maneuver.SHARP_RIGHT -> "right" in direction
        Maneuver.SLIGHT_LEFT -> "left" in direction
        Maneuver.SLIGHT_RIGHT -> "right" in direction
        Maneuver.UTURN -> direction == "uturn"
        else -> direction == "straight"
    }

    private fun laneIcon(direction: String) = when (direction) {
        "left" -> R.drawable.ic_m_left
        "right" -> R.drawable.ic_m_right
        "slight left" -> R.drawable.ic_m_slight_left
        "slight right" -> R.drawable.ic_m_slight_right
        "sharp left" -> R.drawable.ic_m_sharp_left
        "sharp right" -> R.drawable.ic_m_sharp_right
        "uturn" -> R.drawable.ic_m_uturn
        else -> R.drawable.ic_m_straight
    }

    /** A white car seen from above, pointing along [degrees] on screen. */
    private fun car(degrees: Float): Drawable {
        val step = ((((degrees % 360) + 360) % 360) / 10f).roundToInt() * 10 % 360
        return carCache.getOrPut(step) {
            val d = resources.displayMetrics.density
            val w = 26 * d
            val h = 46 * d
            val size = (h + 8 * d).toInt()
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val c = size / 2f
            canvas.rotate(step.toFloat(), c, c)
            val left = c - w / 2
            val top = c - h / 2
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.color = 0x33000000
            canvas.drawRoundRect(android.graphics.RectF(left - 2 * d, top + 1 * d, left + w + 2 * d, top + h + 3 * d), 10 * d, 10 * d, paint)
            paint.color = 0xFFF8FAFC.toInt()
            canvas.drawRoundRect(android.graphics.RectF(left, top, left + w, top + h), 9 * d, 9 * d, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.2f * d
            paint.color = 0xFF94A3B8.toInt()
            canvas.drawRoundRect(android.graphics.RectF(left, top, left + w, top + h), 9 * d, 9 * d, paint)
            paint.style = Paint.Style.FILL
            paint.color = 0xFF1E293B.toInt()
            canvas.drawRoundRect(android.graphics.RectF(left + 3 * d, top + 11 * d, left + w - 3 * d, top + 18 * d), 3 * d, 3 * d, paint)
            canvas.drawRoundRect(android.graphics.RectF(left + 4 * d, top + 34 * d, left + w - 4 * d, top + 39 * d), 2 * d, 2 * d, paint)
            paint.color = 0xFFE2E8F0.toInt()
            canvas.drawRoundRect(android.graphics.RectF(left + 4 * d, top + 19 * d, left + w - 4 * d, top + 33 * d), 3 * d, 3 * d, paint)
            paint.color = 0xFFFDE68A.toInt()
            canvas.drawCircle(left + 5 * d, top + 3.5f * d, 2.2f * d, paint)
            canvas.drawCircle(left + w - 5 * d, top + 3.5f * d, 2.2f * d, paint)
            paint.color = 0xFFDC2626.toInt()
            canvas.drawRoundRect(android.graphics.RectF(left + 3 * d, top + h - 3 * d, left + 9 * d, top + h - 1 * d), d, d, paint)
            canvas.drawRoundRect(android.graphics.RectF(left + w - 9 * d, top + h - 3 * d, left + w - 3 * d, top + h - 1 * d), d, d, paint)
            BitmapDrawable(resources, bitmap)
        }
    }

    /** Blue circle with a white arrow, rotated on screen by [degrees]. */
    private fun arrow(degrees: Float): Drawable {
        val step = ((((degrees % 360) + 360) % 360) / 10f).roundToInt() * 10 % 360
        return arrowCache.getOrPut(step) {
            val density = resources.displayMetrics.density
            val size = (36 * density).toInt()
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            val c = size / 2f
            paint.color = ContextCompat.getColor(this, R.color.surface)
            canvas.drawCircle(c, c, c, paint)
            paint.color = ContextCompat.getColor(this, R.color.real)
            canvas.drawCircle(c, c, c - 3 * density, paint)
            canvas.rotate(step.toFloat(), c, c)
            paint.color = ContextCompat.getColor(this, R.color.surface)
            val s = 7.5f * density
            canvas.drawPath(Path().apply {
                moveTo(c, c - s * 1.3f)
                lineTo(c + s, c + s)
                lineTo(c, c + s * 0.45f)
                lineTo(c - s, c + s)
                close()
            }, paint)
            BitmapDrawable(resources, bitmap)
        }
    }

    // endregion

    companion object {
        private const val EXTRA_TO_LAT = "to_lat"
        private const val EXTRA_TO_LON = "to_lon"
        private const val EXTRA_TO_NAME = "to_name"

        fun intent(context: Context, destination: Place? = null) =
            Intent(context, NavigateActivity::class.java).apply {
                destination?.let {
                    putExtra(EXTRA_TO_LAT, it.position.lat)
                    putExtra(EXTRA_TO_LON, it.position.lon)
                    putExtra(EXTRA_TO_NAME, it.name ?: it.address ?: "")
                }
            }
    }
}

private fun Location.isMockCompat(): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) isMock else @Suppress("DEPRECATION") isFromMockProvider
