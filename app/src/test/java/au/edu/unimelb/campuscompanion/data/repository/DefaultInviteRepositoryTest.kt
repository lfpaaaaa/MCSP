package au.edu.unimelb.campuscompanion.data.repository

import au.edu.unimelb.campuscompanion.data.DataError
import au.edu.unimelb.campuscompanion.data.model.GroupInvite
import au.edu.unimelb.campuscompanion.data.model.GroupRole
import au.edu.unimelb.campuscompanion.data.remote.GroupSummaryRow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
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
        invites.joinWithToken("  def4567  ").getOrThrow()

        assertEquals(listOf("abc123", "def4567"), remote.joinedTokens)
    }

    @Test
    fun typedGroupCodesAreSentUpperCaseWithoutSpaces() = runBlocking<Unit> {
        invites.joinWithToken(" abc 234 ").getOrThrow()
        invites.joinWithToken("abc-234").getOrThrow()

        assertEquals(listOf("ABC234", "ABC234"), remote.joinedTokens)
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
    fun joinByQrCodeAndNfcKeepsTheReturnedGroupWhenRefreshFails() = runBlocking<Unit> {
        remote.summaryFailure = DataError.Offline()
        // QR links, typed codes and NFC tokens use the same normalized join path.
        listOf("campuscompanion://join?token=qr-token", "abc 234", "nfc-token").forEach { input ->
            val joined = invites.joinWithToken(input).getOrThrow()
            val summary = groups.observeMyGroups().first().single()
            assertEquals(joined, summary.group)
            assertEquals(GroupRole.Member, summary.myRole)
        }
        assertEquals(listOf("qr-token", "ABC234", "nfc-token"), remote.joinedTokens)
    }

    @Test
    fun joinedGroupIsPublishedAndCachedBeforeSummaryRefreshCompletes() = runBlocking<Unit> {
        val cache = InviteGroupListCache()
        cache.save("user-1", listOf(summaryRow("existing")))
        val cached = DefaultGroupRepository(remote, cache) { "user-1" }
        val cachedInvites = DefaultInviteRepository(remote, cached)
        val started = CompletableDeferred<Unit>()
        val response = CompletableDeferred<List<GroupSummaryRow>>()
        remote.summaryRequest = { started.complete(Unit); response.await() }
        val join = async { cachedInvites.joinWithToken("abc123") }
        try {
            started.await()
            val expected = setOf("existing", "joined-group")
            assertEquals(expected, cached.observeMyGroups().first().map { it.group.id }.toSet())
            assertEquals(expected, cache.load("user-1")!!.map { it.id }.toSet())
        } finally {
            response.complete(listOf(summaryRow("joined-group"), summaryRow("existing")))
            assertTrue(join.await().isSuccess)
        }
    }

    @Test
    fun joinedGroupSurvivesFailedRefreshAndRepositoryRestart() = runBlocking<Unit> {
        val cache = InviteGroupListCache()
        cache.save("user-1", listOf(summaryRow("existing")))
        val cached = DefaultGroupRepository(remote, cache) { "user-1" }
        remote.summaryFailure = DataError.Offline()

        val joined = DefaultInviteRepository(remote, cached).joinWithToken("abc123").getOrThrow()
        cached.clearSession()
        val restored = DefaultGroupRepository(remote, cache) { "user-1" }.observeMyGroups().first()

        assertEquals(setOf("existing", joined.id), restored.map { it.group.id }.toSet())
        assertEquals(joined, restored.first { it.group.id == joined.id }.group)
    }

    @Test
    fun joiningAnExistingMembershipKeepsItsRoleAndActivityWhenRefreshFails() = runBlocking<Unit> {
        remote.summaries = listOf(summaryRow("joined-group", role = "owner").copy(
            unreadCount = 4, memberCount = 3,
            latestActivityAt = "2026-09-25T02:00:00+00:00", latestMessagePreview = "Revision at noon"
        ))
        groups.refresh().getOrThrow()
        remote.summaryFailure = DataError.Offline()

        invites.joinWithToken("abc123").getOrThrow()

        val summary = groups.observeMyGroups().first().single()
        assertEquals(GroupRole.Owner, summary.myRole)
        assertEquals(4, summary.unreadCount)
        assertEquals(3, summary.memberCount)
        assertEquals("Revision at noon", summary.latestMessagePreview)
        assertEquals(Instant.parse("2026-09-25T02:00:00Z"), summary.latestActivityAt)
    }

    @Test
    fun expiredInvitesKeepTheirReason() = runBlocking<Unit> {
        remote.failure = DataError.InvalidInvite(DataError.InvalidInvite.Reason.Expired)

        val error = invites.joinWithToken("abc123").exceptionOrNull()

        assertEquals(DataError.InvalidInvite.Reason.Expired, (error as DataError.InvalidInvite).reason)
    }
}

private class InviteGroupListCache : GroupListCache {
    private val lists = mutableMapOf<String, List<GroupSummaryRow>>()
    override fun load(userId: String): List<GroupSummaryRow>? = lists[userId]
    override fun save(userId: String, rows: List<GroupSummaryRow>) { lists[userId] = rows }
}
