package au.edu.unimelb.campuscompanion.ui.screens

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import au.edu.unimelb.campuscompanion.ui.text.textResources
import au.edu.unimelb.campuscompanion.R
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import au.edu.unimelb.campuscompanion.data.toUserMessage
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

@Composable
internal fun GroupExitAction(isOwner: Boolean, onExit: suspend (Boolean) -> Result<Unit>) {
    val textResources = textResources()
    var confirm by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val label = if (isOwner) textResources.getString(R.string.group_exit_action_dissolve_group) else textResources.getString(R.string.group_exit_action_leave_group)
    Button(
        onClick = { error = null; confirm = true },
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError
        )
    ) { Text(label) }
    if (confirm) AlertDialog(
        onDismissRequest = { if (!busy) confirm = false },
        title = { Text(label) },
        text = { Text(error ?: if (isOwner)
            textResources.getString(R.string.group_exit_action_this_permanently_deletes_the_group_messages_and_shared)
            else textResources.getString(R.string.group_exit_action_this_group_will_be_removed_from_your_list), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    try {
                        onExit(isOwner).fold(
                            onSuccess = { confirm = false },
                            onFailure = { error = it.toUserMessage().body.resolve(textResources) }
                        )
                    } catch (cancelled: CancellationException) { throw cancelled
                    } catch (failure: Exception) { error = failure.toUserMessage().body.resolve(textResources)
                    } finally { busy = false }
                }
            }) { Text(if (busy) textResources.getString(R.string.group_exit_action_please_wait) else label) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = { confirm = false }) { Text(textResources.getString(R.string.action_cancel)) } }
    )
}
