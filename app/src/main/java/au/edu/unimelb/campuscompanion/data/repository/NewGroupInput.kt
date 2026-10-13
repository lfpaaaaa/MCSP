package au.edu.unimelb.campuscompanion.data.repository

import au.edu.unimelb.campuscompanion.ui.text.UiText
import au.edu.unimelb.campuscompanion.R
import au.edu.unimelb.campuscompanion.data.DataError

/** Input for a new group after the same checks that the database enforces. */
data class NewGroupInput(val name: String, val courseCode: String?) {
    companion object {
        /** Trims [name], upper-cases [courseCode] and rejects values that the server would refuse. */
        fun parse(name: String, courseCode: String?): Result<NewGroupInput> {
            val trimmedName = name.trim()
            if (trimmedName.isEmpty() || trimmedName.length > GroupRepository.MAX_NAME_LENGTH) {
                return Result.failure(
                    DataError.Validation(UiText(R.string.validation_group_names_need_1_to_characters, GroupRepository.MAX_NAME_LENGTH))
                )
            }
            val code = courseCode?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
            if (code != null && !GroupRepository.COURSE_CODE_PATTERN.matches(code)) {
                return Result.failure(DataError.Validation(UiText(R.string.validation_use_a_subject_code_such_as_comp90018)))
            }
            return Result.success(NewGroupInput(trimmedName, code))
        }
    }
}
