package com.abdulrahman.fakelocation.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.abdulrahman.fakelocation.R
import com.abdulrahman.fakelocation.data.formatClock
import com.abdulrahman.fakelocation.data.formatDistance
import com.abdulrahman.fakelocation.data.formatDuration
import com.abdulrahman.fakelocation.databinding.FragmentMapBinding
import com.abdulrahman.fakelocation.mock.MockLocationService
import com.abdulrahman.fakelocation.mock.TripUi
import com.abdulrahman.fakelocation.trip.TripPhase
import com.abdulrahman.fakelocation.trip.TripTimeline
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import kotlin.math.roundToInt

/** Draws a running trip on the map screen: route lines, stop letters, the moving arrow, the top card and the controls. */
class TripView(private val binding: FragmentMapBinding) {

    private val context: Context = binding.root.context
    private val map: MapView = binding.map
    private val density = context.resources.displayMetrics.density
    private var drawnTimeline: TripTimeline? = null
    private val stopMarkers = mutableListOf<Marker>()
    private val arrowCache = HashMap<Int, Drawable>()
    var follow = true

    private val doneLine = Polyline(map).apply {
        outlinePaint.color = ContextCompat.getColor(context, R.color.fake)
        outlinePaint.strokeWidth = 7 * density
        outlinePaint.strokeCap = Paint.Cap.ROUND
        outlinePaint.strokeJoin = Paint.Join.ROUND
        setOnClickListener { _, _, _ -> false }
    }
    private val restLine = Polyline(map).apply {
        outlinePaint.color = ContextCompat.getColor(context, R.color.ink)
        outlinePaint.strokeWidth = 6 * density
        outlinePaint.strokeCap = Paint.Cap.ROUND
        outlinePaint.pathEffect = DashPathEffect(floatArrayOf(2 * density, 12 * density), 0f)
        setOnClickListener { _, _, _ -> false }
    }
    private val mover = Marker(map).apply {
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        setInfoWindow(null)
        setOnMarkerClickListener { _, _ -> true }
    }
    private val pin: Drawable = ContextCompat.getDrawable(context, R.drawable.marker_fake)!!

    fun show(trip: TripUi) {
        drawOverlays(trip)
        bindTopCard(trip)
        bindPanel(trip)
    }

    fun clear() {
        map.overlays.remove(doneLine)
        map.overlays.remove(restLine)
        map.overlays.remove(mover)
        stopMarkers.forEach { map.overlays.remove(it) }
        stopMarkers.clear()
        drawnTimeline = null
        follow = true
    }

    private fun drawOverlays(trip: TripUi) {
        val timeline = trip.timeline
        val points = timeline.path.points
        if (drawnTimeline !== timeline) {
            stopMarkers.forEach { map.overlays.remove(it) }
            stopMarkers.clear()
            timeline.stops.forEachIndexed { i, stop ->
                stopMarkers += Marker(map).apply {
                    position = GeoPoint(stop.position.lat, stop.position.lon)
                    icon = context.letterMarker(stopLetter(i), stopColorRes(i, timeline.stops.size), 26)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    setInfoWindow(null)
                    setOnMarkerClickListener { _, _ -> true }
                }
            }
            if (!map.overlays.contains(restLine)) map.overlays.add(restLine)
            if (!map.overlays.contains(doneLine)) map.overlays.add(doneLine)
            map.overlays.addAll(stopMarkers)
            drawnTimeline = timeline
        }
        val here = GeoPoint(trip.point.position.lat, trip.point.position.lon)
        val k = trip.point.pointIndex.coerceIn(0, points.lastIndex)
        if (trip.point.phase == TripPhase.SCHEDULED) {
            doneLine.setPoints(emptyList())
            restLine.setPoints(points.map { GeoPoint(it.lat, it.lon) })
        } else {
            doneLine.setPoints(points.subList(0, k + 1).map { GeoPoint(it.lat, it.lon) } + here)
            restLine.setPoints(listOf(here) + points.subList((k + 1).coerceAtMost(points.size), points.size).map { GeoPoint(it.lat, it.lon) })
        }
        mover.position = here
        mover.icon = if (trip.point.phase == TripPhase.MOVING) arrow(trip.point.bearing) else pin
        mover.setAnchor(Marker.ANCHOR_CENTER, if (trip.point.phase == TripPhase.MOVING) Marker.ANCHOR_CENTER else Marker.ANCHOR_BOTTOM)
        map.overlays.remove(mover)
        map.overlays.add(mover)
        if (follow && trip.point.phase != TripPhase.SCHEDULED) map.controller.animateTo(here)
        map.invalidate()
    }

    private fun bindTopCard(trip: TripUi) {
        val stops = trip.stops
        val p = trip.point
        val (index, caption) = when (p.phase) {
            TripPhase.WAITING -> p.stopIndex to context.getString(R.string.waiting_at)
            TripPhase.SCHEDULED -> 0 to context.getString(R.string.trip_starts_at_stop, stopLetter(0))
            else -> (trip.nextStop ?: stops.lastIndex) to context.getString(R.string.next_stop)
        }
        binding.tripLetter.text = stopLetter(index)
        binding.tripLetter.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.surface))
        binding.tripLetter.setTextColor(ContextCompat.getColor(context, stopColorRes(index, stops.size).let { if (it == R.color.ink) R.color.ink else it }))
        binding.tripCaption.text = caption
        binding.tripTitle.text = stops[index].name
        when (p.phase) {
            TripPhase.WAITING -> {
                binding.tripTime.text = clock(trip.timeline.leave[p.stopIndex] - trip.t)
                binding.tripSub.text = trip.nextStop?.let { context.getString(R.string.then_stop, stopLetter(it)) }
                    ?: context.getString(R.string.wait_stay)
            }
            TripPhase.SCHEDULED -> {
                binding.tripTime.text = clockText(trip.wallOf(0))
                binding.tripSub.text = context.getString(R.string.in_time, formatDuration(-trip.t + 59_999))
            }
            else -> {
                val next = trip.nextStop ?: stops.lastIndex
                binding.tripTime.text = formatDuration(trip.timeline.arrive[next] - trip.t + 999)
                binding.tripSub.text = formatDistance(p.distanceToNextStop)
            }
        }
    }

    private fun bindPanel(trip: TripUi) {
        val p = trip.point
        val stops = trip.stops
        val lastLetter = stopLetter(stops.lastIndex)
        val arriveLast = clockText(trip.wallOf(trip.timeline.arrive.last()))
        when (p.phase) {
            TripPhase.MOVING -> stats(
                R.string.stat_speed to context.getString(R.string.kmh, (p.speedMps * 3.6f).roundToInt()),
                R.string.stat_done to formatDistance(p.distanceDone),
                lastLetter to arriveLast,
            )
            TripPhase.WAITING -> stats(
                R.string.stat_left_here to clock(trip.timeline.leave[p.stopIndex] - trip.t),
                R.string.stat_done to formatDistance(p.distanceDone),
                lastLetter to arriveLast,
            )
            else -> stats(
                R.string.stat_starts_in to formatDuration(-trip.t + 59_999),
                R.string.stat_distance to formatDistance(trip.timeline.totalDistance),
                lastLetter to arriveLast,
            )
        }

        binding.tripHint.text = when {
            trip.paused -> context.getString(R.string.hint_paused)
            p.phase == TripPhase.SCHEDULED -> context.getString(R.string.hint_scheduled, clockText(trip.wallOf(0)))
            p.phase == TripPhase.WAITING && trip.nextStop == null -> context.getString(R.string.hint_last_wait)
            p.phase == TripPhase.WAITING && trip.beforeLeaveMs > 0 &&
                trip.timeline.leave[p.stopIndex] - trip.timeline.arrive[p.stopIndex] > trip.beforeLeaveMs ->
                context.getString(R.string.hint_leave_alarm, (trip.beforeLeaveMs / 60_000).toInt())
            else -> context.getString(
                R.string.hint_alarm_before, (trip.beforeArriveMs / 60_000).toInt(),
                stopLetter(trip.nextStop ?: stops.lastIndex)
            )
        }

        bindProgress(trip)

        val b1 = binding.tripButton1
        val b2 = binding.tripButton2
        binding.tripStopButton.setText(if (p.phase == TripPhase.SCHEDULED) R.string.cancel_trip else R.string.stop)
        binding.tripStopButton.setOnClickListener { MockLocationService.stop(context) }
        when (p.phase) {
            TripPhase.WAITING -> {
                b1.isVisible = true
                b1.setText(R.string.plus_five)
                b1.setIconResource(R.drawable.ic_plus)
                b1.setOnClickListener { MockLocationService.addWait(context) }
                b2.setText(R.string.leave_now)
                b2.setIconResource(R.drawable.ic_play)
                b2.isEnabled = trip.nextStop != null
            }
            TripPhase.SCHEDULED -> {
                b1.isVisible = false
                b2.setText(R.string.start_now_action)
                b2.setIconResource(R.drawable.ic_play)
                b2.isEnabled = true
            }
            else -> {
                b1.isVisible = true
                b1.setText(if (trip.paused) R.string.resume else R.string.pause)
                b1.setIconResource(if (trip.paused) R.drawable.ic_play else R.drawable.ic_pause)
                b1.setOnClickListener {
                    if (trip.paused) MockLocationService.resume(context) else MockLocationService.pause(context)
                }
                b2.setText(R.string.skip)
                b2.setIconResource(R.drawable.ic_skip)
                b2.isEnabled = true
            }
        }
        b2.setOnClickListener { MockLocationService.skip(context) }
    }

    private fun stats(first: Pair<Int, String>, second: Pair<Int, String>, third: Pair<String, String>) {
        binding.stat1Label.setText(first.first)
        binding.stat1Value.text = first.second
        binding.stat2Label.setText(second.first)
        binding.stat2Value.text = second.second
        binding.stat3Label.text = context.getString(R.string.stat_arrive, third.first)
        binding.stat3Value.text = third.second
    }

    /** A B C D with bars between them, filled up to where the trip is. */
    private fun bindProgress(trip: TripUi) {
        val row = binding.progressRow
        row.removeAllViews()
        val stops = trip.stops
        val p = trip.point
        val size = (26 * density).toInt()
        val gap = (6 * density).toInt()
        stops.forEachIndexed { i, _ ->
            if (i > 0) {
                val leg = i - 1
                val fill = when {
                    p.phase == TripPhase.SCHEDULED -> 0f
                    p.phase == TripPhase.MOVING && p.stopIndex == leg -> {
                        val start = trip.timeline.leave[leg]
                        val end = trip.timeline.arrive[leg + 1]
                        ((trip.t - start).toFloat() / (end - start).coerceAtLeast(1)).coerceIn(0f, 1f)
                    }
                    p.stopIndex > leg || (p.phase == TripPhase.WAITING && p.stopIndex >= i) -> 1f
                    else -> 0f
                }
                row.addView(bar(fill), LinearLayout.LayoutParams(0, (4 * density).toInt(), 1f).apply { marginStart = gap; marginEnd = gap })
            }
            val reached = p.phase != TripPhase.SCHEDULED && (p.stopIndex > i || (p.stopIndex == i && p.phase != TripPhase.MOVING) || (p.phase == TripPhase.MOVING && p.stopIndex >= i))
            val current = (p.phase == TripPhase.WAITING && p.stopIndex == i) || (p.phase == TripPhase.MOVING && trip.nextStop == i)
            val dot = TextView(context).apply {
                text = stopLetter(i)
                textSize = 12f
                gravity = android.view.Gravity.CENTER
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                background = ContextCompat.getDrawable(context, R.drawable.bg_circle)
                val color = when {
                    current -> R.color.ink
                    reached -> R.color.idle_dot
                    i == stops.lastIndex -> R.color.action_tint
                    else -> R.color.nav_active
                }
                backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, color))
                setTextColor(ContextCompat.getColor(context, if (current || reached) R.color.surface else if (i == stops.lastIndex) R.color.action else R.color.ink))
            }
            row.addView(dot, LinearLayout.LayoutParams(size, size))
        }
    }

    private fun bar(fill: Float): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        val done = View(context).apply { setBackgroundColor(ContextCompat.getColor(context, R.color.fake)) }
        val rest = View(context).apply { setBackgroundColor(ContextCompat.getColor(context, R.color.line)) }
        addView(done, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, fill))
        addView(rest, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f - fill))
    }

    private fun clock(ms: Long) = formatClock(ms.coerceAtLeast(0) + 999).removePrefix("00:")

    /** Orange circle with a white arrow pointing along [bearing] (degrees clockwise from north). */
    private fun arrow(bearing: Float): Drawable {
        val step = ((bearing / 10f).roundToInt() * 10) % 360
        return arrowCache.getOrPut(step) {
            val size = (34 * density).toInt()
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            val c = size / 2f
            paint.color = ContextCompat.getColor(context, R.color.surface)
            canvas.drawCircle(c, c, c, paint)
            paint.color = ContextCompat.getColor(context, R.color.fake)
            canvas.drawCircle(c, c, c - 3 * density, paint)
            canvas.rotate(step.toFloat(), c, c)
            paint.color = ContextCompat.getColor(context, R.color.surface)
            val s = 7 * density
            val path = Path().apply {
                moveTo(c, c - s * 1.3f)
                lineTo(c + s, c + s)
                lineTo(c, c + s * 0.45f)
                lineTo(c - s, c + s)
                close()
            }
            canvas.drawPath(path, paint)
            BitmapDrawable(context.resources, bitmap)
        }
    }
}
