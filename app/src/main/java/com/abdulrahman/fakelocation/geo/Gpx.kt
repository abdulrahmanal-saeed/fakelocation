package com.abdulrahman.fakelocation.geo

import com.abdulrahman.fakelocation.data.LatLon
import com.abdulrahman.fakelocation.data.Leg
import com.abdulrahman.fakelocation.data.Route
import com.abdulrahman.fakelocation.data.RoutePath
import com.abdulrahman.fakelocation.data.Stop
import com.abdulrahman.fakelocation.data.TravelMode
import com.abdulrahman.fakelocation.data.distanceMeters
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * GPX and KML files (from Strava, Google My Maps, Garmin, Wikiloc…) as routes, and routes written out as GPX.
 * A recorded track keeps its exact shape and, when it has times, its own speed; a list of points is routed on the
 * streets like any new route.
 */
object Gpx {

    /** What a file holds: a line of points (with times when recorded) and named points. */
    data class Track(
        val name: String?,
        val points: List<LatLon>,
        val times: List<Long?>,
        val waypoints: List<Pair<String?, LatLon>>,
    )

    /** The file isn't GPX/KML, or has nothing usable in it. */
    class NotATrackException(message: String) : IllegalArgumentException(message)

    fun read(text: String): Track {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            // No DOCTYPE: an XML file can't pull in other files from the phone.
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        }
        val doc = runCatching { factory.newDocumentBuilder().parse(ByteArrayInputStream(text.toByteArray())) }
            .getOrElse { throw NotATrackException("Not a GPX or KML file") }
        val root = doc.documentElement
        return when (root.localName ?: root.nodeName) {
            "gpx" -> readGpx(root)
            "kml" -> readKml(root)
            else -> throw NotATrackException("Not a GPX or KML file")
        }
    }

    private fun readGpx(root: Element): Track {
        val name = root.all("trk").firstOrNull()?.child("name") ?: root.all("metadata").firstOrNull()?.child("name")
            ?: root.all("rte").firstOrNull()?.child("name")
        val trackPoints = root.all("trkpt").ifEmpty { root.all("rtept") }
        val points = trackPoints.mapNotNull { it.latLon() }
        val times = trackPoints.filter { it.latLon() != null }.map { it.child("time")?.let(::parseTime) }
        val waypoints = root.all("wpt").mapNotNull { w -> w.latLon()?.let { w.child("name") to it } }
        return Track(name, points, times, waypoints)
    }

    private fun readKml(root: Element): Track {
        val name = root.all("Document").firstOrNull()?.child("name") ?: root.all("Placemark").firstOrNull()?.child("name")
        // Google's recorded tracks: <gx:Track> with <when> and "lon lat alt" <gx:coord>.
        val gxCoords = root.all("coord").mapNotNull { c ->
            c.textContent.trim().split(Regex("\\s+")).takeIf { it.size >= 2 }?.let { valid(it[1].toDoubleOrNull(), it[0].toDoubleOrNull()) }
        }
        val points: List<LatLon>
        val times: List<Long?>
        if (gxCoords.size >= 2) {
            points = gxCoords
            times = root.all("when").map { parseTime(it.textContent.trim()) }.takeIf { it.size == gxCoords.size } ?: gxCoords.map { null }
        } else {
            // The longest line in the file: "lon,lat[,alt] lon,lat[,alt] …".
            points = root.all("LineString").map { line -> coordinates(line.child("coordinates").orEmpty()) }.maxByOrNull { it.size }.orEmpty()
            times = points.map { null }
        }
        val waypoints = root.all("Placemark").mapNotNull { p ->
            p.all("Point").firstOrNull()?.child("coordinates")?.let { coordinates(it).firstOrNull() }?.let { p.child("name") to it }
        }
        return Track(name, points, times, waypoints)
    }

    /**
     * A route from what was read. A track keeps its shape: its first and last points are stops, plus any named point
     * within 50 m of it. Without a track, the named points become stops for the router to join.
     */
    fun toRoute(track: Track, id: Long, fallbackName: String, startName: String, endName: String): Route {
        if (track.points.size >= 2) return trackRoute(track, id, fallbackName, startName, endName)
        if (track.waypoints.size >= 2) {
            val stops = track.waypoints.take(MAX_STOPS).mapIndexed { i, (name, at) -> Stop(i + 1L, name ?: "${i + 1}", at, 0) }
            return Route(id, track.name ?: fallbackName, TravelMode.CAR, stops)
        }
        throw NotATrackException("No track or points in this file")
    }

    private fun trackRoute(track: Track, id: Long, fallbackName: String, startName: String, endName: String): Route {
        // Drop points closer than 2 m to the last one kept (GPS jitter while standing still).
        val keep = ArrayList<Int>()
        track.points.forEachIndexed { i, p -> if (keep.isEmpty() || distanceMeters(track.points[keep.last()], p) >= 2.0) keep += i }
        if (keep.last() != track.points.lastIndex) keep += track.points.lastIndex
        val points = keep.map { track.points[it] }
        if (points.size < 2) throw NotATrackException("The track has no length")
        val times = keep.map { track.times.getOrNull(it) }
        val lengths = (0 until points.lastIndex).map { distanceMeters(points[it], points[it + 1]) }
        val total = lengths.sum()

        val timed = times.all { it != null } && (1 until times.size).all { times[it]!! > times[it - 1]!! }
        val mode = when {
            timed -> if (total / ((times.last()!! - times.first()!!) / 1000.0) > CAR_ABOVE_MPS) TravelMode.CAR else TravelMode.WALK
            else -> if (total > WALK_UNDER_METERS) TravelMode.CAR else TravelMode.WALK
        }
        val defaultSpeed = if (mode == TravelMode.CAR) CAR_MPS else WALK_MPS
        val seconds = (0 until points.lastIndex).map { k ->
            if (timed) ((times[k + 1]!! - times[k]!!) / 1000.0).coerceAtLeast(0.2) else (lengths[k] / defaultSpeed).coerceAtLeast(0.2)
        }

        // Named points along the track become stops where it passes them.
        val along = track.waypoints.mapNotNull { (name, at) ->
            val k = points.indices.minByOrNull { distanceMeters(points[it], at) } ?: return@mapNotNull null
            if (k in 1 until points.lastIndex && distanceMeters(points[k], at) < 50.0) k to name else null
        }.distinctBy { it.first }.sortedBy { it.first }.take(MAX_STOPS - 2)
        val stopAt = listOf(0) + along.map { it.first } + points.lastIndex
        val names = listOf(track.waypoints.nearestName(points.first()) ?: startName) +
            along.map { it.second ?: "" } + (track.waypoints.nearestName(points.last()) ?: endName)
        val stops = stopAt.mapIndexed { i, k -> Stop(i + 1L, names[i].ifBlank { "${i + 1}" }, points[k], 0) }
        val legs = (0 until stopAt.lastIndex).map { i ->
            val (a, b) = stopAt[i] to stopAt[i + 1]
            Leg(lengths.subList(a, b).sum(), seconds.subList(a, b).sum(), a, b)
        }
        // A recorded drive already has the real traffic in its times, so the traffic setting isn't added again.
        return Route(id, track.name ?: fallbackName, mode, stops, RoutePath(points, seconds, legs, trafficAware = timed))
    }

    private fun List<Pair<String?, LatLon>>.nearestName(at: LatLon): String? =
        filter { it.first != null && distanceMeters(it.second, at) < 50.0 }.minByOrNull { distanceMeters(it.second, at) }?.first

    /** The route as GPX: stops as waypoints and the path as a track with times, starting at [startAt]. */
    fun write(route: Route, startAt: Long): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        append("""<gpx version="1.1" creator="Fake Location" xmlns="http://www.topografix.com/GPX/1/1">""").append('\n')
        append("  <metadata><name>").append(escape(route.name)).append("</name></metadata>\n")
        for (stop in route.stops) {
            append("  <wpt ").append(latLon(stop.position)).append("><name>").append(escape(stop.name)).append("</name></wpt>\n")
        }
        val path = route.path
        if (path == null) {
            append("  <rte><name>").append(escape(route.name)).append("</name>\n")
            for (stop in route.stops) append("    <rtept ").append(latLon(stop.position)).append(" />\n")
            append("  </rte>\n")
        } else {
            append("  <trk><name>").append(escape(route.name)).append("</name><trkseg>\n")
            var time = startAt.toDouble()
            var leg = 0
            path.points.forEachIndexed { k, point ->
                // Leaving a stop: its wait comes first.
                if (leg < path.legs.size && k == path.legs[leg].firstPoint) time += route.stops.getOrNull(leg)?.waitMinutes?.times(60_000.0) ?: 0.0
                append("    <trkpt ").append(latLon(point)).append("><time>")
                    .append(DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(time.toLong()))).append("</time></trkpt>\n")
                if (k < path.segmentSeconds.size) time += path.segmentSeconds[k] * 1000
                if (leg < path.legs.size && k + 1 == path.legs[leg].lastPoint) leg++
            }
            append("  </trkseg></trk>\n")
        }
        append("</gpx>\n")
    }

    private const val MAX_STOPS = 25
    /** Faster than this on average (about 22 km/h) is a drive; running and cycling stay on foot. */
    private const val CAR_ABOVE_MPS = 6.0
    private const val WALK_UNDER_METERS = 8_000.0
    private const val CAR_MPS = 11.1
    private const val WALK_MPS = 1.4

    private fun latLon(p: LatLon) = String.format(Locale.US, "lat=\"%.6f\" lon=\"%.6f\"", p.lat, p.lon)

    private fun escape(text: String) =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun parseTime(text: String): Long? =
        runCatching { Instant.parse(text).toEpochMilli() }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()

    private fun coordinates(text: String): List<LatLon> = text.trim().split(Regex("\\s+")).mapNotNull { part ->
        val values = part.split(",")
        if (values.size < 2) null else valid(values[1].toDoubleOrNull(), values[0].toDoubleOrNull())
    }

    private fun valid(lat: Double?, lon: Double?): LatLon? =
        if (lat == null || lon == null || lat !in -90.0..90.0 || lon !in -180.0..180.0) null else LatLon(lat, lon)

    private fun Element.latLon(): LatLon? = valid(getAttribute("lat").toDoubleOrNull(), getAttribute("lon").toDoubleOrNull())

    /** Every element with this name anywhere below, whatever its namespace. */
    private fun Element.all(name: String): List<Element> {
        val list = getElementsByTagNameNS("*", name)
        return (0 until list.length).map { list.item(it) as Element }
    }

    /** Text of the first direct child with this name. */
    private fun Element.child(name: String): String? {
        var node: Node? = firstChild
        while (node != null) {
            if (node is Element && (node.localName ?: node.nodeName) == name) return node.textContent.trim().ifEmpty { null }
            node = node.nextSibling
        }
        return null
    }
}
