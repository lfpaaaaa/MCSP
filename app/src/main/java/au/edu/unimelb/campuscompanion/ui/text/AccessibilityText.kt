package au.edu.unimelb.campuscompanion.ui.text

import android.content.res.Resources
import au.edu.unimelb.campuscompanion.R

/** Spoken units for media controls; a bare 01:30 can be interpreted as a clock time. */
internal fun Resources.spokenDuration(totalSeconds: Int): String {
    val seconds = totalSeconds.coerceAtLeast(0)
    val minutes = seconds / 60
    val remainder = seconds % 60
    val secondsText = getQuantityString(R.plurals.a11y_seconds, remainder, remainder)
    return if (minutes == 0) secondsText else getString(R.string.a11y_duration,
        getQuantityString(R.plurals.a11y_minutes, minutes, minutes), secondsText)
}
