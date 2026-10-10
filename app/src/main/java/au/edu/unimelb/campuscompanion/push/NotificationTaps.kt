package au.edu.unimelb.campuscompanion.push

import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the notification the user tapped asked the app to show. The request waits here until the
 * signed-in interface can act on it, since a tap can also start the app from scratch.
 */
object NotificationTaps {
    const val EXTRA_GROUP_ID = "group_id"
    const val EXTRA_SCREEN = "screen"
    const val SCREEN_SCHEDULE = "schedule"

    sealed interface Target {
        data class GroupChat(val groupId: String) : Target

        data object Schedule : Target
    }

    private val _pending = MutableStateFlow<Target?>(null)
    val pending: StateFlow<Target?> = _pending.asStateFlow()

    /** Records the target carried by [intent]. Returns false when it did not come from a notification. */
    fun offer(intent: Intent?): Boolean {
        val target = intent?.let { targetOf(it.getStringExtra(EXTRA_GROUP_ID), it.getStringExtra(EXTRA_SCREEN)) }
            ?: return false
        _pending.value = target
        return true
    }

    fun clear() {
        _pending.value = null
    }

    internal fun targetOf(groupId: String?, screen: String?): Target? = when {
        !groupId.isNullOrBlank() -> Target.GroupChat(groupId)
        screen == SCREEN_SCHEDULE -> Target.Schedule
        else -> null
    }
}
