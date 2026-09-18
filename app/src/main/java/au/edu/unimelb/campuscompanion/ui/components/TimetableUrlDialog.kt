package au.edu.unimelb.campuscompanion.ui.components

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun TimetableUrlDialog(
    initialUrl: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var url by rememberSaveable(initialUrl) { mutableStateOf(initialUrl) }
    var showError by rememberSaveable { mutableStateOf(false) }
    val normalizedUrl = url.trim()
    val isValid = remember(normalizedUrl) { isValidTimetableUrl(normalizedUrl) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Connect timetable") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Paste the private calendar subscription URL from MyTimetable.",
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = {
                        url = it
                        showError = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Calendar URL") },
                    isError = showError && !isValid,
                    supportingText = if (showError && !isValid) {
                        { Text("Enter a complete http:// or https:// URL.") }
                    } else {
                        null
                    }
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (isValid) onSave(normalizedUrl) else showError = true
                }
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Not now")
            }
        }
    )
}

private fun isValidTimetableUrl(value: String): Boolean {
    if (value.isBlank()) return false
    val uri = Uri.parse(value)
    return uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank()
}
