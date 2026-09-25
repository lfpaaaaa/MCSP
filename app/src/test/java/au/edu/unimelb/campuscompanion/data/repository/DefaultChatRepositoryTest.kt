package au.edu.unimelb.campuscompanion.data.repository

import au.edu.unimelb.campuscompanion.data.DataError
import au.edu.unimelb.campuscompanion.data.model.ChatConnection
import au.edu.unimelb.campuscompanion.data.model.ChatMessage
import au.edu.unimelb.campuscompanion.data.model.CurrentUser
import au.edu.unimelb.campuscompanion.data.model.MessageStatus
import au.edu.unimelb.campuscompanion.data.remote.ChatEvent
import au.edu.unimelb.campuscompanion.data.remote.GroupMemberRow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class DefaultChatRepositoryTest {
    private val remote = FakeChatRemoteDataSource(selfId = SELF_ID)
    private val groups = FakeGroupRemoteDataSource()
    private val dao = FakeMessageDao()
    private var clientIds = 0
    private val repository = DefaultChatRepository(
        remote = remote,
        groups = groups,
        messages = dao,
        currentUser = { CurrentUser(SELF_ID, "Cedric") },
        clock = { Instant.parse("2026-09-25T06:30:00Z") },
        newClientId = { "local-${++clientIds}" },
        firstRetryDelayMillis = 5,
        maxRetryDelayMillis = 20
    )
    private val shown = MutableStateFlow<List<ChatMessage>>(emptyList())

    @Test
    fun openingAnEmptyChatLoadsTheLatestPage() = runBlocking<Unit> {
        remote.stored += (1..3).map { messageRow(it) }

        observing {
            val messages = awaitShown { it.size == 3 }
            assertEquals(listOf("message 1", "message 2", "message 3"), messages.map { it.body })
            assertEquals("Bob", messages.first().senderName)
            awaitConnection(ChatConnection.Live)
        }

        assertEquals(listOf(null), remote.fetchBeforeCursors)
    }

    @Test
    fun reopeningAChatFetchesOnlyTheMissedMessages() = runBlocking<Unit> {
        dao.upsert(listOf(sentEntity(messageRow(1)), sentEntity(messageRow(2))))
        remote.stored += (1..4).map { messageRow(it) }

        observing {
            awaitShown { it.size == 4 }
            awaitConnection(ChatConnection.Live)
        }

        assertTrue(remote.fetchBeforeCursors.isEmpty())
        assertEquals(listOf("m0002"), remote.fetchAfterCursors.map { it.id })
    }

    @Test
    fun savedMessagesStayVisibleWhileOffline() = runBlocking<Unit> {
        dao.upsert(listOf(sentEntity(messageRow(1))))
        remote.fetchFailure = DataError.Offline()

        observing {
            awaitConnection(ChatConnection.Offline)
            assertEquals(listOf("message 1"), awaitShown { it.isNotEmpty() }.map { it.body })
        }
    }

    @Test
    fun theChatGoesLiveWhenTheServerIsReachableAgain() = runBlocking<Unit> {
        remote.fetchFailure = DataError.Offline()

        observing {
            awaitConnection(ChatConnection.Offline)
            remote.stored += messageRow(1)
            remote.fetchFailure = null

            awaitConnection(ChatConnection.Live)
            awaitShown { it.size == 1 }
        }
    }

    @Test
    fun realtimeMessagesShowTheSendersName() = runBlocking<Unit> {
        groups.members = listOf(
            GroupMemberRow(userId = "user-3", displayName = "Priya", role = "member", joinedAt = "2026-09-20T00:00:00+00:00")
        )

        observing {
            awaitConnection(ChatConnection.Live)
            remote.events.subscriptionCount.first { it > 0 }
            remote.events.emit(ChatEvent.Inserted(messageRow(7, senderId = "user-3", senderName = null)))

            assertEquals("Priya", awaitShown { it.size == 1 }.single().senderName)
        }
    }

    @Test
    fun reconnectingFetchesMessagesSentInTheMeantime() = runBlocking<Unit> {
        remote.stored += messageRow(1)

        observing {
            awaitShown { it.size == 1 }
            awaitConnection(ChatConnection.Live)
            remote.stored += messageRow(2)
            remote.events.subscriptionCount.first { it > 0 }
            remote.events.emit(ChatEvent.Subscribed)

            assertEquals("message 2", awaitShown { it.size == 2 }.last().body)
        }
    }

    @Test
    fun sentMessagesAppearAtOnceAndAreThenConfirmed() = runBlocking<Unit> {
        val release = CompletableDeferred<Unit>()
        remote.beforeInsert = { release.await() }

        observing {
            awaitConnection(ChatConnection.Live)
            val sending = async { repository.sendMessage(CHAT_GROUP_ID, "  Hello team  ") }

            val pending = awaitShown { it.isNotEmpty() }.single()
            assertEquals(MessageStatus.Sending, pending.status)
            assertEquals("Hello team", pending.body)
            assertEquals("Cedric", pending.senderName)

            release.complete(Unit)
            val sent = sending.await().getOrThrow()
            assertEquals(MessageStatus.Sent, sent.status)
            assertEquals(sent, awaitShown { it.singleOrNull()?.status == MessageStatus.Sent }.single())
        }

        assertEquals(listOf("local-1"), remote.insertedClientIds)
    }

    @Test
    fun failedMessagesAreRetriedWithTheSameClientId() = runBlocking<Unit> {
        remote.insertFailure = DataError.Offline()

        assertTrue(repository.sendMessage(CHAT_GROUP_ID, "Are we meeting today?").exceptionOrNull() is DataError.Offline)
        assertEquals(MessageStatus.Failed.name, dao.all.single().status)

        remote.insertFailure = null
        val sent = repository.retryMessage(CHAT_GROUP_ID, "local-1").getOrThrow()

        assertEquals(MessageStatus.Sent, sent.status)
        assertEquals(listOf("local-1", "local-1"), remote.insertedClientIds)
        assertEquals(1, remote.stored.size)
    }

    @Test
    fun aLostResponseDoesNotMarkADeliveredMessageAsFailed() = runBlocking<Unit> {
        remote.beforeInsert = { clientId ->
            // The realtime feed confirms the message, then the response to the insert is lost.
            val pending = dao.find(SELF_ID, clientId)!!
            dao.upsert(listOf(pending.copy(serverId = "m0100", status = MessageStatus.Sent.name)))
        }
        remote.insertFailure = DataError.Offline()

        assertTrue(repository.sendMessage(CHAT_GROUP_ID, "Hello").isFailure)

        assertEquals(MessageStatus.Sent.name, dao.all.single().status)
    }

    @Test
    fun invalidMessagesAreRejectedBeforeSending() = runBlocking<Unit> {
        assertTrue(repository.sendMessage(CHAT_GROUP_ID, "   ").exceptionOrNull() is DataError.Validation)
        assertTrue(repository.sendMessage(CHAT_GROUP_ID, "x".repeat(2_001)).exceptionOrNull() is DataError.Validation)

        assertTrue(remote.insertedClientIds.isEmpty())
        assertTrue(dao.all.isEmpty())
    }

    @Test
    fun olderMessagesArePagedFromTheOldestCachedMessage() = runBlocking<Unit> {
        remote.stored += (1..5).map { messageRow(it) }
        dao.upsert(listOf(sentEntity(messageRow(4)), sentEntity(messageRow(5))))

        assertTrue(repository.loadOlderMessages(CHAT_GROUP_ID, pageSize = 2).getOrThrow())
        assertFalse(repository.loadOlderMessages(CHAT_GROUP_ID, pageSize = 2).getOrThrow())

        assertEquals(listOf("m0004", "m0002"), remote.fetchBeforeCursors.map { it?.id })
        assertEquals(5, dao.all.size)
    }

    @Test
    fun retryingAMessageThatDidNotFailReportsNotFound() = runBlocking<Unit> {
        assertTrue(repository.retryMessage(CHAT_GROUP_ID, "local-9").exceptionOrNull() is DataError.NotFound)
    }

    @Test
    fun markingAChatAsReadReachesTheServer() = runBlocking<Unit> {
        assertTrue(repository.markAsRead(CHAT_GROUP_ID).isSuccess)

        assertEquals(listOf(CHAT_GROUP_ID), remote.markedRead)
    }

    /** Runs [block] while the chat is open on screen, and fails if it takes more than five seconds. */
    private suspend fun observing(block: suspend CoroutineScope.() -> Unit) = coroutineScope {
        val screen = launch { repository.observeMessages(CHAT_GROUP_ID).collect { shown.value = it } }
        try {
            withTimeout(5_000) { block() }
        } finally {
            screen.cancel()
        }
    }

    private suspend fun awaitShown(predicate: (List<ChatMessage>) -> Boolean): List<ChatMessage> =
        shown.first { predicate(it) }

    private suspend fun awaitConnection(state: ChatConnection) {
        repository.connection.first { it == state }
    }

    private companion object {
        const val SELF_ID = "user-1"
    }
}
