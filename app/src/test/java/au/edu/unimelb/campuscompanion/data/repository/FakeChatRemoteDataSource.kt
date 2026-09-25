package au.edu.unimelb.campuscompanion.data.repository

import au.edu.unimelb.campuscompanion.data.DataError
import au.edu.unimelb.campuscompanion.data.local.MessageEntity
import au.edu.unimelb.campuscompanion.data.local.toEpochMicros
import au.edu.unimelb.campuscompanion.data.model.MessageStatus
import au.edu.unimelb.campuscompanion.data.remote.ChatEvent
import au.edu.unimelb.campuscompanion.data.remote.ChatRemoteDataSource
import au.edu.unimelb.campuscompanion.data.remote.MessageCursor
import au.edu.unimelb.campuscompanion.data.remote.MessageRow
import au.edu.unimelb.campuscompanion.data.remote.parseTimestamp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import java.time.Instant

const val CHAT_GROUP_ID = "group-1"

/** Keeps messages in memory the way the server does and records every call. */
class FakeChatRemoteDataSource(private val selfId: String) : ChatRemoteDataSource {
    /** Makes every fetch fail, as if the device were offline. */
    var fetchFailure: DataError? = null

    /** Makes inserts fail after [beforeInsert] has run. */
    var insertFailure: DataError? = null

    /** Runs at the start of every insert, for example to hold a message in the sending state. */
    var beforeInsert: suspend (clientId: String) -> Unit = {}

    /** Server clock for new messages; it advances one second per insert. */
    var serverTime: Instant = Instant.parse("2026-09-25T06:00:00Z")

    /** Messages stored on the server. */
    val stored = mutableListOf<MessageRow>()
    val events = MutableSharedFlow<ChatEvent>(extraBufferCapacity = 16)
    val insertedClientIds = mutableListOf<String>()
    val fetchBeforeCursors = mutableListOf<MessageCursor?>()
    val fetchAfterCursors = mutableListOf<MessageCursor>()
    val markedRead = mutableListOf<String>()

    override suspend fun fetchBefore(groupId: String, before: MessageCursor?, limit: Int): List<MessageRow> {
        fetchFailure?.let { throw it }
        fetchBeforeCursors += before
        return timeline(groupId)
            .filter { before == null || it.isBefore(before) }
            .reversed()
            .take(limit)
    }

    override suspend fun fetchAfter(groupId: String, after: MessageCursor, limit: Int): List<MessageRow> {
        fetchFailure?.let { throw it }
        fetchAfterCursors += after
        return timeline(groupId).filter { it.isAfter(after) }.take(limit)
    }

    override suspend fun insertMessage(groupId: String, clientId: String, body: String): MessageRow {
        insertedClientIds += clientId
        beforeInsert(clientId)
        insertFailure?.let { throw it }
        stored.firstOrNull { it.senderId == selfId && it.clientId == clientId }?.let { return it }
        serverTime = serverTime.plusSeconds(1)
        val row = MessageRow(
            id = "m" + (stored.size + 100).toString().padStart(4, '0'),
            groupId = groupId,
            senderId = selfId,
            clientId = clientId,
            body = body,
            createdAt = serverTime.toString()
        )
        stored += row
        return row
    }

    override suspend fun markRead(groupId: String) {
        markedRead += groupId
    }

    override fun messageEvents(groupId: String): Flow<ChatEvent> = events

    private fun timeline(groupId: String): List<MessageRow> =
        stored.filter { it.groupId == groupId }.sortedWith(compareBy({ parseTimestamp(it.createdAt) }, { it.id }))

    private fun MessageRow.isBefore(cursor: MessageCursor): Boolean {
        val time = parseTimestamp(createdAt)
        val cursorTime = parseTimestamp(cursor.createdAt)
        return time < cursorTime || (time == cursorTime && id < cursor.id)
    }

    private fun MessageRow.isAfter(cursor: MessageCursor): Boolean {
        val time = parseTimestamp(createdAt)
        val cursorTime = parseTimestamp(cursor.createdAt)
        return time > cursorTime || (time == cursorTime && id > cursor.id)
    }
}

/** Message number [n] from another member, stored at 05:nn. */
fun messageRow(n: Int, senderId: String = "user-2", senderName: String? = "Bob") = MessageRow(
    id = "m" + n.toString().padStart(4, '0'),
    groupId = CHAT_GROUP_ID,
    senderId = senderId,
    clientId = "c" + n.toString().padStart(4, '0'),
    body = "message $n",
    createdAt = "2026-09-25T05:" + n.toString().padStart(2, '0') + ":00Z",
    senderName = senderName
)

/** The cached copy of a message that the server has confirmed. */
fun sentEntity(row: MessageRow) = MessageEntity(
    senderId = row.senderId,
    clientId = row.clientId,
    groupId = row.groupId,
    serverId = row.id,
    senderName = row.senderName,
    body = row.body,
    createdAtMicros = parseTimestamp(row.createdAt).toEpochMicros(),
    status = MessageStatus.Sent.name
)
