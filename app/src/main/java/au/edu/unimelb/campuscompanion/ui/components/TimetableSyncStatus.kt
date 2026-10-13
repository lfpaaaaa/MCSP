package au.edu.unimelb.campuscompanion.ui.components

import au.edu.unimelb.campuscompanion.ui.text.textResources
import au.edu.unimelb.campuscompanion.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import au.edu.unimelb.campuscompanion.ui.model.TimetableState
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Refreshing never hides the last successfully imported classes. */
@Composable
fun TimetableSyncStatus(state: TimetableState, onRefresh: () -> Unit) {
    val textResources = textResources()
    if (!state.isConnected) return
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(when {
                    state.isLoading -> textResources.getString(R.string.timetable_sync_status_refreshing_timetable)
                    state.isCached -> textResources.getString(R.string.timetable_sync_status_showing_saved_timetable)
                    else -> textResources.getString(R.string.timetable_sync_status_timetable_saved_for_offline_use)
                }, style = MaterialTheme.typography.labelLarge)
                state.lastSyncedAt?.let { savedAt ->
                    val date = savedAt.atZone(ZoneId.systemDefault())
                        .format(DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a"))
                    Text(textResources.getString(R.string.timetable_sync_status_last_updated, date), style = MaterialTheme.typography.bodySmall)
                }
                if (state.errorMessage != null) {
                    Text(textResources.getString(R.string.timetable_sync_status_could_not_update_the_timetable_your_saved_classes),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (state.isLoading) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                TextButton(onClick = onRefresh) { Text(textResources.getString(R.string.action_refresh)) }
            }
        }
    }
}
