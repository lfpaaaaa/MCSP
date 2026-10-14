package au.edu.unimelb.campuscompanion.ui.screens

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import au.edu.unimelb.campuscompanion.ui.text.spokenDuration
import au.edu.unimelb.campuscompanion.ui.text.textResources
import android.Manifest
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import au.edu.unimelb.campuscompanion.R
import au.edu.unimelb.campuscompanion.ui.model.recordingLevel
import au.edu.unimelb.campuscompanion.ui.profile.AppPermission
import au.edu.unimelb.campuscompanion.ui.profile.AppPermissions
import kotlinx.coroutines.delay
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Foreground-only recording: switching apps or locking the screen stops and preserves it. */
@Composable
internal fun RecordingDialog(onDismiss: () -> Unit, onUse: (Uri, String) -> Unit) {
    val textResources = textResources()
    val context = LocalContext.current
    var savedPath by rememberSaveable { mutableStateOf<String?>(null) }
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var inputLevel by remember { mutableStateOf<Float?>(0f) }
    var started by remember { mutableLongStateOf(0L) }
    var elapsed by rememberSaveable { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var denied by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    fun stop() {
        val active = recorder ?: return
        recorder = null
        inputLevel = 0f
        elapsed = ((SystemClock.elapsedRealtime() - started) / 1000).toInt()
        runCatching { active.stop() }.onFailure {
            savedPath?.let { File(it).delete() }; savedPath = null
            error = context.getString(R.string.recording_too_short)
        }
        active.release()
    }
    fun start() {
        error = null; denied = false; inputLevel = 0f
        val directory = File(context.filesDir, "recordings").apply { mkdirs() }
        val file = File.createTempFile("recording-", ".m4a", directory)
        @Suppress("DEPRECATION")
        val active = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
        try {
            active.setAudioSource(MediaRecorder.AudioSource.MIC)
            active.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            active.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            active.setAudioChannels(1)
            active.setAudioSamplingRate(44_100)
            active.setAudioEncodingBitRate(96_000)
            active.setMaxDuration(600_000)
            active.setOutputFile(file.absolutePath)
            active.setOnInfoListener { _, what, _ -> if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) stop() }
            active.setOnErrorListener { _, _, _ -> stop(); error = context.getString(R.string.recording_failed) }
            active.prepare(); active.start()
            savedPath = file.absolutePath; recorder = active; elapsed = 0; started = SystemClock.elapsedRealtime()
        } catch (_: Exception) { active.release(); file.delete(); error = context.getString(R.string.recording_failed) }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if (allowed) start() else denied = true
    }
    fun discard() { stop(); savedPath?.let { File(it).delete() }; savedPath = null; onDismiss() }
    fun close() { if (savedPath != null || recorder != null) confirmDiscard = true else onDismiss() }
    val stopLatest by rememberUpdatedState(::stop)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) stopLatest() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); stopLatest() }
    }
    LaunchedEffect(recorder) {
        val active = recorder ?: return@LaunchedEffect
        // Never read a recorder after stop/release; this effect is also cancelled on disposal.
        while (recorder === active) {
            elapsed = ((SystemClock.elapsedRealtime() - started) / 1000).toInt()
            inputLevel = runCatching { recordingLevel(active.maxAmplitude) }.getOrNull()
            delay(100)
        }
    }
    AlertDialog(onDismissRequest = ::close, title = { Text(stringResource(R.string.record_audio)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.recording_help))
            // The timer is readable on demand; only start/stop changes are live announcements.
            Text(audioTime(elapsed), style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.semantics { contentDescription = textResources.getString(R.string.a11y_recording_duration, textResources.spokenDuration(elapsed)) })
            RecordingLevelMeter(level = inputLevel, active = recorder != null)
            Text(stringResource(if (recorder != null) R.string.recording_active else if (savedPath != null) R.string.a11y_recording_stopped else R.string.a11y_recording_ready),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            if (recorder == null) savedPath?.let { AudioAttachmentPlayer(File(it)) }
            if (denied) {
                Text(stringResource(R.string.recording_permission_denied), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                TextButton(onClick = { AppPermissions.openSettings(context, AppPermission.Microphone) }) {
                    Text(stringResource(R.string.recording_open_settings))
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            if (recorder != null) Button(onClick = ::stop) { Text(stringResource(R.string.recording_stop)) }
            else if (savedPath == null) Button(onClick = {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) start()
                else permission.launch(Manifest.permission.RECORD_AUDIO)
            }) { Text(stringResource(R.string.recording_start)) }
            else TextButton(onClick = { savedPath?.let { File(it).delete() }; savedPath = null; elapsed = 0; inputLevel = 0f }) {
                Text(stringResource(R.string.recording_again))
            }
        } },
        confirmButton = { TextButton(enabled = recorder == null && savedPath != null, onClick = {
            val file = savedPath?.let(::File) ?: return@TextButton
            val name = textResources.getString(R.string.recording_recording_m4a, LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH.mm.ss")))
            onUse(FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file), name)
        }) { Text(stringResource(R.string.recording_use)) } },
        dismissButton = { TextButton(onClick = ::close) { Text(stringResource(R.string.action_cancel)) } })
    if (confirmDiscard) AlertDialog(onDismissRequest = { confirmDiscard = false },
        title = { Text(stringResource(R.string.recording_discard_title)) },
        text = { Text(stringResource(R.string.recording_discard_body)) },
        confirmButton = { TextButton(onClick = ::discard) { Text(stringResource(R.string.action_discard)) } },
        dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.action_cancel)) } })
}
