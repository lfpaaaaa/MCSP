package au.edu.unimelb.campuscompanion.ui.components

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.runtime.setValue

import androidx.compose.runtime.getValue

import au.edu.unimelb.campuscompanion.ui.text.textResources
import au.edu.unimelb.campuscompanion.R
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun TimetableUrlDialog(
    initialUrl: String,
    onDismiss: () -> Unit,
    onSave: suspend (String) -> Result<Unit>
) {
    val textResources = textResources()
    var url by rememberSaveable(initialUrl) { mutableStateOf(initialUrl) }
    var showError by rememberSaveable { mutableStateOf(false) }
    var requestError by rememberSaveable { mutableStateOf<String?>(null) }
    var isSaving by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val normalizedUrl = url.trim()
    val isValid = remember(normalizedUrl) { isValidTimetableUrl(normalizedUrl) }
    val supportingMessage = when {
        showError && !isValid -> textResources.getString(R.string.timetable_url_enter_a_complete_http_or_https_url)
        requestError != null -> requestError
        else -> null
    }

    AlertDialog(
        onDismissRequest = {
            if (!isSaving) onDismiss()
        },
        title = { Text(textResources.getString(R.string.timetable_url_connect_timetable)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = textResources.getString(R.string.timetable_url_paste_the_private_calendar_subscription_url_from_mytimetable),
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = {
                        url = it
                        showError = false
                        requestError = null
                    },
                    modifier = Modifier.fillMaxWidth().semantics { supportingMessage?.let { error(it) } },
                    singleLine = true,
                    label = { Text(textResources.getString(R.string.timetable_url_calendar_url)) },
                    isError = (showError && !isValid) || requestError != null,
                    supportingText = supportingMessage?.let { message ->
                        { Text(message, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
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
                                requestError = (error as? au.edu.unimelb.campuscompanion.data.TimetableImportException)?.userMessage?.resolve(textResources) ?: textResources.getString(R.string.timetable_url_the_timetable_could_not_be_connected)
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
                        text = textResources.getString(R.string.timetable_url_checking),
                        modifier = Modifier.padding(start = 8.dp)
                    )
                } else {
                    Text(textResources.getString(R.string.timetable_url_connect))
                }
            }
        },
        dismissButton = {
            TextButton(
                enabled = !isSaving,
                onClick = onDismiss
            ) {
                Text(textResources.getString(R.string.timetable_url_not_now))
            }
        }
    )
}

private fun isValidTimetableUrl(value: String): Boolean {
    if (value.isBlank()) return false
    val uri = Uri.parse(value)
    return uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank()
}
