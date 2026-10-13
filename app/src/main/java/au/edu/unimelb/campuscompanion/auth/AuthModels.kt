package au.edu.unimelb.campuscompanion.auth

import au.edu.unimelb.campuscompanion.ui.text.UiText
import au.edu.unimelb.campuscompanion.R

data class AuthenticatedUser(
    val id: String,
    val displayName: String?,
    val email: String?,
    val provider: SignInProvider,
    val avatarUrl: String? = null
) {
    val profileName: String
        get() = displayName ?: email?.substringBefore('@') ?: "Campus Companion user"
}

enum class SignInProvider(val displayName: UiText) {
    Google(UiText(R.string.auth_google)),
    Apple(UiText(R.string.auth_apple)),
    Email(UiText(R.string.auth_email))
}

data class AuthUiState(
    val isConfigured: Boolean = SupabaseProvider.isConfigured,
    val isInitializing: Boolean = true,
    val isSubmitting: Boolean = false,
    val user: AuthenticatedUser? = null,
    val pendingEmail: String? = null,
    val errorMessage: UiText? = null,
    val infoMessage: UiText? = null
) {
    val requiresProfileName: Boolean
        get() = user != null && user.displayName.isNullOrBlank()

    val isSignedIn: Boolean
        get() = user != null && !requiresProfileName
}

/** Background session restoration must not dispose the current signed-in screen. */
internal fun AuthUiState.onSessionInitializing(): AuthUiState = copy(isInitializing = user == null)
