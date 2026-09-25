package au.edu.unimelb.campuscompanion.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.jan.supabase.auth.OtpType
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.Apple
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.auth.providers.OAuthProvider
import io.github.jan.supabase.auth.providers.builtin.OTP
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.user.UserInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class AuthViewModel : ViewModel() {
    private val client = SupabaseProvider.client
    private val _uiState = MutableStateFlow(
        AuthUiState(isInitializing = client != null)
    )
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    init {
        if (client == null) {
            _uiState.value = AuthUiState(
                isConfigured = false,
                isInitializing = false,
                infoMessage = "Add the Supabase URL and publishable key to local.properties to enable sign-in."
            )
        } else {
            viewModelScope.launch {
                client.auth.sessionStatus.collectLatest(::handleSessionStatus)
            }
        }
    }

    fun signInWithGoogle() = startOAuth(Google)

    fun signInWithApple() = startOAuth(Apple)

    fun sendEmailCode(email: String) {
        val normalizedEmail = email.trim().lowercase()
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(normalizedEmail).matches()) {
            setError("Enter a valid email address.")
            return
        }

        runAuthRequest {
            clientOrThrow().auth.signInWith(OTP) {
                this.email = normalizedEmail
                createUser = true
            }
            _uiState.value = _uiState.value.copy(
                isSubmitting = false,
                pendingEmail = normalizedEmail,
                errorMessage = null,
                infoMessage = "A 6-digit code was sent to $normalizedEmail."
            )
        }
    }

    fun verifyEmailCode(code: String) {
        val email = _uiState.value.pendingEmail ?: return
        val normalizedCode = code.filter(Char::isDigit)
        if (normalizedCode.length != 6) {
            setError("Enter the 6-digit code from the email.")
            return
        }

        runAuthRequest {
            clientOrThrow().auth.verifyEmailOtp(
                type = OtpType.Email.EMAIL,
                email = email,
                token = normalizedCode
            )
        }
    }

    fun resendEmailCode() {
        val email = _uiState.value.pendingEmail ?: return
        sendEmailCode(email)
    }

    fun changeEmail() {
        _uiState.value = _uiState.value.copy(
            pendingEmail = null,
            errorMessage = null,
            infoMessage = null
        )
    }

    fun saveDisplayName(name: String) {
        val normalizedName = name.trim().replace(Regex("\\s+"), " ")
        if (normalizedName.length < 2) {
            setError("Enter the name you want shown in your profile.")
            return
        }

        runAuthRequest {
            val updatedUser = clientOrThrow().auth.updateUser {
                data {
                    put("display_name", normalizedName)
                }
            }
            _uiState.value = _uiState.value.copy(
                isSubmitting = false,
                user = updatedUser.toAuthenticatedUser(),
                errorMessage = null,
                infoMessage = null
            )
        }
    }

    fun signOut() {
        runAuthRequest {
            clientOrThrow().auth.signOut()
        }
    }

    fun clearMessage() {
        _uiState.value = _uiState.value.copy(errorMessage = null, infoMessage = null)
    }

    private fun startOAuth(provider: OAuthProvider) {
        runAuthRequest {
            clientOrThrow().auth.signInWith(provider)
            _uiState.value = _uiState.value.copy(isSubmitting = false)
        }
    }

    private fun runAuthRequest(block: suspend () -> Unit) {
        if (_uiState.value.isSubmitting) return
        _uiState.value = _uiState.value.copy(
            isSubmitting = true,
            errorMessage = null,
            infoMessage = null
        )
        viewModelScope.launch {
            try {
                block()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                _uiState.value = _uiState.value.copy(
                    isSubmitting = false,
                    errorMessage = authErrorMessage(error)
                )
            }
        }
    }

    private fun handleSessionStatus(status: SessionStatus) {
        _uiState.value = when (status) {
            SessionStatus.Initializing -> _uiState.value.copy(isInitializing = true)
            is SessionStatus.Authenticated -> {
                val user = status.session.user?.toAuthenticatedUser()
                _uiState.value.copy(
                    isInitializing = false,
                    isSubmitting = false,
                    user = user,
                    pendingEmail = null,
                    errorMessage = if (user == null) "The account profile could not be loaded." else null,
                    infoMessage = null
                )
            }
            is SessionStatus.NotAuthenticated -> AuthUiState(
                isConfigured = SupabaseProvider.isConfigured,
                isInitializing = false
            )
            is SessionStatus.RefreshFailure -> _uiState.value.copy(
                isInitializing = false,
                isSubmitting = false,
                errorMessage = "The saved session could not be refreshed. Check the connection and try again."
            )
        }
    }

    private fun clientOrThrow() = client ?: error("Authentication is not configured.")

    private fun setError(message: String) {
        _uiState.value = _uiState.value.copy(errorMessage = message, infoMessage = null)
    }
}

private fun UserInfo.toAuthenticatedUser(): AuthenticatedUser {
    val metadataName = userMetadata.stringValue("display_name")
        ?: userMetadata.stringValue("full_name")
        ?: userMetadata.stringValue("name")
    val providerName = appMetadata.stringValue("provider")
    val provider = when (providerName) {
        "google" -> SignInProvider.Google
        "apple" -> SignInProvider.Apple
        else -> SignInProvider.Email
    }

    return AuthenticatedUser(
        id = id,
        displayName = metadataName?.takeIf(String::isNotBlank),
        email = email,
        provider = provider
    )
}

private fun kotlinx.serialization.json.JsonObject?.stringValue(key: String): String? =
    this?.get(key)?.jsonPrimitive?.contentOrNull
