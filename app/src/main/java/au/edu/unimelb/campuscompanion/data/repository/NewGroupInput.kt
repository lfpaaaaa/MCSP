package au.edu.unimelb.campuscompanion.data.repository

import au.edu.unimelb.campuscompanion.data.DataError

/** Input for a new group after the same checks that the database enforces. */
data class NewGroupInput(val name: String, val courseCode: String?) {
    companion object {
        /** Trims [name], upper-cases [courseCode] and rejects values that the server would refuse. */
        fun parse(name: String, courseCode: String?): Result<NewGroupInput> {
            val trimmedName = name.trim()
            if (trimmedName.isEmpty() || trimmedName.length > GroupRepository.MAX_NAME_LENGTH) {
                return Result.failure(
                    DataError.Validation("Group names need 1 to ${GroupRepository.MAX_NAME_LENGTH} characters.")
                )
            }
            val code = courseCode?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
            if (code != null && !GroupRepository.COURSE_CODE_PATTERN.matches(code)) {
                return Result.failure(DataError.Validation("Use a subject code such as COMP90018."))
            }
            return Result.success(NewGroupInput(trimmedName, code))
        }
    }
}
