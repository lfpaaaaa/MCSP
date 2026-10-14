package au.edu.unimelb.campuscompanion.ui.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingLevelTest {
    @Test fun silenceAndInvalidNegativeSamplesStayEmpty() {
        for (amplitude in listOf(Int.MIN_VALUE, -1, 0, 1, 32)) {
            assertEquals(0f, recordingLevel(amplitude), 0f)
        }
    }

    @Test fun fullScaleAndOutOfRangePeaksCannotOverflowTheMeter() {
        for (amplitude in listOf(32767, 32768, Int.MAX_VALUE)) {
            assertEquals(1f, recordingLevel(amplitude), 0f)
        }
    }

    @Test fun tenfoldAmplitudeStepsOccupyEqualLogarithmicIntervals() {
        assertEquals(1f / 3f, recordingLevel(328), 0.001f)
        assertEquals(2f / 3f, recordingLevel(3277), 0.001f)
        assertEquals(1f, recordingLevel(32767), 0.001f)
    }

    @Test fun louderInputNeverMovesTheBarBackwardsAcrossTheWholePcmRange() {
        var previous = 0f
        for (amplitude in 0..32767) {
            val level = recordingLevel(amplitude)
            assertTrue(level.isFinite() && level in 0f..1f && level >= previous)
            previous = level
        }
    }
}
