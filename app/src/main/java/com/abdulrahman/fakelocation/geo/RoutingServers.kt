package com.abdulrahman.fakelocation.geo

import com.abdulrahman.fakelocation.data.TravelMode
import java.net.URI

/**
 * Where OpenStreetMap routes come from: the user's own servers (Settings → Map & routing) first, then the public
 * ones. Pure Kotlin so it can be unit tested.
 */
object RoutingServers {
    private const val FOSSGIS = "https://routing.openstreetmap.de"
    private const val OSRM_DEMO = "https://router.project-osrm.org"
    private const val VALHALLA = "https://valhalla1.openstreetmap.de"

    /** "mailto:…", "https:" with nothing after it… but not "host:port". */
    private val otherScheme = Regex("""^[a-zA-Z][a-zA-Z0-9+.-]*:(?!\d)""")

    /**
     * OSRM route endpoints to try in turn, each to be followed by "/<lon,lat;lon,lat…>?…". An own OSRM server
     * serves one profile, so it is used for car routes only.
     */
    fun osrm(own: String, mode: TravelMode): List<String> = when (mode) {
        TravelMode.CAR -> listOfNotNull(normalize(own)?.let { "$it/route/v1/driving" }) +
            listOf("$FOSSGIS/routed-car/route/v1/driving", "$OSRM_DEMO/route/v1/driving")
        TravelMode.WALK -> listOf("$FOSSGIS/routed-foot/route/v1/foot")
    }

    /** Valhalla route endpoints to try in turn (POST a JSON request to each). */
    fun valhalla(own: String): List<String> =
        (listOfNotNull(normalize(own)?.let { "$it/route" }) + "$VALHALLA/route").distinct()

    /**
     * A server address as typed ("osrm.example.com/", "http://10.0.0.5:5000") → its base URL without a trailing
     * slash or a trailing "/route" (people paste the endpoint too). Null when blank or not a web address.
     */
    fun normalize(typed: String): String? {
        var text = typed.trim()
        if (text.isEmpty()) return null
        if (!text.contains("://")) {
            if (otherScheme.containsMatchIn(text)) return null
            text = "https://$text"
        }
        text = text.trimEnd('/').removeSuffix("/route").trimEnd('/')
        val uri = runCatching { URI(text) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrBlank()) return null
        if (uri.query != null || uri.fragment != null) return null
        return text
    }

    /** True for a blank entry (= public servers only) or something [normalize] accepts. */
    fun isValid(typed: String): Boolean = typed.isBlank() || normalize(typed) != null

    /** Short form for Settings: "osrm.example.com" or "10.0.0.5:5000". */
    fun label(own: String): String? = normalize(own)?.let { URI(it) }?.let { uri ->
        uri.host + (if (uri.port > 0) ":${uri.port}" else "") + uri.path.orEmpty()
    }
}
