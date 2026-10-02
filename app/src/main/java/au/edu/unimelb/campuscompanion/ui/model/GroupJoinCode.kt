package au.edu.unimelb.campuscompanion.ui.model

import java.time.Instant

const val GROUP_JOIN_CODE_LENGTH = 6

data class StartedGroupAccess(
    val groupName: String,
    val joinCode: String?,
    val expiresAt: Instant?
)

fun normalizeGroupJoinCode(input: String): String =
    input.uppercase()
        .filter { character -> character in 'A'..'Z' || character in '0'..'9' }
        .take(GROUP_JOIN_CODE_LENGTH)

fun isValidGroupJoinCode(code: String): Boolean =
    code.length == GROUP_JOIN_CODE_LENGTH &&
        code.all { character -> character in 'A'..'Z' || character in '0'..'9' }
