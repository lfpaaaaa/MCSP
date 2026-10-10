package au.edu.unimelb.campuscompanion.data.repository

import au.edu.unimelb.campuscompanion.data.DataError
import au.edu.unimelb.campuscompanion.data.model.GroupRole
import au.edu.unimelb.campuscompanion.data.remote.GroupMemberRow
import au.edu.unimelb.campuscompanion.data.remote.GroupSummaryRow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultGroupRepositoryTest {
    private val remote = FakeGroupRemoteDataSource()
    private var signedInUser: String? = "user-1"
    private val repository = DefaultGroupRepository(remote) { signedInUser }

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
