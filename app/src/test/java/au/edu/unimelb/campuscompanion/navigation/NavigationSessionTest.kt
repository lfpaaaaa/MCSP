package au.edu.unimelb.campuscompanion.navigation

import au.edu.unimelb.campuscompanion.data.TravelMode
import au.edu.unimelb.campuscompanion.data.model.GeoPoint
import au.edu.unimelb.campuscompanion.sensing.location.LocationSample
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Test

class NavigationSessionTest {
    private val leg = TransitLeg("19", "Campus", "A", GeoPoint(0.0, 0.0), "B", GeoPoint(.01, 0.0), listOf(GeoPoint(0.0, 0.0), GeoPoint(.01, 0.0)))
    private val route = GoogleRoute(600, 1100, leg.path, emptyList(), listOf(leg, leg.copy(line = "58")))
    private val destination = ClassDestination("class", "COMP90018", "Campus", leg.arrival)
    private var calls = 0
    private var alerts = 0
    private var active = false
    private fun session(result: GoogleRoute = route) = NavigationSession(
        GoogleRouteSource { _, _, _ -> calls++; listOf(result) },
        CoroutineScope(Dispatchers.Unconfined), onAlert = { alerts++ }, onStop = {}, onActiveChanged = { active = it }
    )
    private fun NavigationSession.prepare() {
        open(destination)
        val t = System.currentTimeMillis()
        updateLocation(LocationSample(0.0, 0.0, 5f, t), t)
        loadRoutes(t)
    }
    @Test fun `opening a class initializes the navigation page with the resolved mode`() {
        for (mode in TravelMode.entries) {
            val session = session()
            session.open(destination, mode)
            assertEquals(mode, session.state.value.mode)
        }
    }
    @Test fun `reopening the same class applies a changed preference and discards old routes`() {
        val session = session()
        session.prepare()
        assertTrue(session.state.value.routes.isNotEmpty())
        session.open(destination, TravelMode.Driving)
        assertEquals(TravelMode.Driving, session.state.value.mode)
        assertTrue(session.state.value.routes.isEmpty())
    }
    @Test fun `opening the same class does not replace a running transit reminder`() {
        val session = session()
        session.prepare()
        assertTrue(session.start())
        session.open(destination, TravelMode.Driving)
        assertTrue(session.state.value.active)
        assertEquals(TravelMode.PublicTransport, session.state.value.mode)
    }
    @Test fun `no fresh GPS means no remote API request`() {
        val session = session()
        session.open(destination)
        session.loadRoutes()
        assertEquals(0, calls)
        assertNotNull(session.state.value.error)
    }
    @Test fun `selected route starts tracking and transfer resets boarding state`() {
        val session = session()
        session.prepare()
        assertTrue(session.start())
        assertTrue(active)
        session.confirmBoarding()
        assertEquals(BoardingState.Riding, session.state.value.progress.state)
        session.nextLeg()
        assertEquals("58", session.state.value.leg!!.line)
        assertEquals(BoardingState.Waiting, session.state.value.progress.state)
        session.nextLeg()
        assertFalse(active)
        assertFalse(session.state.value.active)
    }
    @Test fun `ending journey prevents later location updates from notifying`() {
        val session = session()
        session.prepare()
        session.start()
        session.confirmBoarding()
        session.stop()
        repeat(6) { i -> val t = System.currentTimeMillis() + i * 10_000; session.updateLocation(LocationSample(.004 + .001 * i, 0.0, 5f, t), t) }
        assertEquals(0, alerts)
        assertFalse(active)
    }
    @Test fun `sign out clears routes and precise location`() {
        val session = session()
        session.prepare()
        session.start()
        session.clear()
        assertNull(session.state.value.destination)
        assertTrue(session.state.value.routes.isEmpty())
        assertNull(session.location.value)
        assertFalse(active)
    }
    @Test fun `mode changes cannot replace a route while tracking`() {
        val session = session()
        session.prepare()
        session.start()
        session.changeMode(TravelMode.Walking)
        assertEquals(TravelMode.PublicTransport, session.state.value.mode)
        session.stop()
        session.changeMode(TravelMode.Walking)
        assertTrue(session.state.value.routes.isEmpty())
    }
    @Test fun `a different course stops the earlier reminder`() {
        val session = session()
        session.prepare()
        session.start()
        session.open(destination.copy(sessionId = "other"))
        assertFalse(active)
        assertFalse(session.state.value.active)
        assertTrue(session.state.value.routes.isEmpty())
    }
    @Test fun `missing transit geometry cannot promise get-off tracking`() {
        val session = session(route.copy(transitLegs = listOf(leg.copy(path = emptyList()))))
        session.prepare()
        assertFalse(session.start())
        assertFalse(active)
        assertNotNull(session.state.value.error)
    }
}
