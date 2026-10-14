package au.edu.unimelb.campuscompanion.navigation

import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import au.edu.unimelb.campuscompanion.MainActivity
import au.edu.unimelb.campuscompanion.data.TravelMode
import au.edu.unimelb.campuscompanion.data.model.GeoPoint
import au.edu.unimelb.campuscompanion.sensing.location.LocationSample
import au.edu.unimelb.campuscompanion.ui.model.CourseSession
import au.edu.unimelb.campuscompanion.ui.screens.ClassNavigationScreen
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import java.time.ZonedDateTime
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tests the real Compose screen with an isolated session and gated, local routes. Setup grants
 * the app's startup permissions so fresh installs show no permission dialogs. These tests never edit the
 * persisted timetable/account or start get-off notifications or the sensing service from the UI.
 */
@RunWith(AndroidJUnit4::class)
class NavigationScreenTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation
    private val startLabel = "Start trip & get-off reminders"
    private val lecture = CourseSession(
        id = "navigation-ui-lecture", code = "QA-NAV", title = "Navigation lecture",
        location = "Parkville", room = "PAR-160-B117", start = ZonedDateTime.now().plusHours(1),
        end = ZonedDateTime.now().plusHours(2), activity = "lecture"
    )
    private val leg = TransitLeg(
        "19", "North Coburg", "Flinders St", GeoPoint(-37.8183, 144.9671),
        "Melbourne University", GeoPoint(-37.7983, 144.9614),
        listOf(GeoPoint(-37.8183, 144.9671), GeoPoint(-37.7983, 144.9614))
    )
    private val route = GoogleRoute(1_200, 2_500, leg.path, emptyList(), listOf(leg))

    @Before
    fun grantStartupPermissions() {
        val packageName = instrumentation.targetContext.packageName
        val permissions = mutableListOf(android.Manifest.permission.ACCESS_COARSE_LOCATION,
            android.Manifest.permission.ACCESS_FINE_LOCATION)
        if (android.os.Build.VERSION.SDK_INT >= 29) permissions += android.Manifest.permission.ACTIVITY_RECOGNITION
        if (android.os.Build.VERSION.SDK_INT >= 33) permissions += android.Manifest.permission.POST_NOTIFICATIONS
        permissions.forEach { permission ->
            if (android.os.Build.VERSION.SDK_INT >= 28) automation.grantRuntimePermission(packageName, permission)
            else automation.executeShellCommand("pm grant $packageName $permission").close()
        }
    }

    @Test
    fun startButtonIsDisabledDuringReloadAndEnabledAfterRoutesReturn() = withFixture { fixture ->
        fixture.loadRoute()
        withScreen(fixture.navigation) {
            assertButtonEnabled(startLabel, true)

            fixture.locate()
            fixture.navigation.loadRoutes()
            val reload = fixture.routes.next()
            assertTrue(fixture.navigation.state.value.loading)
            assertButtonEnabled(startLabel, false)
            assertFalse(fixture.navigation.start())
            assertTrue(fixture.activeChanges.none { it })

            reload.complete(listOf(route))
            waitFor("completed route reload") { !fixture.navigation.state.value.loading }
            assertButtonEnabled(startLabel, true)
            assertFalse(fixture.navigation.state.value.active)
            assertTrue(fixture.activeChanges.none { it })
        }
    }

    @Test
    fun deletingTheSelectedClassShowsWhyItsDirectionsClosed() = withFixture { fixture ->
        fixture.startTrip()
        withScreen(fixture.navigation) {
            awaitVisibleText("End trip")

            fixture.navigation.followTimetable(emptyList(), ::destinationOf)
            instrumentation.waitForIdleSync()

            val state = fixture.navigation.state.value
            assertFalse(state.active)
            assertEquals(null, state.destination)
            val reason = state.error!!
            assertTrue(reason.contains("no longer among your upcoming classes"))
            awaitVisibleText(reason)
            assertFalse(visibleTexts().contains("End trip"))
            assertEquals(listOf(true, false), fixture.activeChanges.toList())
            assertEquals(1, fixture.stops.get())
        }
    }

    @Test
    fun correctingLabelsInTheSameBuildingRefreshesTheScreenAndKeepsTheTrip() = withFixture { fixture ->
        fixture.startTrip()
        fixture.navigation.confirmBoarding()
        withScreen(fixture.navigation) {
            awaitVisibleText("End trip")
            val corrected = lecture.copy(title = "Corrected navigation class", room = "PAR-160-201", activity = "tutorial")

            fixture.navigation.followTimetable(listOf(corrected), ::destinationOf)
            instrumentation.waitForIdleSync()

            scrollToTop()
            val destination = destinationOf(corrected)
            awaitVisibleText(destination.title)
            awaitVisibleText("Tutorial")
            awaitVisibleText(destination.buildingLabel)
            awaitVisibleText(destination.locationLabel)
            awaitVisibleText("End trip")
            val state = fixture.navigation.state.value
            assertTrue(state.active)
            assertEquals(BoardingState.Riding, state.progress.state)
            assertEquals(listOf(route), state.routes)
            assertEquals(listOf(true), fixture.activeChanges.toList())
            assertEquals(0, fixture.stops.get())
        }
    }

    @Test
    fun movingTheClassToAnotherBuildingEndsTheTripAndShowsTheNewDestination() = withFixture { fixture ->
        fixture.startTrip()
        withScreen(fixture.navigation) {
            awaitVisibleText("End trip")
            val moved = lecture.copy(room = "PAR-104-G01")

            fixture.navigation.followTimetable(listOf(moved), ::destinationOf)
            instrumentation.waitForIdleSync()

            scrollToTop()
            val destination = destinationOf(moved)
            awaitVisibleText(destination.buildingLabel)
            awaitVisibleText(destination.locationLabel)
            val state = fixture.navigation.state.value
            val reason = state.error!!
            assertTrue(reason.contains("moved to another building"))
            awaitVisibleText(reason)
            awaitVisibleText("Find Google Maps routes")
            assertFalse(visibleTexts().contains("End trip"))
            assertFalse(state.active)
            assertTrue(state.routes.isEmpty())
            assertFalse(fixture.navigation.start())
            assertEquals(listOf(true, false), fixture.activeChanges.toList())
            assertEquals(1, fixture.stops.get())
        }
    }

    private fun withFixture(block: (Fixture) -> Unit) {
        val fixture = Fixture()
        try { block(fixture) } finally { fixture.scope.cancel() }
    }

    private fun withScreen(navigation: NavigationSession, block: () -> Unit) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                if (android.os.Build.VERSION.SDK_INT >= 27) activity.setTurnScreenOn(true)
                activity.setContent { MaterialTheme { ClassNavigationScreen(navigation = navigation) } }
            }
            instrumentation.waitForIdleSync()
            block()
        }
    }

    private inner class Fixture {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val routes = GatedRoutes()
        val activeChanges = CopyOnWriteArrayList<Boolean>()
        val stops = AtomicInteger()
        val navigation = NavigationSession(routes, scope, onAlert = {}, onStop = { stops.incrementAndGet() },
            onActiveChanged = { activeChanges += it })

        init { navigation.open(destinationOf(lecture)); locate() }

        fun locate() {
            val now = System.currentTimeMillis()
            navigation.updateLocation(LocationSample(leg.departure.latitude, leg.departure.longitude, 5f, now), now)
        }

        fun loadRoute() {
            locate()
            navigation.loadRoutes()
            routes.next().complete(listOf(route))
            waitFor("initial route") { !navigation.state.value.loading }
            assertNotNull(navigation.state.value.route)
        }

        fun startTrip() {
            loadRoute()
            activeChanges.clear()
            stops.set(0)
            assertTrue(navigation.start())
        }
    }

    private class GatedRoutes : GoogleRouteSource {
        private val requests = Channel<CompletableDeferred<List<GoogleRoute>>>(Channel.UNLIMITED)

        override suspend fun routes(origin: GeoPoint, destination: GeoPoint, mode: TravelMode): List<GoogleRoute> {
            val answer = CompletableDeferred<List<GoogleRoute>>()
            requests.send(answer)
            return answer.await()
        }

        fun next(): CompletableDeferred<List<GoogleRoute>> = runBlocking {
            withTimeout(5_000) { requests.receive() }
        }
    }

    private fun destinationOf(session: CourseSession): ClassDestination {
        val moved = session.room.startsWith("PAR-104")
        return ClassDestination(
            sessionId = session.id, title = "${session.code} · ${session.title}",
            address = if (moved) "161 Barry Street" else "813 Swanston Street",
            point = if (moved) GeoPoint(-37.8004, 144.9608) else GeoPoint(-37.7983, 144.9632),
            activity = session.activity?.replaceFirstChar { it.titlecase() }, room = session.room,
            buildingCode = if (moved) "PAR-104" else "PAR-160",
            buildingName = if (moved) "Alan Gilbert" else "Peter Hall", buildingNumber = if (moved) "104" else "160"
        )
    }

    private fun assertButtonEnabled(label: String, enabled: Boolean) {
        awaitVisibleText(label)
        waitFor("'$label' enabled=$enabled", diagnostics = { buttonAncestors(label).joinToString("\n", transform = ::describeNode) }) {
            // Compose may expose the merged button text as TextView. Its effective enabled
            // state includes its containing semantics nodes, regardless of their class names.
            val ancestors = buttonAncestors(label)
            ancestors.isNotEmpty() && ancestors.all { it.isEnabled } == enabled
        }
    }

    private fun buttonAncestors(label: String): List<AccessibilityNodeInfo> {
        val ancestors = mutableListOf<AccessibilityNodeInfo>()
        var node = findNode { it.text?.toString() == label && it.isVisibleToUser }
        while (node != null) {
            ancestors += node
            if (node.className?.toString()?.endsWith("AndroidComposeView") == true) break
            node = node.parent
        }
        return ancestors
    }

    private fun describeNode(node: AccessibilityNodeInfo): String =
        "class=${node.className}, text=${node.text}, enabled=${node.isEnabled}, " +
            "clickable=${node.isClickable}, visible=${node.isVisibleToUser}, actions=${node.actionList.map { it.id }}"

    private fun awaitVisibleText(text: String) {
        waitFor("visible text '$text'") {
            if (findNode { it.text?.toString() == text && it.isVisibleToUser } != null) true
            else {
                if (!scroll(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) scrollToTop()
                false
            }
        }
    }

    private fun scrollToTop() {
        repeat(4) {
            if (findNode { it.text?.toString()?.startsWith("QA-NAV · ") == true && it.isVisibleToUser } != null) return
            if (!scroll(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) return
            SystemClock.sleep(150)
        }
    }

    private fun scroll(action: Int): Boolean = findNode { node ->
        node.actionList.any { it.id == action }
    }?.performAction(action) == true

    private fun findNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun find(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (predicate(node)) return node
            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                find(child)?.let { return it }
            }
            return null
        }
        return automation.rootInActiveWindow?.let(::find)
    }

    private fun visibleTexts(): List<String> {
        val texts = mutableListOf<String>()
        findNode { node -> if (node.isVisibleToUser) node.text?.toString()?.let(texts::add); false }
        return texts
    }

    private fun waitFor(description: String, diagnostics: () -> String = { "" }, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        assertTrue("Timed out waiting for $description. Visible texts: ${visibleTexts()}\n${diagnostics()}", condition())
    }
}
