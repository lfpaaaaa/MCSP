package au.edu.unimelb.campuscompanion.ui.chat

/**
 * The group whose chat is on screen right now, if any. Messages for that group are already
 * visible, so they are not also shown as notifications.
 */
object OpenChat {
    @Volatile
    var groupId: String? = null
        private set

    fun opened(groupId: String) {
        this.groupId = groupId
    }

    /** Clears [groupId] if it is still the open one; another chat may have taken over. */
    fun closed(groupId: String) {
        if (this.groupId == groupId) this.groupId = null
    }
}
