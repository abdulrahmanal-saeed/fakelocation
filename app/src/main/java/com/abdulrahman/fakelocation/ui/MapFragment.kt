package com.abdulrahman.fakelocation.ui

import android.annotation.SuppressLint
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup.MarginLayoutParams
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.abdulrahman.fakelocation.R
import com.abdulrahman.fakelocation.data.FakeSession
import com.abdulrahman.fakelocation.data.Favorite
import com.abdulrahman.fakelocation.data.LatLon
import com.abdulrahman.fakelocation.data.Place
import com.abdulrahman.fakelocation.data.PlacesRepository
import com.abdulrahman.fakelocation.data.Prefs
import com.abdulrahman.fakelocation.data.distanceMeters
import com.abdulrahman.fakelocation.data.format
import com.abdulrahman.fakelocation.data.formatClock
import com.abdulrahman.fakelocation.databinding.FragmentMapBinding
import com.abdulrahman.fakelocation.mock.MockLocationService
import com.abdulrahman.fakelocation.mock.MockState
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.material.chip.Chip
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker

class MapFragment : Fragment(R.layout.fragment_map) {

    private var _binding: FragmentMapBinding? = null
    private val binding get() = _binding!!
    private lateinit var prefs: Prefs
    private lateinit var places: PlacesRepository
    private lateinit var fakeMarker: Marker
    private lateinit var realMarker: Marker
    private lateinit var selectionMarker: Marker
    private val fusedClient by lazy { LocationServices.getFusedLocationProviderClient(requireContext()) }
    private var trackingRealLocation = false
    private var centerOnNextRealFix = false
    private val timerHandler = Handler(Looper.getMainLooper())

    private val main get() = requireActivity() as MainActivity
    private var tripView: TripView? = null
    private var tripShown = false

    private val realLocationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val location = result.lastLocation ?: return
            // While faking, the system hands us our own fake position, so ignore it.
            if (location.isMockCompat() || MockState.isFaking) return
            val real = LatLon(location.latitude, location.longitude)
            prefs.lastRealLocation = real
            showRealMarker(real)
            if (centerOnNextRealFix) {
                centerOnNextRealFix = false
                binding.map.controller.animateTo(GeoPoint(real.lat, real.lon), 16.0, 600L)
            }
        }
    }

    private val timerTick = object : Runnable {
        override fun run() {
            val session = MockState.session.value ?: return
            _binding?.activeTimer?.text = formatClock(System.currentTimeMillis() - session.startedAt)
            timerHandler.postDelayed(this, 1000L)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentMapBinding.bind(view)
        prefs = Prefs(requireContext())
        places = PlacesRepository.get(requireContext())
        applyInsets()
        setupMap()
        setupControls()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(MockState.session, MockState.trip, places.favorites) { s, t, f -> Triple(s, t, f) }
                    .collect { (session, trip, favorites) ->
                        if (trip != null) {
                            renderTrip(trip)
                        } else {
                            if (tripShown) endTripView()
                            renderSession(session, favorites)
                            renderChips(favorites)
                        }
                    }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        binding.map.onResume()
        updateRealLocationTracking()
    }

    override fun onPause() {
        binding.map.onPause()
        stopRealLocationTracking()
        super.onPause()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) stopRealLocationTracking() else updateRealLocationTracking()
    }

    override fun onDestroyView() {
        timerHandler.removeCallbacks(timerTick)
        stopRealLocationTracking()
        binding.map.onDetach()
        _binding = null
        super.onDestroyView()
    }

    /** Centers the map on [position] and marks it as the place being looked at. */
    fun focus(position: LatLon) {
        val b = _binding ?: return
        val point = GeoPoint(position.lat, position.lon)
        selectionMarker.position = point
        if (!b.map.overlays.contains(selectionMarker)) b.map.overlays.add(selectionMarker)
        b.map.controller.animateTo(point, b.map.zoomLevelDouble.coerceAtLeast(16.0), 500L)
        b.map.invalidate()
    }

    fun clearSelection() {
        val b = _binding ?: return
        b.map.overlays.remove(selectionMarker)
        b.map.invalidate()
    }

    /** Remember where we really are before the GPS starts reporting the fake position. */
    @SuppressLint("MissingPermission")
    fun rememberRealLocation() {
        if (MockState.isFaking || !requireContext().hasLocationPermission()) return
        fusedClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).addOnSuccessListener { location ->
            if (location != null && !location.isMockCompat()) prefs.lastRealLocation = LatLon(location.latitude, location.longitude)
        }
    }

    private fun applyInsets() {
        val top = (binding.topPanel.layoutParams as MarginLayoutParams).topMargin
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            binding.topPanel.updateLayoutParams<MarginLayoutParams> { topMargin = top + bars.top }
            insets
        }
    }

    private fun setupMap() = with(binding.map) {
        setTileSource(TileSourceFactory.MAPNIK)
        setMultiTouchControls(true)
        zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        isTilesScaledToDpi = true
        controller.setZoom(15.0)
        val start = prefs.activeSession?.position ?: prefs.lastRealLocation ?: LatLon(30.0444, 31.2357) // Cairo
        controller.setCenter(GeoPoint(start.lat, start.lon))

        realMarker = Marker(this).apply {
            icon = ContextCompat.getDrawable(requireContext(), R.drawable.marker_real)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            setInfoWindow(null)
            setOnMarkerClickListener { _, _ -> true }
        }
        fakeMarker = Marker(this).apply {
            icon = ContextCompat.getDrawable(requireContext(), R.drawable.marker_fake)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            setInfoWindow(null)
            setOnMarkerClickListener { _, _ -> true }
        }
        selectionMarker = Marker(this).apply {
            icon = ContextCompat.getDrawable(requireContext(), R.drawable.marker_fake)?.mutate()?.apply { alpha = 150 }
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            setInfoWindow(null)
            setOnMarkerClickListener { _, _ -> true }
        }

        overlays.add(MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint) = false
            override fun longPressHelper(p: GeoPoint): Boolean {
                main.showPlace(Place(LatLon(p.latitude, p.longitude)))
                return true
            }
        }))
        prefs.lastRealLocation?.let { showRealMarker(it) }
    }

    private fun setupControls() {
        binding.searchPill.setOnClickListener { main.openSearch() }
        binding.myLocationButton.setOnClickListener {
            val trip = tripView
            if (tripShown && trip != null && !trip.follow) {
                trip.follow = true
                MockState.trip.value?.let { trip.show(it) }
            } else {
                goToMyLocation()
            }
        }
        // Dragging the map during a trip stops it from following the moving arrow.
        binding.map.setOnTouchListener { _, event ->
            if (event.actionMasked == android.view.MotionEvent.ACTION_MOVE) tripView?.follow = false
            false
        }
        binding.teleportButton.setOnClickListener {
            val center = binding.map.mapCenter
            main.showPlace(Place(LatLon(center.latitude, center.longitude)))
        }
        binding.planRouteButton.setOnClickListener { main.newRouteFromHere() }
        binding.routeFromHereButton.setOnClickListener { main.newRouteFromHere() }
        binding.moveButton.setOnClickListener { main.openSearch() }
        binding.stopButton.setOnClickListener { MockLocationService.stop(requireContext()) }
        binding.saveButton.setOnClickListener {
            val session = MockState.session.value ?: return@setOnClickListener
            val existing = places.favoriteAt(session.position)
            if (existing != null) {
                places.deleteFavorite(existing.id)
                requireContext().toast(R.string.favorite_removed)
            } else {
                requireContext().askName(R.string.favorite_name_title, session.name) { name ->
                    places.addFavorite(name, session.position)
                    requireContext().toast(R.string.favorite_added)
                }
            }
        }
    }

    private fun renderTrip(trip: com.abdulrahman.fakelocation.mock.TripUi) {
        val view = tripView ?: TripView(binding).also { tripView = it }
        if (!tripShown) {
            tripShown = true
            timerHandler.removeCallbacks(timerTick)
            binding.map.overlays.remove(fakeMarker)
            binding.map.overlays.remove(selectionMarker)
            binding.searchPill.isVisible = false
            binding.chipsScroll.isVisible = false
            binding.activePill.isVisible = false
            binding.idlePanel.isVisible = false
            binding.activePanel.isVisible = false
            binding.tripCard.isVisible = true
            binding.tripPanel.isVisible = true
            binding.map.controller.setZoom(16.5)
        }
        view.show(trip)
        updateRealLocationTracking()
    }

    private fun endTripView() {
        tripShown = false
        tripView?.clear()
        binding.tripCard.isVisible = false
        binding.tripPanel.isVisible = false
        binding.searchPill.isVisible = true
    }

    private fun renderSession(session: FakeSession?, favorites: List<Favorite>) {
        val map = binding.map
        binding.idlePanel.isVisible = session == null
        binding.activePanel.isVisible = session != null
        binding.activePill.isVisible = session != null
        binding.chipsScroll.isVisible = session == null
        timerHandler.removeCallbacks(timerTick)
        if (session == null) {
            map.overlays.remove(fakeMarker)
        } else {
            fakeMarker.position = GeoPoint(session.position.lat, session.position.lon)
            if (!map.overlays.contains(fakeMarker)) map.overlays.add(fakeMarker)
            map.overlays.remove(selectionMarker)
            binding.activeName.text = session.name
            binding.activeCoords.text = session.position.format()
            val saved = favorites.any { distanceMeters(it.position, session.position) < 15.0 }
            binding.saveButton.setText(if (saved) R.string.saved else R.string.save)
            binding.saveButton.setIconResource(if (saved) R.drawable.ic_star_filled else R.drawable.ic_star)
            timerHandler.post(timerTick)
        }
        map.invalidate()
        updateRealLocationTracking()
    }

    private fun renderChips(favorites: List<Favorite>) {
        val group = binding.chips
        group.removeAllViews()
        for (favorite in favorites.take(6)) {
            group.addView(chip(favorite.name, iconFor(favorite.name), tinted = true) {
                main.showPlace(Place(favorite.position, favorite.name))
            })
        }
        group.addView(chip(getString(R.string.recent), R.drawable.ic_history, tinted = false) {
            main.selectTab(R.id.nav_places)
            (parentFragmentManager.findFragmentByTag("places") as? PlacesFragment)?.showHistory()
        })
    }

    private fun chip(text: String, icon: Int, tinted: Boolean, onClick: () -> Unit) =
        Chip(requireContext(), null, 0).apply {
            setChipDrawable(com.google.android.material.chip.ChipDrawable.createFromAttributes(requireContext(), null, 0, R.style.Chip_Place))
            setTextAppearance(R.style.Text_Item)
            textSize = 14f
            this.text = text
            setChipIconResource(icon)
            chipIconTint = ContextCompat.getColorStateList(requireContext(), if (tinted) R.color.action else R.color.muted)
            setOnClickListener { onClick() }
        }

    private fun iconFor(name: String): Int {
        val lower = name.lowercase()
        return when {
            "home" in lower || "house" in lower -> R.drawable.ic_home
            else -> R.drawable.ic_star
        }
    }

    private fun goToMyLocation() {
        val context = requireContext()
        if (!context.hasLocationPermission()) {
            main.openSetup()
            return
        }
        val real = prefs.lastRealLocation
        if (MockState.isFaking) {
            if (real == null) {
                context.toast(R.string.real_location_unknown)
                return
            }
            context.toast(R.string.real_location_cached)
        }
        if (real != null) {
            binding.map.controller.animateTo(GeoPoint(real.lat, real.lon), 17.0, 600L)
        } else {
            centerOnNextRealFix = true
        }
    }

    private fun showRealMarker(real: LatLon) {
        realMarker.position = GeoPoint(real.lat, real.lon)
        if (!binding.map.overlays.contains(realMarker)) binding.map.overlays.add(realMarker)
        binding.map.invalidate()
    }

    /** Track the real GPS only while the fake location is off and the map is on screen. */
    @SuppressLint("MissingPermission")
    private fun updateRealLocationTracking() {
        val shouldTrack = isAdded && !isHidden && requireContext().hasLocationPermission() &&
            !MockState.isFaking && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        if (shouldTrack && !trackingRealLocation) {
            val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5_000L).build()
            fusedClient.requestLocationUpdates(request, realLocationCallback, Looper.getMainLooper())
            trackingRealLocation = true
        } else if (!shouldTrack) {
            stopRealLocationTracking()
        }
    }

    private fun stopRealLocationTracking() {
        if (!trackingRealLocation) return
        fusedClient.removeLocationUpdates(realLocationCallback)
        trackingRealLocation = false
    }
}

private fun android.location.Location.isMockCompat(): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) isMock else @Suppress("DEPRECATION") isFromMockProvider
