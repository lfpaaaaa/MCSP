package au.edu.unimelb.campuscompanion.ui.screens

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import au.edu.unimelb.campuscompanion.ui.text.textResources
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import au.edu.unimelb.campuscompanion.R
import au.edu.unimelb.campuscompanion.data.model.SharedFile
import au.edu.unimelb.campuscompanion.data.repository.FileTranscript
import au.edu.unimelb.campuscompanion.data.repository.TranscriptRepository
import au.edu.unimelb.campuscompanion.data.toUserMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun TranscriptPanel(file: SharedFile, currentUserId: String) {
    val textResources = textResources()
    var transcript by remember(file.id) { mutableStateOf<FileTranscript?>(null) }
    var loading by remember(file.id) { mutableStateOf(true) }
    var submitting by remember(file.id) { mutableStateOf(false) }
    var error by remember(file.id) { mutableStateOf<String?>(null) }
    var refresh by remember(file.id) { mutableIntStateOf(0) }
    var showReader by rememberSaveable(file.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(file.id, refresh) {
        loading = true
        do {
            val result = TranscriptRepository.get(file.id)
            result.onSuccess { transcript = it; error = null }.onFailure { error = it.toUserMessage().body.resolve(textResources) }
            loading = false
            if (result.isFailure || transcript?.isWorking != true) break
            delay(3_000)
        } while (true)
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider()
        Text(stringResource(R.string.transcript_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        if (loading || submitting) LinearProgressIndicator(Modifier.fillMaxWidth().semantics { contentDescription = textResources.getString(R.string.a11y_loading_transcript) })
        if (transcript?.status == "ready") Text(stringResource(R.string.a11y_transcript_ready),
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        when (transcript?.status) {
            "ready" -> {
                val text = transcript?.transcript.orEmpty().ifBlank { stringResource(R.string.transcript_empty) }
                val preview = if (text.length > 400) text.take(400) + "…" else text
                if (file.canDownloadBy(currentUserId)) SelectionContainer { Text(preview) } else Text(preview)
                Button(onClick = { showReader = true }) { Text(stringResource(R.string.transcript_read_search)) }
                Text(stringResource(if (transcript?.editedAt != null) R.string.transcript_edited else R.string.transcript_accuracy), style = MaterialTheme.typography.bodySmall)
            }
            "queued" -> Text(stringResource(R.string.transcript_queued), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            "processing" -> Text(stringResource(R.string.transcript_processing), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            "failed" -> Text(stringResource(R.string.transcript_failed), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.error)
            else -> if (!loading) Text(stringResource(R.string.transcript_not_requested))
        }
        if (file.uploaderId == currentUserId && !loading && transcript?.isWorking != true && transcript?.status != "ready") {
            Text(stringResource(R.string.transcript_share_notice), style = MaterialTheme.typography.bodySmall)
            Button(enabled = !submitting, onClick = {
                submitting = true
                scope.launch {
                    try { TranscriptRepository.request(file.id).onSuccess { transcript = it; error = null; refresh++ }.onFailure { error = it.toUserMessage().body.resolve(textResources) } }
                    finally { submitting = false }
                }
            }) { Text(stringResource(if (transcript?.status == "failed") R.string.transcript_retry else R.string.transcript_generate)) }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        if (transcript?.status == "ready" || error != null || (transcript == null && file.uploaderId != currentUserId)) {
            TextButton(onClick = { refresh++ }) { Text(stringResource(R.string.action_refresh)) }
        }
    }
    if (showReader) transcript?.takeIf { it.status == "ready" }?.let { current ->
        TranscriptDialog(file.fileName, current, canEdit = file.uploaderId == currentUserId,
            canCopy = file.canDownloadBy(currentUserId), onChanged = { transcript = it }, onDismiss = { showReader = false })
    }
}
