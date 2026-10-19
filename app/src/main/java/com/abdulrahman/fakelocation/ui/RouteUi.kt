package com.abdulrahman.fakelocation.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.text.format.DateUtils
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.abdulrahman.fakelocation.R
import com.abdulrahman.fakelocation.data.LatLon
import com.abdulrahman.fakelocation.data.Route
import com.abdulrahman.fakelocation.data.Schedule
import com.abdulrahman.fakelocation.data.TravelMode
import com.abdulrahman.fakelocation.data.formatDistance
import com.abdulrahman.fakelocation.data.formatDuration
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A, B, C … Z, then 27, 28 … */
fun stopLetter(index: Int): String = if (index < 26) ('A' + index).toString() else (index + 1).toString()

/** First stop blue (where you start), last stop orange (where you end up), the rest ink. */
fun stopColorRes(index: Int, count: Int): Int = when {
    index == 0 -> R.color.real
    index == count - 1 -> R.color.fake
    else -> R.color.ink
}

fun Context.modeLabel(mode: TravelMode) = getString(if (mode == TravelMode.CAR) R.string.mode_car else R.string.mode_walk)

fun Context.routeMeta(route: Route): String {
    val path = route.path
    return if (path == null) {
        getString(R.string.route_meta_unrouted, modeLabel(route.mode), route.stops.size)
    } else {
        getString(
            R.string.route_meta, modeLabel(route.mode), route.stops.size,
            formatDistance(path.distanceMeters), formatDuration(Schedule.totalMillis(route.stops, path))
        )
    }
}

fun Context.editedText(time: Long): String =
    getString(R.string.updated_ago, DateUtils.getRelativeTimeSpanString(time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS))

fun clockText(time: Long): String = SimpleDateFormat("HH:mm", Locale.US).format(Date(time))

/** Round map marker with a stop letter, drawn at runtime. */
fun Context.letterMarker(letter: String, colorRes: Int, sizeDp: Int = 30): Drawable {
    val density = resources.displayMetrics.density
    val size = (sizeDp * density).toInt()
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.color = ContextCompat.getColor(this, R.color.surface)
    canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
    paint.color = ContextCompat.getColor(this, colorRes)
    canvas.drawCircle(size / 2f, size / 2f, size / 2f - 3 * density, paint)
    paint.color = ContextCompat.getColor(this, R.color.surface)
    paint.textAlign = Paint.Align.CENTER
    paint.textSize = (if (letter.length > 1) 11 else 13) * density
    paint.typeface = ResourcesCompat.getFont(this, R.font.manrope_800) ?: Typeface.DEFAULT_BOLD
    val y = size / 2f - (paint.descent() + paint.ascent()) / 2
    canvas.drawText(letter, size / 2f, y, paint)
    return BitmapDrawable(resources, bitmap)
}

/** Small sketch of a route's shape for the routes list. */
class RouteThumbView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE9EDF1.toInt() }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.ink)
        style = Paint.Style.STROKE
        strokeWidth = 3.5f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.surface) }
    private var points: List<LatLon> = emptyList()

    fun setRoute(route: Route) {
        points = route.path?.points?.let(::thin) ?: route.stops.map { it.position }
        invalidate()
    }

    private fun thin(all: List<LatLon>): List<LatLon> {
        if (all.size <= 200) return all
        val step = all.size / 200.0
        return (0 until 200).map { all[(it * step).toInt()] } + all.last()
    }

    override fun onDraw(canvas: Canvas) {
        val r = 14 * density
        canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), r, r, bg)
        if (points.isEmpty()) return
        val pad = 12 * density
        val minLat = points.minOf { it.lat }
        val maxLat = points.maxOf { it.lat }
        val minLon = points.minOf { it.lon }
        val maxLon = points.maxOf { it.lon }
        val span = maxOf(maxLat - minLat, maxLon - minLon, 1e-6)
        val box = minOf(width, height) - 2 * pad
        val offX = (width - box * ((maxLon - minLon) / span)) / 2
        val offY = (height - box * ((maxLat - minLat) / span)) / 2
        fun x(p: LatLon) = (offX + (p.lon - minLon) / span * box).toFloat()
        fun y(p: LatLon) = (offY + (maxLat - p.lat) / span * box).toFloat()
        val path = Path()
        points.forEachIndexed { i, p -> if (i == 0) path.moveTo(x(p), y(p)) else path.lineTo(x(p), y(p)) }
        canvas.drawPath(path, line)
        for ((p, color) in listOf(points.first() to R.color.real, points.last() to R.color.fake)) {
            canvas.drawCircle(x(p), y(p), 6 * density, white)
            dot.color = ContextCompat.getColor(context, color)
            canvas.drawCircle(x(p), y(p), 4.5f * density, dot)
        }
    }
}
