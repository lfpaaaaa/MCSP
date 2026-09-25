package au.edu.unimelb.campuscompanion.data.repository

import au.edu.unimelb.campuscompanion.data.DataError
import au.edu.unimelb.campuscompanion.data.model.GroupRole
import au.edu.unimelb.campuscompanion.data.remote.GroupMemberRow
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
