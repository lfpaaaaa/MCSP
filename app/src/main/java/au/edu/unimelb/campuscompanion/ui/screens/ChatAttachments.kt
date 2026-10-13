package au.edu.unimelb.campuscompanion.ui.screens

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import au.edu.unimelb.campuscompanion.ui.text.UiText
import au.edu.unimelb.campuscompanion.ui.text.textResources
import au.edu.unimelb.campuscompanion.R
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import au.edu.unimelb.campuscompanion.data.*
import au.edu.unimelb.campuscompanion.data.model.SharedFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun SharedAttachmentBubble(file: SharedFile, isMine: Boolean, onOpen: () -> Unit) {
    val textResources = textResources()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start) {
        Card(onClick = onOpen, modifier = Modifier.widthIn(max = 300.dp)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(if (isMine) textResources.getString(R.string.chat_attachments_you) else file.uploaderName, style = MaterialTheme.typography.labelMedium)
                Text(file.fileName, style = MaterialTheme.typography.titleSmall)
                val kind = when {
                    file.mimeType.startsWith("image/") -> textResources.getString(R.string.chat_attachments_photo)
                    file.mimeType.startsWith("video/") -> textResources.getString(R.string.chat_attachments_video)
                    else -> textResources.getString(R.string.chat_attachments_file)
                }
                Text(textResources.getString(R.string.attachment_kind_size, kind, formatFileSize(file.sizeBytes)), style = MaterialTheme.typography.bodySmall)
                Text(if (!file.allowDownloads && !isMine) textResources.getString(R.string.chat_attachments_tap_to_preview) else textResources.getString(R.string.chat_attachments_tap_to_view_or_download), color = MaterialTheme.colorScheme.primary)
                Text(file.createdAt.atZone(ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("MMM d, h:mm a")), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
internal fun AttachmentUploadBubble(upload: AttachmentUpload, onRetry: () -> Unit, onDismiss: () -> Unit) {
    val textResources = textResources()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(upload.fileName)
            when (upload.phase) {
                AttachmentPhase.Uploading -> {
                    LinearProgressIndicator(progress = { upload.progress }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = textResources.getString(R.string.a11y_uploading_file, upload.fileName) })
                    Text(textResources.getString(R.string.chat_attachments_uploading, (upload.progress * 100).toInt()), style = MaterialTheme.typography.labelSmall)
                }
                AttachmentPhase.Completed -> Text(textResources.getString(R.string.chat_attachments_uploaded_syncing), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                AttachmentPhase.Failed -> {
                    Text(upload.error ?: textResources.getString(R.string.chat_attachments_upload_failed), color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    Row {
                        TextButton(onClick = onRetry, modifier = Modifier.semantics { contentDescription = textResources.getString(R.string.a11y_retry_upload, upload.fileName) }) { Text(textResources.getString(R.string.action_retry)) }
                        TextButton(onClick = onDismiss, modifier = Modifier.semantics { contentDescription = textResources.getString(R.string.a11y_remove_file, upload.fileName) }) { Text(textResources.getString(R.string.chat_attachments_remove)) }
                    }
                }
            }
        }
    }
}

/** Retrieves protected bytes using a fresh signed URL, then previews or saves those bytes. */
@Composable
internal fun ChatAttachmentViewer(file: SharedFile, currentUserId: String, onDismiss: () -> Unit) {
    val textResources = textResources()
    val context = LocalContext.current
    val canDownload = file.canDownloadBy(currentUserId)
    val canPreview = file.mimeType.startsWith("image/") || file.mimeType.startsWith("video/") ||
        file.mimeType == "application/pdf" || file.mimeType.startsWith("text/") || file.mimeType.startsWith("audio/")
    val scope = rememberCoroutineScope()
    var localFile by remember(file.id) { mutableStateOf<File?>(null) }
    var bitmap by remember(file.id) { mutableStateOf<ImageBitmap?>(null) }
    var error by remember(file.id) { mutableStateOf<String?>(null) }
    var loading by remember(file.id) { mutableStateOf(false) }
    var saving by remember(file.id) { mutableStateOf(false) }
    var saved by remember(file.id) { mutableStateOf(false) }
    var attempt by remember(file.id) { mutableIntStateOf(0) }
    var player by remember(file.id) { mutableStateOf<VideoView?>(null) }
    val saveDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(file.mimeType)) { uri ->
        val source = localFile
        if (uri != null && source != null) scope.launch {
            saving = true
            try {
                AppRepositories.files.createDownloadUrl(file.id).getOrThrow()
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        source.inputStream().use { it.copyTo(output) }
                    } ?: throw DataError.Validation(UiText(R.string.validation_the_selected_location_could_not_be_written))
                }
                saved = true
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.toUserMessage().body.resolve(textResources) }
            finally { saving = false }
        }
    }
    LaunchedEffect(file.id, attempt) {
        if (!canPreview && !canDownload) return@LaunchedEffect
        loading = true
        error = null
        try {
            val downloaded = downloadAttachment(context, AppRepositories.files, file, forDownload = !canPreview)
            localFile = downloaded
            if (file.mimeType.startsWith("image/")) {
                bitmap = withContext(Dispatchers.IO) {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(downloaded.path, bounds)
                    var sample = 1
                    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1600) sample *= 2
                    BitmapFactory.decodeFile(downloaded.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
                }
                if (bitmap == null) error = textResources.getString(R.string.chat_attachments_this_image_cannot_be_previewed_you_can_still)
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.toUserMessage().body.resolve(textResources) }
        finally { loading = false }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(file.id, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) player?.pause()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); player?.stopPlayback() }
    }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(file.fileName) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!file.allowDownloads) Text(if (canDownload) textResources.getString(R.string.chat_attachments_only_you_can_download_this_attachment) else textResources.getString(R.string.chat_attachments_downloads_disabled_by_the_uploader))
                if (!canPreview) Text(textResources.getString(R.string.chat_attachments_in_app_preview_is_not_available_for_this))
                if (loading) CircularProgressIndicator(Modifier.semantics { contentDescription = textResources.getString(R.string.a11y_loading_file, file.fileName) })
                bitmap?.let { Image(it, contentDescription = file.fileName, modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp)) }
                localFile?.let { source ->
                    if (file.mimeType.startsWith("audio/")) AudioAttachmentPlayer(source)
                    if (file.mimeType == "application/pdf") PdfAttachmentPreview(source) { error = it }
                    if (file.mimeType.startsWith("text/")) TextAttachmentPreview(source) { error = it }
                    if (file.mimeType.startsWith("video/")) {
                        AndroidView(modifier = Modifier.fillMaxWidth().height(240.dp), factory = { viewContext ->
                            VideoView(viewContext).apply {
                                player = this
                                setMediaController(MediaController(viewContext).also { it.setAnchorView(this) })
                                setVideoURI(Uri.fromFile(source))
                                setOnPreparedListener { start() }
                                setOnErrorListener { _, _, _ -> error = textResources.getString(R.string.chat_attachments_this_video_cannot_be_played_here_try_opening); true }
                            }
                        })
                    }
                    if (canDownload) TextButton(onClick = {
                        scope.launch {
                            try {
                                AppRepositories.files.createDownloadUrl(file.id).getOrThrow()
                                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", source)
                                context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, file.mimeType)
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                            } catch (e: CancellationException) { throw e }
                            catch (_: android.content.ActivityNotFoundException) {
                                error = textResources.getString(R.string.chat_attachments_no_app_can_open_this_file_type_use)
                            } catch (e: Exception) { error = e.toUserMessage().body.resolve(textResources) }
                        }
                    }) { Text(textResources.getString(R.string.chat_attachments_open_in_another_app)) }
                }
                if (file.mimeType.startsWith("audio/")) TranscriptPanel(file, currentUserId)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                if (error != null && localFile == null && !loading) {
                    TextButton(onClick = { attempt++ }) { Text(textResources.getString(R.string.action_retry)) }
                }
                if (saved) Text(textResources.getString(R.string.chat_attachments_saved))
            }
        },
        confirmButton = {
            if (canDownload) TextButton(enabled = localFile != null && !saving, onClick = {
                scope.launch {
                    try {
                        AppRepositories.files.createDownloadUrl(file.id).getOrThrow()
                        saveDocument.launch(file.fileName.substringAfterLast('/').substringAfterLast('\\'))
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { error = e.toUserMessage().body.resolve(textResources) }
                }
            }) { Text(if (saving) textResources.getString(R.string.chat_attachments_saving) else textResources.getString(R.string.chat_attachments_download)) }
        },
        dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text(textResources.getString(R.string.chat_attachments_close)) } }
    )
}
