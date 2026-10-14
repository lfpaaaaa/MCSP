package au.edu.unimelb.campuscompanion.data.repository

import au.edu.unimelb.campuscompanion.data.DataError
import au.edu.unimelb.campuscompanion.data.model.GroupRole
import au.edu.unimelb.campuscompanion.data.remote.GroupMemberRow
import au.edu.unimelb.campuscompanion.data.remote.GroupRow
import au.edu.unimelb.campuscompanion.data.remote.GroupSummaryRow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultGroupRepositoryTest {
    private val remote = FakeGroupRemoteDataSource()
    private var signedInUser: String? = "user-1"
    private val repository = DefaultGroupRepository(remote) { signedInUser }

    @Test
    fun nicknameIsTrimmedAndSavedToOnlyTheRequestedGroup() = runBlocking<Unit> {
        assertEquals("Tutorial Alice", repository.setMyNickname("group-1", " Tutorial Alice ").getOrThrow())
        assertEquals(listOf("group-1" to "Tutorial Alice"), remote.nicknameUpdates)
    }

    @Test
    fun invalidNicknamesAndSignedOutUpdatesNeverReachServer() = runBlocking<Unit> {
        assertTrue(repository.setMyNickname("group-1", "  ").exceptionOrNull() is DataError.Validation)
        assertTrue(repository.setMyNickname("group-1", "x".repeat(41)).exceptionOrNull() is DataError.Validation)
        signedInUser = null
        assertTrue(repository.setMyNickname("group-1", "Alice").exceptionOrNull() is DataError.Unauthenticated)
        assertTrue(remote.nicknameUpdates.isEmpty())
    }

    @Test
    fun failedNicknameSaveReportsFailure() = runBlocking<Unit> {
        remote.failure = DataError.Offline()
        assertTrue(repository.setMyNickname("group-1", "Alice").exceptionOrNull() is DataError.Offline)
        assertTrue(remote.nicknameUpdates.isEmpty())
    }

    @Test
    fun memberListObservesNicknameChangesWithoutReopening() = runBlocking<Unit> {
        remote.members = listOf(GroupMemberRow("user-2", "Bob", role = "member", joinedAt = "2026-09-25T00:00:00Z"))
        val seen = kotlinx.coroutines.flow.MutableStateFlow("")
        val job = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            repository.observeMembers("group-1").collect { seen.value = it.single().displayName }
        }
        try {
            kotlinx.coroutines.withTimeout(5000) {
                seen.first { it == "Bob" }
                remote.memberUpdates.subscriptionCount.first { it > 0 }
                remote.members = remote.members.map { it.copy(displayName = "Tutorial Bob") }
                remote.memberUpdates.emit(Unit)
                seen.first { it == "Tutorial Bob" }
            }
        } finally { job.cancel() }
    }

    @Test
    fun refreshListsGroupsWithTheLatestActivityFirst() = runBlocking<Unit> {
        remote.summaries = listOf(
            summaryRow("older", latestActivityAt = "2026-09-25T01:00:00+00:00"),
            summaryRow("newer", latestActivityAt = "2026-09-25T02:00:00+00:00")
        )

        assertTrue(repository.refresh().isSuccess)

        assertEquals(listOf("newer", "older"), repository.observeMyGroups().first().map { it.group.id })
    }

    @Test
    fun theSavedListIsShownUntilTheFirstRefreshAndReplacedAfterIt() = runBlocking<Unit> {
        val cache = InMemoryGroupListCache()
        cache.save("user-1", listOf(summaryRow("saved", latestActivityAt = "2026-09-24T01:00:00+00:00")))
        remote.summaries = listOf(summaryRow("fresh", latestActivityAt = "2026-09-25T01:00:00+00:00"))
        val cached = DefaultGroupRepository(remote, cache) { signedInUser }

        assertEquals(listOf("saved"), cached.observeMyGroups().first().map { it.group.id })
        assertEquals(0, remote.summaryFetches)

        assertTrue(cached.refresh().isSuccess)

        assertEquals(listOf("fresh"), cached.observeMyGroups().first().map { it.group.id })
        assertEquals(listOf("fresh"), cache.load("user-1")?.map { it.id })
    }

    @Test
    fun anotherUsersSavedListIsNotShown() = runBlocking<Unit> {
        val cache = InMemoryGroupListCache()
        cache.save("user-2", listOf(summaryRow("theirs")))
        remote.summaries = listOf(summaryRow("mine"))
        val cached = DefaultGroupRepository(remote, cache) { signedInUser }

        assertTrue(cached.refresh().isSuccess)

        assertEquals(listOf("mine"), cached.observeMyGroups().first().map { it.group.id })
        assertEquals(listOf("theirs"), cache.load("user-2")?.map { it.id })
    }

    @Test
    fun createdGroupSurvivesFailedRefreshSignOutAndRepositoryRestart() = runBlocking<Unit> {
        val cache = InMemoryGroupListCache()
        cache.save("user-1", listOf(summaryRow("existing")))
        val cached = DefaultGroupRepository(remote, cache) { signedInUser }
        remote.summaryFailure = DataError.Offline()

        assertTrue(cached.createGroup("Revision", null).isSuccess)
        signedInUser = null
        cached.clearSession()
        assertTrue(cached.observeMyGroups().first().isEmpty())
        signedInUser = "user-1"
        val restarted = DefaultGroupRepository(remote, cache) { signedInUser }
        val restored = restarted.observeMyGroups().first()
        assertEquals(setOf("new-group", "existing"), restored.map { it.group.id }.toSet())
        assertEquals(GroupRole.Owner, restored.first { it.group.id == "new-group" }.myRole)
    }

    @Test
    fun reusedRepositoryRestoresOnlyTheSignedInAccountsCache() = runBlocking<Unit> {
        val cache = InMemoryGroupListCache()
        cache.save("user-1", listOf(summaryRow("alice")))
        cache.save("user-2", listOf(summaryRow("bob")))
        val cached = DefaultGroupRepository(remote, cache) { signedInUser }
        assertEquals("alice", cached.observeMyGroups().first().single().group.id)
        signedInUser = "user-2"
        assertEquals("bob", cached.observeMyGroups().first().single().group.id)
        signedInUser = "user-1"
        assertEquals("alice", cached.observeMyGroups().first().single().group.id)
    }

    @Test
    fun restoredCacheIsVisibleWhileRefreshIsWaitingForTheNetwork() = runBlocking<Unit> {
        val cache = InMemoryGroupListCache()
        cache.save("user-1", listOf(summaryRow("saved")))
        val cached = DefaultGroupRepository(remote, cache) { signedInUser }
        val started = CompletableDeferred<Unit>()
        val response = CompletableDeferred<List<GroupSummaryRow>>()
        remote.summaryRequest = { started.complete(Unit); response.await() }
        val request = async { cached.refresh() }
        try {
            started.await()
            val list = kotlinx.coroutines.withTimeout(5000) { cached.observeMyGroups().first() }
            assertEquals("saved", list.single().group.id)
        } finally {
            response.complete(listOf(summaryRow("fresh")))
            request.await()
        }
    }

    @Test
    fun responseFromBeforeLogoutCannotReplaceTheNextLoginsGroups() = runBlocking<Unit> {
        val cache = InMemoryGroupListCache()
        cache.save("user-1", listOf(summaryRow("saved")))
        val cached = DefaultGroupRepository(remote, cache) { signedInUser }
        val started = CompletableDeferred<Unit>()
        val response = CompletableDeferred<List<GroupSummaryRow>>()
        remote.summaryRequest = { started.complete(Unit); response.await() }
        val request = async { cached.refresh() }
        started.await()
        signedInUser = null
        cached.clearSession()
        signedInUser = "user-1"
        response.complete(listOf(summaryRow("stale")))
        assertTrue(request.await().exceptionOrNull() is DataError.Unauthenticated)
        assertEquals("saved", cached.observeMyGroups().first().single().group.id)
        assertEquals("saved", cache.load("user-1")!!.single().id)
    }

    @Test
    fun accountSwitchDuringRefreshCannotWriteIntoAnotherAccountsCache() = runBlocking<Unit> {
        val cache = InMemoryGroupListCache()
        cache.save("user-2", listOf(summaryRow("bob")))
        val cached = DefaultGroupRepository(remote, cache) { signedInUser }
        val started = CompletableDeferred<Unit>()
        val response = CompletableDeferred<List<GroupSummaryRow>>()
        remote.summaryRequest = { started.complete(Unit); response.await() }
        val request = async { cached.refresh() }
        started.await()
        signedInUser = "user-2"
        response.complete(listOf(summaryRow("alice")))
        assertTrue(request.await().isFailure)
        assertEquals("bob", cached.observeMyGroups().first().single().group.id)
        assertEquals("bob", cache.load("user-2")!!.single().id)
    }

    @Test
    fun overlappingRefreshCannotEraseASuccessfullyCreatedGroup() = runBlocking<Unit> {
        val cache = InMemoryGroupListCache()
        val cached = DefaultGroupRepository(remote, cache) { signedInUser }
        val started = CompletableDeferred<Unit>()
        val response = CompletableDeferred<List<GroupSummaryRow>>()
        remote.summaryRequest = { started.complete(Unit); response.await() }
        val refresh = async { cached.refresh() }
        started.await()
        val create = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            cached.createGroup("Revision", null)
        }
        // The create waits for the older refresh, then its follow-up list request fails.
        remote.summaryFailure = DataError.Offline()
        response.complete(emptyList())
        assertTrue(refresh.await().isSuccess)
        assertTrue(create.await().isSuccess)
        assertEquals("new-group", cached.observeMyGroups().first().single().group.id)
        assertEquals("new-group", cache.load("user-1")!!.single().id)
    }

    @Test
    fun overlappingRefreshCannotEraseASuccessfullyJoinedGroup() = runBlocking<Unit> {
        val cache = InMemoryGroupListCache()
        val cached = DefaultGroupRepository(remote, cache) { signedInUser }
        val started = CompletableDeferred<Unit>()
        val response = CompletableDeferred<List<GroupSummaryRow>>()
        remote.summaryRequest = { started.complete(Unit); response.await() }
        val refresh = async { cached.refresh() }
        started.await()
        val join = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            cached.joinWithToken("abc123")
        }
        remote.summaryFailure = DataError.Offline()
        response.complete(emptyList())

        assertTrue(refresh.await().isSuccess)
        assertTrue(join.await().isSuccess)
        assertEquals("joined-group", cached.observeMyGroups().first().single().group.id)
        assertEquals("joined-group", cache.load("user-1")!!.single().id)
    }

    @Test
    fun joinResponseFromBeforeLogoutCannotPublishIntoTheNextLogin() = runBlocking<Unit> {
        val cache = InMemoryGroupListCache()
        cache.save("user-1", listOf(summaryRow("saved")))
        val cached = DefaultGroupRepository(remote, cache) { signedInUser }
        val started = CompletableDeferred<Unit>()
        val response = CompletableDeferred<GroupRow>()
        remote.joinRequest = { started.complete(Unit); response.await() }
        val join = async { cached.joinWithToken("abc123") }
        started.await()
        signedInUser = null
        cached.clearSession()
        signedInUser = "user-1"
        response.complete(groupRow("stale-join"))

        assertTrue(join.await().exceptionOrNull() is DataError.Unauthenticated)
        assertEquals("saved", cached.observeMyGroups().first().single().group.id)
        assertEquals("saved", cache.load("user-1")!!.single().id)
        assertEquals(0, remote.summaryFetches)
    }

    @Test
    fun accountSwitchDuringJoinCannotWriteIntoAnotherAccountsCache() = runBlocking<Unit> {
        val cache = InMemoryGroupListCache()
        cache.save("user-2", listOf(summaryRow("bob")))
        val cached = DefaultGroupRepository(remote, cache) { signedInUser }
        val started = CompletableDeferred<Unit>()
        val response = CompletableDeferred<GroupRow>()
        remote.joinRequest = { started.complete(Unit); response.await() }
        val join = async { cached.joinWithToken("abc123") }
        started.await()
        signedInUser = "user-2"
        response.complete(groupRow("alice-join"))

        assertTrue(join.await().exceptionOrNull() is DataError.Unauthenticated)
        assertEquals("bob", cached.observeMyGroups().first().single().group.id)
        assertEquals("bob", cache.load("user-2")!!.single().id)
        assertEquals(0, remote.summaryFetches)
    }

    @Test
    fun timetableSyncFailureDoesNotHideCachedManualGroups() = runBlocking<Unit> {
        val cache = InMemoryGroupListCache()
        cache.save("user-1", listOf(summaryRow("manual", role = "owner")))
        val cached = DefaultGroupRepository(remote, cache) { signedInUser }
        remote.timetableRows = listOf(groupRow("course").copy(timetableKey = "COMP90018|course"))
        remote.summaryFailure = DataError.Offline()
        assertTrue(cached.syncTimetableGroups(emptyList()).isFailure)
        assertEquals(setOf("manual", "course"), cached.observeMyGroups().first().map { it.group.id }.toSet())
        assertEquals(setOf("manual", "course"), cache.load("user-1")!!.map { it.id }.toSet())
    }

    @Test
    fun leaveDissolveAndTransferAlsoUpdateTheListRestoredAfterLogin() = runBlocking<Unit> {
        val cache = InMemoryGroupListCache()
        cache.save("user-1", listOf("leave", "dissolve", "transfer", "keep").map { summaryRow(it) })
        val cached = DefaultGroupRepository(remote, cache) { signedInUser }
        assertTrue(cached.leaveGroup("leave").isSuccess)
        assertTrue(cached.dissolveGroup("dissolve").isSuccess)
        assertTrue(cached.transferAndLeave("transfer", "user-2").isSuccess)
        cached.clearSession()
        assertEquals("keep", cached.observeMyGroups().first().single().group.id)
    }

    @Test
    fun failingToWriteCacheDoesNotReportAnAlreadyCreatedGroupAsFailed() = runBlocking<Unit> {
        val cache = object : GroupListCache {
            override fun load(userId: String): List<GroupSummaryRow>? = null
            override fun save(userId: String, rows: List<GroupSummaryRow>) { throw java.io.IOException("Disk full") }
        }
        val cached = DefaultGroupRepository(remote, cache) { signedInUser }
        assertTrue(cached.createGroup("Revision", null).isSuccess)
        assertEquals("new-group", cached.observeMyGroups().first().single().group.id)
    }

    @Test
    fun failingToWriteCacheDoesNotReportAnAlreadyJoinedGroupAsFailed() = runBlocking<Unit> {
        val cache = object : GroupListCache {
            override fun load(userId: String): List<GroupSummaryRow>? = null
            override fun save(userId: String, rows: List<GroupSummaryRow>) { throw java.io.IOException("Disk full") }
        }
        val cached = DefaultGroupRepository(remote, cache) { signedInUser }
        remote.summaryFailure = DataError.Offline()

        assertTrue(cached.joinWithToken("abc123").isSuccess)
        assertEquals("joined-group", cached.observeMyGroups().first().single().group.id)
    }

    @Test
    fun signedOutRefreshAndCreationDoNotReachTheServer() = runBlocking<Unit> {
        signedInUser = null
        assertTrue(repository.refresh().exceptionOrNull() is DataError.Unauthenticated)
        assertTrue(repository.createGroup("Revision", null).exceptionOrNull() is DataError.Unauthenticated)
        assertTrue(repository.joinWithToken("abc123").exceptionOrNull() is DataError.Unauthenticated)
        assertEquals(0, remote.summaryFetches)
        assertTrue(remote.createdGroups.isEmpty())
        assertTrue(remote.joinedTokens.isEmpty())
    }

    @Test
    fun refreshFailuresKeepTheirDataErrorType() = runBlocking<Unit> {
        remote.failure = DataError.Offline()

        assertTrue(repository.refresh().exceptionOrNull() is DataError.Offline)
    }

    @Test
    fun invalidGroupsAreRejectedBeforeAnyServerCall() = runBlocking<Unit> {
        assertTrue(repository.createGroup("   ", null).exceptionOrNull() is DataError.Validation)
        assertTrue(repository.createGroup("Revision", "COMP9").exceptionOrNull() is DataError.Validation)

        assertTrue(remote.createdGroups.isEmpty())
    }

    @Test
    fun createGroupSendsNormalisedInputAndRefreshesTheList() = runBlocking<Unit> {
        val group = repository.createGroup("  Revision  ", " swen90006 ").getOrThrow()

        assertEquals(listOf("Revision" to "SWEN90006"), remote.createdGroups)
        assertEquals("SWEN90006", group.courseCode)
        assertEquals(1, remote.summaryFetches)
    }

    @Test
    fun membersKeepTheirRoles() = runBlocking<Unit> {
        remote.members = listOf(
            GroupMemberRow(userId = "user-1", displayName = "Alice", role = "owner", joinedAt = "2026-09-20T00:00:00+00:00"),
            GroupMemberRow(userId = "user-2", displayName = "Bob", role = "member", joinedAt = "2026-09-21T00:00:00+00:00")
        )

        val members = repository.observeMembers("group-1").first()

        assertEquals(listOf(GroupRole.Owner, GroupRole.Member), members.map { it.role })
        assertEquals("Alice", members.first().displayName)
    }

    @Test
    fun leavingRequiresASignedInUser() = runBlocking<Unit> {
        signedInUser = null

        assertTrue(repository.leaveGroup("group-1").exceptionOrNull() is DataError.Unauthenticated)
        assertTrue(remote.deletedMemberships.isEmpty())
    }

    @Test
    fun leavingRemovesTheGroupFromTheList() = runBlocking<Unit> {
        remote.summaries = listOf(summaryRow("group-1"), summaryRow("group-2"))
        repository.refresh()

        assertTrue(repository.leaveGroup("group-1").isSuccess)

        assertEquals(listOf("group-1" to "user-1"), remote.deletedMemberships)
        assertEquals(listOf("group-2"), repository.observeMyGroups().first().map { it.group.id })
    }

    @Test
    fun leavingAGroupYouAreNotInReportsNotFound() = runBlocking<Unit> {
        remote.deletedRowCount = 0

        assertTrue(repository.leaveGroup("group-1").exceptionOrNull() is DataError.NotFound)
    }
}

private class InMemoryGroupListCache : GroupListCache {
    private val lists = mutableMapOf<String, List<GroupSummaryRow>>()

    override fun load(userId: String): List<GroupSummaryRow>? = lists[userId]

    override fun save(userId: String, rows: List<GroupSummaryRow>) {
        lists[userId] = rows
    }
}
