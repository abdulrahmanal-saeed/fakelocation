package com.abdulrahman.fakelocation.drive

import com.abdulrahman.fakelocation.R

/** UAE fuel grades with the official price per litre for October 2026 (AED); editable in Settings each month. */
enum class FuelType(val label: Int, val defaultPrice: Double) {
    SUPER_98(R.string.fuel_super98, 4.40),
    SPECIAL_95(R.string.fuel_special95, 4.28),
    E_PLUS_91(R.string.fuel_eplus91, 4.21),
    DIESEL(R.string.fuel_diesel, 4.80),
}

object Fuel {
    const val PRICES_MONTH = "October 2026"

    /** Fuel cost in AED for [meters] at [litresPer100Km] and [pricePerLitre]. */
    fun cost(meters: Double, litresPer100Km: Double, pricePerLitre: Double): Double =
        meters / 100_000.0 * litresPer100Km * pricePerLitre

    fun aed(value: Double): String = String.format(java.util.Locale.US, if (value < 100) "AED %.2f" else "AED %.0f", value)
}
