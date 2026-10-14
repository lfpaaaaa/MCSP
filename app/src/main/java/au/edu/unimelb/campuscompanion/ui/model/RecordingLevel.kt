package au.edu.unimelb.campuscompanion.ui.model

import kotlin.math.log10

/** Relative input level, not a calibrated sound-pressure measurement. */
internal fun recordingLevel(amplitude: Int): Float {
    // MediaRecorder reports the peak absolute PCM amplitude since the previous read.
    val peak = amplitude.coerceIn(0, 32767)
    if (peak == 0) return 0f
    val decibelsBelowFullScale = 20.0 * log10(peak / 32767.0)
    // A logarithmic display keeps normal speech visible; suppress the bottom 60 dB.
    return ((decibelsBelowFullScale + 60.0) / 60.0).toFloat().coerceIn(0f, 1f)
}
