package com.abdulrahman.fakelocation.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LaneDetectorTest {

    private val w = 320
    private val h = 240

    /**
     * A synthetic road: grey asphalt with noise, two painted lines meeting at a vanishing point above the middle.
     * [shift] slides the camera sideways (in lane widths); the next lines appear as the old ones move away.
     */
    private fun road(shift: Double, yellowLeftEdge: Boolean = false, rotation: Int = 0): LaneDetector.Frame {
        val random = java.util.Random(3)
        val y = ByteArray(w * h)
        val u = ByteArray((w / 2) * (h / 2)) { 128.toByte() }
        val v = ByteArray((w / 2) * (h / 2)) { 128.toByte() }
        val vanishX = 0.5
        val vanishY = 0.42
        for (py in 0 until h) for (px in 0 until w) {
            var lum = 70 + random.nextInt(16)
            val fy = (py + 0.5) / h
            if (fy > vanishY + 0.02) {
                val depth = (fy - vanishY) / (1 - vanishY)
                val fx = (px + 0.5) / w
                // Lane lines at lateral offsets k - shift (lane width 1) projected to x = vanish + offset * 0.55 * depth.
                for (k in -2..3) {
                    val lineX = vanishX + (k - 0.5 - shift) * 0.55 * depth
                    if (abs(fx - lineX) < 0.006 + 0.01 * depth) {
                        lum = 220
                        if (yellowLeftEdge && k == 0 && shift == 0.0) {
                            u[(py / 2) * (w / 2) + px / 2] = 60
                            v[(py / 2) * (w / 2) + px / 2] = 170.toByte()
                        }
                    }
                }
            }
            y[py * w + px] = lum.toByte()
        }
        return LaneDetector.Frame(w, h, rotation, y, w, u, v, w / 2, 1)
    }

    private fun abs(d: Double) = kotlin.math.abs(d)

    @Test
    fun findsBothLinesAndOurPlace() {
        val o = LaneDetector().detect(road(0.0))
        assertNotNull(o.left)
        assertNotNull(o.right)
        assertTrue(o.left!!.xBottom < 0.5 && o.right!!.xBottom > 0.5)
        assertEquals(0.5, o.position!!, 0.08)
    }

    @Test
    fun offCentreInTheLane() {
        val o = LaneDetector().detect(road(-0.3))
        assertEquals(0.2, o.position!!, 0.08)
    }

    @Test
    fun seesALaneChangeToTheLeft() {
        val d = LaneDetector()
        var t = 0L
        var event: LaneDetector.Event? = null
        for (s in listOf(0.0, -0.1, -0.2, -0.3, -0.4, -0.6, -0.7, -0.8, -0.9, -1.0)) {
            d.track(d.detect(road(s)), t)?.let { event = it }
            t += 150
        }
        assertEquals(LaneDetector.Event.LaneChange(-1), event)
    }

    @Test
    fun seesALaneChangeToTheRight() {
        val d = LaneDetector()
        var t = 0L
        val events = ArrayList<LaneDetector.Event>()
        for (s in listOf(0.0, 0.15, 0.3, 0.45, 0.55, 0.7, 0.85, 1.0)) {
            d.track(d.detect(road(s)), t)?.let { events += it }
            t += 150
        }
        assertEquals(listOf(LaneDetector.Event.LaneChange(1)), events)
    }

    @Test
    fun yellowEdgeOnTheLeft() {
        val d = LaneDetector()
        val o = d.detect(road(0.0, yellowLeftEdge = true))
        assertTrue(o.leftIsYellow)
        var event: LaneDetector.Event? = null
        repeat(5) { i -> d.track(d.detect(road(0.0, yellowLeftEdge = true)), i * 100L)?.let { event = it } }
        assertEquals(LaneDetector.Event.LeftEdge, event)
    }

    @Test
    fun sidewaysSensor() {
        // Same road, but the sensor delivers it rotated (phone held in portrait): pixels transposed.
        val upright = road(0.0)
        val y = ByteArray(w * h)
        // Sensor frame is h wide and w tall here; rotation 90 means upright(u,v) = sensor(v, 1-u).
        val sw = h
        val sh = w
        for (sy in 0 until sh) for (sx in 0 until sw) {
            val uu = 1 - (sy + 0.5) / sh
            val vv = (sx + 0.5) / sw
            val ux = (uu * w).toInt().coerceIn(0, w - 1)
            val uy = (vv * h).toInt().coerceIn(0, h - 1)
            y[sy * sw + sx] = upright.y[uy * w + ux]
        }
        val o = LaneDetector().detect(LaneDetector.Frame(sw, sh, 90, y, sw))
        assertEquals(0.5, o.position!!, 0.1)
    }
}
