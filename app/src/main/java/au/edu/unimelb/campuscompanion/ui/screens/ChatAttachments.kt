package au.edu.unimelb.campuscompanion.ui.screens

import android.app.KeyguardManager
import android.content.Context
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
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start) {
        Card(onClick = onOpen, modifier = Modifier.widthIn(max = 300.dp)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(if (isMine) "You" else file.uploaderName, style = MaterialTheme.typography.labelMedium)
                Text(file.fileName, style = MaterialTheme.typography.titleSmall)
                val kind = when {
                    file.mimeType.startsWith("image/") -> "Photo"
                    file.mimeType.startsWith("video/") -> "Video"
                    else -> "File"
                }
                Text("$kind · ${"%.1f".format(file.sizeBytes / 1024.0)} KB", style = MaterialTheme.typography.bodySmall)
                Text(if (file.isPrivate) "Unlock to view or download" else "Tap to view or download", color = MaterialTheme.colorScheme.primary)
                Text(file.createdAt.atZone(ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("MMM d, h:mm a")), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
internal fun AttachmentUploadBubble(upload: AttachmentUpload, onRetry: () -> Unit, onDismiss: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(upload.fileName)
            when (upload.phase) {
                AttachmentPhase.Uploading -> {
                    LinearProgressIndicator(progress = { upload.progress }, modifier = Modifier.fillMaxWidth())
                    Text("Uploading ${(upload.progress * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
                }
                AttachmentPhase.Completed -> Text("Uploaded · syncing…")
                AttachmentPhase.Failed -> {
                    Text(upload.error ?: "Upload failed", color = MaterialTheme.colorScheme.error)
                    Row {
                        TextButton(onClick = onRetry) { Text("Retry") }
                        TextButton(onClick = onDismiss) { Text("Remove") }
                    }
                }
            }
        }
    }
}

/** Retrieves protected bytes using a fresh signed URL, then previews or saves those bytes. */
@Suppress("DEPRECATION") // Device credential fallback also supports Android 8 (API 26).
@Composable
internal fun ChatAttachmentViewer(file: SharedFile, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var authorized by remember(file.id) { mutableStateOf(!file.isPrivate) }
    var localFile by remember(file.id) { mutableStateOf<File?>(null) }
    var bitmap by remember(file.id) { mutableStateOf<ImageBitmap?>(null) }
    var error by remember(file.id) { mutableStateOf<String?>(null) }
    var loading by remember(file.id) { mutableStateOf(false) }
    var saving by remember(file.id) { mutableStateOf(false) }
    var saved by remember(file.id) { mutableStateOf(false) }
    var attempt by remember(file.id) { mutableIntStateOf(0) }
    var player by remember(file.id) { mutableStateOf<VideoView?>(null) }
    val unlock = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        authorized = it.resultCode == android.app.Activity.RESULT_OK
    }
    val saveDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(file.mimeType)) { uri ->
        val source = localFile
        if (uri != null && source != null) scope.launch {
            saving = true
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        source.inputStream().use { it.copyTo(output) }
                    } ?: throw DataError.Validation("The selected location could not be written.")
                }
                saved = true
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.toUserMessage().body }
            finally { saving = false }
        }
    }
    LaunchedEffect(file.id, authorized, attempt) {
        if (!authorized) return@LaunchedEffect
        loading = true
        error = null
        try {
            val downloaded = downloadAttachment(context, AppRepositories.files, file)
            localFile = downloaded
            if (file.mimeType.startsWith("image/")) {
                bitmap = withContext(Dispatchers.IO) {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(downloaded.path, bounds)
                    var sample = 1
                    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1600) sample *= 2
                    BitmapFactory.decodeFile(downloaded.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
                }
                if (bitmap == null) error = "This image cannot be previewed. You can still download it."
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.toUserMessage().body }
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
                if (!authorized) {
                    Text("Confirm your device lock to access this private attachment.")
                    TextButton(onClick = {
                        val manager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
                        val intent = manager.createConfirmDeviceCredentialIntent("Private attachment", "Unlock to view or download")
                        if (intent == null) error = "Set up a device lock to open private attachments." else unlock.launch(intent)
                    }) { Text("Unlock") }
                }
                if (loading) CircularProgressIndicator()
                bitmap?.let { Image(it, contentDescription = file.fileName, modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp)) }
                localFile?.let { source ->
                    if (file.mimeType.startsWith("video/")) {
                        AndroidView(modifier = Modifier.fillMaxWidth().height(240.dp), factory = { viewContext ->
                            VideoView(viewContext).apply {
                                player = this
                                setMediaController(MediaController(viewContext).also { it.setAnchorView(this) })
                                setVideoURI(Uri.fromFile(source))
                                setOnPreparedListener { start() }
                                setOnErrorListener { _, _, _ -> error = "This video cannot be played here. Try opening or downloading it."; true }
                            }
                        })
                    }
                    TextButton(onClick = {
                        try {
                            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", source)
                            context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, file.mimeType)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                        } catch (_: android.content.ActivityNotFoundException) {
                            error = "No app can open this file type. Use Download to save it."
                        }
                    }) { Text("Open in another app") }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (error != null && localFile == null && authorized && !loading) {
                    TextButton(onClick = { attempt++ }) { Text("Retry") }
                }
                if (saved) Text("Saved")
            }
        },
        confirmButton = {
            TextButton(enabled = authorized && localFile != null && !saving, onClick = {
                saveDocument.launch(file.fileName.substringAfterLast('/').substringAfterLast('\\'))
            }) { Text(if (saving) "Saving…" else "Download") }
        },
        dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text("Close") } }
    )
}
