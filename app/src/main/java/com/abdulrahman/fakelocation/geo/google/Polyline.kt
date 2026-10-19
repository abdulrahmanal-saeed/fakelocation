package com.abdulrahman.fakelocation.geo.google

import com.abdulrahman.fakelocation.data.LatLon

/** Google's encoded polyline format (used by the Routes API; Valhalla uses the same with 6 decimals). */
object Polyline {
    fun decode(encoded: String, precision: Int = 5): List<LatLon> {
        val factor = Math.pow(10.0, precision.toDouble())
        val points = ArrayList<LatLon>()
        var index = 0
        var lat = 0
        var lon = 0
        while (index < encoded.length) {
            var result = 0
            var shift = 0
            var b: Int
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            lat += if (result and 1 != 0) (result shr 1).inv() else result shr 1
            result = 0
            shift = 0
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            lon += if (result and 1 != 0) (result shr 1).inv() else result shr 1
            points += LatLon(lat / factor, lon / factor)
        }
        return points
    }
}
