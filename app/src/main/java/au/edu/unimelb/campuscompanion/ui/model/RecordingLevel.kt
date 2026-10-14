package au.edu.unimelb.campuscompanion.ui.model

import kotlin.math.log10

/** Relative input level from MediaRecorder peak PCM amplitude; not a sound-pressure reading. */
internal fun recordingLevel(amplitude: Int): Float {
    val peak = amplitude.coerceIn(0, 32767)
    if (peak == 0) return 0f
    val decibelsBelowFullScale = 20.0 * log10(peak / 32767.0)
    return ((decibelsBelowFullScale + 60.0) / 60.0).toFloat().coerceIn(0f, 1f)
}

/** Smooth measured samples only; unavailable input immediately becomes unavailable. */
internal fun smoothRecordingLevel(previous: Float?, sample: Float?): Float? {
    val next = sample?.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: return null
    val before = previous?.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: return next
    val fraction = if (next > before) 0.65f else 0.25f
    return (before + (next - before) * fraction).coerceIn(0f, 1f)
}

internal enum class RecordingInputStatus { Inactive, Unavailable, Quiet, Detected, High }

internal fun recordingInputStatus(level: Float?, active: Boolean): RecordingInputStatus = when {
    !active -> RecordingInputStatus.Inactive
    level == null || !level.isFinite() -> RecordingInputStatus.Unavailable
    level <= 0.1f -> RecordingInputStatus.Quiet
    level >= 0.95f -> RecordingInputStatus.High
    else -> RecordingInputStatus.Detected
}
