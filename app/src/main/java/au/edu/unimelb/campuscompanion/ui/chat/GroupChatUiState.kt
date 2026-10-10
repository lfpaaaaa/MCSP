package au.edu.unimelb.campuscompanion.ui.chat

import au.edu.unimelb.campuscompanion.data.model.ChatConnection
import au.edu.unimelb.campuscompanion.data.model.ChatMessage
import au.edu.unimelb.campuscompanion.data.model.SharedFile
import java.time.Instant

/** One row of a group's timeline: a message or a shared file, in the order they were created. */
sealed interface ChatTimelineItem {
    /** Stable across status changes, so list animations and scroll positions are kept. */
    val id: String
    val createdAt: Instant
    val senderName: String
    val isMine: Boolean

    data class Message(
        val message: ChatMessage,
        override val isMine: Boolean
    ) : ChatTimelineItem {
        override val id: String get() = "message:${message.clientId}"
        override val createdAt: Instant get() = message.createdAt
        override val senderName: String get() = message.senderName
    }

    data class File(
        val file: SharedFile,
        override val isMine: Boolean
    ) : ChatTimelineItem {
        override val id: String get() = "file:${file.id}"
        override val createdAt: Instant get() = file.createdAt
        override val senderName: String get() = file.uploaderName
    }
}

/** A file that is being sent. It stays above the composer until it is stored or dismissed. */
data class PendingUpload(
    val id: String,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    /** Share of the bytes stored so far, 0 to 1. */
    val fraction: Float = 0f,
    /** Why the upload stopped; null while it is running. */
    val error: String? = null
) {
    val isFailed: Boolean get() = error != null
}

/** Everything the chat screen renders: the timeline, the connection state, paging and uploads. */
data class GroupChatUiState(
    val items: List<ChatTimelineItem> = emptyList(),
    val connection: ChatConnection = ChatConnection.Connecting,
    val isLoadingOlder: Boolean = false,
    /** False once the server has confirmed that the oldest cached message is the first one. */
    val hasOlderMessages: Boolean = true,
    val uploads: List<PendingUpload> = emptyList(),
    /** A short explanation of the latest failure, cleared by the screen once shown. */
    val notice: String? = null
)

/** How long chat operations took, for the responsiveness measurements. */
sealed interface LatencySample {
    val millis: Long

    /** From tapping send to the server confirming the message. */
    data class Sent(override val millis: Long) : LatencySample

    /** From the server storing someone else's message to it being shown on this device. */
    data class Received(override val millis: Long) : LatencySample
}
