package au.edu.unimelb.campuscompanion.ui.chat

import au.edu.unimelb.campuscompanion.data.fake.FakeChatRepository
import au.edu.unimelb.campuscompanion.data.fake.FakeData
import au.edu.unimelb.campuscompanion.data.fake.FakeFileRepository
import au.edu.unimelb.campuscompanion.data.model.ChatConnection
import au.edu.unimelb.campuscompanion.data.model.ChatMessage
import au.edu.unimelb.campuscompanion.data.model.MessageStatus
import au.edu.unimelb.campuscompanion.data.repository.ChatRepository
import au.edu.unimelb.campuscompanion.data.repository.FileRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class GroupChatSessionTest {
    private var now: Instant = Instant.parse("2026-10-09T06:00:00Z")
    private val clock: () -> Instant = { now }
    private val latencies = mutableListOf<LatencySample>()

    @Test
    fun timelineMergesMessagesAndFilesInTimeOrder() = runBlocking<Unit> {
        withSession(fakeChat(), fakeFiles()) { session ->
            val state = session.await { it.items.size == 4 }

            assertEquals(
                listOf("message:m1", "message:m2", "file:f1", "message:m3"),
                state.items.map { it.id }
            )
            assertTrue(state.items.zipWithNext().all { (earlier, later) -> earlier.createdAt <= later.createdAt })
            assertTrue(state.items.none { it.isMine })
            assertEquals(ChatConnection.Live, state.connection)
        }
    }

    @Test
    fun sendingShowsTheMessageAsMineAndRecordsTheRoundTrip() = runBlocking<Unit> {
        withSession(fakeChat(), fakeFiles()) { session ->
            session.await { it.items.size == 4 }

            session.send("  On my way  ")

            val state = session.await { it.lastIsMine(MessageStatus.Sent) }
            val sent = state.items.last() as ChatTimelineItem.Message
            assertTrue(sent.isMine)
            assertEquals("On my way", sent.message.body)
            assertEquals(listOf(LatencySample.Sent(0)), latencies)
            assertNull(state.notice)
        }
    }

    @Test
    fun failedMessageStaysInTheTimelineUntilRetried() = runBlocking<Unit> {
        val chat = fakeChat().apply { failSends = true }
        withSession(chat, fakeFiles()) { session ->
            session.await { it.items.size == 4 }

            session.send("Running late")
            val failed = session.await { it.lastIsMine(MessageStatus.Failed) }
            // The bubble explains the failure, so no separate notice is shown for being offline.
            assertNull(failed.notice)

            chat.failSends = false
            session.retry((failed.items.last() as ChatTimelineItem.Message).message.clientId)

            val retried = session.await { it.lastIsMine(MessageStatus.Sent) }
            assertEquals(1, retried.items.count { it is ChatTimelineItem.Message && it.message.body == "Running late" })
        }
    }

    @Test
    fun blankMessagesAreNotSent() = runBlocking<Unit> {
        withSession(fakeChat(), fakeFiles()) { session ->
            session.await { it.items.size == 4 }

            session.send("   ")
            repeat(5) { yield() }

            assertEquals(4, session.state.value.items.size)
            assertTrue(latencies.isEmpty())
        }
    }

    @Test
    fun uploadShowsProgressAndThenTheStoredFile() = runBlocking<Unit> {
        withSession(fakeChat(), fakeFiles()) { session ->
            session.await { it.items.size == 4 }

            session.upload("notes.txt", "text/plain", "hello".toByteArray())

            val stored = session.await { state ->
                state.uploads.isEmpty() && state.items.any { it.id.startsWith("file:") && it.isMine }
            }
            val file = stored.items.last() as ChatTimelineItem.File
            assertEquals("notes.txt", file.file.fileName)
            assertEquals(5L, file.file.sizeBytes)
            assertTrue(stored.uploads.isEmpty())
        }
    }

    @Test
    fun rejectedUploadCanBeDismissed() = runBlocking<Unit> {
        withSession(fakeChat(), fakeFiles()) { session ->
            session.await { it.items.size == 4 }

            session.upload("empty.txt", "text/plain", ByteArray(0))

            val failed = session.await { state -> state.uploads.any { it.isFailed } }
            val upload = failed.uploads.single()
            assertEquals("empty.txt", upload.fileName)
            assertNotNull(upload.error)

            session.dismissUpload(upload.id)
            session.await { it.uploads.isEmpty() }
        }
    }

    @Test
    fun olderMessagesAreRequestedOnceUntilTheServerHasNoMore() = runBlocking<Unit> {
        val chat = ScriptedChatRepository(olderPages = ArrayDeque(listOf(true, false)))
        withSession(chat, fakeFiles()) { session ->
            session.await { it.connection == ChatConnection.Live }

            session.loadOlder()
            session.await { !it.isLoadingOlder }
            assertTrue(session.state.value.hasOlderMessages)

            session.loadOlder()
            session.await { !it.isLoadingOlder }
            assertFalse(session.state.value.hasOlderMessages)

            session.loadOlder()
            repeat(5) { yield() }
            assertEquals(2, chat.olderRequests)
        }
    }

    @Test
    fun olderMessagesWaitUntilTheLatestOnesHaveLoaded() = runBlocking<Unit> {
        val chat = ScriptedChatRepository(olderPages = ArrayDeque(listOf(true)))
        chat.connectionState.value = ChatConnection.Connecting
        withSession(chat, fakeFiles()) { session ->
            session.await { it.connection == ChatConnection.Connecting }

            session.loadOlder()
            repeat(5) { yield() }
            assertEquals(0, chat.olderRequests)

            chat.connectionState.value = ChatConnection.Live
            session.await { it.connection == ChatConnection.Live }
            session.loadOlder()
            session.await { !it.isLoadingOlder }
            assertEquals(1, chat.olderRequests)
        }
    }

    @Test
    fun readReceiptsAreSentOnOpeningAndForNewMessagesFromOthers() = runBlocking<Unit> {
        val chat = ScriptedChatRepository()
        withSession(chat, fakeFiles()) { session ->
            session.await { it.connection == ChatConnection.Live }
            repeat(5) { yield() }
            assertEquals(1, chat.markedRead)

            now = now.plusSeconds(2)
            chat.receive(message("m-alex", ALEX_ID, "Alex Chen", createdAt = now.minusMillis(250)))
            session.await { state -> state.items.any { it.id == "message:m-alex" } }
            repeat(5) { yield() }

            assertEquals(2, chat.markedRead)
            assertEquals(listOf(LatencySample.Received(250)), latencies)

            // The user's own message changes nothing about what has been read.
            session.send("Thanks")
            session.await { state -> state.items.any { it.isMine } }
            repeat(5) { yield() }
            assertEquals(2, chat.markedRead)
        }
    }

    @Test
    fun historyLoadedLaterIsNotCountedAsLiveLatency() = runBlocking<Unit> {
        val chat = ScriptedChatRepository()
        withSession(chat, fakeFiles()) { session ->
            session.await { it.connection == ChatConnection.Live }

            chat.receive(message("m-old", ALEX_ID, "Alex Chen", createdAt = now.minus(Duration.ofHours(3))))
            session.await { state -> state.items.any { it.id == "message:m-old" } }

            assertTrue(latencies.isEmpty())
        }
    }

    private suspend fun <T> withSession(
        chat: ChatRepository,
        files: FileRepository,
        block: suspend (GroupChatSession) -> T
    ): T = coroutineScope {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val session = GroupChatSession(
            groupId = FakeData.TEAM_GROUP_ID,
            currentUserId = FakeData.CURRENT_USER_ID,
            chat = chat,
            files = files,
            scope = scope,
            clock = clock,
            onLatency = latencies::add
        )
        session.start()
        try {
            block(session)
        } finally {
            scope.cancel()
        }
    }

    private suspend fun GroupChatSession.await(predicate: (GroupChatUiState) -> Boolean): GroupChatUiState =
        withTimeout(2_000) { state.first(predicate) }

    private fun fakeChat() = FakeChatRepository(latencyMillis = 0, clock = clock)

    private fun fakeFiles() = FakeFileRepository(latencyMillis = 0, clock = clock)

    /** True when the newest entry is the user's own message in [status]. */
    private fun GroupChatUiState.lastIsMine(status: MessageStatus): Boolean {
        val last = items.lastOrNull() as? ChatTimelineItem.Message ?: return false
        return last.isMine && last.message.status == status
    }

    private fun message(id: String, senderId: String, senderName: String, createdAt: Instant) = ChatMessage(
        id = id,
        clientId = id,
        groupId = FakeData.TEAM_GROUP_ID,
        senderId = senderId,
        senderName = senderName,
        body = "Message $id",
        createdAt = createdAt,
        status = MessageStatus.Sent
    )

    /** A chat whose incoming messages, history pages and connection are driven by the test. */
    private class ScriptedChatRepository(
        private val olderPages: ArrayDeque<Boolean> = ArrayDeque()
    ) : ChatRepository {
        val connectionState = MutableStateFlow(ChatConnection.Live)
        override val connection: StateFlow<ChatConnection> = connectionState
        private val messages = MutableStateFlow<List<ChatMessage>>(emptyList())
        var markedRead = 0
        var olderRequests = 0

        fun receive(message: ChatMessage) = messages.update { it + message }

        override fun observeMessages(groupId: String): Flow<List<ChatMessage>> =
            messages.map { list -> list.filter { it.groupId == groupId }.sortedBy { it.createdAt } }

        override suspend fun loadOlderMessages(groupId: String, pageSize: Int): Result<Boolean> {
            olderRequests++
            return Result.success(olderPages.removeFirstOrNull() ?: false)
        }

        override suspend fun sendMessage(groupId: String, body: String): Result<ChatMessage> {
            val sent = ChatMessage(
                id = "sent-${messages.value.size}",
                clientId = "sent-${messages.value.size}",
                groupId = groupId,
                senderId = FakeData.CURRENT_USER_ID,
                senderName = FakeData.CURRENT_USER_NAME,
                body = body,
                createdAt = Instant.parse("2026-10-09T06:00:05Z"),
                status = MessageStatus.Sent
            )
            messages.update { it + sent }
            return Result.success(sent)
        }

        override suspend fun retryMessage(groupId: String, clientId: String): Result<ChatMessage> =
            Result.failure(IllegalStateException("not used"))

        override suspend fun markAsRead(groupId: String): Result<Unit> {
            markedRead++
            return Result.success(Unit)
        }
    }

    private companion object {
        const val ALEX_ID = "00000000-0000-4000-8000-000000000002"
    }
}
