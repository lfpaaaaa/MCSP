package au.edu.unimelb.campuscompanion.data.repository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async

/** Delivery belongs to the app, so navigating away does not cancel an accepted send. */
class ChatMessageSender(
    private val repository: ChatRepository,
    private val scope: CoroutineScope,
    private val onSent: suspend () -> Unit = {}
) {
    fun send(groupId: String, body: String) = scope.async {
        repository.sendMessage(groupId, body).onSuccess { onSent() }
    }

    fun retry(groupId: String, clientId: String) = scope.async {
        repository.retryMessage(groupId, clientId).onSuccess { onSent() }
    }
}
