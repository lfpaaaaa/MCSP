package au.edu.unimelb.campuscompanion.navigation

import au.edu.unimelb.campuscompanion.ui.text.UiText
import au.edu.unimelb.campuscompanion.R
import au.edu.unimelb.campuscompanion.data.TravelMode
import au.edu.unimelb.campuscompanion.data.model.GeoPoint
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.functions.functions
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.*

/** Kept in memory for the active journey only. The server API key never goes into the APK. */
data class GoogleRoute(
    val durationSeconds: Long,
    val distanceMeters: Int,
    val path: List<GeoPoint>,
    val instructions: List<String>,
    val transitLegs: List<TransitLeg>
)

fun interface GoogleRouteSource {
    suspend fun routes(origin: GeoPoint, destination: GeoPoint, mode: TravelMode): List<GoogleRoute>
}

class GoogleRouteRepository(private val client: SupabaseClient?) : GoogleRouteSource {
    override suspend fun routes(origin: GeoPoint, destination: GeoPoint, mode: TravelMode): List<GoogleRoute> {
        if (client == null) throw RouteException(UiText(R.string.route_sign_in_to_load_google_maps_routes))
        fun GeoPoint.json() = buildJsonObject { put("latitude", latitude); put("longitude", longitude) }
        val response = try {
            client.functions.invoke("google-routes", buildJsonObject {
                put("origin", origin.json()); put("destination", destination.json())
                put("mode", when (mode) {
                    TravelMode.Walking -> "WALK"
                    TravelMode.Driving -> "DRIVE"
                    TravelMode.PublicTransport -> "TRANSIT"
                })
            })
        } catch (e: RestException) {
            throw RouteException(when (e.statusCode) {
                503 -> UiText(R.string.route_google_maps_routes_are_not_configured_yet_you)
                404 -> UiText(R.string.route_no_route_is_available_try_another_travel_mode)
                429 -> UiText(R.string.route_too_many_route_requests_please_try_again_in)
                401 -> UiText(R.string.route_please_sign_in_again_to_load_routes)
                else -> UiText(R.string.route_google_maps_routes_are_unavailable_please_try_again)
            })
        }
        return parseGoogleRoutes(response.bodyAsText())
    }
}

internal fun parseGoogleRoutes(raw: String): List<GoogleRoute> {
    val root = Json.parseToJsonElement(raw).jsonObject
    fun JsonObject.text(name: String): String = this[name]?.jsonPrimitive?.contentOrNull.orEmpty()
    fun JsonObject.obj(name: String) = this[name]?.jsonObject ?: buildJsonObject {}
    fun JsonObject.items(name: String) = this[name]?.jsonArray?.map { it.jsonObject }.orEmpty()
    fun JsonObject.path() = decodeRoutePolyline(obj("polyline").text("encodedPolyline"))
    fun JsonObject.point(): GeoPoint {
        val p = obj("location").obj("latLng")
        return GeoPoint(p.getValue("latitude").jsonPrimitive.double, p.getValue("longitude").jsonPrimitive.double)
    }
    return root.items("routes").map { route ->
        val steps = route.items("legs").flatMap { it.items("steps") }
        GoogleRoute(
            durationSeconds = route.text("duration").removeSuffix("s").toDouble().toLong(),
            distanceMeters = route["distanceMeters"]?.jsonPrimitive?.int ?: 0,
            path = route.path(),
            instructions = steps.mapNotNull { it.obj("navigationInstruction").text("instructions").takeIf(String::isNotBlank) },
            transitLegs = steps.filter { it["transitDetails"] != null }.map { step ->
                val detail = step.obj("transitDetails")
                val stops = detail.obj("stopDetails")
                val departure = stops.obj("departureStop")
                val arrival = stops.obj("arrivalStop")
                val line = detail.obj("transitLine")
                TransitLeg(
                    line = line.text("nameShort").ifBlank { line.text("name") }.ifBlank { "Public transport" },
                    headsign = detail.text("headsign"),
                    departureName = departure.text("name"), departure = departure.point(),
                    arrivalName = arrival.text("name"), arrival = arrival.point(), path = step.path()
                )
            }
        )
    }
}

class RouteException(val userMessage: UiText) : Exception("Route request failed")
