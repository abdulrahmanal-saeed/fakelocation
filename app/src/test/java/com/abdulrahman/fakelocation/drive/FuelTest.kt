package com.abdulrahman.fakelocation.drive

import org.junit.Assert.assertEquals
import org.junit.Test

class FuelTest {

    @Test
    fun costPerTrip() {
        // 100 km at 8 L/100 km and AED 2.50 a litre.
        assertEquals(20.0, Fuel.cost(100_000.0, 8.0, 2.5), 1e-9)
        assertEquals(0.0, Fuel.cost(0.0, 8.0, 2.5), 0.0)
        // Dubai Marina → Abu Dhabi (about 140 km) in a 9 L/100 km car on Special 95.
        assertEquals(53.928, Fuel.cost(140_000.0, 9.0, FuelType.SPECIAL_95.defaultPrice), 1e-9)
    }

    @Test
    fun aedFormatting() {
        assertEquals("AED 4.28", Fuel.aed(4.28))
        assertEquals("AED 0.50", Fuel.aed(0.5))
        assertEquals("AED 99.99", Fuel.aed(99.99))
        assertEquals("AED 100", Fuel.aed(100.0))
        assertEquals("AED 254", Fuel.aed(253.6))
    }
}
