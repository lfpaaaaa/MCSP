package au.edu.unimelb.campuscompanion.auth

import au.edu.unimelb.campuscompanion.ui.text.UiText
import au.edu.unimelb.campuscompanion.R
import au.edu.unimelb.campuscompanion.data.toUserMessage
import io.github.jan.supabase.auth.exception.AuthErrorCode
import io.github.jan.supabase.auth.exception.AuthRestException

/**
 * A short explanation of a failed sign-in step that says what to do next. Raw server text is
 * never shown; errors the app does not know fall back to the general messages.
 */
internal fun authErrorMessage(error: Throwable): UiText =
    when ((error as? AuthRestException)?.errorCode) {
        AuthErrorCode.OtpExpired ->
            UiText(R.string.auth_that_code_is_wrong_or_has_expired_check)
        AuthErrorCode.OverEmailSendRateLimit ->
            UiText(R.string.auth_too_many_codes_were_requested_wait_a_minute)
        AuthErrorCode.OverRequestRateLimit ->
            UiText(R.string.auth_too_many_attempts_wait_a_minute_and_try)
        AuthErrorCode.ValidationFailed ->
            UiText(R.string.auth_check_the_email_address_and_try_again)
        AuthErrorCode.EmailProviderDisabled,
        AuthErrorCode.OtpDisabled,
        AuthErrorCode.ProviderDisabled,
        AuthErrorCode.SignupDisabled ->
            UiText(R.string.auth_this_sign_in_option_is_turned_off_try)
        AuthErrorCode.UserBanned ->
            UiText(R.string.auth_this_account_is_blocked_contact_the_campus_companion)
        AuthErrorCode.SessionNotFound,
        AuthErrorCode.BadJwt,
        AuthErrorCode.NoAuthorization,
        AuthErrorCode.ReauthenticationNeeded ->
            UiText(R.string.auth_your_session_has_ended_sign_in_again)
        AuthErrorCode.BadOauthState,
        AuthErrorCode.BadOauthCallback,
        AuthErrorCode.FlowStateExpired,
        AuthErrorCode.FlowStateNotFound,
        AuthErrorCode.BadCodeVerifier ->
            UiText(R.string.auth_the_sign_in_did_not_finish_try_again)
        else -> error.toUserMessage().body
    }
