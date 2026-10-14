package au.edu.unimelb.campuscompanion.data.repository

import au.edu.unimelb.campuscompanion.data.DataError
import au.edu.unimelb.campuscompanion.data.model.*
import au.edu.unimelb.campuscompanion.data.remote.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class GroupPinsRepositoryTest {
    private fun pin() = GroupPin("pin", "Alex", Instant.EPOCH, true, PinVisibility.Personal,
        message = ChatMessage("old-message", "client", "group", "sender", "Sam", "Old important message", Instant.EPOCH, MessageStatus.Sent))
    private class Remote(var snapshot: GroupPinsSnapshot) : GroupPinsDataSource {
        var failure: Throwable? = null
        var requestedScope: Boolean? = null
        override suspend fun fetch(groupId: String): GroupPinsSnapshot { failure?.let { throw it }; return snapshot }
        override suspend fun pin(groupId: String, target: PinTarget, forEveryone: Boolean) { failure?.let { throw it }; requestedScope = forEveryone }
        override suspend fun unpin(groupId: String, pinId: String) { failure?.let { throw it }; snapshot = snapshot.copy(items = emptyList()) }
        override fun changes(groupId: String): Flow<Unit> = emptyFlow()
    }
    private suspend fun Channel<GroupPinsState>.awaitState(predicate: (GroupPinsState) -> Boolean): GroupPinsState = withTimeout(5_000) {
        var state = receive()
        while (!predicate(state)) state = receive()
        state
    }

    @Test fun backendPinsIncludeMessagesOutsideTheLoadedChatHistory() = runBlocking<Unit> {
        val state = GroupPinsRepository(Remote(GroupPinsSnapshot(listOf(pin()), false))).observe("group").first { !it.loading }
        assertEquals("Old important message", state.items.single().preview)
        assertFalse(state.canPinForEveryone)
    }

    @Test fun mutationScopesAreExplicitAndUnpinRefreshesTheList() = runBlocking<Unit> {
        val remote = Remote(GroupPinsSnapshot(listOf(pin()), true))
        val repo = GroupPinsRepository(remote)
        val states = Channel<GroupPinsState>(Channel.UNLIMITED)
        val job = launch { repo.observe("group").collect { states.send(it) } }
        try {
            states.awaitState { !it.loading }
            repo.pin("group", pin().target, false).getOrThrow()
            assertEquals(false, remote.requestedScope)
            repo.pin("group", pin().target, true).getOrThrow()
            assertEquals(true, remote.requestedScope)
            repo.unpin("group", "pin").getOrThrow()
            assertTrue(states.awaitState { !it.loading && it.items.isEmpty() }.items.isEmpty())
        } finally { job.cancelAndJoin() }
    }

    @Test fun offlineFailureRetainsDisplayedPinsButMembershipLossClearsThem() = runBlocking<Unit> {
        val remote = Remote(GroupPinsSnapshot(listOf(pin()), true));val repo = GroupPinsRepository(remote)
        val states = Channel<GroupPinsState>(Channel.UNLIMITED)
        val job = launch { repo.observe("group").collect { states.send(it) } }
        try {
            states.awaitState { !it.loading }
            remote.failure = DataError.Offline();repo.refresh("group")
            assertEquals(1, states.awaitState { it.error is DataError.Offline }.items.size)
            assertTrue(repo.unpin("group", "pin").isFailure)
            assertEquals(1, remote.snapshot.items.size)
            remote.failure = DataError.Forbidden();repo.refresh("group")
            val denied = states.awaitState { it.error is DataError.Forbidden }
            assertTrue(denied.items.isEmpty());assertFalse(denied.canPinForEveryone)
            remote.failure = null;remote.snapshot = GroupPinsSnapshot(emptyList(), false);repo.refresh("group")
            assertTrue(states.awaitState { !it.loading && it.error == null && it.items.isEmpty() }.items.isEmpty())
        } finally { job.cancelAndJoin() }
    }

    @Test fun periodicRefreshRecoversMissedEventsAndRoleChanges() = runBlocking<Unit> {
        val remote = Remote(GroupPinsSnapshot(emptyList(), false));val repo = GroupPinsRepository(remote, refreshMillis = 25)
        val states = Channel<GroupPinsState>(Channel.UNLIMITED)
        val job = launch { repo.observe("group").collect { states.send(it) } }
        try {
            states.awaitState { !it.loading }
            remote.snapshot = GroupPinsSnapshot(listOf(pin()), true)
            assertTrue(states.awaitState { it.items.isNotEmpty() }.canPinForEveryone)
        } finally { job.cancelAndJoin() }
    }

    @Test fun absentBackendNeverClaimsToSaveAPin() = runBlocking<Unit> {
        val repo = GroupPinsRepository(null)
        assertTrue(repo.observe("group").first().items.isEmpty())
        assertTrue(repo.pin("group", pin().target, false).exceptionOrNull() is DataError.Validation)
    }

    @Test fun rpcSnapshotKeepsVisibilityNamesAndAttachmentRestrictions() {
        val json = """{"can_pin_for_everyone":false,"items":[{"id":"pin","visibility":"group","pinned_by_name":"Owner nickname","pinned_at":"2026-10-13T00:00:00Z","can_unpin":false,"message":null,"file":{"id":"file","group_id":"g","uploader_id":"u","uploader_name":"Nickname","file_name":"notes.pdf","mime_type":"application/pdf","size_bytes":12,"storage_path":"g/u/file","created_at":"2026-10-12T00:00:00Z","allow_downloads":false}}]}"""
        val row = remoteJson.decodeFromString(GroupPinsSnapshotRow.serializer(), json)
        val pin = row.items.single().toModel()
        assertEquals(PinVisibility.Group, pin.visibility)
        assertEquals("Owner nickname", pin.pinnedByName)
        assertEquals("Nickname", pin.senderName)
        assertFalse(pin.canUnpin)
        assertFalse(pin.file!!.canDownloadBy("another-member"))
        assertEquals(PinKind.File, pin.target.kind)
    }

    @Test fun cancellationDoesNotBecomeAnErrorOrSuccess() = runBlocking<Unit> {
        val remote = Remote(GroupPinsSnapshot(emptyList(), false));remote.failure = CancellationException("Cancelled")
        try { GroupPinsRepository(remote).pin("group", pin().target, false);fail("Cancellation must propagate") }
        catch (_: CancellationException) { }
    }
}
