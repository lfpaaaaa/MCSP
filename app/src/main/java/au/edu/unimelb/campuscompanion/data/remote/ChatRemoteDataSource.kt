package au.edu.unimelb.campuscompanion.data.remote

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A row of `public.messages`. [senderName] is only filled in by the history functions. */
@Serializable
data class MessageRow(
    val id: String,
    @SerialName("group_id") val groupId: String,
    @SerialName("sender_id") val senderId: String,
    @SerialName("client_id") val clientId: String,
    val body: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("sender_name") val senderName: String? = null
)

/** A position in a group's timeline. Messages are ordered by creation time, then by id. */
data class MessageCursor(val createdAt: String, val id: String)

/** Events from the live message feed of one group. */
sealed interface ChatEvent {
    /** The feed is connected, either for the first time or after a reconnection. */
    data object Subscribed : ChatEvent

    data class Inserted(val message: MessageRow) : ChatEvent
}

/**
 * Server calls behind the chat repository. Implementations throw
 * [au.edu.unimelb.campuscompanion.data.DataError] when a call fails.
 */
interface ChatRemoteDataSource {
    /** Newest first: the page older than [before], or the latest page when [before] is null. */
    suspend fun fetchBefore(groupId: String, before: MessageCursor?, limit: Int): List<MessageRow>

    /** Oldest first: messages stored after [after]. */
    suspend fun fetchAfter(groupId: String, after: MessageCursor, limit: Int): List<MessageRow>

    /**
     * Stores a message as the signed-in user. Sending the same [clientId] again returns the message
     * that is already stored instead of creating a second one.
     */
    suspend fun insertMessage(groupId: String, clientId: String, body: String): MessageRow

    /** Records that the signed-in user has read [groupId] up to now. */
    suspend fun markRead(groupId: String)

    /**
     * Live feed of new messages in [groupId] while the flow is collected. Emits
     * [ChatEvent.Subscribed] after every (re)connection so that missed messages can be fetched.
     */
    fun messageEvents(groupId: String): Flow<ChatEvent>
}
