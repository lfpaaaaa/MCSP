package au.edu.unimelb.campuscompanion.auth

import au.edu.unimelb.campuscompanion.ui.text.UiText
import au.edu.unimelb.campuscompanion.R
import android.content.Context
import android.net.Uri
import au.edu.unimelb.campuscompanion.profile.*
import kotlinx.coroutines.Job
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import au.edu.unimelb.campuscompanion.data.AppRepositories
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

    private val _profileState = MutableStateFlow(ProfileSaveState())
    val profileState = _profileState.asStateFlow()
    private var profileJob: Job? = null

    fun clearProfileMessage() {
        if (!_profileState.value.saving) _profileState.value = ProfileSaveState()
    }

    fun saveProfile(context: Context, name: String, photo: Uri?, removePhoto: Boolean) {
        if (_profileState.value.saving) return
        val user = _uiState.value.user ?: return
        profileNameError(name)?.let { _profileState.value = ProfileSaveState(error = it); return }
        val appContext = context.applicationContext
        _profileState.value = ProfileSaveState(saving = true)
        profileJob = viewModelScope.launch {
            try {
                val avatar = when {
                    photo != null -> AvatarChange.Replace(AvatarImages.readForUpload(appContext, photo))
                    removePhoto -> AvatarChange.Remove
                    else -> AvatarChange.Keep
                }
                val updated = ProfileUpdater(SupabaseProfileRemote(clientOrThrow())).save(user, name, avatar)
                if (_uiState.value.user?.id == user.id) {
                    _uiState.value = _uiState.value.copy(user = updated)
                    _profileState.value = ProfileSaveState(saved = true)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (_uiState.value.user?.id == user.id) _profileState.value = ProfileSaveState(error = authErrorMessage(error))
            }
        }
    }

    init {
        if (client == null) {
            _uiState.value = AuthUiState(
                isConfigured = false,
                isInitializing = false,
                infoMessage = UiText(R.string.auth_add_the_supabase_url_and_publishable_key_to)
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
            setError(UiText(R.string.auth_enter_a_valid_email_address))
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
                infoMessage = UiText(R.string.auth_a_6_digit_code_was_sent_to, normalizedEmail)
            )
        }
    }

    fun verifyEmailCode(code: String) {
        val email = _uiState.value.pendingEmail ?: return
        val normalizedCode = code.filter(Char::isDigit)
        if (normalizedCode.length != 6) {
            setError(UiText(R.string.auth_enter_the_6_digit_code_from_the_email))
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
        val normalizedName = normalizedProfileName(name)
        profileNameError(normalizedName)?.let { setError(it); return }

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
        profileJob?.cancel()
        _profileState.value = ProfileSaveState()
        runAuthRequest {
            // While the session is still valid: this device stops receiving this user's messages.
            AppRepositories.push.beforeSignOut()
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
        val nextUserId = (status as? SessionStatus.Authenticated)?.session?.user?.id
        if (status is SessionStatus.NotAuthenticated || (nextUserId != null && nextUserId != _uiState.value.user?.id)) {
            profileJob?.cancel()
            _profileState.value = ProfileSaveState()
        }
        _uiState.value = when (status) {
            // Supabase 3.0 resets to Initializing when the app goes into the background.
            // Keep the signed-in composition (including picker callbacks and drafts) until it
            // actually reports NotAuthenticated; only the first session load needs a blank screen.
            SessionStatus.Initializing -> _uiState.value.onSessionInitializing()
            is SessionStatus.Authenticated -> {
                val user = status.session.user?.toAuthenticatedUser()
                _uiState.value.copy(
                    isInitializing = false,
                    isSubmitting = false,
                    user = user,
                    pendingEmail = null,
                    errorMessage = if (user == null) UiText(R.string.auth_the_account_profile_could_not_be_loaded) else null,
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
                errorMessage = UiText(R.string.auth_the_saved_session_could_not_be_refreshed_check)
            )
        }
    }

    private fun clientOrThrow() = client ?: error("Authentication is not configured.")

    private fun setError(message: UiText) {
        _uiState.value = _uiState.value.copy(errorMessage = message, infoMessage = null)
    }
}

internal fun UserInfo.toAuthenticatedUser(): AuthenticatedUser {
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
        provider = provider,
        // An explicit empty avatar_url means the user removed even an OAuth-provided photo.
        avatarUrl = (if (userMetadata?.containsKey("avatar_url") == true) userMetadata.stringValue("avatar_url")
            else userMetadata.stringValue("picture"))?.takeIf(String::isNotBlank)
    )
}

private fun kotlinx.serialization.json.JsonObject?.stringValue(key: String): String? =
    this?.get(key)?.jsonPrimitive?.contentOrNull
