package au.edu.unimelb.campuscompanion.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import au.edu.unimelb.campuscompanion.data.recording.AndroidRecording
import au.edu.unimelb.campuscompanion.data.recording.RecordingController
import au.edu.unimelb.campuscompanion.data.recording.RecordingDraft
import au.edu.unimelb.campuscompanion.data.recording.RecordingDraftStore
import au.edu.unimelb.campuscompanion.data.recording.RecordingState
import au.edu.unimelb.campuscompanion.ui.model.formatAudioDuration
import au.edu.unimelb.campuscompanion.ui.model.stopAllAudioPlayback
import au.edu.unimelb.campuscompanion.ui.profile.AppPermission
import au.edu.unimelb.campuscompanion.ui.profile.AppPermissions
import kotlinx.coroutines.CoroutineScope
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Capture is foreground only. Closing/backgrounding finalizes a valid clip in the draft store. */
@Composable
internal fun RecordingDialog(
    userId: String,
    groupId: String,
    store: RecordingDraftStore,
    onDismiss: () -> Unit,
    onUse: (RecordingDraft) -> Unit,
    controllerFactory: ((CoroutineScope) -> RecordingController)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = remember(userId, groupId, store, controllerFactory) {
        controllerFactory?.invoke(scope) ?: AndroidRecording.createController(context, userId, groupId, scope, store)
    }
    val state by controller.state.collectAsState()
    val active = remember(controller) { AtomicBoolean(true) }
    var pendingPermission by remember(controller) { mutableStateOf<Long?>(null) }
    var confirmCancel by remember(controller) { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(controller, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) controller.onBackground()
        }
        lifecycle.addObserver(observer)
        onDispose {
            active.set(false)
            lifecycle.removeObserver(observer)
            controller.dispose()
        }
    }
    val microphonePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val request = pendingPermission
        pendingPermission = null
        if (active.get() && request != null) {
            if (granted) stopAllAudioPlayback()
            controller.permissionResult(request, granted)
        }
    }
    fun requestCancel() {
        if (state is RecordingState.Recording || state is RecordingState.Review) confirmCancel = true
        else if (controller.cancel()) onDismiss()
    }
    val hasMicrophone = remember(context) { context.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE) }
    AlertDialog(
        onDismissRequest = ::requestCancel,
        title = { Text("Voice recording") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Record up to 10 minutes. Switching apps or locking your phone stops recording and keeps a valid draft.",
                    style = MaterialTheme.typography.bodySmall)
                Text(when (state) {
                    is RecordingState.Recording -> "Recording"
                    is RecordingState.Review -> "Ready to review"
                    is RecordingState.RequestingPermission -> "Microphone access requested"
                    is RecordingState.Failed -> "Recording unavailable"
                    is RecordingState.Ready -> "Ready to record"
                }, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.labelLarge)
                when (val current = state) {
                    is RecordingState.Ready -> current.warning?.let { Text(it) }
                    is RecordingState.RequestingPermission -> Text("Waiting for microphone permission…")
                    is RecordingState.Failed -> {
                        Text(current.message, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { AppPermissions.openSettings(context, AppPermission.Microphone) }) {
                            Text("Microphone settings")
                        }
                    }
                    is RecordingState.Recording -> {
                        Text("${formatAudioDuration(current.elapsedMillis)} / 10:00", style = MaterialTheme.typography.headlineMedium)
                        RecordingLevelMeter(current.level, active = true)
                    }
                    is RecordingState.Review -> {
                        current.warning?.let { Text(it) }
                        Text("Recorded ${formatAudioDuration(current.draft.durationMillis)}")
                        AudioAttachmentPlayer(File(current.draft.localPath))
                        TextButton(onClick = { stopAllAudioPlayback(); controller.recordAgain() }) { Text("Record again") }
                        Text("Use recording adds this clip to your message. Tap Send to share it.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (state !is RecordingState.Recording && state !is RecordingState.Review) {
                    RecordingLevelMeter(level = null, active = false)
                }
                if (!hasMicrophone) Text("This device has no microphone.", color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            when (state) {
                is RecordingState.Recording -> Button(onClick = controller::stop) { Text("Stop") }
                is RecordingState.Review -> Button(onClick = {
                    controller.stage()?.let { draft -> stopAllAudioPlayback(); onUse(draft) }
                }) { Text("Use recording") }
                else -> Button(enabled = hasMicrophone && state !is RecordingState.RequestingPermission, onClick = {
                    stopAllAudioPlayback()
                    val token = controller.requestStart(AppPermissions.isGranted(context, Manifest.permission.RECORD_AUDIO))
                    if (token != null) {
                        pendingPermission = token
                        microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }) { Text("Start") }
            }
        },
        dismissButton = { TextButton(onClick = ::requestCancel) { Text("Cancel") } }
    )
    if (confirmCancel) {
        AlertDialog(onDismissRequest = { confirmCancel = false },
            title = { Text("Discard recording?") },
            text = { Text("This removes the unsent recording from this device.") },
            confirmButton = { TextButton(onClick = {
                stopAllAudioPlayback()
                confirmCancel = false
                if (controller.cancel()) onDismiss()
            }) { Text("Discard") } },
            dismissButton = { TextButton(onClick = { confirmCancel = false }) { Text("Keep recording") } })
    }
}
