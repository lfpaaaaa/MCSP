package au.edu.unimelb.campuscompanion.data.model

import java.time.Instant

enum class GroupRole {
    Owner,
    Member
}

/**
 * A course group. Members join by invitation (QR code or NFC); [courseCode] is an optional tag.
 * [createdBy] is null when the creator has deleted their account.
 */
data class Group(
    val id: String,
    val name: String,
    val courseCode: String?,
    val privateContentEnabled: Boolean,
    val createdBy: String?,
    val createdAt: Instant
)

/** A group as shown in lists, with activity details for the signed-in user. */
data class GroupSummary(
    val group: Group,
    val myRole: GroupRole,
    val memberCount: Int,
    val unreadCount: Int,
    val latestMessagePreview: String?,
    val latestActivityAt: Instant?,
    val latestFileName: String?
)

data class GroupMember(
    val userId: String,
    val displayName: String,
    val avatarUrl: String?,
    val role: GroupRole,
    val joinedAt: Instant
)

/** A short-lived invitation. The same [joinUri] is encoded in QR codes and sent over NFC. */
data class GroupInvite(
    val groupId: String,
    val token: String,
    val expiresAt: Instant
) {
    val joinUri: String
        get() = "$JOIN_URI_PREFIX$token"

    fun isExpired(now: Instant = Instant.now()): Boolean = !now.isBefore(expiresAt)

    companion object {
        const val JOIN_URI_PREFIX = "campuscompanion://join?token="

        /** Returns the token of a scanned or received join URI, or null when it is not one. */
        fun tokenFromUri(uri: String): String? =
            uri.trim()
                .takeIf { it.startsWith(JOIN_URI_PREFIX) }
                ?.removePrefix(JOIN_URI_PREFIX)
                ?.takeIf { it.isNotBlank() && it.none(Char::isWhitespace) }
    }
}
