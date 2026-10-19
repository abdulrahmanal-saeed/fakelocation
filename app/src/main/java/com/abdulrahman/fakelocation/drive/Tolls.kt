package com.abdulrahman.fakelocation.drive

import com.abdulrahman.fakelocation.data.LatLon
import com.abdulrahman.fakelocation.data.distanceMeters
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId

enum class TollSystem(val label: String) { SALIK("Salik"), DARB("Darb") }

/**
 * One toll gate. [points] are its gantries (usually one per direction, from OpenStreetMap).
 * Gates with the same [pairGroup] are charged once when crossed within an hour (Al Mamzar, Al Safa).
 */
data class TollGate(
    val name: String,
    val system: TollSystem,
    val points: List<LatLon>,
    val pairGroup: String? = null,
    /** Charged around the clock (the 2026 Darb gates) instead of only at peak hours. */
    val allDay: Boolean = false,
)

/** A gate the route goes through: where (metres along the route) and what it costs then. */
data class TollPass(val gate: TollGate, val alongMeters: Double, val priceAed: Double)

/**
 * Salik (Dubai) and Darb (Abu Dhabi) gates and their prices, as of October 2026.
 * Holidays, Ramadan and event timings aren't known to the app, so those days may show the normal price.
 */
object Tolls {

    val DUBAI: ZoneId = ZoneId.of("Asia/Dubai")

    val GATES = listOf(
        TollGate("Al Barsha", TollSystem.SALIK, listOf(LatLon(25.11577, 55.19190), LatLon(25.11662, 55.19261))),
        TollGate("Jebel Ali", TollSystem.SALIK, listOf(LatLon(25.02787, 55.10161), LatLon(25.02793, 55.10128))),
        TollGate("Al Safa North", TollSystem.SALIK, listOf(LatLon(25.19350, 55.26200), LatLon(25.19383, 55.26191)), pairGroup = "safa"),
        TollGate("Al Safa South", TollSystem.SALIK, listOf(LatLon(25.17646, 55.24626), LatLon(25.17829, 55.24755), LatLon(25.17842, 55.24735)), pairGroup = "safa"),
        TollGate("Business Bay Crossing", TollSystem.SALIK, listOf(LatLon(25.22388, 55.34139), LatLon(25.22426, 55.34131))),
        TollGate("Al Garhoud Bridge", TollSystem.SALIK, listOf(LatLon(25.23066, 55.33573), LatLon(25.23098, 55.33555))),
        TollGate("Al Maktoum Bridge", TollSystem.SALIK, listOf(LatLon(25.25302, 55.32226), LatLon(25.25399, 55.32270))),
        TollGate("Airport Tunnel", TollSystem.SALIK, listOf(LatLon(25.24906, 55.38727), LatLon(25.24923, 55.38708))),
        TollGate("Al Mamzar South", TollSystem.SALIK, listOf(LatLon(25.28569, 55.35928), LatLon(25.28614, 55.35908), LatLon(25.28619, 55.35890)), pairGroup = "mamzar"),
        TollGate("Al Mamzar North", TollSystem.SALIK, listOf(LatLon(25.29614, 55.36130), LatLon(25.29772, 55.36154)), pairGroup = "mamzar"),
        TollGate("Sheikh Zayed Bridge", TollSystem.DARB, listOf(LatLon(24.42234, 54.49626), LatLon(24.42242, 54.49627), LatLon(24.42272, 54.49646), LatLon(24.42278, 54.49647))),
        TollGate("Sheikh Khalifa Bridge", TollSystem.DARB, listOf(LatLon(24.51818, 54.39788), LatLon(24.51837, 54.39771))),
        TollGate("Al Maqta Bridge", TollSystem.DARB, listOf(LatLon(24.41988, 54.48300), LatLon(24.42028, 54.48306))),
        TollGate("Mussafah Bridge", TollSystem.DARB, listOf(LatLon(24.40121, 54.49444), LatLon(24.40200, 54.49078))),
    )

    /** Price in AED of crossing [gate] at [epochMillis]. */
    fun price(gate: TollGate, epochMillis: Long): Double {
        val t = Instant.ofEpochMilli(epochMillis).atZone(DUBAI)
        val h = t.hour
        val sunday = t.dayOfWeek == DayOfWeek.SUNDAY
        return when (gate.system) {
            // Variable pricing since 31 Jan 2025: AED 6 at peak, 4 off-peak, free 1–6 am; Sundays AED 4.
            TollSystem.SALIK -> when {
                h in 1..5 -> 0.0
                sunday -> 4.0
                h in 6..9 || h in 16..19 -> 6.0
                else -> 4.0
            }
            // Darb: AED 4 Mon–Sat 7–9 am and 3–7 pm, free otherwise; the newer gates charge all day.
            TollSystem.DARB -> when {
                gate.allDay -> 4.0
                sunday -> 0.0
                h in 7..8 || h in 15..18 -> 4.0
                else -> 0.0
            }
        }
    }

    /**
     * The gates a route crosses, in order. [alongOf] tells how far along the route a point lies (null when the
     * route doesn't pass within a few metres); [secondsTo] how long it takes to get that far.
     */
    fun passes(
        gates: List<TollGate>,
        startMillis: Long,
        alongOf: (LatLon) -> Double?,
        secondsTo: (Double) -> Double,
    ): List<TollPass> {
        val crossed = gates.mapNotNull { gate -> gate.points.mapNotNull(alongOf).minOrNull()?.let { gate to it } }
            .sortedBy { it.second }
        val lastPaid = HashMap<String, Long>()
        return crossed.map { (gate, along) ->
            val at = startMillis + (secondsTo(along) * 1000).toLong()
            var price = price(gate, at)
            val group = gate.pairGroup
            if (group != null) {
                val paid = lastPaid[group]
                if (paid != null && at - paid < 3_600_000L) price = 0.0 else if (price > 0) lastPaid[group] = at
            }
            TollPass(gate, along, price)
        }
    }

    /** Gates found live in OpenStreetMap that aren't in [GATES] yet (e.g. new ones), as extra gates. */
    fun extraGates(found: List<Pair<LatLon, String>>): List<TollGate> = found
        .filter { (p, _) -> GATES.none { g -> g.points.any { distanceMeters(it, p) < 400 } } }
        .map { (p, name) ->
            // Every Salik gate is east of 55.05°E; Abu Dhabi's are west of the emirate border.
            val darb = p.lon < 55.02 || name.contains("darb", ignoreCase = true)
            // New Abu Dhabi gates (Al Qurm, Ghantoot) charge around the clock.
            TollGate(name.ifBlank { if (darb) "Darb gate" else "Salik gate" }, if (darb) TollSystem.DARB else TollSystem.SALIK, listOf(p), allDay = darb)
        }

    fun total(passes: List<TollPass>): Double = passes.sumOf { it.priceAed }
}
