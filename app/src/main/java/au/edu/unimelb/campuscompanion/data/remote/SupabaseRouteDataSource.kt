package au.edu.unimelb.campuscompanion.data.remote

import au.edu.unimelb.campuscompanion.data.TravelMode
import au.edu.unimelb.campuscompanion.data.model.GeoPoint
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.functions.functions
import io.ktor.client.statement.bodyAsText

/**
 * [RouteRemoteDataSource] that calls the route-eta Edge Function (supabase/functions/route-eta).
 * The function asks free routing services, caches their answers and enforces fair-use limits.
 */
class SupabaseRouteDataSource(private val client: SupabaseClient) : RouteRemoteDataSource {

    override suspend fun route(origin: GeoPoint, destination: GeoPoint, mode: TravelMode): RouteOutcome =
        remoteCall {
            val request = RouteRequestBody(origin.toRoutePoint(), destination.toRoutePoint(), mode.wireName)
            try {
                val response = client.functions.invoke(FUNCTION_NAME, request)
                val answer = remoteJson.decodeFromString(RouteResponseBody.serializer(), response.bodyAsText())
                RouteOutcome.Found(answer.durationSeconds, answer.distanceMeters)
            } catch (error: RestException) {
                val retryAfter = if (error.statusCode == HTTP_TOO_MANY_REQUESTS) quotaRetryAfterSeconds(error.error) else null
                retryAfter?.let { RouteOutcome.QuotaExceeded(it) } ?: throw error
            }
        }

    private companion object {
        const val FUNCTION_NAME = "route-eta"
        const val HTTP_TOO_MANY_REQUESTS = 429
    }
}
