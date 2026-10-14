package au.edu.unimelb.campuscompanion.ui.model

import au.edu.unimelb.campuscompanion.data.model.GeoPoint
import au.edu.unimelb.campuscompanion.sensing.location.LocationSample
import au.edu.unimelb.campuscompanion.sensing.orientation.CompassSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DestinationCompassTest {
    private val now = 1_800_000_000_000L
    private val elapsed = 20_000_000_000L
    private val north = GeoPoint(0.01, 0.0)
    private val fix = LocationSample(0.0, 0.0, 10f, now)
    private val sample = CompassSample(0f, elapsed, accuracy = 3)
    private fun state(
        target: GeoPoint = north, location: LocationSample? = fix, heading: CompassSample? = sample,
        available: Boolean = true, declination: Float = 0f, rotation: Int = 0
    ) = destinationCompass(target, location, heading, available, declination, now, elapsed, rotation)

    @Test fun cardinalBearingsTurnRelativeToThePhone() {
        assertEquals(0f, (state() as DestinationCompass.Pointing).relativeDegrees, 0.01f)
        assertEquals(90f, (state(target = GeoPoint(0.0, 0.01)) as DestinationCompass.Pointing).relativeDegrees, 0.01f)
        assertEquals(-90f, (state(heading = sample.copy(headingDegrees = 90f)) as DestinationCompass.Pointing).relativeDegrees, 0.01f)
        assertEquals(-180f, (state(heading = sample.copy(headingDegrees = 180f)) as DestinationCompass.Pointing).relativeDegrees, 0.01f)
    }

    @Test fun magneticDeclinationIsAddedToHeadingBeforeCalculatingTheTurn() {
        assertEquals(-12f, (state(declination = 12f) as DestinationCompass.Pointing).relativeDegrees, 0.01f)
        assertEquals(12f, (state(declination = -12f) as DestinationCompass.Pointing).relativeDegrees, 0.01f)
    }

    @Test fun crossingNorthUsesTheShortArcInBothDirections() {
        assertEquals(361f, nextCompassRotation(359f, 1f), 0.01f)
        assertEquals(-1f, nextCompassRotation(1f, 359f), 0.01f)
        assertEquals(181f, nextCompassRotation(179f, -179f), 0.01f)
        assertEquals(-181f, nextCompassRotation(-179f, 179f), 0.01f)
        assertEquals(721f, nextCompassRotation(719f, 1f), 0.01f)
    }

    @Test fun allRelativeDirectionLabelsFollowTheArrowIncludingNegativeAngles() {
        for (sector in 0..7) {
            val angle = sector * 45f
            assertEquals(sector, DestinationCompass.Pointing(angle, 0f).directionSector)
            assertEquals(sector, DestinationCompass.Pointing(angle - 360f, 0f).directionSector)
        }
    }

    @Test fun nearbyBuildingDoesNotDisplayAnUnstableArrow() {
        assertEquals(DestinationCompass.NearBuilding, state(target = GeoPoint(0.0, 0.0), heading = null))
        assertEquals(DestinationCompass.NearBuilding, state(target = GeoPoint(0.0005, 0.0), available = false))
        assertTrue(state(target = GeoPoint(0.001, 0.0)) is DestinationCompass.Pointing)
    }

    @Test fun staleInaccurateFutureOrInvalidLocationsCannotProduceAnArrow() {
        val unusable = listOf(null, fix.copy(timestampMillis = now - 120_001), fix.copy(timestampMillis = now + 1),
            fix.copy(accuracyMeters = 51f), fix.copy(accuracyMeters = -1f), fix.copy(accuracyMeters = Float.NaN),
            fix.copy(latitude = Double.NaN), fix.copy(longitude = 181.0))
        for (location in unusable) assertEquals(DestinationCompass.WaitingForLocation, state(location = location))
        assertTrue(state(location = fix.copy(timestampMillis = now - 120_000, accuracyMeters = 50f)) is DestinationCompass.Pointing)
    }

    @Test fun unsupportedAndSilentSensorsHaveDistinctFallbacks() {
        assertEquals(DestinationCompass.SensorUnavailable, state(available = false))
        assertEquals(DestinationCompass.WaitingForHeading, state(heading = null))
        assertEquals(DestinationCompass.WaitingForHeading, state(heading = sample.copy(timestampNanos = elapsed - 5_000_000_001L)))
        assertEquals(DestinationCompass.WaitingForHeading, state(heading = sample.copy(timestampNanos = elapsed + 1L)))
        assertEquals(DestinationCompass.WaitingForHeading, state(heading = sample.copy(headingDegrees = Float.NaN)))
    }

    @Test fun unreliableMagnetometerAndTiltAskForCorrectionInsteadOfGivingDirections() {
        for (accuracy in listOf(-1, 0, 1)) assertEquals(DestinationCompass.Calibrate, state(heading = sample.copy(accuracy = accuracy)))
        assertTrue(state(heading = sample.copy(accuracy = 2)) is DestinationCompass.Pointing)
        assertEquals(DestinationCompass.HoldFlat, state(heading = sample.copy(tiltDegrees = 46f)))
    }

    @Test fun afterDisplayRotationWaitForAHeadingRemappedToTheNewScreen() {
        assertEquals(DestinationCompass.WaitingForHeading, state(rotation = 1))
        assertEquals(-90f, (state(heading = sample.copy(displayRotation = 1, headingDegrees = 90f), rotation = 1)
            as DestinationCompass.Pointing).relativeDegrees, 0.01f)
    }

    @Test fun changingClassDestinationImmediatelyChangesTheBearing() {
        assertEquals(0f, (state() as DestinationCompass.Pointing).relativeDegrees, 0.01f)
        assertEquals(-90f, (state(target = GeoPoint(0.0, -0.01)) as DestinationCompass.Pointing).relativeDegrees, 0.01f)
    }
}
