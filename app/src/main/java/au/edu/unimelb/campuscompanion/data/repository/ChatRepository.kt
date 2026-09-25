package au.edu.unimelb.campuscompanion.data.repository

import au.edu.unimelb.campuscompanion.data.model.ChatMessage
import kotlinx.coroutines.flow.Flow

/** Real-time group chat backed by an offline cache. */
interface ChatRepository {
    /**
     * Messages of [groupId] in chronological order, including local messages that are still
     * sending or have failed.
     */
    fun observeMessages(groupId: String): Flow<List<ChatMessage>>

    /** Loads one page of older messages. The result is true while more history is available. */
    suspend fun loadOlderMessages(groupId: String, pageSize: Int = DEFAULT_PAGE_SIZE): Result<Boolean>

    /** Adds the message locally as sending, then stores it on the server. */
    suspend fun sendMessage(groupId: String, body: String): Result<ChatMessage>

    /** Sends a failed message again, keeping its client id so it is stored only once. */
    suspend fun retryMessage(groupId: String, clientId: String): Result<ChatMessage>

    /** Records that the user has read the group up to now. */
    suspend fun markAsRead(groupId: String): Result<Unit>

    companion object {
        const val DEFAULT_PAGE_SIZE = 30
        const val MAX_MESSAGE_LENGTH = 2_000
    }
}
