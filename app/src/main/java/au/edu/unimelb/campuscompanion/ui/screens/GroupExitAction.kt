package au.edu.unimelb.campuscompanion.ui.screens

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
    var confirm by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val label = if (isOwner) "Dissolve group" else "Leave group"
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
            "This permanently deletes the group, messages and shared-file records for everyone. This cannot be undone."
            else "This group will be removed from your list. You will need an invitation to join again.") },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    try {
                        onExit(isOwner).fold(
                            onSuccess = { confirm = false },
                            onFailure = { error = it.toUserMessage().body }
                        )
                    } catch (cancelled: CancellationException) { throw cancelled
                    } catch (failure: Exception) { error = failure.toUserMessage().body
                    } finally { busy = false }
                }
            }) { Text(if (busy) "Please wait…" else label) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = { confirm = false }) { Text("Cancel") } }
    )
}
