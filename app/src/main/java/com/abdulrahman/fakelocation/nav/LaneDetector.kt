package com.abdulrahman.fakelocation.nav

import kotlin.math.abs

/**
 * Finds the painted lines either side of the car in a dashboard camera frame (no AI model: bright thin
 * stripes on the road, fitted to two straight lines), and from frame to frame tells when you cross one.
 *
 * Works on the camera's luminance plane (plus colour planes for the yellow edge line). Coordinates in results
 * are for the upright picture, 0..1 left→right and top→bottom.
 */
class LaneDetector {

    /** A frame from the camera (YUV 4:2:0, any row/pixel strides) and how much to turn it to be upright. */
    class Frame(
        val width: Int,
        val height: Int,
        val rotation: Int,
        val y: ByteArray, val yRowStride: Int,
        val u: ByteArray? = null, val v: ByteArray? = null, val uvRowStride: Int = 0, val uvPixelStride: Int = 1,
    )

    /** A fitted line: x = x0 + slope * (row − bottom), in upright 0..1 coordinates. */
    data class Line(val xBottom: Double, val slope: Double, val top: Double, val bottom: Double, val support: Int) {
        fun xAt(row: Double) = xBottom + slope * (row - bottom)
    }

    data class Observation(
        val left: Line?,
        val right: Line?,
        /** Where the camera is between the two lines (0 = on the left line, 1 = on the right line). */
        val position: Double?,
        val leftIsYellow: Boolean,
    )

    sealed class Event {
        data class LaneChange(val direction: Int) : Event()
        object LeftEdge : Event()
    }

    private val cols = 160
    private val rows = 28
    private val top = 0.58
    private val bottom = 0.92
    private var lastPosition: Double? = null
    private var lastPositionAt = 0L
    private var yellowFrames = 0

    fun detect(frame: Frame): Observation {
        val points = ArrayList<DoubleArray>() // (u, v) of stripe centres
        val luma = IntArray(cols)
        for (r in 0 until rows) {
            val v = top + (bottom - top) * r / (rows - 1)
            for (c in 0 until cols) luma[c] = sampleY(frame, (c + 0.5) / cols, v)
            // Stripes are wider near the bottom of the picture (closer to the car).
            val w = (2 + 4 * (v - top) / (bottom - top)).toInt()
            val sorted = luma.sorted()
            val median = sorted[cols / 2]
            var c = w
            while (c < cols - w) {
                val ridge = 2 * luma[c] - luma[c - w] - luma[c + w]
                if (ridge > 40 && luma[c] > median + 25) {
                    // Take the strongest point of this stripe, then skip past it.
                    var best = c
                    var end = c
                    while (end + 1 < cols - w && 2 * luma[end + 1] - luma[end + 1 - w] - luma[end + 1 + w] > 40) end++
                    for (k in c..end) if (luma[k] > luma[best]) best = k
                    points += doubleArrayOf((best + 0.5) / cols, v)
                    c = end + w
                } else c++
            }
        }
        val left = fit(points.filter { it[0] < 0.5 }, slopeSign = -1)
        val right = fit(points.filter { it[0] > 0.5 }, slopeSign = 1)
        val position = if (left != null && right != null) {
            val width = right.xBottom - left.xBottom
            if (width in 0.2..1.6) ((0.5 - left.xBottom) / width) else null
        } else null
        val yellow = left != null && isYellow(frame, left)
        return Observation(left, right, position, yellow)
    }

    /** Feeds one observation; returns what happened (crossed a line, saw the left edge), if anything. */
    fun track(o: Observation, t: Long): Event? {
        yellowFrames = if (o.leftIsYellow) yellowFrames + 1 else 0
        val p = o.position ?: return if (yellowFrames == 5) Event.LeftEdge else null
        val before = lastPosition
        val gap = t - lastPositionAt
        lastPosition = p
        lastPositionAt = t
        if (before == null || gap > 1500) return if (yellowFrames == 5) Event.LeftEdge else null
        return when {
            // Drifting left, the left line passes under the camera and is now on our right.
            before < 0.3 && p > 0.7 -> Event.LaneChange(-1)
            before > 0.7 && p < 0.3 -> Event.LaneChange(1)
            yellowFrames == 5 -> Event.LeftEdge
            else -> null
        }
    }

    /** Best straight line through the points (RANSAC), leaning the right way for its side, nearest the centre. */
    private fun fit(points: List<DoubleArray>, slopeSign: Int): Line? {
        if (points.size < 6) return null
        val random = java.util.Random(points.size.toLong())
        var best: Line? = null
        var bestScore = 0.0
        repeat(80) {
            val a = points[random.nextInt(points.size)]
            val b = points[random.nextInt(points.size)]
            if (abs(a[1] - b[1]) < 0.06) return@repeat
            val slope = (a[0] - b[0]) / (a[1] - b[1])
            // Perspective: lines lean towards the middle as they go up the picture.
            if (slope * slopeSign <= 0.05 || abs(slope) > 4) return@repeat
            val x0 = a[0] + slope * (bottom - a[1])
            val inliers = points.filter { abs(it[0] - (x0 + slope * (it[1] - bottom))) < 0.012 }
            val rowsHit = inliers.map { it[1] }.distinct().size
            if (rowsHit < 6) return@repeat
            // Prefer well-supported lines close to the car.
            val score = rowsHit - 12 * abs(0.5 - x0)
            if (score > bestScore) {
                bestScore = score
                best = refine(inliers, rowsHit)
            }
        }
        return best
    }

    private fun refine(inliers: List<DoubleArray>, support: Int): Line {
        val n = inliers.size
        val mv = inliers.sumOf { it[1] } / n
        val mu = inliers.sumOf { it[0] } / n
        var sxy = 0.0
        var sxx = 0.0
        for (p in inliers) {
            sxy += (p[1] - mv) * (p[0] - mu)
            sxx += (p[1] - mv) * (p[1] - mv)
        }
        val slope = if (sxx > 0) sxy / sxx else 0.0
        return Line(mu + slope * (bottom - mv), slope, inliers.minOf { it[1] }, bottom, support)
    }

    private fun isYellow(frame: Frame, line: Line): Boolean {
        if (frame.u == null || frame.v == null) return false
        var u = 0
        var v = 0
        var n = 0
        for (r in 0 until 10) {
            val row = top + (bottom - top) * (r + 0.5) / 10
            val (x, y) = toSensor(frame, line.xAt(row), row)
            val i = (y / 2) * frame.uvRowStride + (x / 2) * frame.uvPixelStride
            if (i < 0 || i >= frame.u.size || i >= frame.v.size) continue
            u += frame.u[i].toInt() and 0xFF
            v += frame.v[i].toInt() and 0xFF
            n++
        }
        if (n < 5) return false
        // Yellow paint: low blue-difference (U), high red-difference (V).
        return u / n < 112 && v / n > 140
    }

    private fun sampleY(frame: Frame, u: Double, v: Double): Int {
        val (x, y) = toSensor(frame, u, v)
        return frame.y[y * frame.yRowStride + x].toInt() and 0xFF
    }

    /** Upright 0..1 coordinates → sensor pixel. */
    private fun toSensor(frame: Frame, u: Double, v: Double): Pair<Int, Int> {
        val (sx, sy) = when (frame.rotation) {
            90 -> v to (1 - u)
            180 -> (1 - u) to (1 - v)
            270 -> (1 - v) to u
            else -> u to v
        }
        val x = (sx * frame.width).toInt().coerceIn(0, frame.width - 1)
        val y = (sy * frame.height).toInt().coerceIn(0, frame.height - 1)
        return x to y
    }
}
