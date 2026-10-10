package au.edu.unimelb.campuscompanion.ui.components

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
    if (!state.isConnected) return
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(when {
                    state.isLoading -> "Refreshing timetable…"
                    state.isCached -> "Showing saved timetable"
                    else -> "Timetable saved for offline use"
                }, style = MaterialTheme.typography.labelLarge)
                state.lastSyncedAt?.let { savedAt ->
                    val date = savedAt.atZone(ZoneId.systemDefault())
                        .format(DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a"))
                    Text("Last updated $date", style = MaterialTheme.typography.bodySmall)
                }
                if (state.errorMessage != null) {
                    Text("Could not update the timetable. Your saved classes are still available.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (state.isLoading) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                TextButton(onClick = onRefresh) { Text("Refresh") }
            }
        }
    }
}
