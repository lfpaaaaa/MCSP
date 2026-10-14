package au.edu.unimelb.campuscompanion.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import au.edu.unimelb.campuscompanion.ui.model.AudioPlaybackController
import au.edu.unimelb.campuscompanion.ui.model.AudioPlaybackPhase
import au.edu.unimelb.campuscompanion.ui.model.formatAudioDuration
import kotlinx.coroutines.delay
import java.io.File

@Composable
internal fun AudioAttachmentPlayer(
    source: File,
    modifier: Modifier = Modifier,
    controllerFactory: ((String) -> AudioPlaybackController)? = null
) {
    val context = LocalContext.current.applicationContext
    val controller = remember(source.absolutePath, controllerFactory, context) {
        controllerFactory?.invoke(source.absolutePath) ?: AudioPlaybackController(source.absolutePath,
            createBackend = { AndroidAudioPlaybackBackend() }, audioFocus = AndroidPlaybackAudioFocus(context))
    }
    val state by controller.state.collectAsState()
    var scrubPosition by remember(controller) { mutableStateOf<Float?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(controller, lifecycle) {
        controller.prepare()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) controller.pause()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); controller.dispose() }
    }
    LaunchedEffect(controller, state.phase) {
        while (state.phase == AudioPlaybackPhase.Playing) {
            controller.pollPosition()
            delay(250)
        }
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when (state.phase) {
            AudioPlaybackPhase.Preparing -> {
                CircularProgressIndicator(modifier = Modifier.semantics { contentDescription = "Preparing audio" })
                Text("Preparing audio…")
            }
            AudioPlaybackPhase.Failed -> {
                Text(state.error ?: "This audio could not be played. Tap Retry.", color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                TextButton(onClick = { scrubPosition = null; controller.prepare() }) { Text("Retry playback") }
            }
            AudioPlaybackPhase.Released -> Unit
            AudioPlaybackPhase.Ready, AudioPlaybackPhase.Playing -> {
                val position = scrubPosition?.toLong() ?: state.positionMillis.toLong()
                Text("${formatAudioDuration(position)} / ${formatAudioDuration(state.durationMillis.toLong())}")
                Slider(
                    value = (scrubPosition ?: state.positionMillis.toFloat()).coerceIn(0f, state.durationMillis.toFloat().coerceAtLeast(1f)),
                    onValueChange = { scrubPosition = it },
                    onValueChangeFinished = { scrubPosition?.let { controller.seekTo(it.toInt()) }; scrubPosition = null },
                    valueRange = 0f..state.durationMillis.toFloat().coerceAtLeast(1f),
                    enabled = state.durationMillis > 0,
                    modifier = Modifier.fillMaxWidth().semantics {
                        contentDescription = "Audio playback position"
                        stateDescription = "${formatAudioDuration(position)} of ${formatAudioDuration(state.durationMillis.toLong())}"
                    }
                )
                OutlinedButton(onClick = {
                    if (state.phase == AudioPlaybackPhase.Playing) controller.pause() else controller.play()
                }) { Text(if (state.phase == AudioPlaybackPhase.Playing) "Pause" else "Play") }
            }
        }
    }
}
