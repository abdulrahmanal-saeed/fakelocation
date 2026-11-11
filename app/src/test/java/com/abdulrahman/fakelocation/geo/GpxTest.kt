package com.abdulrahman.fakelocation.geo

import com.abdulrahman.fakelocation.data.LatLon
import com.abdulrahman.fakelocation.data.Route
import com.abdulrahman.fakelocation.data.Stop
import com.abdulrahman.fakelocation.data.TravelMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GpxTest {

    private fun route(text: String) = Gpx.toRoute(Gpx.read(text), 1, "Imported", "Start", "End")

    @Test
    fun recordedRunKeepsItsShapeAndSpeed() {
        // A short run along JBR (about 330 m in 2 minutes) with the beach as a named point on the way.
        val gpx = """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1" creator="Strava" xmlns="http://www.topografix.com/GPX/1/1">
              <wpt lat="25.0790" lon="55.1335"><name>Beach</name></wpt>
              <trk><name>Morning run</name><trkseg>
                <trkpt lat="25.0770" lon="55.1320"><time>2026-10-30T05:00:00Z</time></trkpt>
                <trkpt lat="25.07701" lon="55.13201"><time>2026-10-30T05:00:01Z</time></trkpt>
                <trkpt lat="25.0790" lon="55.1335"><time>2026-10-30T05:01:00Z</time></trkpt>
                <trkpt lat="25.0795" lon="55.1340"><time>2026-10-30T05:01:20+00:00</time></trkpt>
                <trkpt lat="25.0795" lon="55.1340"><time>2026-10-30T05:01:21Z</time></trkpt>
                <trkpt lat="25.0800" lon="55.1345"><time>2026-10-30T05:02:00Z</time></trkpt>
              </trkseg></trk>
            </gpx>
        """.trimIndent()
        val r = route(gpx)
        assertEquals("Morning run", r.name)
        assertEquals(TravelMode.WALK, r.mode)
        assertEquals(listOf("Start", "Beach", "End"), r.stops.map { it.name })
        val path = r.path!!
        // Points closer than 2 m to the last one are dropped.
        assertEquals(4, path.points.size)
        assertEquals(path.points.size - 1, path.segmentSeconds.size)
        assertEquals(2, path.legs.size)
        assertEquals(120.0, path.durationSeconds, 1.0)
        assertEquals(path.points.lastIndex, path.legs.last().lastPoint)
    }

    @Test
    fun kmlLineWithoutTimes() {
        val kml = """
            <kml xmlns="http://www.opengis.net/kml/2.2"><Document><name>Sheikh Zayed Rd</name>
              <Placemark><name>Line</name><LineString><coordinates>
                55.2708,25.2048,0 55.2500,25.1900,0
                55.2000,25.1500,0 55.1400,25.0700,0
              </coordinates></LineString></Placemark>
            </Document></kml>
        """.trimIndent()
        val r = route(kml)
        assertEquals("Sheikh Zayed Rd", r.name)
        // About 20 km with no times: a drive at 40 km/h.
        assertEquals(TravelMode.CAR, r.mode)
        assertEquals(LatLon(25.2048, 55.2708), r.stops.first().position)
        assertEquals(LatLon(25.07, 55.14), r.stops.last().position)
        assertEquals(r.path!!.distanceMeters / 11.1, r.path!!.durationSeconds, 1.0)
    }

    @Test
    fun googleTrackWithTimes() {
        val kml = """
            <kml xmlns="http://www.opengis.net/kml/2.2" xmlns:gx="http://www.google.com/kml/ext/2.2"><Placemark><gx:Track>
              <when>2026-10-30T08:00:00Z</when><when>2026-10-30T08:01:00Z</when><when>2026-10-30T08:02:00Z</when>
              <gx:coord>55.2708 25.2048 0</gx:coord><gx:coord>55.2650 25.2000 0</gx:coord><gx:coord>55.2600 25.1950 0</gx:coord>
            </gx:Track></Placemark></kml>
        """.trimIndent()
        val r = route(kml)
        assertEquals(3, r.path!!.points.size)
        assertEquals(120.0, r.path!!.durationSeconds, 0.01)
        // About 1.6 km in 2 minutes: a car.
        assertEquals(TravelMode.CAR, r.mode)
    }

    @Test
    fun pointsOnlyAreRoutedLater() {
        val gpx = """
            <gpx xmlns="http://www.topografix.com/GPX/1/1">
              <wpt lat="25.2048" lon="55.2708"><name>Home</name></wpt>
              <wpt lat="25.1985" lon="55.2796"><name>Mall</name></wpt>
            </gpx>
        """.trimIndent()
        val r = route(gpx)
        assertEquals(listOf("Home", "Mall"), r.stops.map { it.name })
        assertNull(r.path)
    }

    @Test(expected = Gpx.NotATrackException::class)
    fun notXml() {
        route("hello")
    }

    @Test(expected = Gpx.NotATrackException::class)
    fun emptyGpx() {
        route("<gpx xmlns=\"http://www.topografix.com/GPX/1/1\"><wpt lat=\"25\" lon=\"55\"/></gpx>")
    }

    @Test(expected = Gpx.NotATrackException::class)
    fun otherXml() {
        route("<html><body>no</body></html>")
    }

    @Test
    fun exportedRouteReadsBack() {
        val path = Router.parse(javaClass.classLoader!!.getResource("osrm_car.json")!!.readText())
        val original = Route(
            7, "Errands <& more>", TravelMode.CAR,
            listOf(
                Stop(1, "Home", path.points.first(), 10),
                Stop(2, "Shop", path.points[path.legs[0].lastPoint], 5),
                Stop(3, "Work", path.points.last(), 0),
            ),
            path,
        )
        val text = Gpx.write(original, startAt = 1_800_000_000_000L)
        assertTrue(text.contains("Errands &lt;&amp; more&gt;"))
        val back = route(text)
        assertEquals("Errands <& more>", back.name)
        assertEquals(listOf("Home", "Shop", "Work"), back.stops.map { it.name })
        // Same streets. GPX has no waits: the 5 minutes at the shop end up in the timestamps (the 10 minutes at home
        // come before the first one).
        assertEquals(original.path!!.distanceMeters, back.path!!.distanceMeters, original.path!!.distanceMeters * 0.02)
        assertEquals(path.durationSeconds + 5 * 60, back.path!!.durationSeconds, 5.0)
    }
}
