package com.abdulrahman.fakelocation.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.abdulrahman.fakelocation.R
import com.abdulrahman.fakelocation.data.Place
import com.abdulrahman.fakelocation.data.PlacesRepository
import com.abdulrahman.fakelocation.data.Prefs
import com.abdulrahman.fakelocation.data.RoutesRepository
import com.abdulrahman.fakelocation.data.format
import com.abdulrahman.fakelocation.databinding.ActivityMainBinding
import com.abdulrahman.fakelocation.geo.MapsLinkResolver
import com.abdulrahman.fakelocation.mock.LocationMocker
import com.abdulrahman.fakelocation.mock.MockLocationService
import com.abdulrahman.fakelocation.mock.MockState
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs

    private val searchLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        SearchActivity.placeFrom(result.data)?.let { showPlace(it) }
    }

    private val favoriteSearchLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val place = SearchActivity.placeFrom(result.data) ?: return@registerForActivityResult
        askName(R.string.favorite_name_title, place.name.orEmpty()) { name ->
            PlacesRepository.get(this).addFavorite(name, place.position)
            toast(R.string.favorite_added)
        }
    }

    val mapFragment: MapFragment?
        get() = supportFragmentManager.findFragmentByTag(TAG_MAP) as? MapFragment

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        if (!prefs.setupDone) {
            startActivity(Intent(this, SetupActivity::class.java).putExtras(intent))
            finish()
            return
        }
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Navigation isn't open: make sure the GPS chip is faked again if it was left real.
        if (prefs.realGpsForNav && savedInstanceState == null && !com.abdulrahman.fakelocation.nav.NavSession.state.value.navigating) {
            MockLocationService.setRealGpsForNavigation(this, false)
        }

        // Pick up a fake location that is still running from before the app was closed.
        if (MockState.session.value == null) prefs.activeSession?.let { MockState.set(it) }

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .add(R.id.container, MapFragment(), TAG_MAP)
                .add(R.id.container, RoutesFragment(), TAG_ROUTES)
                .add(R.id.container, PlacesFragment(), TAG_PLACES)
                .add(R.id.container, SettingsFragment(), TAG_SETTINGS)
                .commitNow()
        }
        binding.bottomNav.setOnItemSelectedListener { item ->
            showTab(item.itemId)
            true
        }
        showTab(binding.bottomNav.selectedItemId)
        // A trip that is running or scheduled is shown on the map.
        if (MockState.trip.value != null || prefs.activeTrip != null) selectTab(R.id.nav_map)
        if (MockState.trip.value == null && prefs.activeTrip != null) {
            // The service was stopped by the system; start it again so the trip picks up where it was.
            androidx.core.content.ContextCompat.startForegroundService(this, Intent(this, MockLocationService::class.java))
        }
        handleSharedText(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_SHOW_MAP, false)) selectTab(R.id.nav_map)
        handleSharedText(intent)
    }

    override fun onStop() {
        super.onStop()
        // Favorites may have changed: keep the widget's "Go" button current.
        com.abdulrahman.fakelocation.system.StatusWidget.update(this)
    }

    fun selectTab(itemId: Int) {
        binding.bottomNav.selectedItemId = itemId
    }

    private fun showTab(itemId: Int) {
        val tag = when (itemId) {
            R.id.nav_routes -> TAG_ROUTES
            R.id.nav_places -> TAG_PLACES
            R.id.nav_settings -> TAG_SETTINGS
            else -> TAG_MAP
        }
        val transaction = supportFragmentManager.beginTransaction()
        for (t in listOf(TAG_MAP, TAG_ROUTES, TAG_PLACES, TAG_SETTINGS)) {
            val fragment: Fragment = supportFragmentManager.findFragmentByTag(t) ?: continue
            if (t == tag) transaction.show(fragment) else transaction.hide(fragment)
        }
        transaction.commitNow()
    }

    /** Switches to the map, centers on [place] and opens its teleport sheet. */
    fun showPlace(place: Place) {
        selectTab(R.id.nav_map)
        mapFragment?.focus(place.position)
        TeleportSheet.show(supportFragmentManager, place)
    }

    fun openSearch() = searchLauncher.launch(Intent(this, SearchActivity::class.java))

    fun openSearchForFavorite() = favoriteSearchLauncher.launch(Intent(this, SearchActivity::class.java))

    /** Starts faking [place], first making sure Android lets this app do it. */
    fun teleport(place: Place) {
        if (!hasLocationPermission() || !LocationMocker.isSelectedMockApp(this)) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.step_mock)
                .setMessage(R.string.mock_not_selected)
                .setPositiveButton(R.string.setup_guide) { _, _ -> openSetup() }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }
        mapFragment?.rememberRealLocation()
        MockLocationService.teleport(this, place.position, place.name ?: place.position.format())
        selectTab(R.id.nav_map)
        mapFragment?.focus(place.position)
    }

    fun openSetup() = startActivity(SetupActivity.fromSettings(this))

    fun openRouteEditor(routeId: Long? = null, addStop: Place? = null) =
        startActivity(RouteEditorActivity.intent(this, routeId, addStop))

    fun openRoutePlan(routeId: Long) = startActivity(RoutePlanActivity.intent(this, routeId))

    /** Adds [place] as the next stop of a route the user picks (or a new one). */
    fun addToRoute(place: Place) {
        val routes = RoutesRepository.get(this).routes.value
        if (routes.isEmpty()) {
            openRouteEditor(addStop = place)
            return
        }
        val names = listOf(getString(R.string.new_route)) + routes.map { it.name }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.choose_route)
            .setItems(names.toTypedArray()) { _, which ->
                openRouteEditor(routeId = if (which == 0) null else routes[which - 1].id, addStop = place)
            }
            .show()
    }

    /** A new route that starts where the phone currently is (fake or real). */
    fun newRouteFromHere() {
        val session = MockState.session.value
        val start = session?.let { Place(it.position, it.name) }
            ?: prefs.lastRealLocation?.let { Place(it, getString(R.string.my_position)) }
        openRouteEditor(addStop = start)
    }

    private fun handleSharedText(intent: Intent?) {
        val text = intent?.getStringExtra(EXTRA_SHARED_TEXT) ?: return
        intent.removeExtra(EXTRA_SHARED_TEXT)
        val reading = Snackbar.make(binding.root, R.string.reading_link, Snackbar.LENGTH_INDEFINITE)
        reading.show()
        lifecycleScope.launch {
            val place = runCatching { MapsLinkResolver.resolve(text) }.getOrNull()
            reading.dismiss()
            if (place == null) toast(R.string.link_failed) else showPlace(place)
        }
    }

    companion object {
        const val EXTRA_SHARED_TEXT = "shared_text"
        private const val EXTRA_SHOW_MAP = "show_map"

        fun showMap(context: Context) =
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_SHOW_MAP, true)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        private const val TAG_MAP = "map"
        private const val TAG_ROUTES = "routes"
        private const val TAG_PLACES = "places"
        private const val TAG_SETTINGS = "settings"

        fun withSharedText(context: Context, text: String) =
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_SHARED_TEXT, text)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}
