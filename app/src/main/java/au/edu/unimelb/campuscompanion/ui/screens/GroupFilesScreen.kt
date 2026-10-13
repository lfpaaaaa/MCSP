package au.edu.unimelb.campuscompanion.ui.screens

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import au.edu.unimelb.campuscompanion.ui.text.textResources
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import au.edu.unimelb.campuscompanion.R
import au.edu.unimelb.campuscompanion.data.AppRepositories
import au.edu.unimelb.campuscompanion.data.model.SharedFile
import au.edu.unimelb.campuscompanion.data.toUserMessage
import au.edu.unimelb.campuscompanion.ui.model.GroupFileFilter
import au.edu.unimelb.campuscompanion.ui.model.filterGroupFiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GroupFilesScreen(groupId: String, groupName: String, currentUserId: String, onBack: () -> Unit) {
    val textResources = textResources()
    val repository = AppRepositories.files
    val loadFailedMessage = stringResource(R.string.files_load_failed)
    var files by remember(groupId) { mutableStateOf<List<SharedFile>>(emptyList()) }
    var loading by remember(groupId) { mutableStateOf(true) }
    var error by remember(groupId) { mutableStateOf<String?>(null) }
    var retry by remember(groupId) { mutableIntStateOf(0) }
    var query by rememberSaveable(groupId) { mutableStateOf("") }
    var filter by rememberSaveable(groupId) { mutableStateOf(GroupFileFilter.All) }
    var selectedId by rememberSaveable(groupId) { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<SharedFile?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(groupId, retry) {
        loading = true; error = null
        try {
            val stream = repository.observeFiles(groupId)
            files = withTimeout(15_000) { stream.first() }
            loading = false
            stream.collect { files = it }
        } catch (e: TimeoutCancellationException) { error = loadFailedMessage }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.toUserMessage().body.resolve(textResources) }
        finally { loading = false }
    }
    val visible = remember(files, query, filter) { filterGroupFiles(files, query, filter) }
    Scaffold(modifier = Modifier.semantics { paneTitle = textResources.getString(R.string.group_files) }, topBar = { TopAppBar(title = { Text(stringResource(R.string.group_files)) }, navigationIcon = {
        TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
    }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(groupName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true,
                label = { Text(stringResource(R.string.files_search)) }, modifier = Modifier.fillMaxWidth())
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(GroupFileFilter.entries) { option ->
                    FilterChip(selected = filter == option, onClick = { filter = option }, label = { Text(stringResource(option.label)) })
                }
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().semantics { contentDescription = textResources.getString(R.string.a11y_loading_files) })
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }); TextButton(onClick = { retry++ }) { Text(stringResource(R.string.action_retry)) } }
            if (!loading && error == null && visible.isEmpty()) Text(stringResource(if (files.isEmpty()) R.string.files_empty else R.string.files_no_matches))
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(visible, key = { it.id }) { file ->
                    OutlinedCard(onClick = { selectedId = file.id }, modifier = Modifier.fillMaxWidth().semantics {
                        onClick(label = textResources.getString(R.string.a11y_open_file, file.fileName), action = null)
                    }) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(file.fileName, style = MaterialTheme.typography.titleMedium)
                            Text(file.uploaderName + " · " + file.createdAt.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm")), style = MaterialTheme.typography.bodySmall)
                            Text(textResources.getString(R.string.attachment_kind_size, file.mimeType, formatFileSize(file.sizeBytes)), style = MaterialTheme.typography.bodySmall)
                            if (!file.allowDownloads) Text(stringResource(R.string.files_preview_only), style = MaterialTheme.typography.labelMedium)
                            if (file.uploaderId == currentUserId) TextButton(onClick = { deleting = file }, modifier = Modifier.semantics { contentDescription = textResources.getString(R.string.a11y_delete_file, file.fileName) }) { Text(stringResource(R.string.files_delete)) }
                        }
                    }
                }
            }
        }
    }
    files.firstOrNull { it.id == selectedId }?.let { file -> ChatAttachmentViewer(file, currentUserId) { selectedId = null } }
    deleting?.let { file -> AlertDialog(onDismissRequest = { if (!busy) deleting = null },
        title = { Text(stringResource(R.string.files_delete_title)) }, text = { Text(stringResource(R.string.files_delete_body, file.fileName)) },
        confirmButton = { TextButton(enabled = !busy, onClick = {
            busy = true
            scope.launch {
                try { repository.deleteFile(file.id).onSuccess { deleting = null; if (selectedId == file.id) selectedId = null }.onFailure { error = it.toUserMessage().body.resolve(textResources); deleting = null } }
                finally { busy = false }
            }
        }) { Text(stringResource(R.string.files_delete)) } },
        dismissButton = { TextButton(enabled = !busy, onClick = { deleting = null }) { Text(stringResource(R.string.action_cancel)) } }) }
}

private val GroupFileFilter.label: Int get() = when (this) {
    GroupFileFilter.All -> R.string.files_all
    GroupFileFilter.Photos -> R.string.files_photos
    GroupFileFilter.Videos -> R.string.files_videos
    GroupFileFilter.Audio -> R.string.files_audio
    GroupFileFilter.Documents -> R.string.files_documents
}
