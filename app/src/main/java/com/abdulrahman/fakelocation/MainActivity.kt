package com.abdulrahman.fakelocation

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.provider.Settings
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.abdulrahman.fakelocation.databinding.ActivityMainBinding
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import android.view.ViewGroup.MarginLayoutParams

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs
    private lateinit var fakeMarker: Marker
    private lateinit var realMarker: Marker
    private val fusedClient by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private var trackingRealLocation = false
    private var centerOnNextRealFix = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            centerOnNextRealFix = prefs.lastRealLocation == null
            updateRealLocationTracking()
        } else {
            Toast.makeText(this, R.string.permission_needed, Toast.LENGTH_LONG).show()
        }
    }

    private val realLocationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val location = result.lastLocation ?: return
            // While faking, the system hands us our own fake position, so ignore it.
            if (location.isMockCompat() || MockState.fakePosition.value != null) return
            val real = LatLon(location.latitude, location.longitude)
            prefs.lastRealLocation = real
            showRealMarker(real)
            if (centerOnNextRealFix) {
                centerOnNextRealFix = false
                binding.map.controller.animateTo(GeoPoint(real.lat, real.lon), 16.0, 600L)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        Configuration.getInstance().apply {
            load(applicationContext, getSharedPreferences("osmdroid", MODE_PRIVATE))
            userAgentValue = packageName
        }
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyWindowInsets()
        setupMap()
        setupControls()

        // Restore the fake position if the service is still running from before.
        if (MockState.fakePosition.value == null) {
            prefs.activeFakeLocation?.let { MockState.setFakePosition(it) }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                MockState.fakePosition.collect { renderFakePosition(it) }
            }
        }
        requestPermissionsIfNeeded()
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

    private fun applyWindowInsets() {
        val topMargin = (binding.topPanel.layoutParams as MarginLayoutParams).topMargin
        val bottomMargin = (binding.myLocationButton.layoutParams as MarginLayoutParams).bottomMargin
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            binding.topPanel.updateLayoutParams<MarginLayoutParams> { this.topMargin = topMargin + bars.top }
            binding.myLocationButton.updateLayoutParams<MarginLayoutParams> { this.bottomMargin = bottomMargin + bars.bottom }
            binding.stopButton.updateLayoutParams<MarginLayoutParams> { this.bottomMargin = bottomMargin + bars.bottom }
            insets
        }
    }

    private fun setupMap() = with(binding.map) {
        setTileSource(TileSourceFactory.MAPNIK)
        setMultiTouchControls(true)
        zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        isTilesScaledToDpi = true
        controller.setZoom(15.0)
        val start = prefs.activeFakeLocation ?: prefs.lastRealLocation ?: LatLon(30.0444, 31.2357) // Cairo
        controller.setCenter(GeoPoint(start.lat, start.lon))

        realMarker = Marker(this).apply {
            icon = ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_real_dot)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            title = getString(R.string.my_location)
            setInfoWindow(null)
        }
        fakeMarker = Marker(this).apply {
            icon = ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_pin_fake)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            setInfoWindow(null)
        }

        overlays.add(MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint) = false
            override fun longPressHelper(p: GeoPoint): Boolean {
                confirmTeleport(LatLon(p.latitude, p.longitude))
                return true
            }
        }))
        prefs.lastRealLocation?.let { showRealMarker(it) }
    }

    private fun setupControls() {
        binding.myLocationButton.setOnClickListener { goToMyLocation() }
        binding.stopButton.setOnClickListener { MockLocationService.stop(this) }
        binding.menuButton.setOnClickListener { showSetupGuide() }
        binding.searchInput.setOnEditorActionListener { view, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                val query = view.text.toString().trim()
                if (query.isNotEmpty()) searchPlace(query)
                true
            } else {
                false
            }
        }
    }

    private fun confirmTeleport(target: LatLon) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.teleport_title)
            .setMessage("%.6f, %.6f".format(target.lat, target.lon))
            .setPositiveButton(R.string.teleport_action) { _, _ -> teleport(target) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun teleport(target: LatLon) {
        if (!hasLocationPermission()) {
            requestPermissionsIfNeeded()
            return
        }
        if (!LocationMocker.isSelectedMockApp(this)) {
            showSetupGuide()
            return
        }
        // Remember where we really are before the GPS starts reporting the fake position.
        if (MockState.fakePosition.value == null) captureRealLocationOnce()
        MockLocationService.teleport(this, target)
    }

    private fun renderFakePosition(position: LatLon?) {
        val map = binding.map
        if (position == null) {
            map.overlays.remove(fakeMarker)
            binding.statusText.setText(R.string.status_idle)
        } else {
            fakeMarker.position = GeoPoint(position.lat, position.lon)
            if (!map.overlays.contains(fakeMarker)) map.overlays.add(fakeMarker)
            binding.statusText.text = getString(R.string.status_fixed, position.lat, position.lon)
        }
        binding.stopButton.isVisible = position != null
        map.invalidate()
        updateRealLocationTracking()
    }

    private fun goToMyLocation() {
        if (!hasLocationPermission()) {
            requestPermissionsIfNeeded()
            return
        }
        val real = prefs.lastRealLocation
        if (MockState.fakePosition.value != null) {
            if (real == null) {
                Toast.makeText(this, R.string.real_location_unknown, Toast.LENGTH_LONG).show()
                return
            }
            Toast.makeText(this, R.string.real_location_cached, Toast.LENGTH_SHORT).show()
        }
        if (real != null) {
            binding.map.controller.animateTo(GeoPoint(real.lat, real.lon), 17.0, 600L)
        } else {
            centerOnNextRealFix = true
        }
        if (MockState.fakePosition.value == null) captureRealLocationOnce()
    }

    private fun showRealMarker(real: LatLon) {
        realMarker.position = GeoPoint(real.lat, real.lon)
        if (!binding.map.overlays.contains(realMarker)) binding.map.overlays.add(realMarker)
        binding.map.invalidate()
    }

    @SuppressLint("MissingPermission")
    private fun captureRealLocationOnce() {
        if (!hasLocationPermission()) return
        fusedClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).addOnSuccessListener { location ->
            if (location == null || location.isMockCompat()) return@addOnSuccessListener
            realLocationCallback.onLocationResult(LocationResult.create(listOf(location)))
        }
    }

    /** Track the real GPS only while the fake location is off. */
    @SuppressLint("MissingPermission")
    private fun updateRealLocationTracking() {
        val shouldTrack = hasLocationPermission() && MockState.fakePosition.value == null &&
            lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
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

    private fun searchPlace(query: String) {
        getSystemService(InputMethodManager::class.java)
            .hideSoftInputFromWindow(binding.searchInput.windowToken, 0)
        lifecycleScope.launch {
            val results = runCatching { PlaceSearch.search(query, packageName) }.getOrDefault(emptyList())
            if (results.isEmpty()) {
                Toast.makeText(this@MainActivity, R.string.search_failed, Toast.LENGTH_SHORT).show()
                return@launch
            }
            MaterialAlertDialogBuilder(this@MainActivity)
                .setItems(results.map { it.name }.toTypedArray()) { _, which ->
                    val place = results[which].position
                    binding.map.controller.animateTo(GeoPoint(place.lat, place.lon), 17.0, 600L)
                    confirmTeleport(place)
                }
                .show()
        }
    }

    private fun showSetupGuide() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.mock_setup_title)
            .setMessage(R.string.mock_setup_message)
            .setPositiveButton(R.string.open_developer_options) { _, _ -> openDeveloperOptions() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun openDeveloperOptions() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        } catch (_: Exception) {
            Toast.makeText(this, R.string.developer_options_disabled, Toast.LENGTH_LONG).show()
        }
    }

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestPermissionsIfNeeded() {
        val needed = buildList {
            if (!hasLocationPermission()) {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (needed.isNotEmpty()) permissionLauncher.launch(needed.toTypedArray())
    }
}

private fun android.location.Location.isMockCompat(): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) isMock else @Suppress("DEPRECATION") isFromMockProvider
