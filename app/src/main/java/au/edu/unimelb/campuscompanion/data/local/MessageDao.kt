package au.edu.unimelb.campuscompanion.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Room access to the cached messages. */
@Dao
interface MessageDao {
    /** Messages of a group in chronological order. Emits again whenever they change. */
    @Query("SELECT * FROM messages WHERE groupId = :groupId ORDER BY createdAtMicros, serverId")
    fun observeGroup(groupId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE senderId = :senderId AND clientId = :clientId")
    suspend fun find(senderId: String, clientId: String): MessageEntity?

    @Query(
        "SELECT * FROM messages WHERE groupId = :groupId AND status = :status " +
            "ORDER BY createdAtMicros, serverId LIMIT 1"
    )
    suspend fun oldest(groupId: String, status: String): MessageEntity?

    @Query(
        "SELECT * FROM messages WHERE groupId = :groupId AND status = :status " +
            "ORDER BY createdAtMicros DESC, serverId DESC LIMIT 1"
    )
    suspend fun newest(groupId: String, status: String): MessageEntity?

    /** The messages of [senderId] in [groupId] that are in [status], oldest first. */
    @Query(
        "SELECT * FROM messages WHERE groupId = :groupId AND senderId = :senderId AND status = :status " +
            "ORDER BY createdAtMicros, serverId"
    )
    suspend fun withStatus(groupId: String, senderId: String, status: String): List<MessageEntity>

    @Query("UPDATE messages SET senderName = :name WHERE groupId = :groupId AND senderId = :senderId AND (senderName IS NULL OR senderName != :name)")
    suspend fun updateSenderName(groupId: String, senderId: String, name: String)

    @Upsert
    suspend fun upsert(messages: List<MessageEntity>)

    /** Moves every message in status [from] to status [to] and returns how many changed. */
    @Query("UPDATE messages SET status = :to WHERE status = :from")
    suspend fun replaceStatus(from: String, to: String): Int

    @Query("DELETE FROM messages WHERE groupId = :groupId AND status = :status")
    suspend fun deleteGroupMessages(groupId: String, status: String)

    @Query("DELETE FROM messages")
    suspend fun deleteAll()
}
