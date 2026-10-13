package au.edu.unimelb.campuscompanion.context

import au.edu.unimelb.campuscompanion.english

/**
 * The notification shade as Android treats departure prompts: posting with sound adds or replaces a
 * prompt, a silent update only replaces one that is still shown, and the student can dismiss one.
 */
class FakeDepartureShade : DepartureShade {
    /** What each shown prompt says (title, text), by key. */
    val shown = linkedMapOf<String, Pair<String, String>>()
    val alerts = mutableListOf<DepartureReminder>()
    val updates = mutableListOf<DepartureReminder>()
    val withdrawn = mutableListOf<String>()
    var canNotify = true

    /** For the `notify` of [DepartureReminders]: posts with sound. */
    fun post(reminder: DepartureReminder): Boolean {
        if (!canNotify) return false
        alerts += reminder
        shown[reminder.key] = reminder.title.english() to reminder.text.english()
        return true
    }

    override fun shownKeys(): Set<String> = shown.keys.toSet()

    override fun update(reminder: DepartureReminder) {
        if (reminder.key !in shown) return
        updates += reminder
        shown[reminder.key] = reminder.title.english() to reminder.text.english()
    }

    override fun withdraw(key: String) {
        withdrawn += key
        shown.remove(key)
    }

    fun dismiss(key: String) {
        shown.remove(key)
    }

    /** A prompt from an earlier class or app version. */
    fun showOther(key: String) {
        shown[key] = "Time to leave" to "An earlier prompt"
    }

    fun textOf(key: String): String? = shown[key]?.second
}
