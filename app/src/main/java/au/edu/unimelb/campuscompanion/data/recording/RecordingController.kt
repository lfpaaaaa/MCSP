package au.edu.unimelb.campuscompanion.data.recording

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import kotlin.math.sqrt

sealed interface RecordingState {
    data class Ready(val warning: String? = null) : RecordingState
    data class RequestingPermission(val requestId: Long) : RecordingState
    data class Recording(val elapsedMillis: Long = 0, val level: Float = 0f) : RecordingState
    data class Review(val draft: RecordingDraft, val warning: String? = null) : RecordingState
    data class Failed(val message: String) : RecordingState
}

interface RecordingDevice {
    fun start()
    fun amplitude(): Int
    fun stop()
    fun release()
}

fun interface RecordingDeviceFactory {
    fun create(output: File, onDurationLimit: () -> Unit, onError: () -> Unit): RecordingDevice
}

/** Foreground capture ownership, independent of Compose and Android lifecycle callbacks. */
class RecordingController(
    private val userId: String,
    private val groupId: String,
    private val store: RecordingDraftStore,
    private val devices: RecordingDeviceFactory,
    private val scope: CoroutineScope,
    private val monotonicMillis: () -> Long,
    private val newRecordingId: () -> String = { UUID.randomUUID().toString() }
) {
    private val mutableState = MutableStateFlow<RecordingState>(RecordingState.Ready())
    val state: StateFlow<RecordingState> = mutableState.asStateFlow()
    private var closed = false
    private var permissionId = 0L
    private var recorderGeneration = 0L
    private var recorder: RecordingDevice? = null
    private var capture: RecordingCapture? = null
    private var cleanupCapture: RecordingCapture? = null
    private var ownedDraft: RecordingDraft? = null
    private var sampling: Job? = null
    private var startedAt = 0L
    private var smoothedLevel = 0f

    init {
        val restored = runCatching { store.load(userId, groupId) }.getOrElse {
            RecordingLoadResult(warning = "The saved recording is unavailable. Try opening recording again.")
        }
        ownedDraft = restored.draft
        mutableState.value = restored.draft?.let { RecordingState.Review(it, restored.warning) }
            ?: RecordingState.Ready(restored.warning)
    }

    /** Returns the token the UI must associate with its microphone permission request. */
    @Synchronized
    fun requestStart(hasPermission: Boolean): Long? {
        if (closed || mutableState.value is RecordingState.Recording ||
            mutableState.value is RecordingState.RequestingPermission ||
            mutableState.value is RecordingState.Review) return null
        if (!hasPermission) {
            val token = ++permissionId
            mutableState.value = RecordingState.RequestingPermission(token)
            return token
        }
        startCapture()
        return null
    }

    @Synchronized
    fun permissionResult(requestId: Long, granted: Boolean) {
        val waiting = mutableState.value as? RecordingState.RequestingPermission ?: return
        if (closed || waiting.requestId != requestId || requestId != permissionId) return
        if (granted) startCapture()
        else mutableState.value = RecordingState.Failed("Microphone permission was denied. Allow it to record a voice message.")
    }

    @Synchronized
    fun stop() {
        if (!closed) finishCapture()
    }

    @Synchronized
    fun onBackground() {
        if (closed) return
        if (mutableState.value is RecordingState.RequestingPermission) {
            permissionId++
            mutableState.value = RecordingState.Ready("Recording did not start. Tap Start when the app is open.")
        } else finishCapture("Recording stopped when the app left the foreground.")
    }

    /** Explicit Cancel/Record again can discard owned drafts, but cannot remove queued clips. */
    @Synchronized
    fun cancel(): Boolean {
        if (closed) return false
        permissionId++
        finishCapture()
        try {
            cleanupCapture?.let { store.discardCapture(it); cleanupCapture = null }
            ownedDraft?.let { draft ->
                if (!store.discard(draft) && store.load(userId, groupId).draft?.id == draft.id) {
                    throw IllegalStateException("The recording draft could not be removed")
                }
            }
        } catch (_: Exception) {
            mutableState.value = ownedDraft?.let {
                RecordingState.Review(it, "The recording could not be removed. Tap Cancel to retry.")
            } ?: RecordingState.Failed("The recording could not be removed. Tap Cancel to retry.")
            return false
        }
        ownedDraft = null
        mutableState.value = RecordingState.Ready()
        return true
    }

    @Synchronized
    fun recordAgain() = cancel()

    @Synchronized
    fun stage(): RecordingDraft? {
        if (closed) return null
        val review = mutableState.value as? RecordingState.Review ?: return null
        return try {
            store.stage(review.draft).also {
                ownedDraft = it
                mutableState.value = RecordingState.Review(it, review.warning)
            }
        } catch (_: Exception) {
            mutableState.value = RecordingState.Failed("The recording draft could not be saved. Reopen recording to review it again.")
            null
        }
    }

    /** Finalizes a valid capture before disposal; stopped review/staged drafts survive recreation. */
    @Synchronized
    fun dispose() {
        if (closed) return
        permissionId++
        finishCapture("Recording stopped when its screen closed.")
        sampling?.cancel()
        if (mutableState.value is RecordingState.RequestingPermission) {
            mutableState.value = RecordingState.Ready("Recording did not start.")
        }
        closed = true
    }

    /** Samples real recorder amplitude; public for deterministic controller tests. */
    @Synchronized
    fun sample() {
        if (closed || mutableState.value !is RecordingState.Recording) return
        val elapsed = (monotonicMillis() - startedAt).coerceAtLeast(0)
        if (elapsed >= MAX_RECORDING_DURATION_MILLIS) {
            finishCapture("The ten-minute recording limit was reached.")
            return
        }
        try {
            val level = recordingInputLevel(recorder?.amplitude() ?: 0)
            val weight = if (level > smoothedLevel) 0.55f else 0.30f
            smoothedLevel += (level - smoothedLevel) * weight
            mutableState.value = RecordingState.Recording(elapsed, smoothedLevel.coerceIn(0f, 1f))
        } catch (_: Exception) {
            finishCapture("Microphone input stopped. Review the saved clip before using it.")
        }
    }

    private fun startCapture() {
        permissionId++
        val generation = ++recorderGeneration
        try {
            val pending = store.beginCapture(userId, groupId, newRecordingId(), "Voice message.m4a")
            capture = pending
            val device = devices.create(pending.file,
                onDurationLimit = { deviceEnded(generation, "The ten-minute recording limit was reached.") },
                onError = { deviceEnded(generation, "The microphone stopped. Review the saved clip before using it.") })
            recorder = device
            device.start()
            startedAt = monotonicMillis()
            smoothedLevel = 0f
            mutableState.value = RecordingState.Recording()
            sampling = scope.launch {
                while (isActive) {
                    delay(AMPLITUDE_INTERVAL_MILLIS)
                    sample()
                }
            }
        } catch (_: Exception) {
            releaseCapture(discard = true)
            mutableState.value = RecordingState.Failed("Recording could not start. Check microphone access and try again.")
        }
    }

    @Synchronized
    private fun deviceEnded(generation: Long, warning: String) {
        if (closed || generation != recorderGeneration || mutableState.value !is RecordingState.Recording) return
        finishCapture(warning)
    }

    private fun finishCapture(warning: String? = null) {
        val pending = capture ?: return
        // Clear ownership first: manual stop, lifecycle, limit and native callbacks may all arrive.
        capture = null
        sampling?.cancel()
        sampling = null
        val device = recorder
        recorder = null
        recorderGeneration++
        try { device?.stop() } catch (_: Exception) {
            // Native max-duration stop is asynchronous and may already have finalized the file.
            // Validate the real container below rather than discarding a valid saved clip.
        } finally { runCatching { device?.release() } }
        try {
            val draft = store.finishCapture(pending)
            ownedDraft = draft
            mutableState.value = RecordingState.Review(draft, warning)
        } catch (_: Exception) {
            cleanupCapture = if (runCatching { store.discardCapture(pending) }.isFailure) pending else null
            ownedDraft = null
            mutableState.value = RecordingState.Failed("The recording was too short or interrupted. Record for at least one second and try again.")
        }
    }

    private fun releaseCapture(discard: Boolean) {
        sampling?.cancel()
        sampling = null
        recorderGeneration++
        val device = recorder
        recorder = null
        try { device?.stop() } catch (_: Exception) { }
        finally { runCatching { device?.release() } }
        val pending = capture
        capture = null
        if (discard && pending != null) {
            cleanupCapture = if (runCatching { store.discardCapture(pending) }.isFailure) pending else null
        }
    }

    private companion object { const val AMPLITUDE_INTERVAL_MILLIS = 100L }
}

/** Relative input, not a calibrated decibel measurement. Zero/silence stays at zero. */
fun recordingInputLevel(amplitude: Int): Float = sqrt(amplitude.coerceIn(0, 32_767) / 32_767f)
