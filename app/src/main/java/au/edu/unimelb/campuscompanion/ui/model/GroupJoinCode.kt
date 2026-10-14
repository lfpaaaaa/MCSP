package au.edu.unimelb.campuscompanion.ui.model

import java.time.Instant

const val GROUP_JOIN_CODE_LENGTH = 6
const val GROUP_JOIN_CODE_LIFETIME_SECONDS = 300L

data class StartedGroupAccess(
    val groupName: String,
    val joinCode: String?,
    val expiresAt: Instant?
) {
    /** A missing/invalid expiry is never replaced with a new timer when reopening settings. */
    fun activeJoinCode(now: Instant): String? = joinCode?.takeIf {
        isValidGroupJoinCode(it) && expiresAt != null && now.isBefore(expiresAt)
    }
}

/** The original server creation time defines the code's five-minute lifetime. */
fun groupJoinCodeExpiresAt(createdAt: Instant): Instant =
    createdAt.plusSeconds(GROUP_JOIN_CODE_LIFETIME_SECONDS)

fun CourseGroup.joinCodeAccess(): StartedGroupAccess =
    StartedGroupAccess(name, joinCode, joinCodeExpiresAt)

fun normalizeGroupJoinCode(input: String): String =
    input.uppercase()
        .filter { character -> character in 'A'..'Z' || character in '0'..'9' }
        .take(GROUP_JOIN_CODE_LENGTH)

fun isValidGroupJoinCode(code: String): Boolean =
    code.length == GROUP_JOIN_CODE_LENGTH &&
        code.all { character -> character in 'A'..'Z' || character in '0'..'9' }
