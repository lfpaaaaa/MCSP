package au.edu.unimelb.campuscompanion.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import au.edu.unimelb.campuscompanion.data.AppRepositories
import au.edu.unimelb.campuscompanion.data.model.FileCatalogState
import au.edu.unimelb.campuscompanion.data.model.SharedFile
import au.edu.unimelb.campuscompanion.data.repository.FileRepository
import au.edu.unimelb.campuscompanion.data.toUserMessage
import au.edu.unimelb.campuscompanion.ui.model.GroupFileFilter
import au.edu.unimelb.campuscompanion.ui.model.presentGroupFiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GroupFilesScreen(
    groupId: String,
    groupName: String,
    currentUserId: String,
    onBack: () -> Unit,
    repository: FileRepository = AppRepositories.files,
    onConfirmedFiles: (Set<String>) -> Unit = {}
) {
    val catalogFlow = remember(groupId, repository) { repository.observeFileCatalog(groupId) }
    val catalog by catalogFlow.collectAsState(initial = FileCatalogState())
    var query by rememberSaveable(groupId) { mutableStateOf("") }
    var filter by rememberSaveable(groupId) { mutableStateOf(GroupFileFilter.All) }
    var selectedId by rememberSaveable(groupId) { mutableStateOf<String?>(null) }
    var deleting by remember(groupId) { mutableStateOf<SharedFile?>(null) }
    var deleteError by remember(groupId) { mutableStateOf<String?>(null) }
    var deleteBusy by remember(groupId) { mutableStateOf(false) }
    var refreshBusy by remember(groupId) { mutableStateOf(false) }
    var refreshError by remember(groupId) { mutableStateOf<String?>(null) }
    var confirmationError by rememberSaveable(groupId, currentUserId) { mutableStateOf<String?>(null) }
    var pendingConfirmedIds by rememberSaveable(groupId, currentUserId) { mutableStateOf(arrayListOf<String>()) }
    val latestConfirmation by rememberUpdatedState(onConfirmedFiles)
    val presentation = remember(catalog, query, filter) { presentGroupFiles(catalog, query, filter) }
    val authoritativeCatalog = catalog.hasLoaded && !catalog.isLoading && !catalog.isCached && catalog.error == null
    val scope = rememberCoroutineScope()

    fun refresh() {
        if (refreshBusy) return
        refreshBusy = true
        refreshError = null
        scope.launch {
            try {
                repository.refreshFiles(groupId).getOrThrow()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                refreshError = error.toUserMessage().body
            } finally {
                refreshBusy = false
            }
        }
    }

    fun confirmFiles(ids: Set<String>) {
        pendingConfirmedIds = ArrayList((pendingConfirmedIds + ids).distinct())
        try {
            latestConfirmation(pendingConfirmedIds.toSet())
            pendingConfirmedIds = arrayListOf()
            confirmationError = null
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // Retain these confirmed ids even if a later catalog omits a file after deletion.
            // Retrying only saves the confirmation; it never sends or deletes an attachment.
            confirmationError = "Upload confirmation could not be saved. Tap Retry confirmation."
        }
    }

    LaunchedEffect(catalog.files, presentation.canBrowseFiles) {
        if (!presentation.canBrowseFiles || catalog.files.none { it.id == selectedId }) selectedId = null
    }
    LaunchedEffect(catalog.hasLoaded, catalog.isLoading, catalog.isCached, catalog.error) {
        if (catalog.hasLoaded && !catalog.isLoading && !catalog.isCached && catalog.error == null) {
            refreshError = null
        }
    }
    LaunchedEffect(catalog.files, catalog.hasLoaded, catalog.isLoading, catalog.isCached, catalog.error) {
        if (authoritativeCatalog) confirmFiles(catalog.files.mapTo(mutableSetOf()) { it.id })
    }
    LaunchedEffect(presentation.canBrowseFiles, deleteBusy) {
        if (!presentation.canBrowseFiles && !deleteBusy) {
            deleting = null
            deleteError = null
        }
    }
    BackHandler(enabled = selectedId == null && deleting == null, onBack = onBack)

    Scaffold(
        modifier = Modifier.semantics { paneTitle = "Group files" },
        topBar = {
            TopAppBar(
                title = { Text("Group files") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to group settings")
                    }
                },
                actions = {
                    IconButton(enabled = !refreshBusy && !catalog.isLoading, onClick = ::refresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh group files")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(groupName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            }
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    label = { Text("Search file name or uploader") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(GroupFileFilter.entries, key = { it.name }) { option ->
                        FilterChip(selected = filter == option, onClick = { filter = option }, label = { Text(option.name) })
                    }
                }
            }
            if (catalog.isLoading || refreshBusy) item {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Loading group files" })
            }
            presentation.statusMessage?.let { status -> item { Text(status, style = MaterialTheme.typography.bodyMedium) } }
            (presentation.errorMessage ?: refreshError)?.let { error ->
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                        TextButton(enabled = !refreshBusy && !catalog.isLoading, onClick = ::refresh) { Text("Retry") }
                    }
                }
            }
            confirmationError?.let { error ->
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(error, color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                        TextButton(enabled = authoritativeCatalog, onClick = { confirmFiles(emptySet()) }) {
                            Text("Retry confirmation")
                        }
                    }
                }
            }
            presentation.emptyMessage?.let { message -> item { Text(message) } }
            items(presentation.files, key = { it.id }) { file ->
                OutlinedCard(
                    onClick = { selectedId = file.id },
                    modifier = Modifier.fillMaxWidth().semantics { onClick(label = "Open ${file.fileName}", action = null) }
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(file.fileName, style = MaterialTheme.typography.titleMedium)
                        Text("${file.uploaderName} · ${file.createdAt.atZone(ZoneId.systemDefault()).format(groupFileDateFormatter)}",
                            style = MaterialTheme.typography.bodySmall)
                        Text("${file.mimeType} · ${formatGroupFileSize(file.sizeBytes)}", style = MaterialTheme.typography.bodySmall)
                        if (!file.allowDownloads) Text(
                            if (file.uploaderId == currentUserId) "Only you can download this attachment." else "Preview only · downloads disabled",
                            style = MaterialTheme.typography.labelMedium
                        )
                        if (file.uploaderId == currentUserId) {
                            TextButton(onClick = { deleting = file; deleteError = null },
                                modifier = Modifier.semantics { contentDescription = "Delete ${file.fileName}" }) { Text("Delete") }
                        }
                    }
                }
            }
        }
    }
    if (presentation.canBrowseFiles) catalog.files.firstOrNull { it.id == selectedId }?.let { file ->
        ChatAttachmentViewer(file, currentUserId, repository = repository, onDismiss = { selectedId = null })
    }
    deleting?.let { file ->
        AlertDialog(
            onDismissRequest = { if (!deleteBusy) deleting = null },
            title = { Text("Delete attachment?") },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Delete “${file.fileName}” for everyone in this group?")
                    deleteError?.let { Text(it, color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                    if (deleteBusy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().semantics {
                        contentDescription = "Deleting attachment"
                    })
                }
            },
            confirmButton = {
                TextButton(enabled = !deleteBusy, onClick = {
                    deleteBusy = true
                    deleteError = null
                    scope.launch {
                        try {
                            repository.deleteFile(file.id).getOrThrow()
                            deleting = null
                            if (selectedId == file.id) selectedId = null
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (error: Exception) {
                            deleteError = "Deletion could not finish. Retry deletion to complete it. ${error.toUserMessage().body}"
                        } finally {
                            deleteBusy = false
                        }
                    }
                }) { Text(if (deleteBusy) "Deleting…" else if (deleteError != null) "Retry deletion" else "Delete") }
            },
            dismissButton = {
                TextButton(enabled = !deleteBusy, onClick = { deleting = null }) { Text("Cancel") }
            }
        )
    }
}

private val groupFileDateFormatter = DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm")

private fun formatGroupFileSize(sizeBytes: Long): String = when {
    sizeBytes >= 1024L * 1024L -> "%.1f MB".format(sizeBytes / (1024.0 * 1024.0))
    sizeBytes >= 1024L -> "%.1f KB".format(sizeBytes / 1024.0)
    else -> "$sizeBytes B"
}
