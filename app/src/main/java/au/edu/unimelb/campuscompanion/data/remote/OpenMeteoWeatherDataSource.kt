package au.edu.unimelb.campuscompanion.data.remote

import au.edu.unimelb.campuscompanion.data.model.GeoPoint
import au.edu.unimelb.campuscompanion.data.model.WeatherSnapshot
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import java.io.Closeable
import java.time.Instant

/**
 * [WeatherRemoteDataSource] backed by Open-Meteo (https://open-meteo.com), which is free for
 * non-commercial use without a key. Its data is CC BY 4.0, so screens that show it must credit
 * "Weather data by Open-Meteo.com".
 */
class OpenMeteoWeatherDataSource(
    private val client: HttpClient = defaultHttpClient(),
    private val clock: () -> Instant = Instant::now
) : WeatherRemoteDataSource, Closeable {

    override suspend fun currentWeather(location: GeoPoint): WeatherSnapshot = remoteCall {
        val response = client.get(openMeteoUrl(location)) {
            header(HttpHeaders.UserAgent, USER_AGENT)
        }
        if (!response.status.isSuccess()) {
            throw dataErrorFor(response.status.value, null)
        }
        remoteJson.decodeFromString(OpenMeteoForecast.serializer(), response.bodyAsText()).toSnapshot(clock())
    }

    override fun close() {
        client.close()
    }

    private companion object {
        const val USER_AGENT = "CampusCompanion/0.1 (COMP90018 student project)"

        fun defaultHttpClient() = HttpClient(OkHttp) {
            install(HttpTimeout) {
                connectTimeoutMillis = 10_000
                requestTimeoutMillis = 15_000
                socketTimeoutMillis = 15_000
            }
        }
    }
}
