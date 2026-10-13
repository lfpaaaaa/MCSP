package au.edu.unimelb.campuscompanion.data

import au.edu.unimelb.campuscompanion.ui.text.UiText
import au.edu.unimelb.campuscompanion.R
import au.edu.unimelb.campuscompanion.data.remote.toDataError

/**
 * A failure explained for the user: what happened, in plain words, and what they can do next
 * (heuristic 9, "help users recognise, diagnose and recover from errors").
 */
data class UserMessage(val title: UiText, val body: UiText, val action: RecoveryAction?)

/** The action a screen offers next to a [UserMessage]. */
enum class RecoveryAction {
    Retry,
    SignIn,
    ScanNewInvite
}

/** The title, body and recovery action shown for this error: what happened and what to do next. */
fun DataError.toUserMessage(): UserMessage = when (this) {
    is DataError.Offline -> UserMessage(
        title = UiText(R.string.error_no_connection),
        body = UiText(R.string.error_check_your_wi_fi_or_mobile_data_then),
        action = RecoveryAction.Retry
    )
    is DataError.Unauthenticated -> UserMessage(
        title = UiText(R.string.error_signed_out),
        body = UiText(R.string.error_your_session_has_ended_sign_in_again_to),
        action = RecoveryAction.SignIn
    )
    is DataError.Forbidden -> UserMessage(
        title = UiText(R.string.permissions_section_not_allowed),
        body = UiText(R.string.error_you_may_have_left_this_group_or_only),
        action = null
    )
    is DataError.NotFound -> UserMessage(
        title = UiText(R.string.error_no_longer_available),
        body = UiText(R.string.error_it_may_have_been_deleted_refresh_to_see),
        action = RecoveryAction.Retry
    )
    is DataError.InvalidInvite -> when (reason) {
        DataError.InvalidInvite.Reason.Unknown -> UserMessage(
            title = UiText(R.string.error_invite_not_recognised),
            body = UiText(R.string.error_this_code_or_link_is_not_a_campus),
            action = RecoveryAction.ScanNewInvite
        )
        DataError.InvalidInvite.Reason.Expired -> UserMessage(
            title = UiText(R.string.error_invite_expired),
            body = UiText(R.string.error_invites_work_for_10_minutes_ask_a_group),
            action = RecoveryAction.ScanNewInvite
        )
        DataError.InvalidInvite.Reason.UsedUp -> UserMessage(
            title = UiText(R.string.error_invite_used_up),
            body = UiText(R.string.error_this_invite_has_been_used_by_as_many),
            action = RecoveryAction.ScanNewInvite
        )
    }
    is DataError.RateLimited -> UserMessage(
        title = UiText(R.string.error_too_many_requests),
        body = UiText(R.string.error_wait_a_minute_then_try_again),
        action = RecoveryAction.Retry
    )
    is DataError.Validation -> UserMessage(
        title = UiText(R.string.error_check_your_input),
        body = userMessage,
        action = null
    )
    is DataError.Unexpected -> UserMessage(
        title = UiText(R.string.error_something_went_wrong),
        body = UiText(R.string.error_try_again_if_it_keeps_happening_restart_the),
        action = RecoveryAction.Retry
    )
}

/** Explains any failure. Library and network exceptions are first mapped to a [DataError]. */
fun Throwable.toUserMessage(): UserMessage = toDataError().toUserMessage()
