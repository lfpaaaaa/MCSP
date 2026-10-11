package au.edu.unimelb.campuscompanion.data.remote

import au.edu.unimelb.campuscompanion.data.TravelMode
import au.edu.unimelb.campuscompanion.data.model.EstimateSource
import au.edu.unimelb.campuscompanion.data.model.GeoPoint
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.functions.functions
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import java.time.Instant

/** Home uses the same Google provider as navigation, with a minimal response and no geometry. */
class GoogleEtaDataSource(
    private val client: SupabaseClient,
    private val fallback: RouteRemoteDataSource,
    private val clock: () -> Instant = Instant::now
) : RouteRemoteDataSource {
    private var retryAt = Instant.MIN
    override suspend fun route(origin: GeoPoint, destination: GeoPoint, mode: TravelMode): RouteOutcome {
        if (clock().isBefore(retryAt)) return fallback.route(origin, destination, mode)
        fun GeoPoint.json() = buildJsonObject { put("latitude", latitude); put("longitude", longitude) }
        try {
            val response = client.functions.invoke("google-routes", buildJsonObject {
                put("origin", origin.json()); put("destination", destination.json())
                put("mode", mode.wireName); put("etaOnly", true)
            })
            val route = Json.parseToJsonElement(response.bodyAsText()).jsonObject["routes"]
                ?.jsonArray?.firstOrNull()?.jsonObject ?: error("No route")
            return RouteOutcome.Found(
                route.getValue("duration").jsonPrimitive.content.removeSuffix("s").toDouble().toLong(),
                route["distanceMeters"]?.jsonPrimitive?.int ?: 0,
                EstimateSource.GoogleMaps
            )
        } catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) {
            if (error is RestException && error.statusCode == 429) {
                return RouteOutcome.QuotaExceeded(quotaRetryAfterSeconds(error.error) ?: 3600)
            }
            // Missing Google configuration or a network failure must not stop offline reminders.
            retryAt = clock().plusSeconds(300)
            return fallback.route(origin, destination, mode)
        }
    }
}
