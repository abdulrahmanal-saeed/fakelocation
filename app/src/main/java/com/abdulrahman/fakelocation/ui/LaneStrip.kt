package com.abdulrahman.fakelocation.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.abdulrahman.fakelocation.R
import com.abdulrahman.fakelocation.nav.Lane
import com.abdulrahman.fakelocation.nav.Maneuver
import com.abdulrahman.fakelocation.nav.NavIcons

/**
 * The road's lanes side by side (like Amap): your lane in blue with a car marker, and before a turn the lane
 * arrows, bright for the lanes that lead your way.
 */
class LaneStrip @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private var count = 0
    private var lane = 0
    private var arrows: List<Lane>? = null
    private var maneuver = Maneuver.STRAIGHT
    private val d = resources.displayMetrics.density
    private val cell = 34 * d
    private val pad = 6 * d
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2 * d
        color = 0x99FFFFFF.toInt()
        pathEffect = DashPathEffect(floatArrayOf(5 * d, 4 * d), 0f)
    }
    private val rect = RectF()
    private val marker = Path()

    fun set(count: Int, lane: Int, arrows: List<Lane>?, maneuver: Maneuver) {
        val changed = count != this.count || lane != this.lane || arrows != this.arrows || maneuver != this.maneuver
        this.arrows = arrows
        this.maneuver = maneuver
        this.lane = lane
        if (count != this.count) {
            this.count = count
            requestLayout()
        }
        if (changed) invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension((count.coerceAtLeast(1) * cell + 2 * pad).toInt(), (44 * d).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        if (count <= 0) return
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        paint.style = Paint.Style.FILL
        paint.color = ContextCompat.getColor(context, R.color.ink)
        canvas.drawRoundRect(rect, 12 * d, 12 * d, paint)
        val top = pad
        val bottom = height - pad
        for (i in 0 until count) {
            val left = pad + i * cell
            if (i + 1 == lane) {
                rect.set(left + 2 * d, top, left + cell - 2 * d, bottom)
                paint.color = ContextCompat.getColor(context, R.color.nav_blue)
                canvas.drawRoundRect(rect, 7 * d, 7 * d, paint)
            }
            if (i > 0) canvas.drawLine(left, top + 2 * d, left, bottom - 2 * d, dash)
            val laneArrows = arrows?.getOrNull(i)
            if (laneArrows != null) {
                val icon = ContextCompat.getDrawable(context, NavIcons.laneIcon(NavIcons.laneDirection(laneArrows, maneuver)))!!.mutate()
                icon.setTint(if (laneArrows.valid) 0xFFFFFFFF.toInt() else if (i + 1 == lane) 0xBBFFFFFF.toInt() else 0x55FFFFFF)
                val s = (22 * d).toInt()
                val x = (left + (cell - s) / 2).toInt()
                val y = ((height - s) / 2f).toInt()
                icon.setBounds(x, y, x + s, y + s)
                icon.draw(canvas)
            } else if (i + 1 == lane) {
                // No arrows to show: a small car marker says "you".
                val cx = left + cell / 2
                val cy = height / 2f
                marker.reset()
                marker.moveTo(cx, cy - 8 * d)
                marker.lineTo(cx + 6 * d, cy + 7 * d)
                marker.lineTo(cx, cy + 4 * d)
                marker.lineTo(cx - 6 * d, cy + 7 * d)
                marker.close()
                paint.color = 0xFFFFFFFF.toInt()
                canvas.drawPath(marker, paint)
            }
        }
    }
}
