package au.edu.unimelb.campuscompanion.data

import au.edu.unimelb.campuscompanion.ui.model.CourseSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class TimetableSessionCoordinatorTest {
    private val now = Instant.parse("2026-10-13T00:00:00Z")

    private class Repository(var read: suspend (String) -> TimetableSnapshot) : TimetableRepository {
        override suspend fun load(ownerId: String) = read(ownerId)
        override suspend fun replaceImport(ownerId: String, url: String, imported: TimetableImport, syncedAt: Instant): TimetableSnapshot = error("Unexpected write")
        override suspend fun disconnect(ownerId: String) = TimetableSnapshot()
        override suspend fun upsertLocal(ownerId: String, row: LocalClass): TimetableSnapshot = error("Unexpected write")
        override suspend fun deleteLocal(ownerId: String, id: String): TimetableSnapshot = error("Unexpected write")
    }

    private inner class Harness(repository: Repository) : AutoCloseable {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val feeds = mutableListOf<Pair<List<CourseSession>, Boolean>>()
        var resets = 0
        var downloads = 0
        val sessions = mutableListOf<TimetableSession>()
        val coordinator = TimetableSessionCoordinator(scope, { owner ->
            TimetableSession(owner, repository, scope, { downloads++; Result.failure(IOException("Offline")) },
                { _, _ -> error("Unexpected parse") }, Dispatchers.Unconfined, { now }, { ZoneId.of("Australia/Melbourne") })
                .also { sessions += it }
        }, { resets++ }, { classes, reset -> feeds += classes to reset }, Dispatchers.Unconfined)
        override fun close() { scope.cancel() }
    }

    @Test fun pendingAndFailedLocalLoadNeverFeedButRetryLoadedEmptyDoes() = runBlocking {
        val pending = CompletableDeferred<Unit>()
        val repository = Repository { pending.await(); throw IOException("Cannot read Room") }
        Harness(repository).use { app ->
            app.coordinator.replaceOwner("alice")
            assertTrue(app.feeds.isEmpty())
            assertEquals(0, app.resets)
            pending.complete(Unit)
            yield()
            val session = app.coordinator.current.value!!
            assertFalse(session.state.value.isLoaded)
            assertFalse(session.state.value.isLoading)
            assertTrue(app.feeds.isEmpty())
            assertEquals(0, app.downloads)
            repository.read = { TimetableSnapshot() }
            session.retryLoad().getOrThrow()
            yield()
            assertEquals(listOf(emptyList<CourseSession>() to true), app.feeds)
        }
    }

    @Test fun startupDeliversDiskSnapshotBeforeRefreshAndInitializesOnlyOnce() = runBlocking {
        val classStart = ZonedDateTime.parse("2026-10-14T09:00:00+11:00[Australia/Melbourne]")
        val course = CourseSession("stable", "COMP90018", "Mobile", "PAR-104", "PAR-104-G01", classStart, classStart.plusHours(1))
        var reads = 0
        val repository = Repository {
            reads++
            TimetableSnapshot(TimetableSource("https://example.com/calendar", now), listOf(course))
        }
        Harness(repository).use { app ->
            app.coordinator.replaceOwner("alice")
            yield()
            assertEquals(listOf(listOf(course) to true), app.feeds)
            assertEquals(1, app.downloads)
            assertEquals(1, reads)
            app.coordinator.replaceOwner("alice")
            assertEquals(1, app.sessions.size)
            assertEquals(1, reads)
        }
    }

    @Test fun accountReplacementResetsImmediatelyAndRejectsDelayedOldReadIncludingSameOwnerReturn() = runBlocking {
        val pending = CompletableDeferred<Unit>()
        var aliceReads = 0
        val repository = Repository { owner ->
            if (owner == "alice" && aliceReads++ == 0) withContext(NonCancellable) { pending.await() }
            TimetableSnapshot()
        }
        Harness(repository).use { app ->
            app.coordinator.replaceOwner("alice")
            val oldAlice = app.coordinator.current.value!!
            assertTrue(app.feeds.isEmpty())
            app.coordinator.replaceOwner("bob")
            yield()
            assertEquals(1, app.resets)
            assertFalse(oldAlice.isActive)
            app.coordinator.replaceOwner("alice")
            yield()
            val newAlice = app.coordinator.current.value!!
            assertNotSame(oldAlice, newAlice)
            assertEquals(2, app.resets)
            assertEquals(2, app.feeds.size)
            pending.complete(Unit)
            yield()
            assertEquals(2, app.feeds.size)
            assertFalse(oldAlice.state.value.isLoaded)
            assertSame(newAlice, app.coordinator.current.value)
        }
    }

    @Test fun signOutClearsFeedAndClosedSessionCannotReconnect() = runBlocking {
        Harness(Repository { TimetableSnapshot() }).use { app ->
            app.coordinator.replaceOwner("alice")
            yield()
            val old = app.coordinator.current.value!!
            assertEquals(1, app.feeds.size)
            app.coordinator.replaceOwner(null)
            assertEquals(1, app.resets)
            assertNull(app.coordinator.current.value)
            try {
                old.connect("https://example.com/calendar")
                fail("Closed session accepted an action")
            } catch (_: CancellationException) { }
            assertEquals(0, app.downloads)
        }
    }
}
