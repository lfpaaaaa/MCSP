package au.edu.unimelb.campuscompanion.data

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import androidx.activity.compose.setContent
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import au.edu.unimelb.campuscompanion.context.ContextEngine
import au.edu.unimelb.campuscompanion.context.ContextInputs
import au.edu.unimelb.campuscompanion.context.ContextState
import au.edu.unimelb.campuscompanion.context.TravelSnapshot
import au.edu.unimelb.campuscompanion.data.local.TimetableDatabase
import au.edu.unimelb.campuscompanion.testing.HomeRecreationTestActivity
import au.edu.unimelb.campuscompanion.ui.model.CourseGroup
import au.edu.unimelb.campuscompanion.ui.model.GroupOrigin
import au.edu.unimelb.campuscompanion.ui.screens.HomeScreen
import au.edu.unimelb.campuscompanion.ui.theme.CampusCompanionTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Actual recreation of an empty debug-only Activity containing production
 * remember/TimetableSession/Room repository/parser/HomeScreen. The host has no authentication,
 * sensing or permission-prompt logic; pre-existing timetable preference entries are checked unchanged.
 * Group summaries and importer outcomes are fixtures; this is Home lifecycle coverage,
 * not MainActivity/authenticated-shell acceptance,
 * live-backend validation, a real network outage or offline process-death coverage.
 */
@RunWith(AndroidJUnit4::class)
class PostClassHomeRecreationInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val zone = ZoneId.of("Australia/Melbourne")

    @Test
    fun fixtureFinishedCourseCardRendersAfterRealActivityRecreationRefreshAndOfflineRecreation() {
        val timetablePreferences = context.getSharedPreferences("timetable_subscription", Context.MODE_PRIVATE)
        val originalPreferences = timetablePreferences.all.toMap()
        val database = Room.inMemoryDatabaseBuilder(context, TimetableDatabase::class.java).build()
        val repository = RoomTimetableRepository(database)
        val parent = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val account = "post-class-home-${UUID.randomUUID()}"
        val url = "https://calendar.example.invalid/$account"
        val end = ZonedDateTime.now(zone).minusMinutes(2).withNano(0)
        val imported = parse(end)
        val occurrenceId = imported.sessions.single().id
        val offline = AtomicBoolean(false)
        val createdActivities = AtomicInteger()
        val currentSession = AtomicReference<TimetableSession?>()
        val application = context.applicationContext as Application
        val lifecycle = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                if (activity !is HomeRecreationTestActivity) return
                createdActivities.incrementAndGet()
                // Recreation may preserve an attached window. Install a fresh root so the old
                // ComposeView and its lifecycle owners cannot be reused by the new Activity.
                activity.setContentView(FrameLayout(activity))
                activity.setContent {
                    val session = remember(activity, account) {
                        TimetableSession(
                            account, repository, parent,
                            download = { requestedUrl ->
                                check(requestedUrl == url)
                                if (offline.get()) Result.failure(IOException("Offline fixture"))
                                else Result.success(calendar(end))
                            },
                            parse = { bytes, retained ->
                                Result.success(IcsTimetableParser().parse(bytes, ZonedDateTime.now(zone), zone, retained))
                            },
                            displayZone = { zone }
                        )
                    }
                    SideEffect { currentSession.set(session) }
                    DisposableEffect(session) { onDispose { session.close() } }
                    val state by session.state.collectAsState()
                    LaunchedEffect(session) { if (session.initialize().isSuccess) session.refresh() }
                    val decision = ContextEngine.decide(ContextInputs(
                        now = ZonedDateTime.now(zone).toInstant(), sessions = state.sessions,
                        travelMinutes = null, bufferMinutes = 0, distanceMeters = null, isMoving = false
                    ))
                    CampusCompanionTheme {
                        HomeScreen(
                            timetableState = state.copy(groups = emptyList()),
                            travelSnapshot = TravelSnapshot(state = decision.state,
                                session = decision.session, finishedSession = decision.finishedSession),
                            onTimetableUrlSave = session::connect,
                            onTimetableRefresh = {}, onOpenGroup = {}, onNavigate = {}, onOpenCompass = {},
                            postClassGroupCandidates = listOf(groupFixture())
                        )
                    }
                }
            }

            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }
        var scenario: ActivityScenario<HomeRecreationTestActivity>? = null
        try {
            runBlocking { repository.replaceImport(account, url, imported, ZonedDateTime.now(zone).toInstant()) }
            application.registerActivityLifecycleCallbacks(lifecycle)
            scenario = ActivityScenario.launch(HomeRecreationTestActivity::class.java)
            assertFixtureHome(currentSession, occurrenceId, cached = false)
            val initialOwner = requireNotNull(currentSession.get())

            scenario.recreate()
            await("Recreation should compose a new timetable owner") { currentSession.get() !== initialOwner }
            assertFixtureHome(currentSession, occurrenceId, cached = false)
            val recreatedOwner = requireNotNull(currentSession.get())
            assertNotSame("Ordinary remember must reconstruct TimetableSession", initialOwner, recreatedOwner)
            assertEquals(2, createdActivities.get())

            // Refresh the same owner through its production parser/store path.
            runBlocking(Dispatchers.Main) { recreatedOwner.refresh().getOrThrow() }
            assertFixtureHome(currentSession, occurrenceId, cached = false)
            val savedBeforeOffline = runBlocking { repository.load(account) }
            offline.set(true)
            scenario.recreate()
            await("Offline recreation should compose a new timetable owner") { currentSession.get() !== recreatedOwner }
            assertFixtureHome(currentSession, occurrenceId, cached = true)
            assertNotSame(recreatedOwner, requireNotNull(currentSession.get()))
            assertEquals(3, createdActivities.get())
            assertEquals("A failed fixture import must preserve the saved cache", savedBeforeOffline,
                runBlocking { repository.load(account) })
            assertTrue(requireNotNull(currentSession.get()).state.value.errorMessage != null)

            assertEquals("Fixture Home must preserve every pre-existing timetable preference",
                originalPreferences, timetablePreferences.all)
        } finally {
            try {
                scenario?.close()
            } finally {
                try {
                    finishFixtureActivities()
                } finally {
                    application.unregisterActivityLifecycleCallbacks(lifecycle)
                    parent.cancel()
                    database.close()
                }
            }
        }
    }

    private fun assertFixtureHome(
        currentSession: AtomicReference<TimetableSession?>,
        occurrenceId: String,
        cached: Boolean
    ) {
        await("Fixture timetable must finish its import") {
            currentSession.get()?.state?.value?.let { !it.isLoading && it.isCached == cached } == true
        }
        val state = requireNotNull(currentSession.get()).state.value
        assertEquals(occurrenceId, state.sessions.single().id)
        val decision = ContextEngine.decide(ContextInputs(
            now = ZonedDateTime.now(zone).toInstant(), sessions = state.sessions,
            travelMinutes = null, bufferMinutes = 0, distanceMeters = null, isMoving = false
        ))
        assertEquals(ContextState.POST_CLASS, decision.state)
        assertTrue(state.groups.all { it.unreadCount == 0 && it.latestFileName == null })
        val expected = listOf("After COMP90018", "4 new", "Review the post-class proposal", "proposal.pdf")
        await("Rendered fixture Home must retain the course, unread, message and file summary") {
            val texts = visibleTexts()
            expected.all { value -> texts.any { it.contains(value) } }
        }
    }

    private fun visibleTexts(): List<String> {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return emptyList()
        val texts = mutableListOf<String>()
        fun visit(node: AccessibilityNodeInfo) {
            node.text?.toString()?.let(texts::add)
            node.contentDescription?.toString()?.let(texts::add)
            repeat(node.childCount) { index -> node.getChild(index)?.let(::visit) }
        }
        visit(root)
        return texts
    }

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000L
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100L)
        instrumentation.waitForIdleSync()
        assertTrue(message, condition())
    }

    private fun finishFixtureActivities() {
        instrumentation.runOnMainSync {
            val monitor = ActivityLifecycleMonitorRegistry.getInstance()
            Stage.values().filter { it != Stage.DESTROYED }
                .flatMap { monitor.getActivitiesInStage(it) }.filterIsInstance<HomeRecreationTestActivity>()
                .distinct().forEach { it.finish() }
        }
        instrumentation.waitForIdleSync()
    }

    private fun groupFixture() = CourseGroup(
        id = "fixture-course-membership", courseCode = "COMP90018", name = "Mobile Computing course group",
        members = 12, unreadCount = 4, latestMessage = "Review the post-class proposal",
        latestFileName = "proposal.pdf", origin = GroupOrigin.Timetable, timetableKey = "COMP90018|course"
    )

    private fun parse(end: ZonedDateTime): TimetableImport =
        IcsTimetableParser().parse(calendar(end), ZonedDateTime.now(zone), zone)

    private fun calendar(end: ZonedDateTime): ByteArray {
        val utc = ZoneId.of("UTC")
        val format = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
        return """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Campus Companion//Home Recreation Fixture//EN
            BEGIN:VEVENT
            UID:home-recreation-fixture
            DTSTAMP:${end.minusDays(1).withZoneSameInstant(utc).format(format)}
            DTSTART:${end.minusHours(1).withZoneSameInstant(utc).format(format)}
            DTEND:${end.withZoneSameInstant(utc).format(format)}
            SUMMARY:COMP90018 Mobile Computing Systems Programming Tutorial
            LOCATION:Parkville\, PAR-160
            END:VEVENT
            END:VCALENDAR
        """.trimIndent().replace("\n", "\r\n").toByteArray()
    }
}
