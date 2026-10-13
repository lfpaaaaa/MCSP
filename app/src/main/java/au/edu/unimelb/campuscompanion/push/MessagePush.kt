package au.edu.unimelb.campuscompanion.push

import au.edu.unimelb.campuscompanion.ui.text.UiText
import au.edu.unimelb.campuscompanion.R

/** A push notification about a new group message, as sent by the notify-message function. */
data class MessagePush(
    val messageId: String,
    val groupId: String,
    val groupName: String,
    val senderName: String,
    val preview: String
) {
    val title: UiText
        get() = if (groupName.isBlank()) UiText(R.string.push_group) else UiText.Literal(groupName)

    val text: UiText
        get() {
            val sender = if (senderName.isBlank()) UiText(R.string.push_member) else UiText.Literal(senderName)
            return if (preview.isBlank()) UiText(R.string.push_sent_message, sender)
                else UiText(R.string.push_message_preview, sender, preview)
        }

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
                groupName = data["group_name"]?.takeIf { it.isNotBlank() } ?: "",
                senderName = data["sender_name"]?.takeIf { it.isNotBlank() } ?: "",
                preview = data["preview"].orEmpty().trim()
            )
        }
    }
}
