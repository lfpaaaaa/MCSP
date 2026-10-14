package au.edu.unimelb.campuscompanion.ui.model

import au.edu.unimelb.campuscompanion.data.model.SharedFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class AudioPlaybackPhase { Preparing, Ready, Playing, Failed, Released }

internal data class AudioPlaybackState(
    val phase: AudioPlaybackPhase = AudioPlaybackPhase.Preparing,
    val positionMillis: Int = 0,
    val durationMillis: Int = 0,
    val error: String? = null
)

/** Android MediaPlayer is behind this interface so its lifecycle can be tested without a phone. */
internal interface AudioPlaybackBackend {
    fun prepare(sourcePath: String, onPrepared: (Int) -> Unit, onCompleted: () -> Unit, onError: () -> Unit)
    fun start()
    fun pause()
    fun seekTo(positionMillis: Int)
    fun positionMillis(): Int
    fun release()
}

internal interface PlaybackAudioFocus {
    fun request(onInterrupted: () -> Unit): Boolean
    fun abandon()
}

/** One active audio preview across the composer, recorder review and shared attachment viewer. */
internal class AudioPlaybackCoordinator {
    private var active: AudioPlaybackController? = null

    @Synchronized
    fun activate(controller: AudioPlaybackController) {
        if (active === controller) return
        val previous = active
        active = controller
        previous?.pause()
    }

    @Synchronized
    fun release(controller: AudioPlaybackController) {
        if (active === controller) active = null
    }

    @Synchronized
    fun pauseActive() {
        active?.pause()
    }
}

private val sharedAudioPlayback = AudioPlaybackCoordinator()

/** Call before microphone capture starts so an existing preview cannot feed the recording. */
internal fun stopAllAudioPlayback() = sharedAudioPlayback.pauseActive()

internal class AudioPlaybackController(
    private val sourcePath: String,
    private val createBackend: () -> AudioPlaybackBackend,
    private val audioFocus: PlaybackAudioFocus,
    private val coordinator: AudioPlaybackCoordinator = sharedAudioPlayback
) {
    private val mutableState = MutableStateFlow(AudioPlaybackState())
    val state = mutableState.asStateFlow()
    private var backend: AudioPlaybackBackend? = null
    private var generation = 0
    private var disposed = false

    fun prepare() {
        if (disposed) return
        releaseBackend()
        val token = generation
        mutableState.value = mutableState.value.copy(phase = AudioPlaybackPhase.Preparing, error = null)
        try {
            val media = createBackend()
            backend = media
            media.prepare(sourcePath, onPrepared = { duration ->
                if (isCurrent(media, token)) {
                    if (duration <= 0) fail()
                    else {
                        val position = mutableState.value.positionMillis.coerceIn(0, duration)
                        try {
                            if (position > 0) media.seekTo(position)
                            mutableState.value = AudioPlaybackState(AudioPlaybackPhase.Ready, position, duration)
                        } catch (_: Exception) { fail() }
                    }
                }
            }, onCompleted = {
                if (isCurrent(media, token)) {
                    audioFocus.abandon()
                    coordinator.release(this)
                    mutableState.value = mutableState.value.copy(
                        phase = AudioPlaybackPhase.Ready, positionMillis = mutableState.value.durationMillis)
                }
            }, onError = { if (isCurrent(media, token)) fail() })
        } catch (_: Exception) { fail() }
    }

    fun play() {
        if (disposed || mutableState.value.phase != AudioPlaybackPhase.Ready) return
        val media = backend ?: return
        try {
            if (!audioFocus.request(::pause)) {
                fail("Audio is in use by another app. Tap Retry to try again.")
                return
            }
            coordinator.activate(this)
            if (mutableState.value.positionMillis >= mutableState.value.durationMillis) {
                media.seekTo(0)
                mutableState.value = mutableState.value.copy(positionMillis = 0)
            }
            media.start()
            mutableState.value = mutableState.value.copy(phase = AudioPlaybackPhase.Playing)
        } catch (_: Exception) { fail() }
    }

    /** Focus gain and Activity resume never call play; resuming always needs a user tap. */
    fun pause() {
        if (disposed) return
        try {
            if (mutableState.value.phase == AudioPlaybackPhase.Playing) {
                backend?.pause()
                val position = backend?.positionMillis()?.coerceIn(0, mutableState.value.durationMillis)
                    ?: mutableState.value.positionMillis
                mutableState.value = mutableState.value.copy(phase = AudioPlaybackPhase.Ready, positionMillis = position)
            }
            audioFocus.abandon()
            coordinator.release(this)
        } catch (_: Exception) { fail() }
    }

    fun seekTo(positionMillis: Int) {
        if (disposed || mutableState.value.phase !in listOf(AudioPlaybackPhase.Ready, AudioPlaybackPhase.Playing)) return
        val position = positionMillis.coerceIn(0, mutableState.value.durationMillis)
        try {
            backend?.seekTo(position)
            mutableState.value = mutableState.value.copy(positionMillis = position)
        } catch (_: Exception) { fail() }
    }

    fun pollPosition() {
        if (disposed || mutableState.value.phase != AudioPlaybackPhase.Playing) return
        try {
            mutableState.value = mutableState.value.copy(
                positionMillis = (backend?.positionMillis() ?: 0).coerceIn(0, mutableState.value.durationMillis))
        } catch (_: Exception) { fail() }
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        releaseBackend()
        mutableState.value = mutableState.value.copy(phase = AudioPlaybackPhase.Released)
    }

    private fun isCurrent(media: AudioPlaybackBackend, token: Int) =
        !disposed && backend === media && generation == token

    private fun fail(message: String = "This audio could not be played. Tap Retry.") {
        releaseBackend()
        mutableState.value = mutableState.value.copy(phase = AudioPlaybackPhase.Failed, error = message)
    }

    private fun releaseBackend() {
        generation++
        val previous = backend
        backend = null
        runCatching { previous?.release() }
        runCatching { audioFocus.abandon() }
        coordinator.release(this)
    }
}

internal fun formatAudioDuration(durationMillis: Long): String {
    val seconds = durationMillis.coerceAtLeast(0) / 1_000
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}

internal fun sharedAudioLabel(file: SharedFile): String? = when {
    !file.mimeType.startsWith("audio/", ignoreCase = true) -> null
    file.isVoiceRecording -> "Voice message"
    else -> "Audio"
}
