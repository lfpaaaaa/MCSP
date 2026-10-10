package au.edu.unimelb.campuscompanion.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import au.edu.unimelb.campuscompanion.ui.model.CourseGroup
import au.edu.unimelb.campuscompanion.ui.model.GroupOrigin
import au.edu.unimelb.campuscompanion.ui.model.MAX_PENDING_DOCUMENTS
import au.edu.unimelb.campuscompanion.ui.model.PendingDocument
import java.time.ZoneId
import java.time.Instant
import au.edu.unimelb.campuscompanion.data.AttachmentUpload
import au.edu.unimelb.campuscompanion.data.model.SharedFile
import au.edu.unimelb.campuscompanion.data.AppRepositories
import au.edu.unimelb.campuscompanion.data.toUserMessage
import au.edu.unimelb.campuscompanion.data.model.ChatConnection
import au.edu.unimelb.campuscompanion.data.model.MessageStatus
import au.edu.unimelb.campuscompanion.data.repository.ChatRepository
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

private val chatTimeFormatter = DateTimeFormatter.ofPattern("MMM d, h:mm a")

private data class ChatUiMessage(
    val id: String,
    val clientId: String,
    val status: MessageStatus,
    val createdAt: Instant,
    val sender: String,
    val body: String,
    val time: String,
    val isMine: Boolean,
    val attachments: List<ChatAttachment> = emptyList()
)

private data class ChatAttachment(
    val displayName: String,
    val kind: AttachmentKind,
    val sizeBytes: Long? = null,
    val thumbnail: ImageBitmap? = null,
    val uri: String = ""
)

private data class ChatTimelineItem(
    val key: String,
    val createdAt: Instant,
    val message: ChatUiMessage? = null,
    val file: SharedFile? = null,
    val upload: AttachmentUpload? = null
)

private enum class AttachmentKind {
    File,
    Photo,
    Video,
    Camera
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupChatScreen(
    group: CourseGroup,
    myDisplayName: String,
    currentUserId: String,
    pendingDocuments: List<PendingDocument>,
    pendingDocumentError: String?,
    capturedCameraUri: String?,
    onPickDocuments: () -> Unit,
    onPendingDocumentRemoved: (String) -> Unit,
    onPendingDocumentsCleared: () -> Unit,
    onTakePhoto: () -> Unit,
    onCapturedCameraPhotoConsumed: () -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current
    val repository = remember { AppRepositories.chat }
    val sender = remember { AppRepositories.chatSender }
    val messageFlow = remember(group.id) { repository.observeMessages(group.id) }
    val storedMessages by messageFlow.collectAsState(initial = emptyList())
    val connection by repository.connection.collectAsState()
    val messages = storedMessages.filter { it.groupId == group.id }.map { message ->
        ChatUiMessage(
            id = "${message.senderId}:${message.clientId}",
            clientId = message.clientId,
            status = message.status,
            createdAt = message.createdAt,
            sender = if (message.senderId == currentUserId) myDisplayName else message.senderName,
            body = message.body,
            time = message.createdAt.atZone(ZoneId.systemDefault()).format(chatTimeFormatter),
            isMine = message.senderId == currentUserId
        )
    }
    val fileRepository = remember { AppRepositories.files }
    val uploadQueue = remember { AppRepositories.attachmentUploads }
    val fileFlow = remember(group.id) { fileRepository.observeFiles(group.id) }
    val storedFiles by fileFlow.collectAsState(initial = emptyList())
    val allUploads by uploadQueue.uploads.collectAsState()
    val sharedFiles = storedFiles.filter { it.groupId == group.id }
    val sharedIds = sharedFiles.mapTo(mutableSetOf()) { it.id }
    val uploads = allUploads.filter { it.userId == currentUserId && it.groupId == group.id && it.fileId !in sharedIds }
    val timeline = (messages.map { message ->
        ChatTimelineItem("message:${message.id}", message.createdAt, message = message)
    } + sharedFiles.map { ChatTimelineItem("file:${it.id}", it.createdAt, file = it) }
        + uploads.map { ChatTimelineItem("upload:${it.id}", Instant.parse(it.createdAt), upload = it) })
        .sortedWith(compareBy<ChatTimelineItem> { it.createdAt }.thenBy { it.key })
    var selectedFile by remember(group.id) { mutableStateOf<SharedFile?>(null) }
    LaunchedEffect(group.id, sharedIds) { uploadQueue.acknowledge(sharedIds) }
    selectedFile?.let { file -> ChatAttachmentViewer(file, onDismiss = { selectedFile = null }) }
    var draft by rememberSaveable(group.id) { mutableStateOf("") }
    var sendError by remember(group.id) { mutableStateOf<String?>(null) }
    var loadingOlder by remember(group.id) { mutableStateOf(false) }
    var hasOlder by remember(group.id) { mutableStateOf(true) }
    var pendingLocalAttachment by remember(group.id) { mutableStateOf<ChatAttachment?>(null) }
    var showAttachmentSheet by remember { mutableStateOf(false) }
    var cameraPermissionDenied by remember { mutableStateOf(false) }
    val attachmentSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val coroutineScope = rememberCoroutineScope()
    val pendingAttachments = pendingDocuments.map { document ->
        ChatAttachment(
            displayName = document.displayName,
            kind = AttachmentKind.File,
            uri = document.uri,
            sizeBytes = document.sizeBytes
        )
    } + listOfNotNull(pendingLocalAttachment)

    fun stageAttachment(
        displayName: String,
        kind: AttachmentKind,
        uri: Uri,
        thumbnail: ImageBitmap? = null
    ) {
        pendingLocalAttachment = ChatAttachment(
            displayName = displayName,
            kind = kind,
            uri = uri.toString(),
            thumbnail = thumbnail
        )
    }

    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let {
            stageAttachment(
                displayName = resolveDisplayName(context, it, "Photo"),
                kind = AttachmentKind.Photo,
                uri = it
            )
            runCatching { context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
    }
    val videoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let {
            stageAttachment(
                displayName = resolveDisplayName(context, it, "Video"),
                kind = AttachmentKind.Video,
                uri = it
            )
            runCatching { context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        cameraPermissionDenied = !granted
        if (granted) {
            onTakePhoto()
        }
    }

    fun sendDraft() {
        val message = draft.trim()
        if (message.isEmpty() && pendingAttachments.isEmpty()) return
        if (message.length > ChatRepository.MAX_MESSAGE_LENGTH) {
            sendError = "Messages can contain up to ${ChatRepository.MAX_MESSAGE_LENGTH} characters."
            return
        }
        sendError = null
        try {
            val prepared = pendingAttachments.map { attachment ->
                val uri = Uri.parse(attachment.uri)
                val type = context.contentResolver.getType(uri) ?: when (attachment.kind) {
                    AttachmentKind.Camera -> "image/jpeg"
                    AttachmentKind.Photo -> "image/jpeg"
                    AttachmentKind.Video -> "video/mp4"
                    AttachmentKind.File -> "application/octet-stream"
                }
                attachment to type
            }
            prepared.forEach { (attachment, type) ->
                uploadQueue.enqueue(group.id, attachment.uri, attachment.displayName, type)
            }
        } catch (e: Exception) {
            sendError = e.toUserMessage().body
            return
        }
        pendingLocalAttachment = null
        onPendingDocumentsCleared()
        draft = ""
        focusManager.clearFocus()
        if (message.isNotEmpty()) {
            val delivery = sender.send(group.id, message)
            coroutineScope.launch {
                delivery.await().onFailure { sendError = it.toUserMessage().body }
            }
        }
    }

    fun closeAttachmentSheetAnd(action: () -> Unit) {
        coroutineScope.launch {
            attachmentSheetState.hide()
            showAttachmentSheet = false
            action()
        }
    }

    LaunchedEffect(group.id, timeline.lastOrNull()?.key) {
        if (timeline.isNotEmpty()) {
            listState.animateScrollToItem(timeline.size + 1)
        }
    }

    LaunchedEffect(group.id, connection, storedMessages.lastOrNull { it.groupId == group.id && it.status == MessageStatus.Sent }?.id) {
        if (connection == ChatConnection.Live) {
            repository.markAsRead(group.id).onSuccess { AppRepositories.groups.refresh() }
        }
    }

    LaunchedEffect(group.id, capturedCameraUri) {
        val uri = capturedCameraUri?.let(Uri::parse) ?: return@LaunchedEffect
        val name = resolveDisplayName(context, uri, "Camera photo.jpg")
        try {
            // Camera confirmation is the send action; delivery continues outside this screen.
            uploadQueue.enqueue(group.id, uri.toString(), name,
                context.contentResolver.getType(uri) ?: "image/jpeg")
            sendError = null
        } catch (error: Exception) {
            // If it could not enter the queue, keep the original photo available for sending.
            stageAttachment(name, AttachmentKind.Camera, uri)
            sendError = error.toUserMessage().body
        }
        onCapturedCameraPhotoConsumed()
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
        if (connection != ChatConnection.Live) {
            Text(
                text = if (connection == ChatConnection.Offline) "Offline · showing saved messages" else "Connecting…",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelMedium
            )
        }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item(key = "group-notice") {
                GroupNotice(group = group)
            }
            item(key = "older-messages") {
                if (hasOlder) {
                    TextButton(enabled = !loadingOlder && connection == ChatConnection.Live, onClick = {
                        loadingOlder = true
                        coroutineScope.launch {
                            try {
                                repository.loadOlderMessages(group.id).fold(
                                    onSuccess = { hasOlder = it },
                                    onFailure = { sendError = it.toUserMessage().body }
                                )
                            } finally { loadingOlder = false }
                        }
                    }) { Text(if (loadingOlder) "Loading…" else "Load earlier messages") }
                }
            }
            items(items = timeline, key = ChatTimelineItem::key) { item ->
                item.message?.let { message ->
                    ChatBubble(message = message, onRetry = {
                        sendError = null
                        val delivery = sender.retry(group.id, message.clientId)
                        coroutineScope.launch {
                            delivery.await().onFailure { sendError = it.toUserMessage().body }
                        }
                    })
                }
                item.file?.let { file ->
                    SharedAttachmentBubble(file, file.uploaderId == currentUserId, onOpen = { selectedFile = file })
                }
                item.upload?.let { upload ->
                    AttachmentUploadBubble(upload, onRetry = { uploadQueue.retry(upload.id) },
                        onDismiss = { uploadQueue.dismiss(upload.id) })
                }
            }
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
                    if (pendingDocuments.isNotEmpty() || pendingLocalAttachment != null) {
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
                                    attachment = ChatAttachment(
                                        displayName = document.displayName,
                                        kind = AttachmentKind.File,
                                        sizeBytes = document.sizeBytes
                                    ),
                                    onRemove = { onPendingDocumentRemoved(document.uri) }
                                )
                            }
                            pendingLocalAttachment?.let { attachment ->
                                item(key = "local-attachment") {
                                    PendingAttachment(
                                        attachment = attachment,
                                        onRemove = { pendingLocalAttachment = null }
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
                    sendError?.let { error ->
                        Text(error, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error)
                    }
                    TextField(
                        value = draft,
                        onValueChange = { draft = it },
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
                val canSend = draft.isNotBlank() || pendingAttachments.isNotEmpty()
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
private fun GroupNotice(
    group: CourseGroup,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(4.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHighest
        ) {
            Text(
                text = when (group.origin) {
                    GroupOrigin.Timetable -> {
                        "You joined ${group.name} from your timetable"
                    }
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

@Composable
private fun ChatBubble(
    message: ChatUiMessage,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (message.isMine) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top
    ) {
        if (!message.isMine) {
            ChatAvatar(label = message.sender)
            Spacer(Modifier.width(10.dp))
        }

        Surface(
            modifier = Modifier.widthIn(max = 292.dp),
            shape = if (message.isMine) {
                RoundedCornerShape(
                    topStart = 8.dp,
                    topEnd = 2.dp,
                    bottomStart = 8.dp,
                    bottomEnd = 8.dp
                )
            } else {
                RoundedCornerShape(
                    topStart = 2.dp,
                    topEnd = 8.dp,
                    bottomStart = 8.dp,
                    bottomEnd = 8.dp
                )
            },
            color = if (message.isMine) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.surfaceContainerLowest
            },
            tonalElevation = if (message.isMine) 0.dp else 1.dp
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (!message.isMine) {
                    Text(
                        text = message.sender,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (message.body.isNotBlank()) {
                    Text(
                        text = message.body,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (message.isMine) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                    )
                }
                message.attachments.forEach { attachment ->
                    AttachmentContent(
                        attachment = attachment,
                        isMine = message.isMine
                    )
                }
                if (message.isMine) {
                    when (message.status) {
                        MessageStatus.Sending -> Text("Sending…", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary)
                        MessageStatus.Failed -> TextButton(onClick = onRetry) {
                            Text("Not sent · Retry", color = MaterialTheme.colorScheme.onPrimary)
                        }
                        MessageStatus.Sent -> Text("Sent", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.78f))
                    }
                }
                if (message.time.isNotBlank()) {
                    Text(
                        text = message.time,
                        modifier = Modifier.align(Alignment.End),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (message.isMine) {
                            MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.78f)
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
        }

        if (message.isMine) {
            Spacer(Modifier.width(10.dp))
            ChatAvatar(label = "You", isMine = true)
        }
    }
}

@Composable
private fun PendingAttachment(
    attachment: ChatAttachment,
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
                imageVector = attachment.kind.icon(),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = attachment.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = attachment.sizeBytes
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
private fun AttachmentContent(
    attachment: ChatAttachment,
    isMine: Boolean,
    modifier: Modifier = Modifier
) {
    val contentColor = if (isMine) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val secondaryColor = contentColor.copy(alpha = 0.78f)

    Column(
        modifier = modifier.widthIn(min = 180.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        attachment.thumbnail?.let { thumbnail ->
            Image(
                bitmap = thumbnail,
                contentDescription = attachment.displayName,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp),
                contentScale = ContentScale.Crop
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = attachment.kind.icon(),
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(30.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = attachment.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = contentColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = attachment.sizeBytes
                        ?.takeIf { it >= 0L }
                        ?.let { "${attachment.kind.label()} - ${formatFileSize(it)}" }
                        ?: attachment.kind.label(),
                    style = MaterialTheme.typography.labelSmall,
                    color = secondaryColor
                )
            }
        }
    }
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
