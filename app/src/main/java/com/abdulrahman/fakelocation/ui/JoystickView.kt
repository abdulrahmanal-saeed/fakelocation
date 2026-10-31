package com.abdulrahman.fakelocation.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.abdulrahman.fakelocation.R
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

/**
 * A round pad with a knob. Dragging the knob reports a direction (degrees clockwise from the top of the screen,
 * which is north on the map) and how far it is pushed (0…1); letting go reports 0.
 */
class JoystickView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    var onMove: ((bearing: Double, amount: Double) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val base = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.chip) }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.line)
        style = Paint.Style.STROKE
        strokeWidth = 2 * density
    }
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.fake) }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.muted_light) }
    private var knobX = 0f
    private var knobY = 0f
    private var lastAmount = 0.0

    init {
        contentDescription = context.getString(R.string.joystick)
    }

    private val radius get() = min(width, height) / 2f - 4 * density
    private val knobRadius get() = radius * 0.32f

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        canvas.drawCircle(cx, cy, radius, base)
        canvas.drawCircle(cx, cy, radius * 0.55f, ring)
        // Small marks at north, east, south and west.
        for ((dx, dy) in listOf(0f to -1f, 1f to 0f, 0f to 1f, -1f to 0f)) {
            canvas.drawCircle(cx + dx * radius * 0.85f, cy + dy * radius * 0.85f, 3 * density, tick)
        }
        canvas.drawCircle(cx + knobX, cy + knobY, knobRadius, knob)
    }

    @SuppressLint("ClickableViewAccessibility") // dragging only; the panel has its own Done button
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                var dx = event.x - width / 2f
                var dy = event.y - height / 2f
                val reach = radius - knobRadius
                val length = hypot(dx, dy)
                if (length > reach && length > 0) {
                    dx *= reach / length
                    dy *= reach / length
                }
                knobX = dx
                knobY = dy
                val amount = (hypot(dx, dy) / reach).toDouble().coerceIn(0.0, 1.0)
                // A small dead zone in the middle, so resting a thumb on it doesn't creep.
                val pushed = if (amount < 0.15) 0.0 else (amount - 0.15) / 0.85
                if (pushed > 0 && lastAmount == 0.0) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                lastAmount = pushed
                onMove?.invoke((Math.toDegrees(atan2(dx.toDouble(), -dy.toDouble())) + 360) % 360, pushed)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                knobX = 0f
                knobY = 0f
                lastAmount = 0.0
                onMove?.invoke(0.0, 0.0)
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
