package au.edu.unimelb.campuscompanion.data.repository

import au.edu.unimelb.campuscompanion.data.DataError
import au.edu.unimelb.campuscompanion.data.model.GroupInvite
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class DefaultInviteRepositoryTest {
    private val remote = FakeGroupRemoteDataSource()
    private val groups = DefaultGroupRepository(remote) { "user-1" }
    private val invites = DefaultInviteRepository(remote, groups)

    @Test
    fun newInvitesCarryAJoinLink() = runBlocking<Unit> {
        val invite = invites.createInvite("group-1").getOrThrow()

        assertEquals("group-1", invite.groupId)
        assertEquals(GroupInvite.JOIN_URI_PREFIX + remote.inviteToken, invite.joinUri)
        assertEquals(Instant.parse("2026-09-25T06:10:00Z"), invite.expiresAt)
        assertFalse(invite.isExpired(Instant.parse("2026-09-25T06:00:00Z")))
    }

    @Test
    fun joiningAcceptsATokenOrAScannedJoinLink() = runBlocking<Unit> {
        invites.joinWithToken("campuscompanion://join?token=abc123").getOrThrow()
        invites.joinWithToken("  def456  ").getOrThrow()

        assertEquals(listOf("abc123", "def456"), remote.joinedTokens)
    }

    @Test
    fun blankTokensAreRejectedWithoutAServerCall() = runBlocking<Unit> {
        val error = invites.joinWithToken("   ").exceptionOrNull()

        assertTrue(error is DataError.InvalidInvite)
        assertEquals(DataError.InvalidInvite.Reason.Unknown, (error as DataError.InvalidInvite).reason)
        assertTrue(remote.joinedTokens.isEmpty())
    }

    @Test
    fun joiningRefreshesTheGroupList() = runBlocking<Unit> {
        remote.summaries = listOf(summaryRow("joined-group"))

        invites.joinWithToken("abc123").getOrThrow()

        assertEquals(listOf("joined-group"), groups.observeMyGroups().first().map { it.group.id })
    }

    @Test
    fun expiredInvitesKeepTheirReason() = runBlocking<Unit> {
        remote.failure = DataError.InvalidInvite(DataError.InvalidInvite.Reason.Expired)

        val error = invites.joinWithToken("abc123").exceptionOrNull()

        assertEquals(DataError.InvalidInvite.Reason.Expired, (error as DataError.InvalidInvite).reason)
    }
}
