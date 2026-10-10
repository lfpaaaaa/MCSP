package au.edu.unimelb.campuscompanion.ui.chat

import au.edu.unimelb.campuscompanion.data.DataError
import au.edu.unimelb.campuscompanion.data.model.ChatConnection
import au.edu.unimelb.campuscompanion.data.model.ChatMessage
import au.edu.unimelb.campuscompanion.data.model.MessageStatus
import au.edu.unimelb.campuscompanion.data.model.SharedFile
import au.edu.unimelb.campuscompanion.data.model.UploadState
import au.edu.unimelb.campuscompanion.data.repository.ChatRepository
import au.edu.unimelb.campuscompanion.data.repository.FileRepository
import au.edu.unimelb.campuscompanion.data.toUserMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * State of one open group chat: the timeline of messages and shared files, the connection, and
 * the files that are being sent. The screen renders [state] and calls the actions; the
 * repositories do the networking and caching.
 *
 * Call [start] once the screen is shown and cancel [scope] when it goes away. Uploads run in
 * [uploadScope], which may outlive the screen so that a file is still stored when the user
 * leaves the chat while it is sending.
 */
class GroupChatSession(
    private val groupId: String,
    private val currentUserId: String,
    private val chat: ChatRepository,
    private val files: FileRepository,
    private val scope: CoroutineScope,
    private val uploadScope: CoroutineScope = scope,
    private val clock: () -> Instant = Instant::now,
    private val onLatency: (LatencySample) -> Unit = {}
) {
    private val _state = MutableStateFlow(GroupChatUiState())
    val state: StateFlow<GroupChatUiState> = _state.asStateFlow()

    private val payloads = ConcurrentHashMap<String, ByteArray>()
    private val nextUploadId = AtomicInteger()
    private var started = false

    /** True once the latest messages have been fetched, so older pages can be requested in order. */
    @Volatile
    private var historyReady = false

    /** Client ids shown in the previous timeline, or null before the first one. */
    private var shownMessageIds: Set<String>? = null
    private var lastReadMessageId: String? = null

    fun start() {
        if (started) return
        started = true
        scope.launch {
            combine(
                chat.observeMessages(groupId).onStart { emit(emptyList()) },
                files.observeFiles(groupId).onStart { emit(emptyList()) }
            ) { messages, shared -> timeline(messages, shared) }
                .collect { items -> show(items) }
        }
        scope.launch {
            chat.connection.collect { connection ->
                if (connection == ChatConnection.Live) historyReady = true
                _state.update { it.copy(connection = connection) }
            }
        }
        scope.launch { chat.markAsRead(groupId) }
    }

    /** Sends [text] as a message. Blank text is ignored. */
    fun send(text: String) {
        val body = text.trim()
        if (body.isEmpty()) return
        scope.launch {
            val sentAt = clock()
            chat.sendMessage(groupId, body)
                .onSuccess { onLatency(LatencySample.Sent(Duration.between(sentAt, clock()).toMillis())) }
                .onFailure(::reportSendFailure)
        }
    }

    /** Sends a failed message again. */
    fun retry(clientId: String) {
        scope.launch {
            chat.retryMessage(groupId, clientId).onFailure(::reportSendFailure)
        }
    }

    /** Fetches the page before the oldest message shown. Does nothing while a page is loading. */
    fun loadOlder() {
        if (!historyReady) return
        val current = _state.value
        if (current.isLoadingOlder || !current.hasOlderMessages) return
        _state.update { it.copy(isLoadingOlder = true) }
        scope.launch {
            chat.loadOlderMessages(groupId).fold(
                onSuccess = { more ->
                    _state.update { it.copy(isLoadingOlder = false, hasOlderMessages = more) }
                },
                onFailure = { error ->
                    _state.update { it.copy(isLoadingOlder = false) }
                    report(error)
                }
            )
        }
    }

    /** Stores a file in the group and shows its progress above the composer. */
    fun upload(fileName: String, mimeType: String, bytes: ByteArray) {
        val id = "upload-${nextUploadId.incrementAndGet()}"
        payloads[id] = bytes
        _state.update { current ->
            current.copy(uploads = current.uploads + PendingUpload(id, fileName, mimeType, bytes.size.toLong()))
        }
        runUpload(id)
    }

    fun retryUpload(id: String) {
        updateUpload(id) { it.copy(fraction = 0f, error = null) }
        runUpload(id)
    }

    fun dismissUpload(id: String) {
        payloads.remove(id)
        _state.update { current -> current.copy(uploads = current.uploads.filterNot { it.id == id }) }
    }

    /** A short-lived link for opening [fileId] in another app. */
    suspend fun downloadUrl(fileId: String): Result<String> = files.createDownloadUrl(fileId)

    /** Shows [error] to the user, for failures that happen outside this session. */
    fun report(error: Throwable) {
        _state.update { it.copy(notice = error.toUserMessage().body) }
    }

    fun clearNotice() {
        _state.update { it.copy(notice = null) }
    }

    private fun timeline(messages: List<ChatMessage>, shared: List<SharedFile>): List<ChatTimelineItem> =
        (
            messages.map { ChatTimelineItem.Message(it, isMine = it.senderId == currentUserId) } +
                shared.map { ChatTimelineItem.File(it, isMine = it.uploaderId == currentUserId) }
            ).sortedWith(compareBy<ChatTimelineItem> { it.createdAt }.thenBy { it.id })

    private fun show(items: List<ChatTimelineItem>) {
        val previous = shownMessageIds
        val messages = items.filterIsInstance<ChatTimelineItem.Message>()
        if (previous != null) {
            val now = clock()
            messages
                .filter { !it.isMine && it.message.status == MessageStatus.Sent && it.message.clientId !in previous }
                .forEach { item ->
                    val age = Duration.between(item.createdAt, now).toMillis()
                    if (age in 0..RECENT_MILLIS) onLatency(LatencySample.Received(age))
                }
        }
        shownMessageIds = messages.mapTo(HashSet()) { it.message.clientId }
        _state.update { it.copy(items = items) }

        val latestFromOthers = messages.lastOrNull { !it.isMine }?.message?.id
        if (latestFromOthers != null && latestFromOthers != lastReadMessageId) {
            lastReadMessageId = latestFromOthers
            // The first timeline was marked as read when the chat was opened.
            if (previous != null) scope.launch { chat.markAsRead(groupId) }
        }
    }

    private fun reportSendFailure(error: Throwable) {
        // A message that could not be delivered stays in the list as failed, with a retry control.
        if (error !is DataError.Offline) report(error)
    }

    private fun runUpload(id: String) {
        val upload = _state.value.uploads.firstOrNull { it.id == id } ?: return
        val bytes = payloads[id] ?: return
        uploadScope.launch {
            try {
                files.uploadFile(groupId, upload.fileName, upload.mimeType, bytes).collect { progress ->
                    when (progress) {
                        is UploadState.InProgress -> updateUpload(id) { it.copy(fraction = progress.fraction) }
                        is UploadState.Completed -> dismissUpload(id)
                        is UploadState.Failed -> updateUpload(id) {
                            it.copy(error = progress.error.toUserMessage().body)
                        }
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                updateUpload(id) { it.copy(error = error.toUserMessage().body) }
            }
        }
    }

    private fun updateUpload(id: String, transform: (PendingUpload) -> PendingUpload) {
        _state.update { current ->
            current.copy(uploads = current.uploads.map { if (it.id == id) transform(it) else it })
        }
    }

    private companion object {
        /** Messages older than this when they appear were loaded as history, not received live. */
        const val RECENT_MILLIS = 60_000L
    }
}
