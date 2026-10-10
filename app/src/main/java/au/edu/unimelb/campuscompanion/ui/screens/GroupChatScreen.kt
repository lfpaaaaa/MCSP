package au.edu.unimelb.campuscompanion.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import au.edu.unimelb.campuscompanion.data.AppRepositories
import au.edu.unimelb.campuscompanion.data.model.ChatConnection
import au.edu.unimelb.campuscompanion.data.model.MessageStatus
import au.edu.unimelb.campuscompanion.data.model.SharedFile
import au.edu.unimelb.campuscompanion.data.repository.ChatRepository
import au.edu.unimelb.campuscompanion.data.repository.FileRepository
import au.edu.unimelb.campuscompanion.ui.chat.ChatImageLoader
import au.edu.unimelb.campuscompanion.ui.chat.ChatTimelineItem
import au.edu.unimelb.campuscompanion.ui.chat.GroupChatSession
import au.edu.unimelb.campuscompanion.ui.chat.LatencySample
import au.edu.unimelb.campuscompanion.ui.chat.OpenChat
import au.edu.unimelb.campuscompanion.ui.chat.TimelineRow
import au.edu.unimelb.campuscompanion.ui.chat.timelineRows
import au.edu.unimelb.campuscompanion.ui.model.CourseGroup
import au.edu.unimelb.campuscompanion.ui.model.GroupOrigin
import au.edu.unimelb.campuscompanion.ui.model.MAX_PENDING_DOCUMENTS
import au.edu.unimelb.campuscompanion.ui.model.PendingDocument
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val chatTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
private val photoNameFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH.mm.ss", Locale.ENGLISH)
private const val LATENCY_LOG_TAG = "ChatLatency"
private const val NOTICE_MILLIS = 6_000L

/** A photo or video from the picker, waiting with the documents until the user taps send. */
private data class LocalAttachment(
    val uri: Uri,
    val displayName: String,
    val kind: AttachmentKind
)

private enum class AttachmentKind {
    File,
    Photo,
    Video,
    Camera
}

/**
 * One group's chat: the timeline of messages and shared files from the server, kept in sync
 * through [GroupChatSession], and a composer for text and attachments.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun GroupChatScreen(
    group: CourseGroup,
    currentUserId: String,
    myDisplayName: String,
    pendingDocuments: List<PendingDocument>,
    pendingDocumentError: String?,
    capturedCameraUri: String?,
    onPickDocuments: () -> Unit,
    onPendingDocumentRemoved: (String) -> Unit,
    onPendingDocumentsCleared: () -> Unit,
    onTakePhoto: () -> Unit,
    onCapturedCameraPhotoConsumed: () -> Unit,
    modifier: Modifier = Modifier,
    chatRepository: ChatRepository = AppRepositories.chat,
    fileRepository: FileRepository = AppRepositories.files
) {
    val listState = rememberLazyListState()
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current
    val sessionScope = remember(group.id) { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
    val session = remember(group.id) {
        GroupChatSession(
            groupId = group.id,
            currentUserId = currentUserId,
            chat = chatRepository,
            files = fileRepository,
            scope = sessionScope,
            uploadScope = AppRepositories.backgroundScope,
            onLatency = ::logLatency
        )
    }
    val images = remember(group.id) { ChatImageLoader(fileRepository, sessionScope) }
    DisposableEffect(sessionScope) {
        session.start()
        onDispose { sessionScope.cancel() }
    }
    // While this chat is in front, its new messages are not also shown as notifications.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, group.id) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> OpenChat.opened(group.id)
                Lifecycle.Event.ON_PAUSE -> OpenChat.closed(group.id)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            OpenChat.opened(group.id)
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            OpenChat.closed(group.id)
        }
    }
    val state by session.state.collectAsState()
    val rows = remember(state.items, myDisplayName) {
        timelineRows(state.items.map { item ->
            if (item is ChatTimelineItem.Message && item.isMine) {
                item.copy(message = item.message.copy(senderName = myDisplayName))
            } else item
        })
    }
    val sender = remember { AppRepositories.chatSender }
    val uploadQueue = remember { AppRepositories.attachmentUploads }
    val allUploads by uploadQueue.uploads.collectAsState()
    val sharedIds = state.items.filterIsInstance<ChatTimelineItem.File>().mapTo(mutableSetOf()) { it.file.id }
    val uploads = allUploads.filter {
        it.userId == currentUserId && it.groupId == group.id && it.fileId !in sharedIds
    }
    LaunchedEffect(group.id, sharedIds) { uploadQueue.acknowledge(sharedIds) }
    var selectedFile by remember(group.id) { mutableStateOf<SharedFile?>(null) }
    selectedFile?.let { file -> ChatAttachmentViewer(file, onDismiss = { selectedFile = null }) }

    var draft by rememberSaveable(group.id) { mutableStateOf("") }
    var localAttachment by remember(group.id) { mutableStateOf<LocalAttachment?>(null) }
    var showAttachmentSheet by remember { mutableStateOf(false) }
    var cameraPermissionDenied by remember { mutableStateOf(false) }
    val attachmentSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val coroutineScope = rememberCoroutineScope()

    // Persist the URI before reading bytes; uploads survive leaving the chat and can be retried.
    fun uploadFromUri(uri: Uri, fallbackName: String, fallbackType: String = "application/octet-stream") {
        uploadQueue.enqueue(group.id, uri.toString(), fallbackName,
            context.contentResolver.getType(uri) ?: fallbackType)
    }

    fun sendDraft() {
        val text = draft.trim()
        val documents = pendingDocuments
        val local = localAttachment
        if (text.isEmpty() && documents.isEmpty() && local == null) return
        try {
            documents.forEach { document -> uploadFromUri(Uri.parse(document.uri), document.displayName) }
            local?.let {
                uploadFromUri(it.uri, it.displayName,
                    if (it.kind == AttachmentKind.Video) "video/mp4" else "image/jpeg")
            }
        } catch (error: Exception) {
            session.report(error)
            return
        }
        if (text.isNotEmpty()) {
            val delivery = sender.send(group.id, text)
            coroutineScope.launch { delivery.await().onFailure(session::report) }
        }
        draft = ""
        localAttachment = null
        onPendingDocumentsCleared()
        focusManager.clearFocus()
    }

    fun openFile(file: SharedFile) {
        selectedFile = file
    }

    fun stageAttachment(uri: Uri, fallbackName: String, kind: AttachmentKind) {
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        localAttachment = LocalAttachment(
            uri = uri,
            displayName = resolveDisplayName(context, uri, fallbackName),
            kind = kind
        )
    }

    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let { stageAttachment(it, "Photo", AttachmentKind.Photo) }
    }
    val videoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let { stageAttachment(it, "Video", AttachmentKind.Video) }
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        cameraPermissionDenied = !granted
        if (granted) {
            onTakePhoto()
        }
    }

    fun closeAttachmentSheetAnd(action: () -> Unit) {
        coroutineScope.launch {
            attachmentSheetState.hide()
            showAttachmentSheet = false
            action()
        }
    }

    // Camera confirmation sends the original photo immediately through the persistent queue.
    LaunchedEffect(group.id, capturedCameraUri) {
        val uri = capturedCameraUri?.let(Uri::parse) ?: return@LaunchedEffect
        val fileName = "Photo ${LocalDateTime.now().format(photoNameFormatter)}.jpg"
        try {
            uploadFromUri(uri, fileName, "image/jpeg")
        } catch (error: Exception) {
            stageAttachment(uri, fileName, AttachmentKind.Camera)
            session.report(error)
        }
        onCapturedCameraPhotoConsumed()
    }

    // Keep the newest entry in view as messages arrive or are sent, and when the keyboard opens.
    val lastRowKey = rows.lastOrNull()?.key
    val imeVisible = WindowInsets.isImeVisible
    LaunchedEffect(lastRowKey, imeVisible, uploads.lastOrNull()?.id) {
        if (rows.isNotEmpty() || uploads.isNotEmpty()) {
            listState.animateScrollToItem(rows.size + uploads.size)
        }
    }

    // Fetch older pages whenever the top of the list is reached and the server can be asked.
    LaunchedEffect(session) {
        combine(
            snapshotFlow { listState.firstVisibleItemIndex == 0 },
            session.state
                .map { it.connection == ChatConnection.Live && !it.isLoadingOlder && it.hasOlderMessages }
                .distinctUntilChanged()
        ) { atTop, canLoad -> atTop && canLoad }
            .filter { it }
            .collect { session.loadOlder() }
    }

    LaunchedEffect(state.notice) {
        if (state.notice != null) {
            delay(NOTICE_MILLIS)
            session.clearNotice()
        }
    }

    if (showAttachmentSheet) {
        ModalBottomSheet(
            onDismissRequest = { showAttachmentSheet = false },
            sheetState = attachmentSheetState
        ) {
            AttachmentPicker(
                cameraPermissionDenied = cameraPermissionDenied,
                onFileClick = {
                    closeAttachmentSheetAnd {
                        onPickDocuments()
                    }
                },
                onPhotoClick = {
                    closeAttachmentSheetAnd {
                        photoPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    }
                },
                onVideoClick = {
                    closeAttachmentSheetAnd {
                        videoPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                        )
                    }
                },
                onCameraClick = {
                    closeAttachmentSheetAnd {
                        cameraPermissionDenied = false
                        if (
                            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                            PackageManager.PERMISSION_GRANTED
                        ) {
                            onTakePhoto()
                        } else {
                            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                    }
                }
            )
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
    ) {
        ConnectionBanner(connection = state.connection)
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item(key = "group-notice") {
                GroupNotice(
                    group = group,
                    isLoadingOlder = state.isLoadingOlder,
                    isStartOfChat = !state.hasOlderMessages
                )
            }
            items(
                items = rows,
                key = TimelineRow::key
            ) { row ->
                when (row) {
                    is TimelineRow.Day -> DayLabel(label = row.label)
                    is TimelineRow.Entry -> when (val item = row.item) {
                        is ChatTimelineItem.Message -> ChatBubble(
                            item = item,
                            onRetry = {
                                val delivery = sender.retry(group.id, item.message.clientId)
                                coroutineScope.launch { delivery.await().onFailure(session::report) }
                            }
                        )
                        is ChatTimelineItem.File -> FileBubble(
                            item = item,
                            images = images,
                            onOpen = { openFile(item.file) }
                        )
                    }
                }
            }
            items(items = uploads, key = { "upload:${it.id}" }) { upload ->
                AttachmentUploadBubble(upload,
                    onRetry = { uploadQueue.retry(upload.id) },
                    onDismiss = { uploadQueue.dismiss(upload.id) })
            }
        }

        state.notice?.let { notice ->
            NoticeRow(text = notice, onDismiss = session::clearNotice)
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(
                    onClick = {
                        focusManager.clearFocus()
                        showAttachmentSheet = true
                    }
                ) {
                    Icon(
                        imageVector = Icons.Outlined.AddCircleOutline,
                        contentDescription = "Add attachment",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (pendingDocuments.isNotEmpty() || localAttachment != null) {
                        LazyRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            contentPadding = PaddingValues(end = 4.dp)
                        ) {
                            items(
                                items = pendingDocuments,
                                key = PendingDocument::uri
                            ) { document ->
                                PendingAttachment(
                                    displayName = document.displayName,
                                    kind = AttachmentKind.File,
                                    sizeBytes = document.sizeBytes,
                                    onRemove = { onPendingDocumentRemoved(document.uri) }
                                )
                            }
                            localAttachment?.let { attachment ->
                                item(key = "local-attachment") {
                                    PendingAttachment(
                                        displayName = attachment.displayName,
                                        kind = attachment.kind,
                                        sizeBytes = null,
                                        onRemove = { localAttachment = null }
                                    )
                                }
                            }
                        }
                        if (pendingDocuments.isNotEmpty()) {
                            Text(
                                text = "${pendingDocuments.size}/$MAX_PENDING_DOCUMENTS documents",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    pendingDocumentError?.let { error ->
                        Text(
                            text = error,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    TextField(
                        value = draft,
                        onValueChange = { draft = it.take(ChatRepository.MAX_MESSAGE_LENGTH) },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Message") },
                        maxLines = 4,
                        shape = RoundedCornerShape(6.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { sendDraft() }),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surface,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            disabledIndicatorColor = Color.Transparent
                        )
                    )
                }
                val canSend = draft.isNotBlank() || pendingDocuments.isNotEmpty() || localAttachment != null
                IconButton(
                    onClick = { sendDraft() },
                    enabled = canSend
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send message",
                        tint = if (canSend) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ConnectionBanner(
    connection: ChatConnection,
    modifier: Modifier = Modifier
) {
    // A short connecting phase is normal, so the banner only appears when it drags on.
    var showConnecting by remember { mutableStateOf(false) }
    LaunchedEffect(connection) {
        showConnecting = false
        if (connection == ChatConnection.Connecting) {
            delay(1_500)
            showConnecting = true
        }
    }
    val text = when (connection) {
        ChatConnection.Live -> return
        ChatConnection.Connecting -> if (showConnecting) "Connecting…" else return
        ChatConnection.Offline -> "You're offline. Saved messages are shown; new ones arrive once you're back online."
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = if (connection == ChatConnection.Offline) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (connection == ChatConnection.Offline) {
                Icon(
                    imageVector = Icons.Outlined.CloudOff,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
            } else {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = if (connection == ChatConnection.Offline) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}

@Composable
private fun GroupNotice(
    group: CourseGroup,
    isLoadingOlder: Boolean,
    isStartOfChat: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (isLoadingOlder) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp
            )
        }
        if (isStartOfChat) {
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest
            ) {
                Text(
                    text = when (group.origin) {
                        GroupOrigin.Timetable -> "You joined ${group.name} from your timetable"
                        GroupOrigin.CreatedByUser -> "You created this group"
                        GroupOrigin.Joined -> "You joined this group"
                    },
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun DayLabel(
    label: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ChatBubble(
    item: ChatTimelineItem.Message,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    val message = item.message
    val isMine = item.isMine
    val isFailed = message.status == MessageStatus.Failed
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top
    ) {
        if (!isMine) {
            ChatAvatar(label = message.senderName)
            Spacer(Modifier.width(10.dp))
        }

        Surface(
            modifier = Modifier
                .widthIn(max = 292.dp)
                .then(if (isFailed) Modifier.clickable(onClick = onRetry) else Modifier),
            shape = bubbleShape(isMine),
            color = when {
                isFailed -> MaterialTheme.colorScheme.errorContainer
                isMine -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.surfaceContainerLowest
            },
            tonalElevation = if (isMine) 0.dp else 1.dp
        ) {
            val contentColor = when {
                isFailed -> MaterialTheme.colorScheme.onErrorContainer
                isMine -> MaterialTheme.colorScheme.onPrimary
                else -> MaterialTheme.colorScheme.onSurface
            }
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (!isMine) {
                    Text(
                        text = message.senderName,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = message.body,
                    style = MaterialTheme.typography.bodyLarge,
                    color = contentColor
                )
                Text(
                    text = when (message.status) {
                        MessageStatus.Sending -> "Sending…"
                        MessageStatus.Failed -> "Not sent. Tap to try again"
                        MessageStatus.Sent -> formatTime(message.createdAt)
                    },
                    modifier = Modifier.align(Alignment.End),
                    style = MaterialTheme.typography.labelSmall,
                    color = contentColor.copy(alpha = if (isFailed) 1f else 0.78f)
                )
            }
        }

        if (isMine) {
            Spacer(Modifier.width(10.dp))
            ChatAvatar(label = "You", isMine = true)
        }
    }
}

@Composable
private fun FileBubble(
    item: ChatTimelineItem.File,
    images: ChatImageLoader,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier
) {
    val file = item.file
    val isMine = item.isMine
    val isImage = file.mimeType.startsWith("image/") && !file.isPrivate
    val thumbnail by produceState<ImageBitmap?>(initialValue = null, key1 = file.id, key2 = isImage) {
        value = if (isImage) images.load(file.id) else null
    }
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top
    ) {
        if (!isMine) {
            ChatAvatar(label = file.uploaderName)
            Spacer(Modifier.width(10.dp))
        }

        Surface(
            modifier = Modifier
                .widthIn(min = 200.dp, max = 292.dp)
                .clickable(onClick = onOpen),
            shape = bubbleShape(isMine),
            color = if (isMine) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.surfaceContainerLowest
            },
            tonalElevation = if (isMine) 0.dp else 1.dp
        ) {
            val contentColor = if (isMine) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurface
            }
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (!isMine) {
                    Text(
                        text = file.uploaderName,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                thumbnail?.let { image ->
                    Image(
                        bitmap = image,
                        contentDescription = file.fileName,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp),
                        contentScale = ContentScale.Crop
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = fileKind(file.mimeType).icon(),
                        contentDescription = null,
                        tint = contentColor,
                        modifier = Modifier.size(30.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = file.fileName,
                            style = MaterialTheme.typography.bodyMedium,
                            color = contentColor,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${fileKind(file.mimeType).label()} · ${formatFileSize(file.sizeBytes)} · " +
                                "Tap to open",
                            style = MaterialTheme.typography.labelSmall,
                            color = contentColor.copy(alpha = 0.78f)
                        )
                    }
                }
                Text(
                    text = formatTime(file.createdAt),
                    modifier = Modifier.align(Alignment.End),
                    style = MaterialTheme.typography.labelSmall,
                    color = contentColor.copy(alpha = 0.78f)
                )
            }
        }

        if (isMine) {
            Spacer(Modifier.width(10.dp))
            ChatAvatar(label = "You", isMine = true)
        }
    }
}

@Composable
private fun NoticeRow(
    text: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = text,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = "Dismiss",
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }
}

@Composable
private fun PendingAttachment(
    displayName: String,
    kind: AttachmentKind,
    sizeBytes: Long?,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.widthIn(min = 180.dp, max = 240.dp),
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = kind.icon(),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = sizeBytes
                        ?.takeIf { it >= 0L }
                        ?.let { "${formatFileSize(it)} - Ready to send" }
                        ?: "Ready to send",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(
                onClick = onRemove,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = "Remove attachment"
                )
            }
        }
    }
}

@Composable
private fun AttachmentPicker(
    cameraPermissionDenied: Boolean,
    onFileClick: () -> Unit,
    onPhotoClick: () -> Unit,
    onVideoClick: () -> Unit,
    onCameraClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Text(
            text = "Share",
            style = MaterialTheme.typography.titleLarge
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            AttachmentOption(
                label = "File",
                icon = Icons.Outlined.Description,
                onClick = onFileClick
            )
            AttachmentOption(
                label = "Photo",
                icon = Icons.Outlined.Image,
                onClick = onPhotoClick
            )
            AttachmentOption(
                label = "Video",
                icon = Icons.Outlined.Videocam,
                onClick = onVideoClick
            )
            AttachmentOption(
                label = "Camera",
                icon = Icons.Outlined.PhotoCamera,
                onClick = onCameraClick
            )
        }
        if (cameraPermissionDenied) {
            Text(
                text = "Camera permission is required to take a photo.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun AttachmentOption(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.width(72.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        FilledTonalIconButton(
            onClick = onClick,
            modifier = Modifier.size(52.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1
        )
    }
}

@Composable
private fun ChatAvatar(
    label: String,
    isMine: Boolean = false,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.size(38.dp),
        shape = RoundedCornerShape(6.dp),
        color = if (isMine) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.primaryContainer
        }
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = label.trim().firstOrNull()?.uppercase() ?: "G",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (isMine) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onPrimaryContainer
                }
            )
        }
    }
}

private fun bubbleShape(isMine: Boolean): RoundedCornerShape = if (isMine) {
    RoundedCornerShape(topStart = 8.dp, topEnd = 2.dp, bottomStart = 8.dp, bottomEnd = 8.dp)
} else {
    RoundedCornerShape(topStart = 2.dp, topEnd = 8.dp, bottomStart = 8.dp, bottomEnd = 8.dp)
}

private fun fileKind(mimeType: String): AttachmentKind = when {
    mimeType.startsWith("image/") -> AttachmentKind.Photo
    mimeType.startsWith("video/") -> AttachmentKind.Video
    else -> AttachmentKind.File
}

private fun AttachmentKind.icon(): ImageVector = when (this) {
    AttachmentKind.File -> Icons.Outlined.Description
    AttachmentKind.Photo -> Icons.Outlined.Image
    AttachmentKind.Video -> Icons.Outlined.Videocam
    AttachmentKind.Camera -> Icons.Outlined.PhotoCamera
}

private fun AttachmentKind.label(): String = when (this) {
    AttachmentKind.File -> "File"
    AttachmentKind.Photo -> "Photo"
    AttachmentKind.Video -> "Video"
    AttachmentKind.Camera -> "Camera photo"
}

private fun formatFileSize(sizeBytes: Long): String = when {
    sizeBytes >= 1024L * 1024L -> "%.1f MB".format(sizeBytes / (1024.0 * 1024.0))
    sizeBytes >= 1024L -> "%.1f KB".format(sizeBytes / 1024.0)
    else -> "$sizeBytes B"
}

private fun formatTime(instant: Instant): String =
    instant.atZone(ZoneId.systemDefault()).toLocalTime().format(chatTimeFormatter)

private fun resolveDisplayName(
    context: Context,
    uri: Uri,
    fallback: String
): String {
    return runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameColumn >= 0 && cursor.moveToFirst()) cursor.getString(nameColumn) else null
        }
    }.getOrNull()?.takeIf(String::isNotBlank) ?: fallback
}

private fun logLatency(sample: LatencySample) {
    val kind = when (sample) {
        is LatencySample.Sent -> "send_confirmed"
        is LatencySample.Received -> "received"
    }
    Log.i(LATENCY_LOG_TAG, "$kind ms=${sample.millis}")
}
