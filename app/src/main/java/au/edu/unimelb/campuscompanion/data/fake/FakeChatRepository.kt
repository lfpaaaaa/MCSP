package au.edu.unimelb.campuscompanion.data.fake

import au.edu.unimelb.campuscompanion.data.DataError
import au.edu.unimelb.campuscompanion.data.model.ChatMessage
import au.edu.unimelb.campuscompanion.data.model.MessageStatus
import au.edu.unimelb.campuscompanion.data.repository.ChatRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.time.Instant
import java.util.UUID

/** In-memory [ChatRepository] that imitates optimistic sending and delivery failures. */
class FakeChatRepository(
    private val currentUserId: String = FakeData.CURRENT_USER_ID,
    private val currentUserName: String = FakeData.CURRENT_USER_NAME,
    private val latencyMillis: Long = FakeData.DEFAULT_LATENCY_MILLIS,
    private val clock: () -> Instant = Instant::now
) : ChatRepository {

    /** When true, sends fail as if the device were offline, so the retry path can be exercised. */
    @Volatile
    var failSends: Boolean = false

    private val messages = MutableStateFlow(FakeData.messages(clock()))

    override fun observeMessages(groupId: String): Flow<List<ChatMessage>> =
        messages.map { byGroup -> byGroup[groupId].orEmpty().sortedBy { it.createdAt } }

    override suspend fun loadOlderMessages(groupId: String, pageSize: Int): Result<Boolean> {
        delay(latencyMillis)
        return Result.success(false)
    }

    override suspend fun sendMessage(groupId: String, body: String): Result<ChatMessage> {
        val text = body.trim()
        if (text.isEmpty() || text.length > ChatRepository.MAX_MESSAGE_LENGTH) {
            return Result.failure(
                DataError.Validation("Messages need 1 to ${ChatRepository.MAX_MESSAGE_LENGTH} characters.")
            )
        }
        val clientId = UUID.randomUUID().toString()
        val pending = ChatMessage(
            id = clientId,
            clientId = clientId,
            groupId = groupId,
            senderId = currentUserId,
            senderName = currentUserName,
            body = text,
            createdAt = clock(),
            status = MessageStatus.Sending
        )
        upsert(pending)
        return deliver(pending)
    }

    override suspend fun retryMessage(groupId: String, clientId: String): Result<ChatMessage> {
        val failed = messages.value[groupId].orEmpty()
            .firstOrNull { it.clientId == clientId && it.status == MessageStatus.Failed }
            ?: return Result.failure(DataError.NotFound())
        val pending = failed.copy(status = MessageStatus.Sending)
        upsert(pending)
        return deliver(pending)
    }

    override suspend fun markAsRead(groupId: String): Result<Unit> = Result.success(Unit)

    private suspend fun deliver(pending: ChatMessage): Result<ChatMessage> {
        delay(latencyMillis)
        if (failSends) {
            upsert(pending.copy(status = MessageStatus.Failed))
            return Result.failure(DataError.Offline())
        }
        val sent = pending.copy(id = UUID.randomUUID().toString(), status = MessageStatus.Sent)
        upsert(sent)
        return Result.success(sent)
    }

    private fun upsert(message: ChatMessage) {
        messages.update { byGroup ->
            val others = byGroup[message.groupId].orEmpty().filterNot { it.clientId == message.clientId }
            byGroup + (message.groupId to others + message)
        }
    }
}
