package com.abdulrahman.fakelocation.nav

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.core.content.ContextCompat
import com.abdulrahman.fakelocation.R
import kotlin.math.roundToInt

/** Bitmaps shared by the phone and Android Auto navigation screens. */
object NavIcons {
    private val carCache = HashMap<Int, Bitmap>()
    private val arrowCache = HashMap<Int, Bitmap>()

    private fun step(degrees: Float) = ((((degrees % 360) + 360) % 360) / 10f).roundToInt() * 10 % 360

    /** A white car seen from above, pointing along [degrees] (clockwise from screen-up). */
    fun car(context: Context, degrees: Float, scale: Float = context.resources.displayMetrics.density): Bitmap =
        carCache.getOrPut(step(degrees) * 1000 + (scale * 10).toInt()) {
            val d = scale
            val w = 26 * d
            val h = 46 * d
            val size = (h + 8 * d).toInt()
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val c = size / 2f
            canvas.rotate(step(degrees).toFloat(), c, c)
            val left = c - w / 2
            val top = c - h / 2
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.color = 0x33000000
            canvas.drawRoundRect(RectF(left - 2 * d, top + 1 * d, left + w + 2 * d, top + h + 3 * d), 10 * d, 10 * d, paint)
            paint.color = 0xFFF8FAFC.toInt()
            canvas.drawRoundRect(RectF(left, top, left + w, top + h), 9 * d, 9 * d, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.2f * d
            paint.color = 0xFF94A3B8.toInt()
            canvas.drawRoundRect(RectF(left, top, left + w, top + h), 9 * d, 9 * d, paint)
            paint.style = Paint.Style.FILL
            paint.color = 0xFF1E293B.toInt()
            canvas.drawRoundRect(RectF(left + 3 * d, top + 11 * d, left + w - 3 * d, top + 18 * d), 3 * d, 3 * d, paint)
            canvas.drawRoundRect(RectF(left + 4 * d, top + 34 * d, left + w - 4 * d, top + 39 * d), 2 * d, 2 * d, paint)
            paint.color = 0xFFE2E8F0.toInt()
            canvas.drawRoundRect(RectF(left + 4 * d, top + 19 * d, left + w - 4 * d, top + 33 * d), 3 * d, 3 * d, paint)
            paint.color = 0xFFFDE68A.toInt()
            canvas.drawCircle(left + 5 * d, top + 3.5f * d, 2.2f * d, paint)
            canvas.drawCircle(left + w - 5 * d, top + 3.5f * d, 2.2f * d, paint)
            paint.color = 0xFFDC2626.toInt()
            canvas.drawRoundRect(RectF(left + 3 * d, top + h - 3 * d, left + 9 * d, top + h - 1 * d), d, d, paint)
            canvas.drawRoundRect(RectF(left + w - 9 * d, top + h - 3 * d, left + w - 3 * d, top + h - 1 * d), d, d, paint)
            bitmap
        }

    /** Blue circle with a white arrow along [degrees]. */
    fun arrow(context: Context, degrees: Float, scale: Float = context.resources.displayMetrics.density): Bitmap =
        arrowCache.getOrPut(step(degrees) * 1000 + (scale * 10).toInt()) {
            val size = (36 * scale).toInt()
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            val c = size / 2f
            paint.color = ContextCompat.getColor(context, R.color.surface)
            canvas.drawCircle(c, c, c, paint)
            paint.color = ContextCompat.getColor(context, R.color.real)
            canvas.drawCircle(c, c, c - 3 * scale, paint)
            canvas.rotate(step(degrees).toFloat(), c, c)
            paint.color = ContextCompat.getColor(context, R.color.surface)
            val s = 7.5f * scale
            canvas.drawPath(Path().apply {
                moveTo(c, c - s * 1.3f)
                lineTo(c + s, c + s)
                lineTo(c, c + s * 0.45f)
                lineTo(c - s, c + s)
                close()
            }, paint)
            bitmap
        }

    fun maneuverIcon(m: Maneuver) = when (m) {
        Maneuver.LEFT -> R.drawable.ic_m_left
        Maneuver.RIGHT -> R.drawable.ic_m_right
        Maneuver.SLIGHT_LEFT -> R.drawable.ic_m_slight_left
        Maneuver.SLIGHT_RIGHT -> R.drawable.ic_m_slight_right
        Maneuver.SHARP_LEFT -> R.drawable.ic_m_sharp_left
        Maneuver.SHARP_RIGHT -> R.drawable.ic_m_sharp_right
        Maneuver.UTURN -> R.drawable.ic_m_uturn
        Maneuver.ROUNDABOUT -> R.drawable.ic_m_roundabout
        Maneuver.ARRIVE -> R.drawable.ic_m_arrive
        else -> R.drawable.ic_m_straight
    }

    fun laneIcon(direction: String) = when (direction) {
        "left" -> R.drawable.ic_m_left
        "right" -> R.drawable.ic_m_right
        "slight left" -> R.drawable.ic_m_slight_left
        "slight right" -> R.drawable.ic_m_slight_right
        "sharp left" -> R.drawable.ic_m_sharp_left
        "sharp right" -> R.drawable.ic_m_sharp_right
        "uturn" -> R.drawable.ic_m_uturn
        else -> R.drawable.ic_m_straight
    }

    /** The arrow shown for a lane: the one matching the turn if the lane is recommended, else its first arrow. */
    fun laneDirection(lane: Lane, m: Maneuver): String =
        lane.directions.firstOrNull { lane.valid && matches(it, m) } ?: lane.directions.firstOrNull() ?: "straight"

    private fun matches(direction: String, m: Maneuver) = when (m) {
        Maneuver.LEFT, Maneuver.SHARP_LEFT, Maneuver.SLIGHT_LEFT -> "left" in direction
        Maneuver.RIGHT, Maneuver.SHARP_RIGHT, Maneuver.SLIGHT_RIGHT -> "right" in direction
        Maneuver.UTURN -> direction == "uturn"
        else -> direction == "straight"
    }

    /** All lanes drawn side by side (recommended white, others grey), for Android Auto's lane display. */
    fun lanesImage(context: Context, lanes: List<Lane>, m: Maneuver, sizePx: Int = 96): Bitmap {
        val bitmap = Bitmap.createBitmap(sizePx * lanes.size, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        lanes.forEachIndexed { i, lane ->
            val drawable = ContextCompat.getDrawable(context, laneIcon(laneDirection(lane, m)))!!.mutate()
            drawable.setTint(if (lane.valid) 0xFFFFFFFF.toInt() else 0xFF7C8796.toInt())
            val pad = sizePx / 8
            drawable.setBounds(i * sizePx + pad, pad, (i + 1) * sizePx - pad, sizePx - pad)
            drawable.draw(canvas)
        }
        return bitmap
    }

    /** A numbered stop marker: white number on a blue dot with a white ring. */
    fun numberDot(context: Context, number: Int, scale: Float = context.resources.displayMetrics.density): Bitmap {
        val size = (26 * scale).toInt()
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val c = size / 2f
        paint.color = 0xFFFFFFFF.toInt()
        canvas.drawCircle(c, c, c, paint)
        paint.color = ContextCompat.getColor(context, R.color.nav_blue)
        canvas.drawCircle(c, c, c - 2.5f * scale, paint)
        paint.color = 0xFFFFFFFF.toInt()
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 13 * scale
        paint.isFakeBoldText = true
        canvas.drawText(number.toString(), c, c - (paint.descent() + paint.ascent()) / 2, paint)
        return bitmap
    }

    /** Lanes in a blue speech bubble pointing down at the junction (like Yango): lanes to use bright, others faded. */
    fun laneBubble(context: Context, lanes: List<Lane>, m: Maneuver, scale: Float = context.resources.displayMetrics.density): Bitmap {
        val arrow = 30 * scale
        val pad = 8 * scale
        val tail = 10 * scale
        val border = 2 * scale
        val w = (lanes.size * arrow + 2 * pad + 2 * border).toInt()
        val bodyH = arrow + 2 * pad + 2 * border
        val bitmap = Bitmap.createBitmap(w, (bodyH + tail).toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val path = Path().apply {
            addRoundRect(RectF(0f, 0f, w.toFloat(), bodyH), 12 * scale, 12 * scale, Path.Direction.CW)
            moveTo(w / 2f - tail, bodyH - 1)
            lineTo(w / 2f, bodyH + tail - 1)
            lineTo(w / 2f + tail, bodyH - 1)
            close()
        }
        paint.color = 0xFFFFFFFF.toInt()
        canvas.drawPath(path, paint)
        val inner = Path().apply {
            addRoundRect(RectF(border, border, w - border, bodyH - border), 10 * scale, 10 * scale, Path.Direction.CW)
            moveTo(w / 2f - tail + border * 1.5f, bodyH - border - 1)
            lineTo(w / 2f, bodyH + tail - border * 2.2f)
            lineTo(w / 2f + tail - border * 1.5f, bodyH - border - 1)
            close()
        }
        paint.color = ContextCompat.getColor(context, R.color.nav_blue)
        canvas.drawPath(inner, paint)
        lanes.forEachIndexed { i, lane ->
            val d = ContextCompat.getDrawable(context, laneIcon(laneDirection(lane, m)))!!.mutate()
            d.setTint(if (lane.valid) 0xFFFFFFFF.toInt() else 0x66FFFFFF)
            val left = (border + pad + i * arrow).toInt()
            val top = (border + pad).toInt()
            d.setBounds(left, top, (left + arrow).toInt(), (top + arrow).toInt())
            d.draw(canvas)
        }
        return bitmap
    }
}
