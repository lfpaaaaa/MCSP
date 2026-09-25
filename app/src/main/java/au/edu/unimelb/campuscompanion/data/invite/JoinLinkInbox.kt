package au.edu.unimelb.campuscompanion.data.invite

import au.edu.unimelb.campuscompanion.data.model.GroupInvite
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Keeps the invite token from the last join link that opened the app (a scanned QR code, an NFC
 * tap or a shared link) until the user joins or dismisses it. The token is kept while the user
 * signs in, because a join link can open the app before anyone is signed in.
 */
class JoinLinkInbox {
    private val _pendingToken = MutableStateFlow<String?>(null)

    val pendingToken: StateFlow<String?> = _pendingToken.asStateFlow()

    /** Stores the token when [link] is a join link. Returns false for any other link. */
    fun offer(link: String?): Boolean {
        val token = link?.let { GroupInvite.tokenFromUri(it) } ?: return false
        _pendingToken.value = token
        return true
    }

    /** Forgets the pending token after the user has joined or dismissed the invitation. */
    fun clear() {
        _pendingToken.value = null
    }
}
