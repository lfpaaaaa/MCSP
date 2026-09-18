package au.edu.unimelb.campuscompanion.auth

data class AuthenticatedUser(
    val id: String,
    val displayName: String?,
    val email: String?,
    val provider: SignInProvider
) {
    val profileName: String
        get() = displayName ?: email?.substringBefore('@') ?: "Campus Companion user"
}

enum class SignInProvider(val displayName: String) {
    Google("Google"),
    Apple("Apple"),
    Email("Email")
}

data class AuthUiState(
    val isConfigured: Boolean = SupabaseProvider.isConfigured,
    val isInitializing: Boolean = true,
    val isSubmitting: Boolean = false,
    val user: AuthenticatedUser? = null,
    val pendingEmail: String? = null,
    val errorMessage: String? = null,
    val infoMessage: String? = null
) {
    val requiresProfileName: Boolean
        get() = user != null && user.displayName.isNullOrBlank()

    val isSignedIn: Boolean
        get() = user != null && !requiresProfileName
}
