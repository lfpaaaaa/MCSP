package au.edu.unimelb.campuscompanion.ui.screens

import android.view.WindowManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import au.edu.unimelb.campuscompanion.R
import au.edu.unimelb.campuscompanion.data.repository.FileTranscript
import au.edu.unimelb.campuscompanion.data.repository.TranscriptRepository
import au.edu.unimelb.campuscompanion.data.toUserMessage
import au.edu.unimelb.campuscompanion.ui.model.*
import au.edu.unimelb.campuscompanion.ui.text.textResources
import kotlinx.coroutines.launch

/** Edits stay in the dialog after a failed save and are only published after server confirmation. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TranscriptDialog(
    fileName: String,
    initial: FileTranscript,
    canEdit: Boolean,
    canCopy: Boolean,
    onChanged: (FileTranscript) -> Unit,
    onDismiss: () -> Unit
) {
    val resources = textResources()
    val scope = rememberCoroutineScope()
    var document by remember(initial.fileId) { mutableStateOf(initial) }
    var editing by rememberSaveable(initial.fileId) { mutableStateOf(false) }
    // Only the draft is saved in the instance state; do not store a second large original copy.
    var draft by rememberSaveable(initial.fileId) { mutableStateOf<String?>(null) }
    var expectedRevision by rememberSaveable(initial.fileId) { mutableIntStateOf(initial.revision) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf(false) }
    var limitExceeded by remember { mutableStateOf(false) }
    var pendingAction by remember { mutableStateOf<String?>(null) }
    val dirty = editing && draft != document.transcript.orEmpty()
    fun finishEditing() { editing = false; draft = null; error = null; limitExceeded = false }
    fun reload() {
        busy = true; error = null
        scope.launch {
            try {
                TranscriptRepository.get(initial.fileId).onSuccess { latest ->
                    if (latest?.status == "ready") {
                        document = latest; onChanged(latest)
                        if (editing) { draft = latest.transcript.orEmpty(); expectedRevision = latest.revision }
                        limitExceeded = false
                    } else error = resources.getString(R.string.transcript_not_ready_to_edit)
                }.onFailure { error = it.toUserMessage().body.resolve(resources) }
            } finally { busy = false }
        }
    }
    fun goBack() {
        if (busy) return
        if (editing) {
            if (dirty) pendingAction = "cancel" else finishEditing()
        } else onDismiss()
    }
    Dialog(onDismissRequest = ::goBack, properties = DialogProperties()) {
        val window = (LocalView.current.parent as DialogWindowProvider).window
        SideEffect {
            // Keep native window measurement so Compose 1.7 respects the keyboard and system bars.
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        Surface(Modifier.fillMaxSize()) {
            Scaffold(topBar = { TopAppBar(
                title = { Text(stringResource(if (editing) R.string.transcript_edit else R.string.transcript_title)) },
                navigationIcon = { IconButton(enabled = !busy, onClick = ::goBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.action_back))
                } },
                actions = {
                    if (editing) TextButton(enabled = !busy && dirty && !limitExceeded, onClick = {
                        val text = draft ?: return@TextButton
                        busy = true; error = null
                        scope.launch {
                            try {
                                TranscriptRepository.update(initial.fileId, text, expectedRevision).onSuccess {
                                    document = it; onChanged(it); finishEditing(); saved = true
                                }.onFailure { error = it.toUserMessage().body.resolve(resources) }
                            } finally { busy = false }
                        }
                    }) { Text(stringResource(if (busy) R.string.chat_attachments_saving else R.string.group_settings_save)) }
                    else if (canEdit) TextButton(enabled = !busy, onClick = {
                        draft = document.transcript.orEmpty(); expectedRevision = document.revision
                        editing = true; error = null; saved = false
                    }) { Text(stringResource(R.string.transcript_edit_action)) }
                }
            ) }) { padding ->
                Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding().padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(fileName, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                    if (editing) {
                        Text(stringResource(R.string.transcript_edit_shared), style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(value = draft.orEmpty(), onValueChange = {
                            if (it.length <= MAX_TRANSCRIPT_LENGTH) { draft = it; limitExceeded = false }
                            else limitExceeded = true
                        }, enabled = !busy, label = { Text(stringResource(R.string.transcript_text)) },
                            modifier = Modifier.fillMaxWidth().weight(1f), isError = limitExceeded,
                            supportingText = { if (limitExceeded) Text(stringResource(R.string.transcript_edit_invalid)) })
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton(enabled = !busy, onClick = ::goBack) { Text(stringResource(R.string.action_cancel)) }
                            TextButton(enabled = !busy, onClick = {
                                if (dirty) pendingAction = "reload" else reload()
                            }) { Text(stringResource(R.string.transcript_reload)) }
                        }
                    } else {
                        if (saved) Text(stringResource(R.string.transcript_saved),
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(if (document.editedAt != null) R.string.transcript_edited else R.string.transcript_accuracy),
                                style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            TextButton(enabled = !busy, onClick = ::reload) { Text(stringResource(R.string.action_refresh)) }
                        }
                        TranscriptReader(document.transcript.orEmpty(), canCopy, Modifier.weight(1f))
                    }
                }
            }
        }
        if (pendingAction != null) AlertDialog(onDismissRequest = { pendingAction = null },
            title = { Text(stringResource(R.string.transcript_discard_title)) },
            text = { Text(stringResource(R.string.transcript_discard_body)) },
            confirmButton = { TextButton(onClick = {
                val action = pendingAction; pendingAction = null
                if (action == "reload") reload() else finishEditing()
            }) { Text(stringResource(R.string.action_discard)) } },
            dismissButton = { TextButton(onClick = { pendingAction = null }) { Text(stringResource(R.string.transcript_keep_editing)) } })
    }
}

@Composable
internal fun TranscriptReader(text: String, canCopy: Boolean, modifier: Modifier = Modifier) {
    var query by rememberSaveable { mutableStateOf("") }
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val chunks = remember(text) { transcriptChunks(text) }
    val matches = remember(text, query) { transcriptMatches(text, query) }
    val active = matches.getOrNull(selected.coerceIn(0, (matches.size - 1).coerceAtLeast(0)))
    val list = rememberLazyListState()
    var pendingFocus by remember { mutableStateOf<IntRange?>(null) }
    val colors = MaterialTheme.colorScheme
    LaunchedEffect(text, query, selected) {
        pendingFocus = active
        active?.let { match -> list.scrollToItem(chunks.indexOfLast { it.start <= match.first }.coerceAtLeast(0)) }
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(value = query, onValueChange = { query = it.take(MAX_TRANSCRIPT_QUERY_LENGTH); selected = 0 },
            singleLine = true, label = { Text(stringResource(R.string.transcript_search)) }, modifier = Modifier.fillMaxWidth(),
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = ""; selected = 0 }) {
                Icon(Icons.Outlined.Clear, contentDescription = stringResource(R.string.transcript_clear_search))
            } })
        if (query.isNotBlank()) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(if (matches.isEmpty()) stringResource(R.string.transcript_no_matches)
                else stringResource(R.string.transcript_match_count, selected.coerceAtMost(matches.lastIndex) + 1, matches.size),
                modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            IconButton(enabled = matches.isNotEmpty(), onClick = { selected = (selected - 1 + matches.size) % matches.size }) {
                Icon(Icons.Outlined.ExpandLess, contentDescription = stringResource(R.string.transcript_previous_match))
            }
            IconButton(enabled = matches.isNotEmpty(), onClick = { selected = (selected + 1) % matches.size }) {
                Icon(Icons.Outlined.ExpandMore, contentDescription = stringResource(R.string.transcript_next_match))
            }
        }
        LazyColumn(state = list, modifier = Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (text.isEmpty()) item { Text(stringResource(R.string.transcript_empty)) }
            itemsIndexed(chunks, key = { _, chunk -> chunk.start }) { index, chunk ->
                val annotated = remember(chunk, matches, active, colors) { buildAnnotatedString {
                    append(chunk.text)
                    for (match in chunk.intersectingMatches(matches)) addStyle(
                        SpanStyle(background = if (match == active) colors.primary else colors.tertiaryContainer,
                            color = if (match == active) colors.onPrimary else colors.onTertiaryContainer),
                        maxOf(chunk.start, match.first) - chunk.start, minOf(chunk.end, match.last + 1) - chunk.start)
                } }
                var layout by remember(annotated) { mutableStateOf<TextLayoutResult?>(null) }
                val target = pendingFocus?.takeIf { it.first in chunk.start until chunk.end }
                LaunchedEffect(target, layout) {
                    val result = layout
                    if (target != null && result != null && pendingFocus == target) {
                        val line = result.getLineForOffset(target.first - chunk.start)
                        list.scrollToItem(index, result.getLineTop(line).toInt())
                        pendingFocus = null
                    }
                }
                if (canCopy) SelectionContainer { Text(annotated, onTextLayout = { layout = it }) }
                else Text(annotated, onTextLayout = { layout = it })
            }
        }
    }
}
