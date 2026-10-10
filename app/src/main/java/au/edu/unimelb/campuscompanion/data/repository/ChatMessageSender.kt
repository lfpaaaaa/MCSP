package au.edu.unimelb.campuscompanion.data.repository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async

/**
 * Delivery belongs to the app, so navigating away does not cancel an accepted send.
 * [onConfirmed] receives the milliseconds from the call until the server accepted the message,
 * which the app logs as the "send_confirmed" latency sample.
 */
class ChatMessageSender(
    private val repository: ChatRepository,
    private val scope: CoroutineScope,
    private val onConfirmed: (millis: Long) -> Unit = {},
    private val onSent: suspend () -> Unit = {}
) {
    fun send(groupId: String, body: String) = scope.async {
        val startedAt = System.nanoTime()
        repository.sendMessage(groupId, body).onSuccess {
            onConfirmed((System.nanoTime() - startedAt) / NANOS_PER_MILLI)
            onSent()
        }
    }

    fun retry(groupId: String, clientId: String) = scope.async {
        repository.retryMessage(groupId, clientId).onSuccess { onSent() }
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
