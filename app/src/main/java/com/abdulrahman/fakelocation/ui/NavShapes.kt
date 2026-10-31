package com.abdulrahman.fakelocation.ui

import com.abdulrahman.fakelocation.data.LatLon
import com.abdulrahman.fakelocation.nav.Lane
import com.abdulrahman.fakelocation.nav.Maneuver
import com.abdulrahman.fakelocation.nav.NavSession
import com.abdulrahman.fakelocation.nav.NavStep

/** The shapes both Navigate maps draw, worked out once: the white turn arrow and where the lane bubble goes. */
object NavShapes {

    /** The white arrow painted on the road at the next turn: its shaft and head, or null when there's none to show. */
    fun turnArrow(s: NavSession.State): Pair<List<LatLon>, List<LatLon>>? {
        val r = s.route ?: return null
        val p = s.progress ?: return null
        val step = p.nextStep ?: return null
        if (!s.navigating || p.arrived || p.metersToNextStep >= 450 || step.maneuver == Maneuver.ARRIVE || step.maneuver == Maneuver.DEPART) return null
        val at = r.along[step.pointIndex]
        val start = maxOf(at - 30, p.alongMeters)
        val end = minOf(at + 22, r.length)
        val tip = r.pointAt(end)
        val back = r.pointAt(end - 6)
        return r.slice(start, end) to arrowHead(back, tip)
    }

    /** Lanes to take before the next turn, in a bubble just before the junction: where, which lanes, which step. */
    data class LaneBubble(val at: LatLon, val lanes: List<Lane>, val step: NavStep)

    fun laneBubble(s: NavSession.State): LaneBubble? {
        if (turnArrow(s) == null) return null
        val r = s.route ?: return null
        val p = s.progress ?: return null
        val step = p.nextStep ?: return null
        val lanes = s.lanesFor(step)
        if (lanes.size < 2 || p.metersToNextStep >= 700) return null
        return LaneBubble(r.pointAt(r.along[step.pointIndex] - 12), lanes, step)
    }

    /** The two short strokes of an arrow head at [tip], pointing away from [back]. */
    fun arrowHead(back: LatLon, tip: LatLon): List<LatLon> {
        val k = Math.cos(Math.toRadians(tip.lat))
        val dx = (tip.lon - back.lon) * 111_320 * k
        val dy = (tip.lat - back.lat) * 110_540
        val len = Math.hypot(dx, dy).takeIf { it > 0 } ?: return emptyList()
        val ux = dx / len
        val uy = dy / len
        fun at(along: Double, side: Double) = LatLon(
            tip.lat + (uy * along + ux * side) / 110_540,
            tip.lon + (ux * along - uy * side) / (111_320 * k),
        )
        return listOf(at(-9.0, 7.0), tip, at(-9.0, -7.0))
    }
}
