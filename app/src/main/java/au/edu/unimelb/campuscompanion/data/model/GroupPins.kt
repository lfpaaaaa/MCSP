package au.edu.unimelb.campuscompanion.data.model

import au.edu.unimelb.campuscompanion.data.DataError
import java.time.Instant

enum class PinVisibility { Personal, Group }
enum class PinKind { Message, File }
data class PinTarget(val id: String, val kind: PinKind)

data class GroupPin(
    val id: String,
    val pinnedByName: String,
    val pinnedAt: Instant,
    val canUnpin: Boolean,
    val visibility: PinVisibility,
    val message: ChatMessage? = null,
    val file: SharedFile? = null
) {
    init { require((message == null) != (file == null)) }
    val target: PinTarget get() = message?.let { PinTarget(it.id, PinKind.Message) } ?: PinTarget(file!!.id, PinKind.File)
    val senderName: String get() = message?.senderName ?: file!!.uploaderName
    val preview: String get() = message?.body ?: file!!.fileName
}

data class GroupPinsSnapshot(val items: List<GroupPin>, val canPinForEveryone: Boolean)

data class GroupPinsState(
    val items: List<GroupPin> = emptyList(),
    val canPinForEveryone: Boolean = false,
    val loading: Boolean = true,
    val error: DataError? = null
)
