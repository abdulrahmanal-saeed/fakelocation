package com.abdulrahman.fakelocation.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.abdulrahman.fakelocation.R
import com.abdulrahman.fakelocation.data.LatLon
import com.abdulrahman.fakelocation.data.Place
import com.abdulrahman.fakelocation.data.Prefs
import com.abdulrahman.fakelocation.data.TravelMode
import com.abdulrahman.fakelocation.data.formatDistance
import com.abdulrahman.fakelocation.data.formatDuration
import com.abdulrahman.fakelocation.databinding.ActivityNavigateBinding
import com.abdulrahman.fakelocation.drive.Fuel
import com.abdulrahman.fakelocation.drive.Tolls
import com.abdulrahman.fakelocation.mock.MockState
import com.abdulrahman.fakelocation.nav.NavIcons
import com.abdulrahman.fakelocation.nav.NavProgress
import com.abdulrahman.fakelocation.nav.NavRoute
import com.abdulrahman.fakelocation.nav.NavSession
import com.abdulrahman.fakelocation.nav.RoadInfo
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

/**
 * Real turn-by-turn navigation (like Waze / Yango) on the phone. The work happens in [NavSession], which
 * Android Auto shares; this screen draws it. The fake location keeps running for other apps.
 */
class NavigateActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNavigateBinding
    private var follow = true
    private var drawnRoute: NavRoute? = null
    private var drawnRoadInfo: RoadInfo? = null
    private var wasNavigating = false
    private val cameraMarkers = mutableListOf<Marker>()

    private val routeCasing by lazy { line(R.color.route_green_dark, 12f) }
    private val routeLine by lazy { line(R.color.route_green, 8f) }
    private val passedLine by lazy { line(R.color.idle_dot, 8f) }
    private val me by lazy { marker(Marker.ANCHOR_CENTER) }
    private val destination by lazy {
        marker(Marker.ANCHOR_BOTTOM).apply {
            icon = ContextCompat.getDrawable(this@NavigateActivity, R.drawable.marker_fake)?.mutate()
                ?.apply { setTint(ContextCompat.getColor(this@NavigateActivity, R.color.ink)) }
        }
    }

    private val pickFrom = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        SearchActivity.placeFrom(result.data)?.let { NavSession.setStart(it) }
    }
    private val pickTo = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        SearchActivity.placeFrom(result.data)?.let { NavSession.setDestination(it) }
    }
    private val pickStop = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        SearchActivity.placeFrom(result.data)?.let { NavSession.addStop(it) }
    }
    private var shownStops: Pair<List<Place>, Int>? = null
    private val stopMarkers = mutableListOf<Marker>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityNavigateBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.driveProgress.max = 1000
        applyInsets()
        setupMap()
        setupControls()
        if (savedInstanceState == null) {
            intent.getStringExtra(EXTRA_TO_NAME)?.let { name ->
                NavSession.setDestination(Place(LatLon(intent.getDoubleExtra(EXTRA_TO_LAT, 0.0), intent.getDoubleExtra(EXTRA_TO_LON, 0.0)), name))
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                NavSession.state.collect { render(it) }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        NavSession.acquire()
    }

    override fun onStop() {
        NavSession.release()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        binding.map.onResume()
    }

    override fun onPause() {
        binding.map.onPause()
        super.onPause()
    }

    private fun applyInsets() {
        val top = (binding.top.layoutParams as ViewGroup.MarginLayoutParams).topMargin
        val bottom = binding.bottom.paddingBottom
        val barBottom = (binding.driveBar.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            binding.top.updateLayoutParams<ViewGroup.MarginLayoutParams> { topMargin = top + bars.top }
            binding.bottom.updatePadding(bottom = bottom + bars.bottom)
            binding.driveBar.updateLayoutParams<ViewGroup.MarginLayoutParams> { bottomMargin = barBottom + bars.bottom }
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
        val start = Prefs(this@NavigateActivity).lastRealLocation ?: LatLon.HOME_REGION
        controller.setCenter(GeoPoint(start.lat, start.lon))
        overlays.addAll(listOf(routeCasing, passedLine, routeLine))
        setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_MOVE) follow = false
            false
        }
    }

    private fun setupControls() {
        binding.closeButton.setOnClickListener {
            NavSession.clear()
            finish()
        }
        binding.fromRow.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.nav_choose_start)
                .setItems(arrayOf(getString(R.string.nav_from_choice_real), getString(R.string.nav_from_choice_search))) { _, which ->
                    if (which == 0) NavSession.setStart(null) else pickFrom.launch(Intent(this, SearchActivity::class.java))
                }
                .show()
        }
        binding.toRow.setOnClickListener { pickTo.launch(Intent(this, SearchActivity::class.java)) }
        binding.swapButton.setOnClickListener { NavSession.swap() }
        binding.modeGroup.addOnButtonCheckedListener { _, id, checked ->
            if (checked) NavSession.setMode(if (id == R.id.modeWalk) TravelMode.WALK else TravelMode.CAR)
        }
        binding.recenterButton.setOnClickListener {
            follow = true
            val s = NavSession.state.value
            if (s.navigating) render(s) else s.route?.let { fit(it) } ?: s.real?.let { binding.map.controller.animateTo(GeoPoint(it.lat, it.lon)) }
        }
        binding.muteFab.setOnClickListener { NavSession.toggleMute() }
        binding.addStopChip.setOnClickListener { pickStop.launch(Intent(this, SearchActivity::class.java)) }
        binding.addStopFab.setOnClickListener { pickStop.launch(Intent(this, SearchActivity::class.java)) }
        binding.avoidTollsChip.setOnClickListener {
            NavSession.setAvoid(NavSession.state.value.avoid.copy(tolls = binding.avoidTollsChip.isChecked))
        }
        binding.avoidHighwaysChip.setOnClickListener {
            NavSession.setAvoid(NavSession.state.value.avoid.copy(highways = binding.avoidHighwaysChip.isChecked))
        }
        binding.parkFab.setOnClickListener {
            NavSession.stop()
            startActivity(Intent(this, ParkingActivity::class.java))
            finish()
        }
        binding.mainButton.setOnClickListener {
            follow = true
            NavSession.start()
        }
        binding.endButton.setOnClickListener {
            NavSession.stop()
            finish()
        }
    }

    // region Drawing

    private fun render(s: NavSession.State) {
        val navigating = s.navigating
        if (navigating != wasNavigating) {
            wasNavigating = navigating
            if (navigating) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                binding.map.controller.setZoom(if (s.mode == TravelMode.CAR) 17.5 else 18.5)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                binding.map.mapOrientation = 0f
            }
        }
        binding.modeGroup.check(if (s.mode == TravelMode.WALK) R.id.modeWalk else R.id.modeCar)
        binding.fromText.text = s.from?.name ?: getString(R.string.nav_my_location)
        binding.toText.text = s.to?.name ?: getString(R.string.nav_choose_destination)
        binding.planCard.isVisible = !navigating
        binding.instructionCard.isVisible = navigating
        binding.bottom.isVisible = !navigating
        binding.driveBar.isVisible = navigating
        binding.muteFab.isVisible = navigating
        binding.muteFab.setIconResource(if (s.muted) R.drawable.ic_volume_off else R.drawable.ic_volume)
        binding.muteButton.isVisible = false
        binding.addStopFab.isVisible = navigating && s.progress?.arrived != true
        binding.parkFab.isVisible = navigating && s.mode == TravelMode.CAR && s.progress?.arrived == true
        binding.avoidTollsChip.isChecked = s.avoid.tolls
        binding.avoidHighwaysChip.isChecked = s.avoid.highways
        binding.avoidTollsChip.isVisible = s.mode == TravelMode.CAR
        binding.avoidHighwaysChip.isVisible = s.mode == TravelMode.CAR
        renderStops(s)

        val session = MockState.session.value
        val trip = MockState.trip.value
        binding.fakeChip.isVisible = session != null || trip != null
        binding.fakeChip.text = when {
            trip != null -> getString(R.string.nav_fake_trip, trip.routeName)
            session != null -> getString(R.string.nav_fake_on, session.name)
            else -> ""
        }

        drawRoute(s)
        drawMe(s)
        if (navigating) renderDriving(s) else renderPlanning(s)
        binding.map.invalidate()
    }

    private fun renderPlanning(s: NavSession.State) {
        binding.speedRow.isVisible = false
        binding.cameraChip.isVisible = false
        binding.tollChip.isVisible = false
        binding.streetPill.isVisible = false
        binding.banner.isVisible = false
        val r = s.route
        binding.mainButton.isEnabled = r != null && s.status == NavSession.Status.PREVIEW
        when {
            s.to == null -> {
                binding.etaText.setText(R.string.nav_pick_destination)
                binding.summaryText.text = if (MockState.isFaking) getString(R.string.nav_real_gps_note) else ""
            }
            s.status == NavSession.Status.ROUTING -> {
                binding.etaText.setText(if (s.real == null && s.from == null) R.string.nav_waiting_gps else R.string.nav_routing)
                binding.summaryText.text = ""
            }
            s.status == NavSession.Status.ERROR -> {
                binding.etaText.setText(R.string.nav_route_failed)
                binding.summaryText.text = ""
            }
            r != null -> {
                val millis = (r.durationSeconds * 1000).toLong()
                binding.etaText.text = getString(R.string.nav_eta, formatDuration(millis), clockText(System.currentTimeMillis() + millis))
                val distance = formatDistance(r.distanceMeters)
                binding.summaryText.text = if (r.trafficAware) getString(R.string.nav_traffic, distance) else distance
            }
        }
        val cost = if (r != null && s.mode == TravelMode.CAR && s.status == NavSession.Status.PREVIEW) costLine(s) else null
        binding.costText.isVisible = cost != null
        binding.costText.text = cost
    }

    private fun renderDriving(s: NavSession.State) {
        val p = s.progress
        binding.banner.isVisible = s.rerouting || s.toStartMeters > 0
        binding.banner.text = when {
            s.rerouting -> getString(R.string.nav_rerouting)
            s.toStartMeters > 0 -> getString(R.string.nav_go_to_start, formatDistance(s.toStartMeters))
            else -> ""
        }
        binding.speedRow.isVisible = s.mode == TravelMode.CAR
        binding.mySpeed.text = s.speedKmh.toString()
        binding.mySpeed.setTextColor(ContextCompat.getColor(this, if (s.speeding) R.color.limit_red else R.color.ink))
        binding.speedLimit.isVisible = s.limitKmh > 0
        binding.speedLimit.text = s.limitKmh.toString()
        binding.cameraChip.isVisible = s.cameraMeters != null
        binding.cameraChip.text = s.cameraMeters?.let { formatDistance(it) }
        val toll = s.nextToll
        val tollMeters = s.tollMeters
        binding.tollChip.isVisible = toll != null && tollMeters != null
        if (toll != null && tollMeters != null) {
            val price = if (toll.priceAed > 0) Fuel.aed(toll.priceAed).replace(".00", "") else getString(R.string.toll_free_now)
            binding.tollChip.text = getString(R.string.toll_chip, toll.gate.system.label, price, formatDistance(tollMeters))
        }
        if (p == null) {
            binding.maneuverText.setText(R.string.nav_waiting_gps)
            binding.maneuverDistance.text = ""
            return
        }
        renderProgress(p, s.route)
    }

    private fun renderProgress(p: NavProgress, route: NavRoute?) {
        val step = p.nextStep
        binding.maneuverIcon.setImageResource(NavIcons.maneuverIcon(step?.maneuver ?: com.abdulrahman.fakelocation.nav.Maneuver.ARRIVE))
        binding.maneuverDistance.text = if (p.arrived) "" else formatDistance(p.metersToNextStep)
        binding.maneuverDistance.isVisible = !p.arrived
        binding.maneuverText.text = if (p.arrived) getString(R.string.nav_arrived) else step?.text.orEmpty()
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
        // Lanes for the next junction.
        val lanes = step?.lanes.orEmpty()
        val showLanes = lanes.isNotEmpty() && p.metersToNextStep < 800
        binding.lanesRow.isVisible = showLanes
        if (showLanes) {
            binding.lanesRow.removeAllViews()
            val size = (26 * resources.displayMetrics.density).toInt()
            for (lane in lanes) {
                binding.lanesRow.addView(ImageView(this).apply {
                    setImageResource(NavIcons.laneIcon(NavIcons.laneDirection(lane, step!!.maneuver)))
                    imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this@NavigateActivity, R.color.surface))
                    alpha = if (lane.valid) 1f else 0.35f
                }, LinearLayout.LayoutParams(size, size))
            }
        }
    }

    /** "Fuel ≈ AED 3.10 · Salik AED 10 (2 gates)". */
    private fun costLine(s: NavSession.State): String {
        val parts = mutableListOf(getString(R.string.nav_cost_fuel, Fuel.aed(s.fuelCostAed)))
        if (s.tolls.isEmpty()) {
            parts += getString(R.string.nav_cost_no_tolls)
        } else {
            s.tolls.groupBy { it.gate.system }.forEach { (system, passes) ->
                val sum = Fuel.aed(Tolls.total(passes)).replace(".00", "")
                parts += if (passes.size == 1) getString(R.string.nav_cost_toll_one, system.label, sum)
                else getString(R.string.nav_cost_tolls, system.label, sum, passes.size)
            }
        }
        return parts.joinToString(" · ")
    }

    /** In-between stops as rows (number, name, remove) between the start and the destination. */
    private fun renderStops(s: NavSession.State) {
        val key = s.stops to s.stopsReached
        if (key != shownStops) {
            shownStops = key
            binding.stopsContainer.removeAllViews()
            s.stops.forEachIndexed { i, place ->
                val row = layoutInflater.inflate(R.layout.item_nav_stop, binding.stopsContainer, false)
                val passed = s.navigating && i < s.stopsReached
                row.findViewById<android.widget.TextView>(R.id.stopNumber).text = (i + 1).toString()
                row.findViewById<android.widget.TextView>(R.id.stopName).text =
                    if (passed) getString(R.string.nav_stop_done, place.name.orEmpty()) else place.name ?: String.format(java.util.Locale.US, "%.5f, %.5f", place.position.lat, place.position.lon)
                row.findViewById<android.view.View>(R.id.stopRemove).apply {
                    isVisible = !passed
                    setOnClickListener { NavSession.removeStop(i) }
                }
                binding.stopsContainer.addView(row)
            }
            stopMarkers.forEach { binding.map.overlays.remove(it) }
            stopMarkers.clear()
            s.stops.forEachIndexed { i, place ->
                stopMarkers += marker(Marker.ANCHOR_CENTER).apply {
                    position = GeoPoint(place.position.lat, place.position.lon)
                    icon = BitmapDrawable(resources, NavIcons.numberDot(this@NavigateActivity, i + 1))
                }
            }
            binding.map.overlays.addAll(stopMarkers)
        }
    }

    private fun drawRoute(s: NavSession.State) {
        val r = s.route
        if (r !== drawnRoute) {
            drawnRoute = r
            val points = r?.points?.map { GeoPoint(it.lat, it.lon) }.orEmpty()
            routeCasing.setPoints(points)
            routeLine.setPoints(points)
            passedLine.setPoints(emptyList())
            binding.map.overlays.remove(destination)
            if (r != null) {
                destination.position = points.last()
                binding.map.overlays.add(destination)
                if (!s.navigating) fit(r)
            }
        }
        if (s.roadInfo !== drawnRoadInfo) {
            drawnRoadInfo = s.roadInfo
            cameraMarkers.forEach { binding.map.overlays.remove(it) }
            cameraMarkers.clear()
            if (r != null) s.roadInfo?.camerasAlong?.forEach { along ->
                val p = r.points[r.along.indexOfFirst { it >= along }.coerceAtLeast(0)]
                cameraMarkers += marker(Marker.ANCHOR_CENTER).apply {
                    position = GeoPoint(p.lat, p.lon)
                    icon = ContextCompat.getDrawable(this@NavigateActivity, R.drawable.ic_camera)?.mutate()
                        ?.apply { setTint(ContextCompat.getColor(this@NavigateActivity, R.color.limit_red)) }
                }
            }
            binding.map.overlays.addAll(cameraMarkers)
        }
        val p = s.progress
        if (r != null && p != null) {
            passedLine.setPoints(r.points.subList(0, p.segment + 1).map { GeoPoint(it.lat, it.lon) } + GeoPoint(p.snapped.lat, p.snapped.lon))
        }
    }

    private fun drawMe(s: NavSession.State) {
        val here = s.real ?: return
        val p = s.progress
        val shown = if (s.navigating && p != null && p.offRouteMeters < 25) p.snapped else here
        me.position = GeoPoint(shown.lat, shown.lon)
        if (s.navigating && follow) {
            binding.map.mapOrientation = -s.bearing
            binding.map.controller.animateTo(me.position)
        }
        val onScreen = s.bearing + binding.map.mapOrientation
        val bitmap = if (s.navigating && s.mode == TravelMode.CAR) NavIcons.car(this, onScreen) else NavIcons.arrow(this, onScreen)
        me.icon = BitmapDrawable(resources, bitmap)
        binding.map.overlays.remove(me)
        binding.map.overlays.add(me)
    }

    private fun fit(r: NavRoute) {
        binding.map.post {
            val box = BoundingBox.fromGeoPoints(r.points.map { GeoPoint(it.lat, it.lon) })
            binding.map.mapOrientation = 0f
            binding.map.zoomToBoundingBox(box, true, (64 * resources.displayMetrics.density).toInt())
        }
    }

    private fun line(color: Int, widthDp: Float) = Polyline(binding.map).apply {
        outlinePaint.color = ContextCompat.getColor(this@NavigateActivity, color)
        outlinePaint.strokeWidth = widthDp * resources.displayMetrics.density
        outlinePaint.strokeCap = Paint.Cap.ROUND
        outlinePaint.strokeJoin = Paint.Join.ROUND
        setOnClickListener { _, _, _ -> false }
    }

    private fun marker(anchorY: Float) = Marker(binding.map).apply {
        setAnchor(Marker.ANCHOR_CENTER, anchorY)
        setInfoWindow(null)
        setOnMarkerClickListener { _, _ -> true }
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
