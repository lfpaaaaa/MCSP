package au.edu.unimelb.campuscompanion.auth

import org.junit.Assert.*
import org.junit.Test

class AuthSessionStateTest {
    @Test fun `background session restoration keeps the signed-in screen mounted`() {
        val user = AuthenticatedUser("user", "Student", "student@example.com", SignInProvider.Email)
        val state = AuthUiState(isConfigured = true, isInitializing = false, user = user)
        val restored = state.onSessionInitializing()
        assertFalse(restored.isInitializing)
        assertSame(user, restored.user)
        assertTrue(restored.isSignedIn)
    }

    @Test fun `initial session load still waits before showing sign in`() {
        val state = AuthUiState(isConfigured = true, isInitializing = false)
        assertTrue(state.onSessionInitializing().isInitializing)
        assertFalse(state.onSessionInitializing().isSignedIn)
    }
}
