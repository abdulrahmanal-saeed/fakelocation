package com.abdulrahman.fakelocation.geo

import com.abdulrahman.fakelocation.data.LatLon
import java.net.URLDecoder

/**
 * Pulls coordinates out of text people paste or share: plain "lat, lon", geo: URIs
 * and the many shapes of Google Maps links. Pure Kotlin so it can be unit tested.
 */
object LocationText {

    private const val NUM = """(-?\d{1,3}(?:\.\d+)?)"""
    private val plainPair = Regex("""^\s*(?:geo:)?$NUM\s*[,;\s]\s*$NUM\s*$""")
    private val placePin = Regex("""!3d$NUM!4d$NUM""")
    private val queryPair = Regex("""[?&](?:q|query|ll|sll|destination|daddr|center|viewpoint)=(?:loc:)?$NUM\s*,\s*$NUM""")
    private val atPair = Regex("""@$NUM,$NUM""")
    private val geoPair = Regex("""geo:$NUM,$NUM""")
    private val staticCenter = Regex("""center=$NUM(?:%2C|,)$NUM""")
    private val url = Regex("""https?://\S+""")
    private val placeName = Regex("""/maps/place/([^/@?]+)""")

    /** "30.0444, 31.2357", "30.0444 31.2357" or "geo:30.0444,31.2357". */
    fun parseCoordinates(text: String): LatLon? {
        val match = plainPair.find(text.trim()) ?: return null
        return valid(match.groupValues[1], match.groupValues[2])
    }

    /** Coordinates in a full (not shortened) maps URL, preferring the exact place pin. */
    fun parseMapsUrl(raw: String): LatLon? {
        val decoded = decode(raw)
        val candidates = listOf(decoded) + continueTarget(decoded).let { if (it != null) listOf(it) else emptyList() }
        for (text in candidates.reversed()) {
            placePin.findAll(text).lastOrNull()?.let { m -> valid(m.groupValues[1], m.groupValues[2])?.let { return it } }
            for (regex in listOf(queryPair, geoPair, atPair, staticCenter)) {
                regex.find(text)?.let { m -> valid(m.groupValues[1], m.groupValues[2])?.let { return it } }
            }
        }
        return null
    }

    /** First http(s) link in shared text, without trailing punctuation. */
    fun extractUrl(text: String): String? = url.find(text)?.value?.trimEnd('.', ',', ')', ']', '"', '\'')

    /** A readable place name from shared text or a /maps/place/ URL. */
    fun extractName(text: String): String? {
        placeName.find(decode(text))?.let { return it.groupValues[1].replace('+', ' ').trim() }
        return text.lines()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() && !url.containsMatchIn(it) && parseCoordinates(it) == null }
    }

    private fun continueTarget(text: String): String? {
        val idx = text.indexOf("continue=")
        if (idx < 0) return null
        return decode(text.substring(idx + "continue=".length).substringBefore('&'))
    }

    private fun decode(text: String): String =
        try {
            URLDecoder.decode(text.replace("+", "%2B"), "UTF-8").replace("%2B", "+")
        } catch (_: IllegalArgumentException) {
            text
        }

    private fun valid(latText: String, lonText: String): LatLon? {
        val lat = latText.toDoubleOrNull() ?: return null
        val lon = lonText.toDoubleOrNull() ?: return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        if (lat == 0.0 && lon == 0.0) return null
        return LatLon(lat, lon)
    }
}
