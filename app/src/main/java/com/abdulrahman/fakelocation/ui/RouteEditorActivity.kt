package com.abdulrahman.fakelocation.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
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
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.abdulrahman.fakelocation.R
import com.abdulrahman.fakelocation.data.LatLon
import com.abdulrahman.fakelocation.data.Place
import com.abdulrahman.fakelocation.data.Prefs
import com.abdulrahman.fakelocation.data.Route
import com.abdulrahman.fakelocation.data.RoutesRepository
import com.abdulrahman.fakelocation.data.Schedule
import com.abdulrahman.fakelocation.data.Stop
import com.abdulrahman.fakelocation.data.TravelMode
import com.abdulrahman.fakelocation.data.format
import com.abdulrahman.fakelocation.data.formatDistance
import com.abdulrahman.fakelocation.data.formatDuration
import com.abdulrahman.fakelocation.databinding.ActivityRouteEditorBinding
import com.abdulrahman.fakelocation.databinding.ItemAddStopBinding
import com.abdulrahman.fakelocation.databinding.ItemStopBinding
import com.abdulrahman.fakelocation.geo.PlaceSearch
import com.abdulrahman.fakelocation.geo.Router
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

/** Build a route: stops (search or long-press), wait time at each, car or walk, path on real streets. */
class RouteEditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRouteEditorBinding
    private lateinit var repo: RoutesRepository
    private lateinit var route: Route
    private val adapter = StopsAdapter()
    private lateinit var touchHelper: ItemTouchHelper
    private var routingJob: Job? = null
    private var routingFailed = false
    private val pathLine by lazy {
        Polyline(binding.map).apply {
            outlinePaint.color = ContextCompat.getColor(this@RouteEditorActivity, R.color.ink)
            outlinePaint.strokeWidth = 7 * resources.displayMetrics.density
            outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
            outlinePaint.strokeJoin = android.graphics.Paint.Join.ROUND
        }
    }
    private val stopMarkers = mutableListOf<Marker>()

    private val searchLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        SearchActivity.placeFrom(result.data)?.let { addStop(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityRouteEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        repo = RoutesRepository.get(this)

        val id = intent.getLongExtra(EXTRA_ROUTE_ID, -1)
        route = repo.get(id) ?: Route(
            RoutesRepository.newId(),
            getString(R.string.route_name_default, repo.routes.value.size + 1),
            TravelMode.CAR,
            emptyList(),
        )
        if (savedInstanceState == null) placeFromIntent(intent)?.let { addStop(it, save = false) }

        applyInsets()
        setupMap()
        setupPanel()
        render()
        if (route.path == null) requestRoute(immediate = true) else fitMap()
    }

    override fun onResume() {
        super.onResume()
        binding.map.onResume()
    }

    override fun onPause() {
        binding.map.onPause()
        persist()
        super.onPause()
    }

    private fun persist() {
        if (route.stops.isEmpty()) repo.delete(route.id) else repo.save(route)
    }

    private fun applyInsets() {
        val top = (binding.backButton.layoutParams as ViewGroup.MarginLayoutParams).topMargin
        val bottom = binding.footer.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            binding.backButton.updateLayoutParams<ViewGroup.MarginLayoutParams> { topMargin = top + bars.top }
            binding.deleteButton.updateLayoutParams<ViewGroup.MarginLayoutParams> { topMargin = top + bars.top }
            binding.footer.updatePadding(bottom = bottom + bars.bottom)
            insets
        }
    }

    private fun setupMap() = with(binding.map) {
        MapStyle.apply(this, lifecycleScope)
        setMultiTouchControls(true)
        zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        isTilesScaledToDpi = true
        controller.setZoom(14.0)
        val start = route.stops.firstOrNull()?.position ?: Prefs(this@RouteEditorActivity).lastRealLocation ?: LatLon.HOME_REGION
        controller.setCenter(GeoPoint(start.lat, start.lon))
        overlays.add(MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint) = false
            override fun longPressHelper(p: GeoPoint): Boolean {
                addStop(Place(LatLon(p.latitude, p.longitude)))
                return true
            }
        }))
        overlays.add(pathLine)
    }

    private fun setupPanel() {
        binding.backButton.setOnClickListener { finish() }
        binding.deleteButton.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.delete_route_title, route.name))
                .setPositiveButton(R.string.delete) { _, _ ->
                    route = route.copy(stops = emptyList())
                    finish()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
        binding.routeName.setOnClickListener {
            askName(R.string.rename_route, route.name) { name ->
                route = route.copy(name = name)
                render()
                persist()
            }
        }
        binding.modeGroup.check(if (route.mode == TravelMode.CAR) R.id.modeCar else R.id.modeWalk)
        binding.modeGroup.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            val mode = if (id == R.id.modeWalk) TravelMode.WALK else TravelMode.CAR
            if (mode != route.mode) changeStops(route.stops, mode)
        }
        binding.retryButton.setOnClickListener { requestRoute(immediate = true) }
        binding.nextButton.setOnClickListener {
            persist()
            startActivity(RoutePlanActivity.intent(this, route.id))
        }

        binding.stops.layoutManager = LinearLayoutManager(this)
        binding.stops.adapter = adapter
        touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
        ) {
            private var dragged: MutableList<Stop>? = null

            override fun getMovementFlags(rv: RecyclerView, holder: RecyclerView.ViewHolder) =
                if (holder is StopsAdapter.AddHolder) 0 else super.getMovementFlags(rv, holder)

            override fun isLongPressDragEnabled() = true

            override fun onMove(rv: RecyclerView, from: RecyclerView.ViewHolder, to: RecyclerView.ViewHolder): Boolean {
                if (to is StopsAdapter.AddHolder) return false
                val list = dragged ?: route.stops.toMutableList().also { dragged = it }
                val item = list.removeAt(from.bindingAdapterPosition)
                list.add(to.bindingAdapterPosition, item)
                adapter.preview(list)
                return true
            }

            override fun clearView(rv: RecyclerView, holder: RecyclerView.ViewHolder) {
                super.clearView(rv, holder)
                dragged?.let { changeStops(it) }
                dragged = null
            }

            override fun onSwiped(holder: RecyclerView.ViewHolder, direction: Int) {
                val index = holder.bindingAdapterPosition
                val removed = route.stops[index]
                changeStops(route.stops.filterIndexed { i, _ -> i != index })
                Snackbar.make(binding.root, R.string.stop_removed, Snackbar.LENGTH_LONG)
                    .setAnchorView(binding.footer)
                    .setAction(R.string.undo) {
                        changeStops(route.stops.toMutableList().apply { add(index.coerceAtMost(size), removed) })
                    }
                    .show()
            }
        })
        touchHelper.attachToRecyclerView(binding.stops)
    }

    private fun addStop(place: Place, save: Boolean = true) {
        val stop = Stop(RoutesRepository.newId(), place.name ?: getString(R.string.dropped_pin), place.position, 0)
        val stops = route.stops + stop
        if (save) changeStops(stops) else route = route.copy(stops = stops, path = null)
        if (place.name == null) lookUpName(stop)
        binding.stops.post { binding.stops.smoothScrollToPosition(route.stops.size) }
    }

    private fun lookUpName(stop: Stop) {
        lifecycleScope.launch {
            val name = runCatching { PlaceSearch.reverse(this@RouteEditorActivity, stop.position)?.name }.getOrNull() ?: stop.position.format(4)
            route = route.copy(stops = route.stops.map { if (it.id == stop.id) it.copy(name = name) else it })
            render()
            persist()
        }
    }

    /** Applies new stops (or mode), keeps the path if only wait times changed, and saves. */
    private fun changeStops(stops: List<Stop>, mode: TravelMode = route.mode) {
        val sameWay = mode == route.mode && stops.map { it.position } == route.stops.map { it.position }
        route = route.copy(stops = stops, mode = mode, path = if (sameWay) route.path else null)
        render()
        persist()
        if (!sameWay) requestRoute(immediate = false)
    }

    private fun requestRoute(immediate: Boolean) {
        routingJob?.cancel()
        routingFailed = false
        if (route.stops.size < 2) {
            render()
            return
        }
        val wanted = route.stops.map { it.position }
        val mode = route.mode
        routingJob = lifecycleScope.launch {
            if (!immediate) delay(350)
            renderSummary(loading = true)
            val result = runCatching { Router.route(this@RouteEditorActivity, wanted, mode) }
            if (route.stops.map { it.position } != wanted || route.mode != mode) return@launch
            result.onSuccess { path ->
                route = route.copy(path = path)
                persist()
                render()
                fitMap()
            }.onFailure {
                routingFailed = true
                render()
            }
        }
    }

    private fun render() {
        binding.routeName.text = route.name
        adapter.submit(route.stops)
        renderMap()
        renderSummary(loading = routingJob?.isActive == true && route.path == null && !routingFailed)
    }

    private fun renderSummary(loading: Boolean) {
        val path = route.path
        binding.progress.isVisible = loading
        binding.retryButton.isVisible = routingFailed && !loading
        binding.summary.text = when {
            route.stops.size < 2 -> getString(R.string.route_need_two)
            loading -> getString(R.string.route_calculating)
            routingFailed -> getString(R.string.route_failed)
            path != null -> getString(
                R.string.route_summary,
                formatDistance(path.distanceMeters),
                formatDuration((path.durationSeconds * 1000).toLong()),
                getString(if (route.mode == TravelMode.CAR) R.string.driving else R.string.walking),
                formatDuration(route.waitMinutes * 60_000L).takeIf { route.waitMinutes > 0 } ?: "0m",
            ) + if (path.trafficAware) " · " + getString(R.string.route_google_traffic) else ""
            else -> getString(R.string.route_calculating)
        }
        binding.nextButton.isEnabled = path != null && route.stops.size >= 2 && !loading
    }

    private fun renderMap() {
        val map = binding.map
        stopMarkers.forEach { map.overlays.remove(it) }
        stopMarkers.clear()
        route.stops.forEachIndexed { i, stop ->
            stopMarkers += Marker(map).apply {
                position = GeoPoint(stop.position.lat, stop.position.lon)
                icon = letterMarker(stopLetter(i), stopColorRes(i, route.stops.size))
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                setInfoWindow(null)
                setOnMarkerClickListener { _, _ -> true }
            }
        }
        map.overlays.addAll(stopMarkers)
        val path = route.path
        pathLine.setPoints(
            path?.points?.map { GeoPoint(it.lat, it.lon) }
                ?: if (route.stops.size >= 2) route.stops.map { GeoPoint(it.position.lat, it.position.lon) } else emptyList()
        )
        pathLine.outlinePaint.alpha = if (path == null) 90 else 255
        map.invalidate()
    }

    private fun fitMap() {
        val points = route.path?.points ?: route.stops.map { it.position }
        if (points.isEmpty()) return
        binding.map.post {
            if (points.size == 1) {
                binding.map.controller.animateTo(GeoPoint(points[0].lat, points[0].lon), 16.0, 400L)
                return@post
            }
            val box = BoundingBox.fromGeoPoints(points.map { GeoPoint(it.lat, it.lon) })
            val pad = (48 * resources.displayMetrics.density).toInt()
            binding.map.zoomToBoundingBox(box, true, pad)
        }
    }

    private fun setWait(index: Int, minutes: Int) {
        changeStops(route.stops.mapIndexed { i, s -> if (i == index) s.copy(waitMinutes = minutes.coerceIn(0, 24 * 60)) else s })
    }

    private fun askWait(index: Int) {
        val stop = route.stops[index]
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(stop.waitMinutes.toString())
            setSelection(text.length)
        }
        val frame = FrameLayout(this).apply {
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 3, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.wait_title, stop.name))
            .setView(frame)
            .setPositiveButton(R.string.save) { _, _ -> input.text.toString().toIntOrNull()?.let { setWait(index, it) } }
            .setNegativeButton(R.string.cancel, null)
            .show()
        input.requestFocus()
    }

    private fun stepWait(current: Int, up: Boolean): Int = when {
        up && current < 5 -> current + 1
        up -> current + 5
        current <= 5 -> current - 1
        else -> current - 5
    }

    private fun stopDetail(index: Int, stops: List<Stop>): String {
        val path = route.path
        return when {
            index == 0 -> getString(R.string.stop_start)
            path != null && index - 1 < path.legs.size && path.legs.size == stops.size - 1 -> {
                val leg = path.legs[index - 1]
                val text = getString(
                    R.string.stop_leg, formatDistance(leg.distanceMeters),
                    formatDuration((leg.durationSeconds * 1000).toLong()), stopLetter(index - 1)
                )
                if (index == stops.lastIndex) "$text · ${getString(R.string.wait_stay)}" else text
            }
            index == stops.lastIndex -> getString(R.string.stop_final)
            else -> ""
        }
    }

    private inner class StopsAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var stops: List<Stop> = emptyList()

        inner class StopHolder(val binding: ItemStopBinding) : RecyclerView.ViewHolder(binding.root)
        inner class AddHolder(binding: ItemAddStopBinding) : RecyclerView.ViewHolder(binding.root)

        @SuppressLint("NotifyDataSetChanged")
        fun submit(list: List<Stop>) {
            stops = list
            notifyDataSetChanged()
        }

        /** Shows the list mid-drag without saving. */
        fun preview(list: List<Stop>) {
            val old = stops
            stops = list
            val moved = old.indices.firstOrNull { old[it].id != list[it].id } ?: return
            val target = list.indexOfFirst { it.id == old[moved].id }
            notifyItemMoved(moved, target)
        }

        override fun getItemCount() = stops.size + 1
        override fun getItemViewType(position: Int) = if (position == stops.size) 1 else 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == 1) {
                AddHolder(ItemAddStopBinding.inflate(inflater, parent, false)).also { holder ->
                    holder.itemView.setOnClickListener {
                        searchLauncher.launch(Intent(this@RouteEditorActivity, SearchActivity::class.java))
                    }
                }
            } else {
                StopHolder(ItemStopBinding.inflate(inflater, parent, false))
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (holder !is StopHolder) return
            val stop = stops[position]
            val last = position == stops.lastIndex && stops.size > 1
            val context = holder.itemView.context
            with(holder.binding) {
                letter.text = stopLetter(position)
                letter.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, stopColorRes(position, stops.size)))
                name.text = stop.name
                detail.text = stopDetail(position, stops)
                detail.isVisible = detail.text.isNotEmpty()
                waitBox.isVisible = !last
                wait.text = if (stop.waitMinutes == 0) getString(R.string.wait_none) else getString(R.string.wait_minutes, stop.waitMinutes)
                less.isEnabled = stop.waitMinutes > 0
                less.setOnClickListener { holder.bindingAdapterPosition.takeIf { it >= 0 }?.let { setWait(it, stepWait(stop.waitMinutes, up = false)) } }
                more.setOnClickListener { holder.bindingAdapterPosition.takeIf { it >= 0 }?.let { setWait(it, stepWait(stop.waitMinutes, up = true)) } }
                wait.setOnClickListener { holder.bindingAdapterPosition.takeIf { it >= 0 }?.let { askWait(it) } }
                root.setOnClickListener {
                    binding.map.controller.animateTo(GeoPoint(stop.position.lat, stop.position.lon), 16.0, 400L)
                }
                handle.setOnTouchListener { _, event ->
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) touchHelper.startDrag(holder)
                    false
                }
            }
        }
    }

    companion object {
        private const val EXTRA_ROUTE_ID = "route_id"
        private const val EXTRA_LAT = "add_lat"
        private const val EXTRA_LON = "add_lon"
        private const val EXTRA_NAME = "add_name"

        fun intent(context: Context, routeId: Long? = null, addStop: Place? = null) =
            Intent(context, RouteEditorActivity::class.java).apply {
                routeId?.let { putExtra(EXTRA_ROUTE_ID, it) }
                addStop?.let {
                    putExtra(EXTRA_LAT, it.position.lat)
                    putExtra(EXTRA_LON, it.position.lon)
                    putExtra(EXTRA_NAME, it.name)
                }
            }

        private fun placeFromIntent(intent: Intent): Place? {
            if (!intent.hasExtra(EXTRA_LAT)) return null
            return Place(LatLon(intent.getDoubleExtra(EXTRA_LAT, 0.0), intent.getDoubleExtra(EXTRA_LON, 0.0)), intent.getStringExtra(EXTRA_NAME))
        }
    }
}
