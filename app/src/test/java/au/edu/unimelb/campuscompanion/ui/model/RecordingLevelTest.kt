package au.edu.unimelb.campuscompanion.ui.model

import org.junit.Assert.*
import org.junit.Test

class RecordingLevelTest {
    @Test fun silenceAndInvalidNegativeSamplesStayEmpty() {
        listOf(Int.MIN_VALUE, -1, 0, 1, 32).forEach { assertEquals(0f, recordingLevel(it), 0f) }
    }
    @Test fun fullScaleAndOutOfRangePeaksCannotOverflowTheMeter() {
        listOf(32767, 32768, Int.MAX_VALUE).forEach { assertEquals(1f, recordingLevel(it), 0f) }
    }
    @Test fun tenfoldAmplitudeStepsOccupyEqualLogarithmicIntervals() {
        assertEquals(1f / 3f, recordingLevel(328), 0.001f)
        assertEquals(2f / 3f, recordingLevel(3277), 0.001f)
        assertEquals(1f, recordingLevel(32767), 0.001f)
    }
    @Test fun louderRealSamplesNeverMoveTheBarBackwards() {
        var previous = 0f
        for (amplitude in 0..32767) {
            val level = recordingLevel(amplitude)
            assertTrue(level.isFinite() && level in 0f..1f && level >= previous)
            previous = level
        }
    }
    @Test fun smoothingStaysBetweenMeasuredSamplesAndUnavailableInputClearsIt() {
        assertEquals(0.65f, smoothRecordingLevel(0f, 1f)!!, 0.0001f)
        assertEquals(0.75f, smoothRecordingLevel(1f, 0f)!!, 0.0001f)
        assertEquals(0.4f, smoothRecordingLevel(null, 0.4f)!!, 0f)
        assertNull(smoothRecordingLevel(1f, null))
        assertNull(smoothRecordingLevel(1f, Float.NaN))
        assertEquals(0f, smoothRecordingLevel(0f, 0f)!!, 0f)
    }
    @Test fun meterDistinguishesInactiveUnavailableQuietAndClippingInput() {
        assertEquals(RecordingInputStatus.Inactive, recordingInputStatus(1f, false))
        assertEquals(RecordingInputStatus.Unavailable, recordingInputStatus(null, true))
        assertEquals(RecordingInputStatus.Unavailable, recordingInputStatus(Float.NaN, true))
        assertEquals(RecordingInputStatus.Quiet, recordingInputStatus(0f, true))
        assertEquals(RecordingInputStatus.Detected, recordingInputStatus(0.5f, true))
        assertEquals(RecordingInputStatus.High, recordingInputStatus(1f, true))
    }
}
