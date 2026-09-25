package au.edu.unimelb.campuscompanion.data.model

import java.time.Instant

enum class MessageStatus {
    /** Shown immediately after sending, before the server has confirmed it. */
    Sending,

    Sent,

    /** Could not be delivered; the user can retry. */
    Failed
}

data class ChatMessage(
    /** Server id once stored; equal to [clientId] while the message only exists on the device. */
    val id: String,
    /** Id generated on the device so that a retried send is stored only once. */
    val clientId: String,
    val groupId: String,
    val senderId: String,
    val senderName: String,
    val body: String,
    val createdAt: Instant,
    val status: MessageStatus
)
