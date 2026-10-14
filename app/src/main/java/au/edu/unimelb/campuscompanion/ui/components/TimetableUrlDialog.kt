package au.edu.unimelb.campuscompanion.ui.components

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun TimetableUrlDialog(
    initialUrl: String,
    onDismiss: () -> Unit,
    onSave: suspend (String) -> Result<Unit>
) {
    var url by rememberSaveable(initialUrl) { mutableStateOf(initialUrl) }
    var showError by rememberSaveable { mutableStateOf(false) }
    var requestError by rememberSaveable { mutableStateOf<String?>(null) }
    var isSaving by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val normalizedUrl = url.trim()
    val isValid = remember(normalizedUrl) { isValidTimetableUrl(normalizedUrl) }
    val supportingMessage = when {
        showError && !isValid -> "Enter a complete http:// or https:// URL."
        requestError != null -> requestError
        else -> null
    }

    AlertDialog(
        onDismissRequest = {
            if (!isSaving) onDismiss()
        },
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
                        requestError = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Calendar URL") },
                    isError = (showError && !isValid) || requestError != null,
                    supportingText = supportingMessage?.let { message ->
                        { Text(message) }
                    }
                )
            }
        },
        confirmButton = {
            Button(
                enabled = !isSaving,
                onClick = {
                    if (!isValid) {
                        showError = true
                        return@Button
                    }
                    coroutineScope.launch {
                        isSaving = true
                        requestError = null
                        val result = onSave(normalizedUrl)
                        isSaving = false
                        result.fold(
                            onSuccess = { onDismiss() },
                            onFailure = { error ->
                                requestError = error.message ?: "The timetable could not be connected."
                            }
                        )
                    }
                }
            ) {
                if (isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                    Text(
                        text = "Checking",
                        modifier = Modifier.padding(start = 8.dp)
                    )
                } else {
                    Text("Connect")
                }
            }
        },
        dismissButton = {
            TextButton(
                enabled = !isSaving,
                onClick = onDismiss
            ) {
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
