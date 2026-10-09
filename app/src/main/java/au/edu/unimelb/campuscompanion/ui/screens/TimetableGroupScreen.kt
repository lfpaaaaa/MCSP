package au.edu.unimelb.campuscompanion.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import au.edu.unimelb.campuscompanion.data.toUserMessage
import au.edu.unimelb.campuscompanion.ui.model.CourseGroup
import kotlinx.coroutines.launch

/**
 * Shown in place of a chat for a class that only exists in the timetable. Messages and files
 * live in a server-side group, so the user either starts one for the class or joins a
 * classmate's with its code.
 */
@Composable
fun TimetableGroupScreen(
    group: CourseGroup,
    onStartGroup: suspend () -> Result<Unit>,
    onGoToGroups: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isStarting by rememberSaveable(group.id) { mutableStateOf(false) }
    var errorMessage by rememberSaveable(group.id) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(
            imageVector = Icons.Outlined.Groups,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Text(
            text = group.courseCode,
            style = MaterialTheme.typography.titleLarge
        )
        Text(
            text = group.name,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "This class comes from your timetable. Messages and files are shared in a group " +
                "that one classmate starts; everyone else joins it with the group's six-character code.",
            style = MaterialTheme.typography.bodyMedium
        )
        Button(
            onClick = {
                scope.launch {
                    isStarting = true
                    errorMessage = null
                    onStartGroup().onFailure { error ->
                        errorMessage = error.toUserMessage().body
                    }
                    isStarting = false
                }
            },
            enabled = !isStarting,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (isStarting) "Starting…" else "Start the ${group.courseCode} group")
        }
        OutlinedButton(
            onClick = onGoToGroups,
            enabled = !isStarting,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("I have a code from a classmate")
        }
        errorMessage?.let { error ->
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}
