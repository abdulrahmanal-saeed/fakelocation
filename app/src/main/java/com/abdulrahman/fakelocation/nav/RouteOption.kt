package com.abdulrahman.fakelocation.nav

import com.abdulrahman.fakelocation.drive.TollPass
import com.abdulrahman.fakelocation.drive.Tolls

/** One way to get there, shown before starting so you can choose. */
data class RouteOption(
    val route: NavRoute,
    val tolls: List<TollPass> = emptyList(),
    val fuelCostAed: Double = 0.0,
    /** What this route was asked to keep off (the "No tolls" option avoids tolls even when the setting is off). */
    val avoid: Avoid = Avoid(),
) {
    val tollAed get() = Tolls.total(tolls)

    /** What makes an option worth picking. */
    enum class Tag { FASTEST, NO_TOLLS, CHEAPER, SHORTER, OTHER }

    companion object {
        /** One tag per option: the quickest is "Fastest"; the others say what they offer instead. */
        fun tags(options: List<RouteOption>): List<Tag> {
            val fastest = options.minByOrNull { it.route.durationSeconds } ?: return emptyList()
            return options.map { o ->
                when {
                    o === fastest -> Tag.FASTEST
                    fastest.tolls.isNotEmpty() && o.tolls.isEmpty() -> Tag.NO_TOLLS
                    o.tollAed < fastest.tollAed - 0.5 -> Tag.CHEAPER
                    o.route.distanceMeters < fastest.route.distanceMeters * 0.95 -> Tag.SHORTER
                    else -> Tag.OTHER
                }
            }
        }
    }
}
