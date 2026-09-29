package au.edu.unimelb.campuscompanion.push

/** A push notification about a new group message, as sent by the notify-message function. */
data class MessagePush(
    val messageId: String,
    val groupId: String,
    val groupName: String,
    val senderName: String,
    val preview: String
) {
    val title: String
        get() = groupName

    val text: String
        get() = if (preview.isBlank()) "$senderName sent a message" else "$senderName: $preview"

    /** One notification per group, so a new message replaces the previous one. */
    val notificationId: Int
        get() = groupId.hashCode()

    companion object {
        const val TYPE = "group_message"

        /** Reads the data of a push message; null when it is not about a group message. */
        fun from(data: Map<String, String>): MessagePush? {
            if (data["type"] != TYPE) return null
            val groupId = data["group_id"]?.takeIf { it.isNotBlank() } ?: return null
            return MessagePush(
                messageId = data["message_id"].orEmpty(),
                groupId = groupId,
                groupName = data["group_name"]?.takeIf { it.isNotBlank() } ?: "Group",
                senderName = data["sender_name"]?.takeIf { it.isNotBlank() } ?: "A member",
                preview = data["preview"].orEmpty().trim()
            )
        }
    }
}
