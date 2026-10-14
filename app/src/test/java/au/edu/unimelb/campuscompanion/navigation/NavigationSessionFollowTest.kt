package au.edu.unimelb.campuscompanion.navigation

import au.edu.unimelb.campuscompanion.context.BuildingResolver
import au.edu.unimelb.campuscompanion.context.FakeBuildingLookup
import au.edu.unimelb.campuscompanion.data.TravelMode
import au.edu.unimelb.campuscompanion.data.model.GeoPoint
import au.edu.unimelb.campuscompanion.sensing.location.LocationSample
import au.edu.unimelb.campuscompanion.ui.model.CourseSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** The selected class's directions follow timetable edits; route results never overwrite newer state. */
class NavigationSessionFollowTest {
    private val leg = TransitLeg(
        "19", "North Coburg", "Flinders St", GeoPoint(-37.8183, 144.9671), "Melbourne University",
        GeoPoint(-37.7983, 144.9614), listOf(GeoPoint(-37.8183, 144.9671), GeoPoint(-37.7983, 144.9614))
    )
    private val route = GoogleRoute(1_200, 2_500, leg.path, emptyList(), listOf(leg))
    private val lecture = CourseSession(
        id = "lecture",
        code = "COMP90018",
        title = "Mobile Computing",
        location = "Parkville Campus",
        room = "PAR-160-B117",
        start = ZonedDateTime.of(2026, 10, 13, 12, 0, 0, 0, ZoneId.of("Australia/Melbourne")),
        end = ZonedDateTime.of(2026, 10, 13, 13, 0, 0, 0, ZoneId.of("Australia/Melbourne")),
        activity = "lecture"
    )
    private val tracking = mutableListOf<Boolean>()

    @Test
    fun aCorrectedRoomOrTitleKeepsTheRoutesAndTheTripInProgress() {
        val navigation = startedTrip()
        navigation.confirmBoarding()
        val corrected = lecture.copy(room = "PAR-160-201", title = "Mobile Computing Systems")

        navigation.followTimetable(listOf(corrected), ::destinationOf)

        val state = navigation.state.value
        assertEquals("PAR-160-201", state.destination?.room)
        assertEquals("COMP90018 · Mobile Computing Systems", state.destination?.title)
        assertTrue(state.active)
        assertEquals(listOf(route), state.routes)
        assertEquals(BoardingState.Riding, state.progress.state)
        assertEquals(listOf(true), tracking)
    }

    @Test
    fun aMoveToAnotherBuildingEndsTheTripAndAsksForNewRoutes() {
        val navigation = startedTrip()
        navigation.changeMode(TravelMode.Walking) // Ignored during a trip; the mode stays Transit.

        navigation.followTimetable(listOf(lecture.copy(room = "PAR-104-G01")), ::destinationOf)

        val state = navigation.state.value
        assertEquals(FakeBuildingLookup.ALAN_GILBERT.locCode, state.destination?.buildingCode)
        assertFalse(state.active)
        assertTrue(state.routes.isEmpty())
        assertEquals(TravelMode.PublicTransport, state.mode)
        assertTrue(state.error!!.contains("moved to another building"))
        assertEquals(listOf(true, false), tracking)
        assertFalse(navigation.start())
    }

    @Test
    fun aRemovedClassEndsItsDirectionsWithTheReason() {
        val navigation = startedTrip()

        navigation.followTimetable(emptyList(), ::destinationOf)

        val state = navigation.state.value
        assertNull(state.destination)
        assertFalse(state.active)
        assertTrue(state.error!!.contains("no longer among your upcoming classes"))
        assertEquals(listOf(true, false), tracking)
    }

    @Test
    fun aClassWhoseVenueIsNoLongerKnownEndsItsDirectionsWithTheReason() {
        val navigation = startedTrip()

        navigation.followTimetable(listOf(lecture.copy(location = "Online", room = "Online")), ::destinationOf)

        assertNull(navigation.state.value.destination)
        assertTrue(navigation.state.value.error!!.contains("no longer has a campus building"))
        assertEquals(listOf(true, false), tracking)
    }

    @Test
    fun editsToOtherClassesChangeNothing() {
        val navigation = startedTrip()
        val before = navigation.state.value
        val tutorial = lecture.copy(id = "tutorial", room = "PAR-104-G01", start = lecture.start.plusDays(1))

        navigation.followTimetable(listOf(lecture, tutorial.copy(room = "PAR-105-101")), ::destinationOf)

        assertSame(before, navigation.state.value)
        assertEquals(listOf(true), tracking)
    }

    @Test
    fun startingATripAgainKeepsItsProgress() {
        val navigation = startedTrip()
        navigation.confirmBoarding()

        assertTrue(navigation.start())

        assertEquals(BoardingState.Riding, navigation.state.value.progress.state)
        assertEquals(listOf(true), tracking)
    }

    @Test
    fun startIsRefusedWhileRoutesReloadAndTheReloadLeavesAConsistentState() = runBlocking<Unit> {
        val routes = GatedRoutes()
        val navigation = navigation(routes, this)
        navigation.open(destinationOf(lecture)!!)
        locate(navigation)
        navigation.loadRoutes()
        val initial = routes.next()
        initial.complete(listOf(route))
        yield()
        assertTrue(initial.completed)

        navigation.loadRoutes()
        val reload = routes.next()
        val startedWhileLoading = navigation.start()
        reload.complete(listOf(route))
        yield()
        assertFalse(startedWhileLoading)
        assertTrue(reload.completed)

        assertFalse(navigation.state.value.active)
        assertFalse(navigation.state.value.loading)
        assertTrue(tracking.none { it })
        assertTrue(navigation.start())
        assertEquals(true, tracking.last())
    }

    @Test
    fun aLabelCorrectionDuringARouteRequestSurvivesItsResult() = runBlocking<Unit> {
        val routes = GatedRoutes()
        val navigation = navigation(routes, this)
        navigation.open(destinationOf(lecture)!!)
        locate(navigation)
        navigation.loadRoutes()
        val request = routes.next()

        navigation.followTimetable(listOf(lecture.copy(room = "PAR-160-201")), ::destinationOf)
        request.complete(listOf(route))
        yield()
        assertTrue(request.completed)

        val state = navigation.state.value
        assertEquals("PAR-160-201", state.destination?.room)
        assertEquals(listOf(route), state.routes)
        assertFalse(state.loading)
    }

    @Test
    fun aMoveDuringARouteRequestDiscardsItsResult() = runBlocking<Unit> {
        val routes = GatedRoutes()
        val navigation = navigation(routes, this)
        navigation.open(destinationOf(lecture)!!)
        locate(navigation)
        navigation.loadRoutes()
        val request = routes.next()

        navigation.followTimetable(listOf(lecture.copy(room = "PAR-104-G01")), ::destinationOf)
        val moved = navigation.state.value
        request.complete(listOf(route))
        yield()
        assertTrue("The obsolete source still returned after cancellation", request.completed)

        val state = navigation.state.value
        assertSame(moved, state)
        assertEquals(FakeBuildingLookup.ALAN_GILBERT.locCode, state.destination?.buildingCode)
        assertTrue(state.routes.isEmpty())
        assertNotNull(state.error)
    }

    @Test
    fun removingAClassDuringARouteRequestDiscardsItsResult() = runBlocking<Unit> {
        val routes = GatedRoutes()
        val navigation = navigation(routes, this)
        navigation.open(destinationOf(lecture)!!)
        locate(navigation)
        navigation.loadRoutes()
        val request = routes.next()

        navigation.followTimetable(emptyList(), ::destinationOf)
        val removed = navigation.state.value
        request.complete(listOf(route))
        yield()

        assertTrue("The obsolete source still returned after cancellation", request.completed)
        assertSame(removed, navigation.state.value)
        assertNull(removed.destination)
        assertTrue(removed.routes.isEmpty())
        assertFalse(removed.loading)
        assertTrue(removed.error!!.contains("no longer among your upcoming classes"))
    }

    @Test
    fun losingTheVenueDuringARouteRequestDiscardsItsResult() = runBlocking<Unit> {
        val routes = GatedRoutes()
        val navigation = navigation(routes, this)
        navigation.open(destinationOf(lecture)!!)
        locate(navigation)
        navigation.loadRoutes()
        val request = routes.next()

        navigation.followTimetable(listOf(lecture.copy(location = "Online", room = "Online")), ::destinationOf)
        val unknown = navigation.state.value
        request.complete(listOf(route))
        yield()

        assertTrue("The obsolete source still returned after cancellation", request.completed)
        assertSame(unknown, navigation.state.value)
        assertNull(unknown.destination)
        assertTrue(unknown.routes.isEmpty())
        assertFalse(unknown.loading)
        assertTrue(unknown.error!!.contains("no longer has a campus building"))
    }

    @Test
    fun clearingTheAccountDuringARouteRequestDiscardsItsResultAndLocation() = runBlocking<Unit> {
        val routes = GatedRoutes()
        val navigation = navigation(routes, this)
        navigation.open(destinationOf(lecture)!!)
        locate(navigation)
        navigation.loadRoutes()
        val request = routes.next()
        tracking.clear()

        navigation.clear()
        val cleared = navigation.state.value
        request.complete(listOf(route))
        yield()

        assertTrue("The obsolete source still returned after cancellation", request.completed)
        assertSame(cleared, navigation.state.value)
        assertEquals(NavigationState(), cleared)
        assertNull(navigation.location.value)
        assertEquals(listOf(false), tracking)
    }

    @Test
    fun aLabelCorrectionDuringARouteRequestSurvivesItsFailure() = runBlocking<Unit> {
        val routes = GatedRoutes()
        val navigation = navigation(routes, this)
        navigation.open(destinationOf(lecture)!!)
        locate(navigation)
        navigation.loadRoutes()
        val request = routes.next()
        val corrected = lecture.copy(room = "PAR-160-201", title = "Mobile Computing Systems")

        navigation.followTimetable(listOf(corrected), ::destinationOf)
        request.fail(IllegalStateException("Routes are temporarily unavailable."))
        yield()

        assertTrue(request.completed)
        val state = navigation.state.value
        assertEquals(destinationOf(corrected), state.destination)
        assertFalse(state.loading)
        assertTrue(state.routes.isEmpty())
        assertEquals("Routes are temporarily unavailable.", state.error)
    }

    /** A trip under way to the lecture in Peter Hall. */
    private fun startedTrip(): NavigationSession {
        val navigation = navigation(GoogleRouteSource { _, _, _ -> listOf(route) }, CoroutineScope(Dispatchers.Unconfined))
        navigation.open(destinationOf(lecture)!!)
        locate(navigation)
        navigation.loadRoutes()
        tracking.clear() // Opening a class reports that no trip is being tracked yet.
        assertTrue(navigation.start())
        return navigation
    }

    private fun navigation(routes: GoogleRouteSource, scope: CoroutineScope) = NavigationSession(
        routes, scope, onAlert = {}, onStop = {}, onActiveChanged = { tracking += it }
    )

    private fun locate(navigation: NavigationSession) {
        val now = System.currentTimeMillis()
        navigation.updateLocation(LocationSample(-37.8183, 144.9671, 5f, now), now)
    }

    private fun destinationOf(session: CourseSession): ClassDestination? =
        BuildingResolver.resolve(session, FakeBuildingLookup())?.let { session.toClassDestination(it) }

    private class GatedRoutes : GoogleRouteSource {
        private val requests = Channel<PendingRoutes>(Channel.UNLIMITED)

        override suspend fun routes(origin: GeoPoint, destination: GeoPoint, mode: TravelMode): List<GoogleRoute> {
            val request = PendingRoutes()
            requests.send(request)
            // Simulate a source that cannot cancel its response: generation guards must reject it.
            val answer = withContext(NonCancellable) { request.answer.await() }
            request.completed = true
            return answer.getOrThrow()
        }

        suspend fun next(): PendingRoutes = withTimeout(5_000) { requests.receive() }
    }

    private class PendingRoutes {
        val answer = CompletableDeferred<Result<List<GoogleRoute>>>()
        var completed = false

        fun complete(routes: List<GoogleRoute>) { answer.complete(Result.success(routes)) }
        fun fail(error: Throwable) { answer.complete(Result.failure(error)) }
    }
}
