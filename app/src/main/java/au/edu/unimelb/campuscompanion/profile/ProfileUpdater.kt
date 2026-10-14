package au.edu.unimelb.campuscompanion.profile

import au.edu.unimelb.campuscompanion.auth.AuthenticatedUser
import au.edu.unimelb.campuscompanion.data.DataError
import java.util.UUID
import kotlinx.coroutines.CancellationException

const val AVATAR_BUCKET = "profile-avatars"
const val AVATAR_PREFIX = "storage://profile-avatars/"
const val MAX_AVATAR_BYTES = 1_048_576

sealed interface AvatarChange {
    data object Keep : AvatarChange
    data object Remove : AvatarChange
    class Replace(val bytes: ByteArray) : AvatarChange
}

data class ProfileSaveState(val saving: Boolean = false, val saved: Boolean = false, val error: String? = null)

fun normalizedProfileName(name: String): String = name.trim().replace(Regex("\\s+"), " ")

fun profileNameError(name: String): String? = when (normalizedProfileName(name).length) {
    in 2..80 -> null
    else -> "Use 2 to 80 characters for your display name."
}

internal fun avatarPath(reference: String?): String? = reference?.takeIf { it.startsWith(AVATAR_PREFIX) }
    ?.removePrefix(AVATAR_PREFIX)?.takeIf {
        it.matches(Regex("[0-9a-fA-F-]{36}/[0-9a-fA-F-]{36}\\.jpg"))
    }

interface ProfileRemote {
    fun currentUserId(): String?
    suspend fun uploadAvatar(path: String, bytes: ByteArray)
    /** null leaves the avatar unchanged; the empty string explicitly removes it. */
    suspend fun update(userId: String, name: String, avatar: String?): AuthenticatedUser
    suspend fun deleteAvatar(path: String)
}

/** Commits the photo reference and name together only after its upload has completed. */
class ProfileUpdater(private val remote: ProfileRemote) {
    suspend fun save(user: AuthenticatedUser, name: String, avatar: AvatarChange): AuthenticatedUser {
        profileNameError(name)?.let { throw DataError.Validation(it) }
        checkSession(user.id)
        val reference = when (avatar) {
            AvatarChange.Keep -> null
            AvatarChange.Remove -> ""
            is AvatarChange.Replace -> {
                if (avatar.bytes.isEmpty() || avatar.bytes.size > MAX_AVATAR_BYTES) {
                    throw DataError.Validation("Choose a smaller photo and try again.")
                }
                val path = "${user.id}/${UUID.randomUUID()}.jpg"
                remote.uploadAvatar(path, avatar.bytes)
                AVATAR_PREFIX + path
            }
        }
        checkSession(user.id)
        // A timeout can occur after Auth commits: keep the newly uploaded object on failure so an
        // ambiguous response cannot leave a successfully saved profile pointing at a deleted photo.
        val saved = remote.update(user.id, normalizedProfileName(name), reference)
        checkSession(user.id)
        if (saved.id != user.id) throw DataError.Unauthenticated()
        if (avatar != AvatarChange.Keep) {
            avatarPath(user.avatarUrl)?.takeIf { it.startsWith("${user.id}/") && AVATAR_PREFIX + it != saved.avatarUrl }?.let { oldPath ->
                try { remote.deleteAvatar(oldPath) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* Cleanup failure must not turn a committed profile save into an error. */ }
            }
        }
        return saved
    }

    private fun checkSession(userId: String) {
        if (remote.currentUserId() != userId) throw DataError.Unauthenticated()
    }
}
