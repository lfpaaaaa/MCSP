package au.edu.unimelb.campuscompanion.ui.screens

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import au.edu.unimelb.campuscompanion.ui.model.AudioPlaybackBackend
import au.edu.unimelb.campuscompanion.ui.model.PlaybackAudioFocus

internal class AndroidAudioPlaybackBackend : AudioPlaybackBackend {
    private val media = MediaPlayer()
    override fun prepare(sourcePath: String, onPrepared: (Int) -> Unit, onCompleted: () -> Unit, onError: () -> Unit) {
        media.setAudioAttributes(playbackAudioAttributes())
        media.setOnPreparedListener { onPrepared(it.duration) }
        media.setOnCompletionListener { onCompleted() }
        media.setOnErrorListener { _, _, _ -> onError(); true }
        media.setDataSource(sourcePath)
        media.prepareAsync()
    }
    override fun start() = media.start()
    override fun pause() {
        if (media.isPlaying) media.pause()
    }
    override fun seekTo(positionMillis: Int) = media.seekTo(positionMillis)
    override fun positionMillis(): Int = media.currentPosition
    override fun release() {
        media.setOnPreparedListener(null)
        media.setOnCompletionListener(null)
        media.setOnErrorListener(null)
        media.release()
    }
}

internal class AndroidPlaybackAudioFocus(context: Context) : PlaybackAudioFocus {
    private val manager = requireNotNull(context.getSystemService(AudioManager::class.java))
    private var request: AudioFocusRequest? = null
    private var generation = 0

    override fun request(onInterrupted: () -> Unit): Boolean {
        abandon()
        val token = generation
        val next = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(playbackAudioAttributes())
            .setWillPauseWhenDucked(true)
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener({ change ->
                if (generation == token && change < 0) onInterrupted()
            }, Handler(Looper.getMainLooper()))
            .build()
        request = next
        return manager.requestAudioFocus(next) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    override fun abandon() {
        generation++
        request?.let { manager.abandonAudioFocusRequest(it) }
        request = null
    }
}

private fun playbackAudioAttributes(): AudioAttributes = AudioAttributes.Builder()
    .setUsage(AudioAttributes.USAGE_MEDIA)
    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
    .build()
