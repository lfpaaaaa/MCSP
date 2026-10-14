package au.edu.unimelb.campuscompanion.data

import au.edu.unimelb.campuscompanion.data.model.TimetableGroupSpec
import au.edu.unimelb.campuscompanion.ui.model.CourseSession
import au.edu.unimelb.campuscompanion.ui.model.TimetableState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZonedDateTime

class TimetableGroupSyncTest {
    private val syncedAt = Instant.parse("2026-10-13T01:00:00Z")
    private val spec = TimetableGroupSpec("COMP90018", "Mobile Computing", "course")
    private val parent = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private fun session(ownerId: String = "alice") = TimetableSession(
        ownerId = ownerId, repository = EmptyRepository, parentScope = parent,
        download = { error("No download should run in a group-sync test") },
        parse = { _, _ -> error("No parser should run in a group-sync test") },
        lifecycleDispatcher = Dispatchers.Unconfined
    )

    private fun fresh() = TimetableState(url = "https://calendar.example/private",
        groups = listOf(spec.toTimetableGroup()), isLoaded = true, isConnected = true,
        lastSyncedAt = syncedAt)

    @Test fun onlyFreshLoadedImportsFromTheCurrentOwnerAreEligible() {
        val session = session()
        val state = fresh()
        assertTrue(TimetableGroupSyncKey.from("alice", session, state).eligible)
        for (blocked in listOf(
            TimetableState(url = state.url),
            state.copy(isLoaded = false),
            state.copy(isConnected = false),
            state.copy(isCached = true),
            state.copy(isLoading = true),
            state.copy(lastSyncedAt = null)
        )) assertFalse(TimetableGroupSyncKey.from("alice", session, blocked).eligible)
        assertFalse(TimetableGroupSyncKey.from("alice", null, state).eligible)
        assertFalse(TimetableGroupSyncKey.from("bob", session, state).eligible)
        session.close()
        assertFalse(TimetableGroupSyncKey.from("alice", session, state).eligible)
    }

    @Test fun freshEmptyImportStillRunsTheExistingIdempotentSync() {
        val key = TimetableGroupSyncKey.from("alice", session(), fresh().copy(groups = emptyList()))
        assertTrue(key.eligible)
        assertTrue(key.specs.isEmpty())
    }

    @Test fun localChangesDoNotChangeTheSyncKeyOrImportedSpecs() {
        val session = session()
        val original = fresh()
        val start = ZonedDateTime.parse("2026-10-14T09:00:00+11:00[Australia/Melbourne]")
        val local = CourseSession("manual:00000000-0000-0000-0000-000000000001", "SWEN90006",
            "Local class", "Parkville", "PAR-160", start, start.plusHours(1))
        val key = TimetableGroupSyncKey.from("alice", session, original)
        val changed = original.copy(sessions = listOf(local), detectedEventCount = 200,
            errorMessage = "A local save failed", groupSyncError = "Retrying", isSyncingGroups = true)
        assertEquals(key, TimetableGroupSyncKey.from("alice", session, changed))
        assertEquals(listOf(spec), key.specs)
    }

    @Test fun aFastAcceptedRefreshChangesTheKeyWithoutAVisibleLoadingEmission() {
        val session = session()
        val original = fresh()
        val first = TimetableGroupSyncKey.from("alice", session, original)
        val second = TimetableGroupSyncKey.from("alice", session,
            original.copy(lastSyncedAt = syncedAt.plusSeconds(1)))
        assertNotEquals(first, second)
        assertTrue(second.eligible)
    }

    @Test fun sameOwnerReactivationHasANewKeyDespiteIdenticalMetadata() {
        val old = session()
        val next = session()
        assertNotEquals(TimetableGroupSyncKey.from("alice", old, fresh()),
            TimetableGroupSyncKey.from("alice", next, fresh()))
    }

    @Test fun urlOnlyAndDiskStatesDoNotStartSyncAndLocalChangesDoNotRestartIt() = runBlocking {
        val session = session()
        val states = MutableStateFlow(TimetableState(url = fresh().url))
        var attempts = 0
        val syncing = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            states.map { TimetableGroupSyncKey.from("alice", session, it) }
                .distinctUntilChanged().collectLatest { key ->
                    if (key.eligible) {
                        attempts++
                        awaitCancellation()
                    }
                }
        }
        assertEquals(0, attempts)
        states.value = fresh().copy(isCached = true)
        assertEquals(0, attempts)
        states.value = fresh()
        assertEquals(1, attempts)
        states.value = fresh().copy(errorMessage = "Local validation failed", sessions = emptyList())
        assertEquals(1, attempts)
        states.value = fresh().copy(lastSyncedAt = syncedAt.plusSeconds(1))
        assertEquals(2, attempts)
        syncing.cancelAndJoin()
    }

    @Test fun disconnectCancelsThePendingRetry() = runBlocking {
        val session = session()
        val states = MutableStateFlow(fresh())
        val retryEntered = CompletableDeferred<Unit>()
        val retryCancelled = CompletableDeferred<Unit>()
        val syncing = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            states.map { TimetableGroupSyncKey.from("alice", session, it) }
                .distinctUntilChanged().collectLatest { key ->
                    if (!key.eligible) return@collectLatest
                    try {
                        retryEntered.complete(Unit)
                        awaitCancellation()
                    } finally {
                        retryCancelled.complete(Unit)
                    }
                }
        }
        retryEntered.await()
        states.value = TimetableState(isLoaded = true)
        retryCancelled.await()
        assertFalse(TimetableGroupSyncKey.from("alice", session, states.value).eligible)
        syncing.cancelAndJoin()
    }

    @Test fun sameOwnerSessionReplacementCancelsTheOldActivationsRetry() = runBlocking {
        val old = session()
        val next = session()
        val keys = MutableStateFlow(TimetableGroupSyncKey.from("alice", old, fresh()))
        val oldEntered = CompletableDeferred<Unit>()
        val oldCancelled = CompletableDeferred<Unit>()
        val nextEntered = CompletableDeferred<Unit>()
        val syncing = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            keys.collectLatest { key ->
                if (!key.eligible) return@collectLatest
                if (key.session === old) {
                    try {
                        oldEntered.complete(Unit)
                        awaitCancellation()
                    } finally {
                        oldCancelled.complete(Unit)
                    }
                } else {
                    nextEntered.complete(Unit)
                    awaitCancellation()
                }
            }
        }
        oldEntered.await()
        old.close()
        keys.value = TimetableGroupSyncKey.from("alice", next, fresh())
        oldCancelled.await()
        nextEntered.await()
        syncing.cancelAndJoin()
    }

    private object EmptyRepository : TimetableRepository {
        override suspend fun load(ownerId: String) = TimetableSnapshot()
        override suspend fun replaceImport(ownerId: String, url: String, imported: TimetableImport, syncedAt: Instant): TimetableSnapshot =
            error("Not used")
        override suspend fun disconnect(ownerId: String): TimetableSnapshot = error("Not used")
        override suspend fun upsertLocal(ownerId: String, row: LocalClass): TimetableSnapshot = error("Not used")
        override suspend fun deleteLocal(ownerId: String, id: String): TimetableSnapshot = error("Not used")
    }
}
