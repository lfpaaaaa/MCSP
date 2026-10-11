package au.edu.unimelb.campuscompanion.data.repository

import au.edu.unimelb.campuscompanion.data.DataError
import au.edu.unimelb.campuscompanion.data.TravelMode
import au.edu.unimelb.campuscompanion.data.model.EstimateSource
import au.edu.unimelb.campuscompanion.data.model.GeoPoint
import au.edu.unimelb.campuscompanion.data.remote.RouteOutcome
import au.edu.unimelb.campuscompanion.data.remote.RouteRemoteDataSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class RoutedEtaRepositoryTest {
    private val remote = FakeRouteRemoteDataSource()
    private var now = Instant.parse("2026-09-25T08:00:00Z")
    private val repository = RoutedEtaRepository(remote, clock = { now })

    @Test
    fun routedTimesComeFromTheServiceAndAreCached() = runBlocking<Unit> {
        val first = repository.estimate(HOME, CAMPUS, TravelMode.Walking).getOrThrow()
        val second = repository.estimate(HOME, CAMPUS, TravelMode.Walking).getOrThrow()

        assertEquals(EstimateSource.Routing, first.source)
        assertEquals(10, first.durationMinutes)
        assertEquals(first, second)
        assertEquals(1, remote.requests.size)
    }

    @Test
    fun theExactPositionNeverLeavesTheDevice() = runBlocking<Unit> {
        repository.estimate(GeoPoint(-37.79812, 144.96078), CAMPUS, TravelMode.Walking)

        assertEquals(GeoPoint(-37.798, 144.961), remote.requests.single().origin)
    }

    @Test
    fun failuresFallBackToTheOfflineEstimate() = runBlocking<Unit> {
        remote.answer = { throw DataError.Offline() }

        val estimate = repository.estimate(HOME, CAMPUS, TravelMode.PublicTransport).getOrThrow()

        assertTrue(estimate.isApproximate)
    }

    @Test
    fun eachDestinationIsRequestedAtMostOnceEveryThreeMinutes() = runBlocking<Unit> {
        repository.estimate(HOME, CAMPUS, TravelMode.Walking)

        now = now.plus(Duration.ofMinutes(1))
        val whileWalking = repository.estimate(HALFWAY, CAMPUS, TravelMode.Walking).getOrThrow()
        assertEquals(EstimateSource.Routing, whileWalking.source)
        assertEquals(1, remote.requests.size)

        now = now.plus(Duration.ofMinutes(3))
        repository.estimate(HALFWAY, CAMPUS, TravelMode.Walking)
        assertEquals(2, remote.requests.size)
    }

    @Test
    fun aUsedUpBudgetPausesRoutingUntilItResets() = runBlocking<Unit> {
        remote.answer = { RouteOutcome.QuotaExceeded(retryAfterSeconds = 3_600) }
        assertTrue(repository.estimate(HOME, CAMPUS, TravelMode.Walking).getOrThrow().isApproximate)

        remote.answer = { RouteOutcome.Found(durationSeconds = 600, distanceMeters = 800) }
        now = now.plus(Duration.ofMinutes(30))
        assertTrue(repository.estimate(HOME, LIBRARY, TravelMode.Walking).getOrThrow().isApproximate)
        assertEquals(1, remote.requests.size)

        now = now.plus(Duration.ofMinutes(31))
        assertEquals(EstimateSource.Routing, repository.estimate(HOME, LIBRARY, TravelMode.Walking).getOrThrow().source)
        assertEquals(2, remote.requests.size)
    }

    @Test
    fun aLimitReachedInOneTravelModeDoesNotPauseTheOthers() = runBlocking<Unit> {
        remote.answer = { RouteOutcome.QuotaExceeded(retryAfterSeconds = 3_600) }
        assertTrue(repository.estimate(HOME, CAMPUS, TravelMode.PublicTransport).getOrThrow().isApproximate)

        remote.answer = { RouteOutcome.Found(durationSeconds = 600, distanceMeters = 800) }
        val walking = repository.estimate(HOME, CAMPUS, TravelMode.Walking).getOrThrow()

        assertEquals(EstimateSource.Routing, walking.source)
        assertEquals(2, remote.requests.size)
    }

    @Test
    fun publicTransportTimesAreRefreshedSoonerThanWalkingTimes() = runBlocking<Unit> {
        repository.estimate(HOME, CAMPUS, TravelMode.PublicTransport)
        repository.estimate(HOME, CAMPUS, TravelMode.Walking)

        now = now.plus(Duration.ofMinutes(11))
        repository.estimate(HOME, CAMPUS, TravelMode.PublicTransport)
        repository.estimate(HOME, CAMPUS, TravelMode.Walking)

        assertEquals(
            listOf(TravelMode.PublicTransport, TravelMode.Walking, TravelMode.PublicTransport),
            remote.requests.map { it.mode }
        )
    }

    private class FakeRouteRemoteDataSource : RouteRemoteDataSource {
        data class Request(val origin: GeoPoint, val destination: GeoPoint, val mode: TravelMode)

        val requests = mutableListOf<Request>()
        var answer: () -> RouteOutcome = { RouteOutcome.Found(durationSeconds = 600, distanceMeters = 800) }

        override suspend fun route(origin: GeoPoint, destination: GeoPoint, mode: TravelMode): RouteOutcome {
            requests += Request(origin, destination, mode)
            return answer()
        }
    }

    private companion object {
        val HOME = GeoPoint(-37.8100, 144.9630)
        val HALFWAY = GeoPoint(-37.8030, 144.9620)
        val CAMPUS = GeoPoint(-37.7964, 144.9612)
        val LIBRARY = GeoPoint(-37.7985, 144.9593)
    }
}
