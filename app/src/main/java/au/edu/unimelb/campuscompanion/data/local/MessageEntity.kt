package au.edu.unimelb.campuscompanion.data.local

import androidx.room.Entity
import androidx.room.Index
import java.time.Instant

/**
 * A cached chat message. Messages written on this device are stored before the server confirms
 * them, so the key is the sender plus the client id rather than the server id.
 */
@Entity(
    tableName = "messages",
    primaryKeys = ["senderId", "clientId"],
    indices = [Index(value = ["groupId", "createdAtMicros"])]
)
data class MessageEntity(
    val senderId: String,
    val clientId: String,
    val groupId: String,
    /** Server id, or null until the server has stored the message. */
    val serverId: String?,
    val senderName: String?,
    val body: String,
    /** Microseconds since the epoch, the precision that Postgres stores. */
    val createdAtMicros: Long,
    /** Name of a [au.edu.unimelb.campuscompanion.data.model.MessageStatus]. */
    val status: String
)

/** Microseconds since the epoch. */
fun Instant.toEpochMicros(): Long =
    Math.addExact(Math.multiplyExact(epochSecond, 1_000_000L), (nano / 1_000).toLong())

fun instantOfEpochMicros(micros: Long): Instant =
    Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000L), Math.floorMod(micros, 1_000_000L) * 1_000L)
