package au.edu.unimelb.campuscompanion.data.repository

import au.edu.unimelb.campuscompanion.data.DataError
import au.edu.unimelb.campuscompanion.data.local.MessageDao
import au.edu.unimelb.campuscompanion.data.local.MessageEntity
import au.edu.unimelb.campuscompanion.data.local.instantOfEpochMicros
import au.edu.unimelb.campuscompanion.data.local.toEpochMicros
import au.edu.unimelb.campuscompanion.data.model.ChatConnection
import au.edu.unimelb.campuscompanion.data.model.ChatMessage
import au.edu.unimelb.campuscompanion.data.model.CurrentUser
import au.edu.unimelb.campuscompanion.data.model.MessageStatus
import au.edu.unimelb.campuscompanion.data.remote.ChatEvent
import au.edu.unimelb.campuscompanion.data.remote.ChatRemoteDataSource
import au.edu.unimelb.campuscompanion.data.remote.GroupRemoteDataSource
import au.edu.unimelb.campuscompanion.data.remote.MessageCursor
import au.edu.unimelb.campuscompanion.data.remote.MessageRow
import au.edu.unimelb.campuscompanion.data.remote.dataResult
import au.edu.unimelb.campuscompanion.data.remote.parseTimestamp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Offline-first [ChatRepository]. Screens read messages from the on-device cache, which is kept up
 * to date while a chat is observed:
 *
 * - opening a chat fetches the messages missed since the newest cached one, or the latest page;
 * - new messages arrive through the realtime feed, and every reconnection fetches missed ones;
 * - a sent message appears at once as [MessageStatus.Sending] and then becomes
 *   [MessageStatus.Sent] or [MessageStatus.Failed]. A retry keeps the client id, so the server
 *   stores the message only once.
 */
class DefaultChatRepository(
    private val remote: ChatRemoteDataSource,
    private val groups: GroupRemoteDataSource,
    private val messages: MessageDao,
    private val currentUser: () -> CurrentUser?,
    private val clock: () -> Instant = Instant::now,
    private val newClientId: () -> String = { UUID.randomUUID().toString() },
    private val firstRetryDelayMillis: Long = FIRST_RETRY_DELAY_MILLIS,
    private val maxRetryDelayMillis: Long = MAX_RETRY_DELAY_MILLIS
) : ChatRepository {

    private val connectionState = MutableStateFlow(ChatConnection.Connecting)
    override val connection: StateFlow<ChatConnection> = connectionState.asStateFlow()

    private val syncLock = Mutex()
    private val memberNames = ConcurrentHashMap<String, Map<String, String>>()

    override fun observeMessages(groupId: String): Flow<List<ChatMessage>> = channelFlow {
        launch { keepInSync(groupId) }
        messages.observeGroup(groupId).collect { cached ->
            send(cached.map { it.toModel() })
        }
    }

    override suspend fun loadOlderMessages(groupId: String, pageSize: Int): Result<Boolean> {
        val size = pageSize.coerceIn(1, MAX_PAGE_SIZE)
        return dataResult {
            val oldest = messages.oldest(groupId, MessageStatus.Sent.name)
            val rows = remote.fetchBefore(groupId, oldest?.cursor(), size)
            store(groupId, rows)
            rows.size == size
        }
    }

    override suspend fun sendMessage(groupId: String, body: String): Result<ChatMessage> {
        val text = body.trim()
        if (text.isEmpty() || text.length > ChatRepository.MAX_MESSAGE_LENGTH) {
            return Result.failure(
                DataError.Validation("Messages need 1 to ${ChatRepository.MAX_MESSAGE_LENGTH} characters.")
            )
        }
        val user = currentUser() ?: return Result.failure(DataError.Unauthenticated())
        val pending = MessageEntity(
            senderId = user.id,
            clientId = newClientId(),
            groupId = groupId,
            serverId = null,
            senderName = user.displayName,
            body = text,
            createdAtMicros = clock().toEpochMicros(),
            status = MessageStatus.Sending.name
        )
        messages.upsert(listOf(pending))
        return deliver(pending)
    }

    override suspend fun retryMessage(groupId: String, clientId: String): Result<ChatMessage> {
        val user = currentUser() ?: return Result.failure(DataError.Unauthenticated())
        val failed = messages.find(user.id, clientId)
            ?.takeIf { it.groupId == groupId && it.status == MessageStatus.Failed.name }
            ?: return Result.failure(DataError.NotFound())
        val pending = failed.copy(status = MessageStatus.Sending.name)
        messages.upsert(listOf(pending))
        return deliver(pending)
    }

    override suspend fun markAsRead(groupId: String): Result<Unit> =
        dataResult { remote.markRead(groupId) }

    private suspend fun deliver(pending: MessageEntity): Result<ChatMessage> =
        dataResult { remote.insertMessage(pending.groupId, pending.clientId, pending.body) }.fold(
            onSuccess = { row ->
                val sent = pending.copy(
                    serverId = row.id,
                    createdAtMicros = parseTimestamp(row.createdAt).toEpochMicros(),
                    status = MessageStatus.Sent.name
                )
                messages.upsert(listOf(sent))
                Result.success(sent.toModel())
            },
            onFailure = { error ->
                // The realtime feed may already have confirmed the message if only the response was lost.
                val current = messages.find(pending.senderId, pending.clientId)
                if (current == null || current.status == MessageStatus.Sending.name) {
                    messages.upsert(listOf(pending.copy(status = MessageStatus.Failed.name)))
                }
                Result.failure(error)
            }
        )

    /** Keeps the cache of [groupId] up to date for as long as the caller is active. */
    private suspend fun keepInSync(groupId: String) {
        connectionState.value = ChatConnection.Connecting
        coroutineScope {
            launch { catchUpUntilSuccessful(groupId) }
            var attempt = 0
            while (true) {
                try {
                    remote.messageEvents(groupId).collect { event ->
                        attempt = 0
                        when (event) {
                            ChatEvent.Subscribed -> launch { catchUpUntilSuccessful(groupId) }
                            is ChatEvent.Inserted -> dataResult { store(groupId, listOf(event.message)) }
                        }
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Throwable) {
                    connectionState.value = ChatConnection.Offline
                }
                // The live feed stopped; join it again after a pause.
                attempt++
                delay(retryDelay(attempt))
            }
        }
    }

    private suspend fun catchUpUntilSuccessful(groupId: String) {
        var attempt = 0
        while (true) {
            if (dataResult { catchUp(groupId) }.isSuccess) {
                connectionState.value = ChatConnection.Live
                return
            }
            connectionState.value = ChatConnection.Offline
            attempt++
            delay(retryDelay(attempt))
        }
    }

    /** Fetches the messages missed since the newest cached one, or the latest page when none are cached. */
    private suspend fun catchUp(groupId: String) {
        syncLock.withLock {
            var cursor = messages.newest(groupId, MessageStatus.Sent.name)?.cursor()
            var pages = 0
            while (cursor != null && pages < MAX_CATCH_UP_PAGES) {
                val rows = remote.fetchAfter(groupId, cursor, CATCH_UP_PAGE_SIZE)
                store(groupId, rows)
                if (rows.size < CATCH_UP_PAGE_SIZE) return
                cursor = rows.last().cursor()
                pages++
            }
            if (cursor != null) {
                // Too many messages were missed to fill the gap; start again from the latest page.
                messages.deleteGroupMessages(groupId, MessageStatus.Sent.name)
            }
            store(groupId, remote.fetchBefore(groupId, before = null, limit = ChatRepository.DEFAULT_PAGE_SIZE))
        }
    }

    /** Caches server rows as sent messages, replacing local copies with the same client id. */
    private suspend fun store(groupId: String, rows: List<MessageRow>) {
        if (rows.isEmpty()) return
        val entities = rows.map { row ->
            val cached = messages.find(row.senderId, row.clientId)
            MessageEntity(
                senderId = row.senderId,
                clientId = row.clientId,
                groupId = row.groupId,
                serverId = row.id,
                senderName = row.senderName ?: cached?.senderName ?: lookUpSenderName(groupId, row.senderId),
                body = row.body,
                createdAtMicros = parseTimestamp(row.createdAt).toEpochMicros(),
                status = MessageStatus.Sent.name
            )
        }
        messages.upsert(entities)
    }

    /** Display name of a sender whose name did not come with the message, as for realtime events. */
    private suspend fun lookUpSenderName(groupId: String, senderId: String): String? {
        val user = currentUser()
        if (user != null && user.id == senderId) return user.displayName
        memberNames[groupId]?.get(senderId)?.let { return it }
        // Unknown sender, for example someone who joined after the names were loaded.
        val names = dataResult { groups.fetchMembers(groupId) }.getOrNull()
            ?.associate { it.userId to it.displayName }
            ?: return null
        memberNames[groupId] = names
        return names[senderId]
    }

    private fun retryDelay(attempt: Int): Long {
        val factor = 1L shl (attempt - 1).coerceIn(0, 6)
        return (firstRetryDelayMillis * factor).coerceAtMost(maxRetryDelayMillis)
    }

    private fun MessageEntity.toModel(): ChatMessage = ChatMessage(
        id = serverId ?: clientId,
        clientId = clientId,
        groupId = groupId,
        senderId = senderId,
        senderName = senderName ?: UNKNOWN_SENDER_NAME,
        body = body,
        createdAt = instantOfEpochMicros(createdAtMicros),
        status = MessageStatus.entries.firstOrNull { it.name == status } ?: MessageStatus.Sent
    )

    private fun MessageEntity.cursor(): MessageCursor? =
        serverId?.let { MessageCursor(instantOfEpochMicros(createdAtMicros).toString(), it) }

    private fun MessageRow.cursor(): MessageCursor =
        MessageCursor(parseTimestamp(createdAt).toString(), id)

    companion object {
        /** The largest page the server returns. */
        const val MAX_PAGE_SIZE = 100

        private const val CATCH_UP_PAGE_SIZE = 100
        private const val MAX_CATCH_UP_PAGES = 5
        private const val FIRST_RETRY_DELAY_MILLIS = 2_000L
        private const val MAX_RETRY_DELAY_MILLIS = 30_000L
        private const val UNKNOWN_SENDER_NAME = "Group member"
    }
}
