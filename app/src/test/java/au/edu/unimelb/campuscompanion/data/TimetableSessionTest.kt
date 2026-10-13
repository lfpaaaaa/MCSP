package au.edu.unimelb.campuscompanion.data

import au.edu.unimelb.campuscompanion.ui.model.CourseSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class TimetableSessionTest {
    private val now = Instant.parse("2026-10-10T01:00:00Z")
    private val zone = ZoneId.of("Australia/Melbourne")
    private val url = "https://calendar.example/private-calendar"
    private val nextUrl = "https://calendar.example/replacement"
    private val parent = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private fun course(id: String = "imported", code: String = "COMP90018"): CourseSession {
        val start = ZonedDateTime.parse("2026-10-12T09:00:00+11:00[Australia/Melbourne]")
        return CourseSession(id, code, "Mobile Computing", "Parkville", "PAR-160", start, start.plusHours(1),
            activity = "tutorial")
    }

    private fun imported(code: String = "COMP90018") = course(code = code).let { session ->
        TimetableImport(listOf(session), listOf(session.timetableGroupSpec()!!.toTimetableGroup()), 1)
    }

    private fun saved(code: String = "COMP90018") = imported(code).let {
        TimetableSnapshot(TimetableSource(url, now.minusSeconds(60), it.sourceEventCount,
            it.groups.mapNotNull { group -> group.timetableSpec }), it.sessions)
    }

    private fun session(
        repository: FakeRepository,
        owner: String = "alice",
        download: suspend (String) -> Result<ByteArray> = { Result.failure(IOException("Offline")) },
        parse: suspend (ByteArray, List<RetainedOccurrence>) -> Result<TimetableImport> = { _, _ -> Result.success(imported()) }
    ) = TimetableSession(owner, repository, parent, download, parse, Dispatchers.Unconfined, { now }, { zone })

    @Test fun startsUnknownAndInitialReadPublishesDiskBeforeAnyNetwork() = runBlocking {
        val repository = FakeRepository(saved())
        val waiting = CompletableDeferred<Unit>()
        repository.beforeLoad = { waiting.await() }
        var downloads = 0
        val session = session(repository, download = { downloads++; Result.success(byteArrayOf()) })
        assertFalse(session.state.value.isLoaded)
        assertTrue(session.state.value.isLoading)
        val load = async(start = CoroutineStart.UNDISPATCHED) { session.initialize() }
        assertTrue(session.state.value.sessions.isEmpty())
        assertEquals(0, downloads)
        waiting.complete(Unit)
        load.await().getOrThrow()
        val state = session.state.value
        assertTrue(state.isLoaded)
        assertTrue(state.isConnected)
        assertTrue(state.isCached)
        assertFalse(state.isLoading)
        assertEquals(imported().sessions, state.sessions)
        assertEquals(imported().groups, state.groups)
        assertEquals(now.minusSeconds(60), state.lastSyncedAt)
        assertEquals(0, downloads)
    }

    @Test fun concurrentInitializationReadsOnlyOnceAndLoadedEmptyIsAuthoritative() = runBlocking {
        val repository = FakeRepository()
        val waiting = CompletableDeferred<Unit>()
        repository.beforeLoad = { waiting.await() }
        val session = session(repository)
        val first = async(start = CoroutineStart.UNDISPATCHED) { session.initialize() }
        val second = async(start = CoroutineStart.UNDISPATCHED) { session.initialize() }
        assertEquals(1, repository.loads)
        waiting.complete(Unit)
        first.await().getOrThrow()
        second.await().getOrThrow()
        session.initialize().getOrThrow()
        assertEquals(1, repository.loads)
        assertTrue(session.state.value.isLoaded)
        assertFalse(session.state.value.isLoading)
        assertTrue(session.state.value.sessions.isEmpty())
    }

    @Test fun initialFailureBlocksDownloadsUntilExplicitRetry() = runBlocking {
        val repository = FakeRepository(saved())
        repository.loadFailure = IOException("Unreadable timetable")
        var downloads = 0
        val session = session(repository, download = { downloads++; Result.success(byteArrayOf()) })
        assertTrue(session.initialize().isFailure)
        assertFalse(session.state.value.isLoaded)
        assertFalse(session.state.value.isLoading)
        assertEquals("Unreadable timetable", session.state.value.errorMessage)
        assertTrue(session.refresh().isFailure)
        assertTrue(session.connect(nextUrl).isFailure)
        assertTrue(session.clear().isFailure)
        assertEquals(0, downloads)
        assertEquals(1, repository.loads)
        repository.loadFailure = null
        session.retryLoad().getOrThrow()
        assertTrue(session.state.value.isLoaded)
        assertNull(session.state.value.errorMessage)
        session.refresh().getOrThrow()
        assertEquals(1, downloads)
    }

    @Test fun refreshWaitsForInitializationAndUsesTheSavedUrl() = runBlocking {
        val repository = FakeRepository(saved())
        val waiting = CompletableDeferred<Unit>()
        repository.beforeLoad = { waiting.await() }
        val urls = mutableListOf<String>()
        val session = session(repository, download = { urls += it; Result.success(byteArrayOf()) })
        val refresh = async(start = CoroutineStart.UNDISPATCHED) { session.refresh() }
        assertTrue(urls.isEmpty())
        waiting.complete(Unit)
        refresh.await().getOrThrow()
        assertEquals(listOf(url), urls)
        assertFalse(session.state.value.isCached)
        assertEquals(now, session.state.value.lastSyncedAt)
    }

    @Test fun urlOnlyIsLoadedButDoesNotCountAsConnectedOrCached() = runBlocking {
        val session = session(FakeRepository(TimetableSnapshot(TimetableSource(url))))
        session.initialize().getOrThrow()
        assertTrue(session.state.value.isLoaded)
        assertEquals(url, session.state.value.url)
        assertFalse(session.state.value.isConnected)
        assertFalse(session.state.value.isCached)
        assertNull(session.state.value.lastSyncedAt)
    }

    @Test fun failedDownloadPreservesTheCommittedStateAndStorage() = runBlocking {
        val repository = FakeRepository(saved())
        val session = session(repository)
        session.initialize().getOrThrow()
        val before = repository.rows["alice"]
        assertTrue(session.refresh().isFailure)
        assertEquals(before, repository.rows["alice"])
        assertEquals(imported().sessions, session.state.value.sessions)
        assertTrue(session.state.value.isLoaded)
        assertTrue(session.state.value.isCached)
        assertFalse(session.state.value.isLoading)
        assertEquals("Offline", session.state.value.errorMessage)
        assertEquals(0, repository.replacements)
    }

    @Test fun parseAndSaveFailuresRetainAllCommittedMetadata() = runBlocking {
        for (failParse in listOf(true, false)) {
            val repository = FakeRepository(saved())
            if (!failParse) repository.replaceFailure = IOException("Disk full")
            val session = session(repository, download = { Result.success(byteArrayOf()) }, parse = { _, _ ->
                if (failParse) Result.failure(IOException("Invalid calendar")) else Result.success(imported("SWEN90006"))
            })
            session.initialize().getOrThrow()
            val before = repository.rows["alice"]
            assertTrue(session.connect(nextUrl).isFailure)
            assertEquals(before, repository.rows["alice"])
            assertEquals(url, session.state.value.url)
            assertEquals(now.minusSeconds(60), session.state.value.lastSyncedAt)
            assertEquals(imported().groups, session.state.value.groups)
            assertEquals(imported().sessions, session.state.value.sessions)
            assertFalse(session.state.value.isLoading)
        }
    }

    @Test fun successfulEmptyImportIsFreshConnectedAndRoundTripsAsCached() = runBlocking {
        val repository = FakeRepository()
        val empty = TimetableImport(emptyList(), emptyList(), 0)
        val session = session(repository, download = { Result.success(byteArrayOf()) }, parse = { _, _ -> Result.success(empty) })
        session.connect(" $url ").getOrThrow()
        assertEquals(url, session.state.value.url)
        assertTrue(session.state.value.isConnected)
        assertFalse(session.state.value.isCached)
        assertTrue(session.state.value.sessions.isEmpty())
        assertEquals(now, session.state.value.lastSyncedAt)
        val reopened = session(repository)
        reopened.initialize().getOrThrow()
        assertTrue(reopened.state.value.isConnected)
        assertTrue(reopened.state.value.isCached)
        assertTrue(reopened.state.value.sessions.isEmpty())
    }

    @Test fun olderResponseCannotReplaceANewerSubscription() = runBlocking {
        val repository = FakeRepository(saved())
        val old = CompletableDeferred<Result<ByteArray>>()
        val session = session(repository, download = { if (it == url) old.await() else Result.success(byteArrayOf(2)) },
            parse = { bytes, _ -> Result.success(imported(if (bytes.first() == 2.toByte()) "SWEN90006" else "COMP90018")) })
        val oldRequest = async(start = CoroutineStart.UNDISPATCHED) { session.refresh() }
        session.connect(nextUrl).getOrThrow()
        old.complete(Result.success(byteArrayOf(1)))
        assertCancelled { oldRequest.await() }
        assertEquals(nextUrl, session.state.value.url)
        assertEquals("SWEN90006", session.state.value.sessions.single().code)
        assertEquals(1, repository.replacements)
    }

    @Test fun disconnectInvalidatesAPendingResponseAndRetainsManualClasses() = runBlocking {
        val manual = course("manual:00000000-0000-0000-0000-000000000001")
        val repository = FakeRepository(saved().copy(local = listOf(LocalClass(manual.id, LocalClassKind.MANUAL, manual))))
        val response = CompletableDeferred<Result<ByteArray>>()
        val session = session(repository, download = { response.await() })
        val refresh = async(start = CoroutineStart.UNDISPATCHED) { session.refresh() }
        session.clear().getOrThrow()
        response.complete(Result.success(byteArrayOf()))
        assertCancelled { refresh.await() }
        assertTrue(session.state.value.isLoaded)
        assertFalse(session.state.value.isConnected)
        assertEquals("", session.state.value.url)
        assertEquals(listOf(manual), session.state.value.sessions)
        assertNull(repository.rows["alice"]!!.source)
        assertEquals(0, repository.replacements)
    }

    @Test fun failedDisconnectKeepsTheTimetableAndInvalidatesOlderDownload() = runBlocking {
        val repository = FakeRepository(saved())
        repository.disconnectFailure = IOException("Cleanup could not be confirmed")
        val response = CompletableDeferred<Result<ByteArray>>()
        val session = session(repository, download = { response.await() })
        val refresh = async(start = CoroutineStart.UNDISPATCHED) { session.refresh() }
        assertTrue(session.clear().isFailure)
        response.complete(Result.success(byteArrayOf()))
        assertCancelled { refresh.await() }
        assertEquals(url, session.state.value.url)
        assertEquals(imported().sessions, session.state.value.sessions)
        assertEquals(saved(), repository.rows["alice"])
        assertEquals("Cleanup could not be confirmed", session.state.value.errorMessage)
    }

    @Test fun failedDisconnectPreservesAFreshImportsFreshness() = runBlocking {
        val repository = FakeRepository(saved())
        val session = session(repository, download = { Result.success(byteArrayOf()) })
        session.refresh().getOrThrow()
        assertFalse(session.state.value.isCached)
        repository.disconnectFailure = IOException("Cleanup failed")
        assertTrue(session.clear().isFailure)
        assertFalse(session.state.value.isCached)
        assertEquals(now, session.state.value.lastSyncedAt)
        assertEquals(url, session.state.value.url)
    }

    @Test fun newestLocalEditIsReadAfterDownloadAndPassedToParser() = runBlocking {
        val repository = FakeRepository(saved())
        val response = CompletableDeferred<Result<ByteArray>>()
        var targets: List<RetainedOccurrence>? = null
        val session = session(repository, download = { response.await() }, parse = { _, retained ->
            targets = retained
            Result.success(imported())
        })
        val refresh = async(start = CoroutineStart.UNDISPATCHED) { session.refresh() }
        val original = course()
        val corrected = original.copy(start = original.start.plusDays(4), end = original.end.plusDays(4))
        repository.upsertLocal("alice", LocalClass(original.id, LocalClassKind.EDITED, corrected, original.start))
        response.complete(Result.success(byteArrayOf()))
        refresh.await().getOrThrow()
        assertEquals(listOf(RetainedOccurrence(original.id, original.start)), targets)
        assertEquals(listOf(corrected), session.state.value.sessions)
    }

    @Test fun aDifferentUrlNeverRetainsOldAdjustmentTargets() = runBlocking {
        val original = course()
        val edit = LocalClass(original.id, LocalClassKind.EDITED, original.copy(end = original.end.plusDays(1)), original.start)
        val repository = FakeRepository(saved().copy(local = listOf(edit)))
        var targets: List<RetainedOccurrence>? = null
        val session = session(repository, download = { Result.success(byteArrayOf()) }, parse = { _, retained ->
            targets = retained
            Result.success(imported())
        })
        session.connect(nextUrl).getOrThrow()
        assertTrue(targets!!.isEmpty())
        assertTrue(repository.rows["alice"]!!.local.isEmpty())
    }

    @Test fun acceptedSaveFinishesBeforeNewerRequestCanBeStarted() = runBlocking {
        val repository = FakeRepository(saved())
        val saving = CompletableDeferred<Unit>()
        val finishSave = CompletableDeferred<Unit>()
        repository.beforeReplace = { requested -> if (requested == url) { saving.complete(Unit); finishSave.await() } }
        val downloaded = mutableListOf<String>()
        val session = session(repository, download = { downloaded += it; Result.success(byteArrayOf()) })
        val old = async(start = CoroutineStart.UNDISPATCHED) { session.refresh() }
        saving.await()
        val newer = async(start = CoroutineStart.UNDISPATCHED) { session.connect(nextUrl) }
        assertEquals(listOf(url), downloaded)
        finishSave.complete(Unit)
        old.await().getOrThrow()
        newer.await().getOrThrow()
        assertEquals(listOf(url, nextUrl), repository.committedUrls)
        assertEquals(nextUrl, session.state.value.url)
    }

    @Test fun disconnectWaitsForAnAcceptedSuspendedSaveThenWins() = runBlocking {
        val repository = FakeRepository(saved())
        val saving = CompletableDeferred<Unit>()
        val finishSave = CompletableDeferred<Unit>()
        repository.beforeReplace = { saving.complete(Unit); finishSave.await() }
        val session = session(repository, download = { Result.success(byteArrayOf()) })
        val refresh = async(start = CoroutineStart.UNDISPATCHED) { session.refresh() }
        saving.await()
        val disconnect = async(start = CoroutineStart.UNDISPATCHED) { session.clear() }
        assertEquals(0, repository.disconnects)
        finishSave.complete(Unit)
        refresh.await().getOrThrow()
        disconnect.await().getOrThrow()
        assertNull(repository.rows["alice"]!!.source)
        assertFalse(session.state.value.isConnected)
    }

    @Test fun callerDisposalDoesNotStopSharedSessionWork() = runBlocking {
        val repository = FakeRepository(saved())
        val response = CompletableDeferred<Result<ByteArray>>()
        val session = session(repository, download = { response.await() })
        val caller = launch(start = CoroutineStart.UNDISPATCHED) { session.refresh() }
        caller.cancelAndJoin()
        assertTrue(session.isActive)
        response.complete(Result.success(byteArrayOf()))
        assertEquals(1, repository.replacements)
        assertFalse(session.state.value.isLoading)
        assertEquals(now, session.state.value.lastSyncedAt)
    }

    @Test fun closingCancelsDelayedReadAndRejectsLaterActions() = runBlocking {
        val repository = FakeRepository(saved())
        repository.beforeLoad = { awaitCancellation() }
        val session = session(repository)
        val loading = async(start = CoroutineStart.UNDISPATCHED) { session.initialize() }
        session.close()
        assertCancelled { loading.await() }
        assertCancelled { session.retryLoad() }
        assertCancelled { session.connect(nextUrl) }
        assertCancelled { session.clear() }
        assertFalse(session.isActive)
        assertFalse(session.state.value.isLoaded)
        assertEquals(0, repository.replacements)
    }

    @Test fun closingOldSessionCannotPublishForANewInstanceOfTheSameAccount() = runBlocking {
        val repository = FakeRepository(saved())
        val response = CompletableDeferred<Result<ByteArray>>()
        val old = session(repository, download = { response.await() })
        val refresh = async(start = CoroutineStart.UNDISPATCHED) { old.refresh() }
        old.close()
        val current = session(repository)
        current.initialize().getOrThrow()
        response.complete(Result.success(byteArrayOf()))
        assertCancelled { refresh.await() }
        assertEquals(now.minusSeconds(60), current.state.value.lastSyncedAt)
        assertTrue(current.isActive)
        assertFalse(old.isActive)
        assertEquals(0, repository.replacements)
    }

    @Test fun disposalAfterACommitDoesNotPublishTheOldSessionResult() = runBlocking {
        val repository = FakeRepository(saved())
        val committed = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        repository.afterReplace = { withContext(NonCancellable) { committed.complete(Unit); finish.await() } }
        val old = session(repository, download = { Result.success(byteArrayOf()) },
            parse = { _, _ -> Result.success(imported("SWEN90006")) })
        old.initialize().getOrThrow()
        val refresh = async(start = CoroutineStart.UNDISPATCHED) { old.refresh() }
        committed.await()
        old.close()
        finish.complete(Unit)
        assertCancelled { refresh.await() }
        // Committed owner-scoped storage remains valid, but a cancelled activation cannot emit it.
        assertEquals("SWEN90006", repository.rows["alice"]!!.imported.single().code)
        assertEquals("COMP90018", old.state.value.sessions.single().code)
        assertEquals(now.minusSeconds(60), old.state.value.lastSyncedAt)
        val current = session(repository)
        current.initialize().getOrThrow()
        assertEquals("SWEN90006", current.state.value.sessions.single().code)
    }

    @Test fun cancelledPendingReadCannotPublishAfterTheSessionCloses() = runBlocking {
        val repository = FakeRepository(saved())
        val reading = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        repository.beforeLoad = { withContext(NonCancellable) { reading.complete(Unit); finish.await() } }
        val old = session(repository)
        val load = async(start = CoroutineStart.UNDISPATCHED) { old.initialize() }
        reading.await()
        old.close()
        finish.complete(Unit)
        assertCancelled { load.await() }
        assertFalse(old.state.value.isLoaded)
        assertTrue(old.state.value.sessions.isEmpty())
    }

    @Test fun cancellationFromDownloadPropagatesWithoutPublishingAnError() = runBlocking {
        val repository = FakeRepository(saved())
        val session = session(repository, download = { throw CancellationException("Cancelled download") })
        session.initialize().getOrThrow()
        assertCancelled { session.refresh() }
        assertFalse(session.state.value.isLoading)
        assertNull(session.state.value.errorMessage)
        assertEquals(saved(), repository.rows["alice"])
    }

    @Test fun accountRowsRemainIsolatedWhenOneSessionDisconnects() = runBlocking {
        val repository = FakeRepository(saved())
        repository.rows["bob"] = saved("SWEN90006")
        val alice = session(repository)
        val bob = session(repository, owner = "bob")
        bob.initialize().getOrThrow()
        alice.clear().getOrThrow()
        assertEquals("SWEN90006", bob.state.value.sessions.single().code)
        assertNotNull(repository.rows["bob"]!!.source)
        assertNull(repository.rows["alice"]!!.source)
    }

    private suspend fun assertCancelled(action: suspend () -> Any?) {
        try {
            action()
            fail("Expected cancellation to propagate")
        } catch (_: CancellationException) { }
    }

    private class FakeRepository(initial: TimetableSnapshot = TimetableSnapshot()) : TimetableRepository {
        val rows = mutableMapOf("alice" to initial)
        var loads = 0
        var replacements = 0
        var disconnects = 0
        val committedUrls = mutableListOf<String>()
        var beforeLoad: suspend () -> Unit = { }
        var beforeReplace: suspend (String) -> Unit = { }
        var afterReplace: suspend () -> Unit = { }
        var loadFailure: Exception? = null
        var replaceFailure: Exception? = null
        var disconnectFailure: Exception? = null

        override suspend fun load(ownerId: String): TimetableSnapshot {
            loads++
            beforeLoad()
            loadFailure?.let { throw it }
            return rows[ownerId] ?: TimetableSnapshot()
        }

        override suspend fun replaceImport(ownerId: String, url: String, imported: TimetableImport, syncedAt: Instant): TimetableSnapshot {
            beforeReplace(url)
            replaceFailure?.let { throw it }
            val old = rows[ownerId] ?: TimetableSnapshot()
            val local = if (old.source?.url == url) old.local else old.local.filter { it.kind == LocalClassKind.MANUAL }
            val result = TimetableSnapshot(TimetableSource(url, syncedAt, imported.sourceEventCount,
                imported.groups.mapNotNull { it.timetableSpec }), imported.sessions, local)
            rows[ownerId] = result
            replacements++
            committedUrls += url
            afterReplace()
            return result
        }

        override suspend fun disconnect(ownerId: String): TimetableSnapshot {
            disconnectFailure?.let { throw it }
            disconnects++
            return TimetableSnapshot(local = rows[ownerId]?.local.orEmpty().filter { it.kind == LocalClassKind.MANUAL })
                .also { rows[ownerId] = it }
        }

        override suspend fun upsertLocal(ownerId: String, row: LocalClass): TimetableSnapshot {
            val before = rows[ownerId] ?: TimetableSnapshot()
            return before.copy(local = before.local.filterNot { it.id == row.id } + row).also { rows[ownerId] = it }
        }

        override suspend fun deleteLocal(ownerId: String, id: String): TimetableSnapshot {
            val before = rows[ownerId] ?: TimetableSnapshot()
            return before.copy(local = before.local.filterNot { it.id == id }).also { rows[ownerId] = it }
        }
    }
}
