package au.edu.unimelb.campuscompanion.ui

import android.content.Context
import android.net.Uri
import au.edu.unimelb.campuscompanion.ui.model.GroupChatPreferences

class GroupChatPreferencesStore(
    context: Context,
    private val userId: String
) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    fun load(groupId: String): GroupChatPreferences {
        val foldedKey = key(groupId, "folded")
        return GroupChatPreferences(
            foldedOverride = if (preferences.contains(foldedKey)) {
                preferences.getBoolean(foldedKey, false)
            } else {
                null
            },
            muted = preferences.getBoolean(key(groupId, "muted"), false),
            displayName = preferences.getString(key(groupId, "display_name"), "").orEmpty()
        )
    }

    fun save(
        groupId: String,
        foldedOverride: Boolean?,
        muted: Boolean,
        displayName: String
    ) {
        val editor = preferences.edit()
        if (foldedOverride == null) {
            editor.remove(key(groupId, "folded"))
        } else {
            editor.putBoolean(key(groupId, "folded"), foldedOverride)
        }
        editor
            .putBoolean(key(groupId, "muted"), muted)
            .putString(key(groupId, "display_name"), displayName.trim())
            .apply()
    }

    private fun key(groupId: String, field: String): String =
        "${Uri.encode(userId)}:${Uri.encode(groupId)}:$field"

    private companion object {
        const val PREFERENCES_NAME = "group_chat_preferences"
    }
}
