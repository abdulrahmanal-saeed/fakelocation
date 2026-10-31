package com.abdulrahman.fakelocation.ui

import android.content.res.ColorStateList
import android.graphics.Paint
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.abdulrahman.fakelocation.R
import com.abdulrahman.fakelocation.data.TravelMode
import com.abdulrahman.fakelocation.data.formatDistance
import com.abdulrahman.fakelocation.data.formatDuration
import com.abdulrahman.fakelocation.databinding.ActivityNavigateBinding
import com.abdulrahman.fakelocation.drive.Fuel
import com.abdulrahman.fakelocation.nav.NavRoute
import com.abdulrahman.fakelocation.nav.NavSession
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Polyline

/**
 * Before starting: the other ways to go, as cards (time · distance · cost) under the ETA and as grey lines on the
 * map. Tapping either picks that way.
 */
class RouteChoices(private val binding: ActivityNavigateBinding) {

    private val context = binding.root.context
    private val density = context.resources.displayMetrics.density
    private val lines = mutableListOf<Polyline>()
    private var shown: Pair<List<NavRoute>, NavRoute?>? = null

    fun render(s: NavSession.State) {
        val options = if (s.navigating) emptyList() else s.routes
        val key = options to s.route
        if (key == shown) return
        shown = key
        binding.routeOptionsScroll.isVisible = options.size > 1
        binding.routeOptions.removeAllViews()
        lines.forEach { binding.map.overlays.remove(it) }
        lines.clear()
        if (options.size < 2) return
        val fastest = options.minOf { it.durationSeconds }
        options.forEachIndexed { i, route ->
            val chosen = route === s.route
            binding.routeOptions.addView(card(route, i, chosen, fastest, s.mode), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = (8 * density).toInt() })
            if (!chosen) {
                lines += Polyline(binding.map).apply {
                    outlinePaint.color = ContextCompat.getColor(context, R.color.muted_light)
                    outlinePaint.strokeWidth = 8 * density
                    outlinePaint.strokeCap = Paint.Cap.ROUND
                    outlinePaint.strokeJoin = Paint.Join.ROUND
                    setPoints(route.points.map { GeoPoint(it.lat, it.lon) })
                    setOnClickListener { _, _, _ -> NavSession.chooseRoute(i); true }
                }
            }
        }
        // Under the chosen (green) route, so it stays on top.
        binding.map.overlays.addAll(0, lines)
        binding.map.invalidate()
    }

    private fun card(route: NavRoute, index: Int, chosen: Boolean, fastest: Double, mode: TravelMode) = TextView(context).apply {
        val (tolls, fuel) = NavSession.costOf(route)
        val time = formatDuration((route.durationSeconds * 1000).toLong())
        val extra = when {
            mode != TravelMode.CAR -> formatDistance(route.distanceMeters)
            else -> formatDistance(route.distanceMeters) + " · " + Fuel.aed(tolls + fuel).replace(".00", "")
        }
        val slower = ((route.durationSeconds - fastest) / 60).toInt()
        val label = if (slower >= 1) context.getString(R.string.nav_option_slower, slower) else context.getString(R.string.nav_option_fastest)
        text = "$time\n$extra\n$label"
        textSize = 13f
        gravity = Gravity.START
        setLineSpacing(2 * density, 1f)
        val pad = (12 * density).toInt()
        setPadding(pad, (10 * density).toInt(), pad, (10 * density).toInt())
        background = ContextCompat.getDrawable(context, R.drawable.bg_round)
        backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, if (chosen) R.color.ink else R.color.chip))
        setTextColor(ContextCompat.getColor(context, if (chosen) R.color.surface else R.color.ink))
        contentDescription = context.getString(R.string.nav_option_description, index + 1, time, extra)
        isSelected = chosen
        setOnClickListener { NavSession.chooseRoute(index) }
    }
}
