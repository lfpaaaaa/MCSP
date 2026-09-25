package au.edu.unimelb.campuscompanion.auth

import au.edu.unimelb.campuscompanion.data.toUserMessage
import io.github.jan.supabase.auth.exception.AuthErrorCode
import io.github.jan.supabase.auth.exception.AuthRestException

/**
 * A short explanation of a failed sign-in step that says what to do next. Raw server text is
 * never shown; errors the app does not know fall back to the general messages.
 */
internal fun authErrorMessage(error: Throwable): String =
    when ((error as? AuthRestException)?.errorCode) {
        AuthErrorCode.OtpExpired ->
            "That code is wrong or has expired. Check the newest email or send a new code."
        AuthErrorCode.OverEmailSendRateLimit ->
            "Too many codes were requested. Wait a minute before sending another."
        AuthErrorCode.OverRequestRateLimit ->
            "Too many attempts. Wait a minute and try again."
        AuthErrorCode.ValidationFailed ->
            "Check the email address and try again."
        AuthErrorCode.EmailProviderDisabled,
        AuthErrorCode.OtpDisabled,
        AuthErrorCode.ProviderDisabled,
        AuthErrorCode.SignupDisabled ->
            "This sign-in option is turned off. Try signing in another way."
        AuthErrorCode.UserBanned ->
            "This account is blocked. Contact the Campus Companion team for help."
        AuthErrorCode.SessionNotFound,
        AuthErrorCode.BadJwt,
        AuthErrorCode.NoAuthorization,
        AuthErrorCode.ReauthenticationNeeded ->
            "Your session has ended. Sign in again."
        AuthErrorCode.BadOauthState,
        AuthErrorCode.BadOauthCallback,
        AuthErrorCode.FlowStateExpired,
        AuthErrorCode.FlowStateNotFound,
        AuthErrorCode.BadCodeVerifier ->
            "The sign-in did not finish. Try again."
        else -> error.toUserMessage().body
    }
