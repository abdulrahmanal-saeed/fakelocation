package com.abdulrahman.fakelocation.car

import android.text.SpannableStringBuilder
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.SectionedItemList
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.RoutePreviewNavigationTemplate
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.abdulrahman.fakelocation.R
import com.abdulrahman.fakelocation.data.LatLon
import com.abdulrahman.fakelocation.data.Place
import com.abdulrahman.fakelocation.data.PlacesRepository
import com.abdulrahman.fakelocation.data.TravelMode
import com.abdulrahman.fakelocation.data.distanceMeters
import com.abdulrahman.fakelocation.drive.Fuel
import com.abdulrahman.fakelocation.drive.Tolls
import com.abdulrahman.fakelocation.geo.NearbySearch
import com.abdulrahman.fakelocation.geo.PlaceCategory
import com.abdulrahman.fakelocation.geo.PlaceSearch
import com.abdulrahman.fakelocation.nav.NavSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private fun Screen.listLimit(): Int =
    if (carContext.carAppApiLevel >= 2) {
        carContext.getCarService(ConstraintManager::class.java).getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST)
    } else 6

private fun here(): LatLon? = NavSession.state.value.real

/** A place row: name, address and how far it is; tapping it previews the route. */
private fun Screen.placeRow(place: Place, fallback: String): Row {
    val row = Row.Builder().setTitle(place.name?.takeIf { it.isNotBlank() } ?: fallback)
    val text = SpannableStringBuilder()
    here()?.let { text.append(CarUi.distanceText(distanceMeters(it, place.position))) }
    place.address?.takeIf { it.isNotBlank() }?.let { if (text.isNotEmpty()) text.append(" · "); text.append(it) }
    if (text.isNotEmpty()) row.addText(text)
    return row.setOnClickListener {
        if (NavSession.state.value.navigating) {
            // While driving, a picked place becomes the next stop on the way.
            NavSession.addStop(place)
            screenManager.popToRoot()
        } else {
            screenManager.push(CarPreviewScreen(carContext, place))
        }
    }.build()
}

/** Shown when the phone app hasn't been given location access yet. */
class CarMessageScreen(carContext: CarContext) : Screen(carContext) {
    override fun onGetTemplate(): Template =
        MessageTemplate.Builder(carContext.getString(R.string.car_need_phone))
            .setTitle(carContext.getString(R.string.app_name))
            .build()
}

class CarSearchScreen(carContext: CarContext) : Screen(carContext) {

    private var results: List<Place> = emptyList()
    private var loading = false
    private var searched = false
    private var job: Job? = null

    override fun onGetTemplate(): Template {
        val builder = SearchTemplate.Builder(object : SearchTemplate.SearchCallback {
            override fun onSearchTextChanged(searchText: String) = search(searchText, 600)
            override fun onSearchSubmitted(searchText: String) = search(searchText, 0)
        })
            .setHeaderAction(Action.BACK)
            .setSearchHint(carContext.getString(R.string.car_search_hint))
            .setShowKeyboardByDefault(true)
        if (loading) return builder.setLoading(true).build()
        val list = ItemList.Builder()
        results.take(listLimit()).forEach { list.addItem(placeRow(it, carContext.getString(R.string.nav_choose_destination))) }
        if (searched && results.isEmpty()) list.setNoItemsMessage(carContext.getString(R.string.car_no_results))
        return builder.setItemList(list.build()).build()
    }

    private fun search(query: String, debounceMs: Long) {
        job?.cancel()
        if (query.isBlank()) {
            results = emptyList(); searched = false; invalidate(); return
        }
        job = lifecycleScope.launch {
            delay(debounceMs)
            loading = true
            invalidate()
            results = runCatching { PlaceSearch.search(carContext, query, here()) }.getOrDefault(emptyList())
            loading = false
            searched = true
            invalidate()
        }
    }
}

/** Favorites and recent places. */
class CarSavedScreen(carContext: CarContext) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        val repo = PlacesRepository.get(carContext)
        val limit = listLimit()
        val favorites = ItemList.Builder()
        repo.favorites.value.take(limit).forEach { favorites.addItem(placeRow(Place(it.position, it.name), it.name)) }
        val recent = ItemList.Builder()
        repo.history.value.distinctBy { it.name }.take(limit).forEach { recent.addItem(placeRow(Place(it.position, it.name), it.name)) }
        val builder = ListTemplate.Builder().setHeaderAction(Action.BACK).setTitle(carContext.getString(R.string.car_saved))
        if (repo.favorites.value.isEmpty() && repo.history.value.isEmpty()) {
            return builder.setSingleList(ItemList.Builder().setNoItemsMessage(carContext.getString(R.string.car_no_saved)).build()).build()
        }
        if (repo.favorites.value.isNotEmpty()) {
            builder.addSectionedList(SectionedItemList.create(favorites.build(), carContext.getString(R.string.nav_places)))
        }
        if (repo.history.value.isNotEmpty()) {
            builder.addSectionedList(SectionedItemList.create(recent.build(), carContext.getString(R.string.car_recent)))
        }
        return builder.build()
    }
}

class CarCategoriesScreen(carContext: CarContext) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        val list = ItemList.Builder()
        PlaceCategory.entries.forEach { category ->
            list.addItem(
                Row.Builder().setTitle(carContext.getString(category.label))
                    .setImage(CarUi.icon(carContext, category.icon))
                    .setBrowsable(true)
                    .setOnClickListener { screenManager.push(CarNearbyScreen(carContext, category)) }
                    .build(),
            )
        }
        return ListTemplate.Builder().setHeaderAction(Action.BACK).setTitle(carContext.getString(R.string.nearby))
            .setSingleList(list.build()).build()
    }
}

class CarNearbyScreen(carContext: CarContext, private val category: PlaceCategory) : Screen(carContext) {

    private var results: List<Place>? = null

    init {
        lifecycleScope.launch {
            val center = here() ?: LatLon.HOME_REGION
            results = runCatching { NearbySearch.find(carContext, category, center) }.getOrDefault(emptyList())
            invalidate()
        }
    }

    override fun onGetTemplate(): Template {
        val label = carContext.getString(category.label)
        val builder = ListTemplate.Builder().setHeaderAction(Action.BACK)
            .setTitle(carContext.getString(R.string.nearby_title, label))
        val places = results ?: return builder.setLoading(true).build()
        val list = ItemList.Builder()
        places.take(listLimit()).forEach { list.addItem(placeRow(it, label)) }
        if (places.isEmpty()) list.setNoItemsMessage(carContext.getString(R.string.nearby_none))
        return builder.setSingleList(list.build()).build()
    }
}

/** "Fuel ≈ AED 3.10 · Salik AED 10" for the car screen. */
private fun Screen.costText(s: NavSession.State): String {
    val parts = mutableListOf(carContext.getString(R.string.nav_cost_fuel, Fuel.aed(s.fuelCostAed)))
    if (s.tolls.isEmpty()) parts += carContext.getString(R.string.nav_cost_no_tolls)
    s.tolls.groupBy { it.gate.system }.forEach { (system, passes) ->
        parts += system.label + " " + Fuel.aed(Tolls.total(passes)).replace(".00", "")
    }
    return parts.joinToString(" · ")
}

/** The route to a place before starting: time and distance, and a Start button. */
class CarPreviewScreen(carContext: CarContext, private val place: Place) : Screen(carContext) {

    init {
        NavSession.clear()
        NavSession.setStart(null)
        NavSession.setMode(TravelMode.CAR)
        NavSession.setDestination(place)
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            // Backing out without starting drops the previewed route from the map.
            override fun onDestroy(owner: LifecycleOwner) = NavSession.clear()
        })
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                var last: Any? = null
                NavSession.state.collect { s ->
                    val key = Triple(s.status, s.route, s.tolls)
                    if (key != last) { last = key; invalidate() }
                }
            }
        }
    }

    override fun onGetTemplate(): Template {
        val s = NavSession.state.value
        val title = place.name ?: carContext.getString(R.string.nav_choose_destination)
        if (s.status == NavSession.Status.ERROR) {
            return MessageTemplate.Builder(carContext.getString(R.string.nav_route_failed))
                .setTitle(title)
                .setHeaderAction(Action.BACK)
                .addAction(Action.Builder().setTitle(carContext.getString(R.string.car_retry)).setOnClickListener { NavSession.plan() }.build())
                .build()
        }
        val builder = RoutePreviewNavigationTemplate.Builder().setTitle(title).setHeaderAction(Action.BACK)
        val route = s.route
        if (route == null || s.status == NavSession.Status.ROUTING) return builder.setLoading(true).build()

        val summary = SpannableStringBuilder()
            .append(CarUi.durationText(route.durationSeconds))
            .append(" · ")
            .append(CarUi.distanceText(route.distanceMeters))
        val row = Row.Builder().setTitle(summary)
        row.addText(costText(s))
        place.address?.takeIf { it.isNotBlank() }?.let { row.addText(it) }
        val avoid = s.avoid
        builder.setActionStrip(
            ActionStrip.Builder().addAction(
                Action.Builder()
                    .setTitle(carContext.getString(if (avoid.tolls) R.string.car_tolls_avoided else R.string.nav_avoid_tolls))
                    .setOnClickListener { NavSession.setAvoid(avoid.copy(tolls = !avoid.tolls)) }
                    .build(),
            ).build(),
        )
        return builder
            .setItemList(ItemList.Builder().addItem(row.build()).setSelectedIndex(0).setOnSelectedListener { }.build())
            .setNavigateAction(
                Action.Builder().setTitle(carContext.getString(R.string.car_start)).setOnClickListener {
                    NavSession.start()
                    screenManager.popToRoot()
                }.build(),
            )
            .build()
    }
}
