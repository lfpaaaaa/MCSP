package au.edu.unimelb.campuscompanion.ui.screens

import androidx.compose.runtime.setValue

import androidx.compose.runtime.getValue

import au.edu.unimelb.campuscompanion.ui.text.textResources
import au.edu.unimelb.campuscompanion.R
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
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
    val textResources = textResources()
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
            text = textResources.getString(R.string.timetable_group_this_class_comes_from_your_timetable_messages_and),
            style = MaterialTheme.typography.bodyMedium
        )
        Button(
            onClick = {
                scope.launch {
                    isStarting = true
                    errorMessage = null
                    onStartGroup().onFailure { error ->
                        errorMessage = error.toUserMessage().body.resolve(textResources)
                    }
                    isStarting = false
                }
            },
            enabled = !isStarting,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (isStarting) textResources.getString(R.string.timetable_group_starting) else textResources.getString(R.string.timetable_group_start_the_group, group.courseCode))
        }
        OutlinedButton(
            onClick = onGoToGroups,
            enabled = !isStarting,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(textResources.getString(R.string.label_i_have_a_code_from_a_classmate))
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
