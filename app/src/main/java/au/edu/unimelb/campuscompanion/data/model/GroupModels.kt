package au.edu.unimelb.campuscompanion.data.model

import java.time.Instant

enum class GroupRole {
    Owner,
    Member
}

/**
 * A course group. Members join with the group's six-character [joinCode] or by invitation (QR code
 * or NFC); [courseCode] is an optional tag. [createdBy] is null when the creator has deleted their
 * account.
 */
data class Group(
    val id: String,
    val name: String,
    val courseCode: String?,
    val privateContentEnabled: Boolean,
    val createdBy: String?,
    val createdAt: Instant,
    /** Shown to members so that they can pass it on; null only for data from older servers. */
    val joinCode: String? = null
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

        /**
         * Returns a typed six-character group code in the form the server expects (upper case, no
         * spaces or dashes), or null when the text is not a code.
         */
        fun joinCodeFromText(text: String): String? =
            text.trim().uppercase().replace(JOIN_CODE_SEPARATORS, "").takeIf { JOIN_CODE_PATTERN.matches(it) }

        private val JOIN_CODE_SEPARATORS = Regex("[\\s-]")
        private val JOIN_CODE_PATTERN = Regex("[A-Z0-9]{6}")
    }
}
