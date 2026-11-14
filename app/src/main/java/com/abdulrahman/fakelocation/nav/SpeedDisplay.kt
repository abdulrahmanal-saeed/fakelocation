package com.abdulrahman.fakelocation.nav

import kotlin.math.roundToInt

internal object SpeedDisplay {
    fun number(kmh: Int, mph: Boolean): String =
        (if (mph) (kmh / 1.609344).roundToInt() else kmh).toString()
}
