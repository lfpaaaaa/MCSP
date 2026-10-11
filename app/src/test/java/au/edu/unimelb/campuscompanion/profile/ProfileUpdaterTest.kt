package au.edu.unimelb.campuscompanion.profile

import au.edu.unimelb.campuscompanion.auth.AuthenticatedUser
import au.edu.unimelb.campuscompanion.auth.SignInProvider
import au.edu.unimelb.campuscompanion.data.DataError
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ProfileUpdaterTest {
    private val id = "11111111-1111-4111-8111-111111111111"
    private val oldPath = "$id/22222222-2222-4222-8222-222222222222.jpg"
    private val original = AuthenticatedUser(id, "Before", "test@example.com", SignInProvider.Email, AVATAR_PREFIX + oldPath)
    private inner class Remote : ProfileRemote {
        var sessionId: String? = id
        var stored = original
        var uploadFails = false
        var updateFails = false
        var cleanupFails = false
        var switchAfterUpload = false
        val uploads = mutableListOf<String>()
        val deletions = mutableListOf<String>()
        var updates = 0
        var lastAvatar: String? = null
        override fun currentUserId() = sessionId
        override suspend fun uploadAvatar(path: String, bytes: ByteArray) {
            if (uploadFails) error("Upload failed")
            uploads += path
            if (switchAfterUpload) sessionId = "another-user"
        }
        override suspend fun update(userId: String, name: String, avatar: String?): AuthenticatedUser {
            updates++
            if (updateFails) error("Server failed")
            lastAvatar = avatar
            stored = stored.copy(displayName = name, avatarUrl = avatar?.takeIf { it.isNotBlank() } ?: if (avatar == null) stored.avatarUrl else null)
            return stored
        }
        override suspend fun deleteAvatar(path: String) {
            if (cleanupFails) error("Offline")
            deletions += path
        }
    }
    @Test fun `name-only edit preserves avatar and normalizes whitespace`() = runBlocking {
        val remote = Remote()
        val result = ProfileUpdater(remote).save(original, "  New   Name  ", AvatarChange.Keep)
        assertEquals("New Name", result.displayName)
        assertEquals(original.avatarUrl, result.avatarUrl)
        assertNull(remote.lastAvatar)
        assertTrue(remote.uploads.isEmpty())
        assertTrue(remote.deletions.isEmpty())
    }
    @Test fun `replacement uploads first and deletes only the previous owned photo after saving`() = runBlocking {
        val remote = Remote()
        val saved = ProfileUpdater(remote).save(original, "New Name", AvatarChange.Replace(byteArrayOf(1)))
        assertEquals(AVATAR_PREFIX + remote.uploads.single(), saved.avatarUrl)
        assertTrue(remote.uploads.single().startsWith("$id/"))
        assertEquals(listOf(oldPath), remote.deletions)
    }
    @Test fun `removal is explicit and stays removed when fetched from remote`() = runBlocking {
        val remote = Remote()
        ProfileUpdater(remote).save(original, "New Name", AvatarChange.Remove)
        assertEquals("", remote.lastAvatar)
        assertNull(remote.stored.avatarUrl)
        assertEquals(listOf(oldPath), remote.deletions)
    }
    @Test fun `upload failure cannot change name or erase previous avatar`() = runBlocking {
        val remote = Remote().apply { uploadFails = true }
        assertTrue(runCatching { ProfileUpdater(remote).save(original, "New Name", AvatarChange.Replace(byteArrayOf(1))) }.isFailure)
        assertEquals(0, remote.updates)
        assertEquals(original, remote.stored)
        assertTrue(remote.deletions.isEmpty())
    }
    @Test fun `ambiguous profile update failure does not delete a possibly committed photo`() = runBlocking {
        val remote = Remote().apply { updateFails = true }
        assertTrue(runCatching { ProfileUpdater(remote).save(original, "New Name", AvatarChange.Replace(byteArrayOf(1))) }.isFailure)
        assertEquals(1, remote.uploads.size)
        assertTrue(remote.deletions.isEmpty())
    }
    @Test fun `account change during upload cannot update the new account`() = runBlocking {
        val remote = Remote().apply { switchAfterUpload = true }
        val failure = runCatching { ProfileUpdater(remote).save(original, "New Name", AvatarChange.Replace(byteArrayOf(1))) }.exceptionOrNull()
        assertTrue(failure is DataError.Unauthenticated)
        assertEquals(0, remote.updates)
        assertTrue(remote.deletions.isEmpty())
    }
    @Test fun `cleanup failure does not hide successful save`() = runBlocking {
        val remote = Remote().apply { cleanupFails = true }
        assertEquals("New Name", ProfileUpdater(remote).save(original, "New Name", AvatarChange.Remove).displayName)
    }
    @Test fun `invalid names and photos make no remote requests`() = runBlocking {
        val remote = Remote()
        for (name in listOf(" ", "A", "A".repeat(81))) {
            assertTrue(runCatching { ProfileUpdater(remote).save(original, name, AvatarChange.Keep) }.exceptionOrNull() is DataError.Validation)
        }
        assertTrue(runCatching { ProfileUpdater(remote).save(original, "Name", AvatarChange.Replace(ByteArray(MAX_AVATAR_BYTES + 1))) }.exceptionOrNull() is DataError.Validation)
        assertEquals(0, remote.updates)
        assertTrue(remote.uploads.isEmpty())
    }
    @Test fun `external or other users avatars are never deleted`() = runBlocking {
        val remote = Remote()
        ProfileUpdater(remote).save(original.copy(avatarUrl = "https://example.com/avatar.jpg"), "New Name", AvatarChange.Remove)
        ProfileUpdater(remote).save(original.copy(avatarUrl = AVATAR_PREFIX + "33333333-3333-4333-8333-333333333333/22222222-2222-4222-8222-222222222222.jpg"), "New Name", AvatarChange.Remove)
        assertNull(avatarPath(AVATAR_PREFIX + "$id/../secret.jpg"))
        assertTrue(remote.deletions.isEmpty())
    }
}
