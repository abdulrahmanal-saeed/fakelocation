package com.abdulrahman.fakelocation.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.abdulrahman.fakelocation.R
import com.abdulrahman.fakelocation.nav.Traffic
import com.abdulrahman.fakelocation.nav.TrafficSpan

/** The trip as one bar (like Yango): green road, yellow/red where traffic is slow, grey behind you, and your position. */
class TrafficBar @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private var length = 1.0
    private var spans: List<TrafficSpan> = emptyList()
    private var along = 0.0
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val clip = Path()
    private val marker = Path()
    private val density = resources.displayMetrics.density

    fun set(length: Double, spans: List<TrafficSpan>, along: Double) {
        if (length == this.length && spans === this.spans && along == this.along) return
        this.length = length.coerceAtLeast(1.0)
        this.spans = spans
        this.along = along.coerceIn(0.0, this.length)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val h = 6 * density
        val top = (height - h) / 2
        val left = 8 * density
        val right = width - 8 * density
        val w = right - left
        fun x(m: Double) = left + (m / length * w).toFloat()
        rect.set(left, top, right, top + h)
        clip.reset()
        clip.addRoundRect(rect, h / 2, h / 2, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clip)
        paint.style = Paint.Style.FILL
        paint.color = ContextCompat.getColor(context, R.color.route_green)
        canvas.drawRect(rect, paint)
        for (s in spans) {
            paint.color = ContextCompat.getColor(context, if (s.level == Traffic.JAM) R.color.traffic_jam else R.color.traffic_slow)
            canvas.drawRect(x(s.fromAlong), top, x(s.toAlong), top + h, paint)
        }
        paint.color = ContextCompat.getColor(context, R.color.line)
        canvas.drawRect(left, top, x(along), top + h, paint)
        canvas.restore()
        // Where you are: a small arrow on the bar.
        val cx = x(along)
        val cy = height / 2f
        val r = 7 * density
        marker.reset()
        marker.moveTo(cx + r, cy)
        marker.lineTo(cx - r * 0.7f, cy - r * 0.8f)
        marker.lineTo(cx - r * 0.3f, cy)
        marker.lineTo(cx - r * 0.7f, cy + r * 0.8f)
        marker.close()
        paint.style = Paint.Style.FILL
        paint.color = ContextCompat.getColor(context, R.color.nav_blue)
        canvas.drawPath(marker, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f * density
        paint.color = 0xFFFFFFFF.toInt()
        canvas.drawPath(marker, paint)
    }
}
