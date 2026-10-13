package au.edu.unimelb.campuscompanion.ui.screens

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import au.edu.unimelb.campuscompanion.ui.text.spokenDuration
import au.edu.unimelb.campuscompanion.ui.text.textResources

import android.media.MediaPlayer
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import au.edu.unimelb.campuscompanion.R
import kotlinx.coroutines.delay
import java.io.File

@Composable
internal fun AudioAttachmentPlayer(source: File) {
    val textResources = textResources()
    var player by remember(source.path) { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember(source.path) { mutableStateOf(false) }
    var position by remember(source.path) { mutableIntStateOf(0) }
    var duration by remember(source.path) { mutableIntStateOf(0) }
    var failed by remember(source.path) { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(source.path, lifecycle) {
        val media = MediaPlayer()
        player = media
        media.setOnPreparedListener { duration = it.duration }
        media.setOnCompletionListener { playing = false; position = duration }
        media.setOnErrorListener { _, _, _ -> failed = true; playing = false; true }
        runCatching { media.setDataSource(source.path); media.prepareAsync() }.onFailure { failed = true }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { runCatching { media.pause() }; playing = false }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); media.release(); player = null }
    }
    LaunchedEffect(playing) {
        while (playing) { position = runCatching { player?.currentPosition ?: 0 }.getOrDefault(0); delay(250) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (failed) Text(stringResource(R.string.audio_playback_failed), color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        else {
            Text(textResources.getString(R.string.audio_playback_position, audioTime(position / 1000), audioTime(duration / 1000)))
            Slider(value = position.toFloat().coerceIn(0f, duration.toFloat().coerceAtLeast(1f)),
                onValueChange = { position = it.toInt(); player?.seekTo(position) },
                valueRange = 0f..duration.toFloat().coerceAtLeast(1f), enabled = duration > 0,
                modifier = Modifier.fillMaxWidth().semantics {
                    contentDescription = textResources.getString(R.string.a11y_audio_position)
                    stateDescription = textResources.getString(R.string.a11y_audio_progress,
                        textResources.spokenDuration(position / 1000), textResources.spokenDuration(duration / 1000))
                })
            OutlinedButton(enabled = duration > 0, onClick = {
                runCatching {
                    if (playing) player?.pause() else { if (position >= duration) player?.seekTo(0); player?.start() }
                    playing = !playing
                }.onFailure { failed = true }
            }) { Text(stringResource(if (playing) R.string.audio_pause else R.string.audio_play)) }
        }
    }
}

internal fun audioTime(seconds: Int): String = "%02d:%02d".format(seconds / 60, seconds % 60)
