package au.edu.unimelb.campuscompanion.navigation

import au.edu.unimelb.campuscompanion.data.TravelMode
import au.edu.unimelb.campuscompanion.data.TravelPreferences
import au.edu.unimelb.campuscompanion.data.model.GeoPoint
import au.edu.unimelb.campuscompanion.sensing.location.LocationSample
import java.net.URI
import java.net.URLDecoder
import org.junit.Assert.*
import org.junit.Test

class NavigationModeTest {
    private val now = 1_800_000_000_000L
    private val origin = LocationSample(0.0, 0.0, 5f, now)
    private val nearby = GeoPoint(0.004, 0.0) // about 445 m
    private val farAway = GeoPoint(0.02, 0.0) // about 2.2 km
    private val driving = TravelPreferences(walkingThresholdMeters = 1_000, longerDistanceMode = TravelMode.Driving)

    @Test fun `nearby classes open walking regardless of the longer distance preference`() {
        for (mode in TravelMode.entries) {
            assertEquals(TravelMode.Walking, preferredNavigationMode(
                nearby, driving.copy(longerDistanceMode = mode), origin, nowMillis = now
            ))
        }
    }

    @Test fun `farther classes open the selected mode even without an ETA`() {
        for (mode in TravelMode.entries) {
            assertEquals(mode, preferredNavigationMode(
                farAway, driving.copy(longerDistanceMode = mode), origin, nowMillis = now
            ))
        }
    }

    @Test fun `changing the walking threshold takes effect on the next tap`() {
        assertEquals(TravelMode.Walking, preferredNavigationMode(
            nearby, driving.copy(walkingThresholdMeters = 500), origin, nowMillis = now
        ))
        assertEquals(TravelMode.Driving, preferredNavigationMode(
            nearby, driving.copy(walkingThresholdMeters = 300), origin, nowMillis = now
        ))
    }

    @Test fun `the selected class destination determines the mode`() {
        assertEquals(TravelMode.Walking, preferredNavigationMode(nearby, driving, origin, nowMillis = now))
        assertEquals(TravelMode.Driving, preferredNavigationMode(farAway, driving, origin, nowMillis = now))
    }

    @Test fun `current position wins over an older route distance`() {
        assertEquals(TravelMode.Walking, preferredNavigationMode(nearby, driving, origin, 5_000, now))
        assertEquals(TravelMode.Driving, preferredNavigationMode(farAway, driving, origin, 100, now))
    }

    @Test fun `without GPS the known class distance still respects the walking boundary`() {
        assertEquals(TravelMode.Walking, preferredNavigationMode(nearby, driving, null, 1_000, now))
        assertEquals(TravelMode.Driving, preferredNavigationMode(nearby, driving, null, 1_001, now))
    }

    @Test fun `unusable location and unknown distance fall back to Profile instead of inventing walking`() {
        val unusable = listOf(null,
            origin.copy(timestampMillis = now - 120_001), origin.copy(timestampMillis = now + 1),
            origin.copy(accuracyMeters = 500f), origin.copy(accuracyMeters = -1f),
            origin.copy(latitude = Double.NaN), origin.copy(longitude = 181.0))
        for (location in unusable) {
            for (mode in TravelMode.entries) {
                assertEquals(mode, preferredNavigationMode(
                    nearby, driving.copy(longerDistanceMode = mode), location, -1, now
                ))
            }
        }
    }

    @Test fun `Google Maps URL explicitly requests the chosen mode and correct destination`() {
        val expected = mapOf(TravelMode.Walking to "walking", TravelMode.Driving to "driving", TravelMode.PublicTransport to "transit")
        for ((mode, parameter) in expected) {
            val selected = preferredNavigationMode(farAway, driving.copy(longerDistanceMode = mode), origin, nowMillis = now)
            val url = googleMapsDirectionsUrl(farAway, selected)
            val uri = URI(url)
            val query = uri.rawQuery.split('&').associate {
                val parts = it.split('=', limit = 2)
                parts[0] to URLDecoder.decode(parts[1], "UTF-8")
            }
            assertEquals("https", uri.scheme)
            assertEquals("www.google.com", uri.host)
            assertEquals("/maps/dir/", uri.path)
            assertEquals("1", query["api"])
            assertEquals("0.02,0.0", query["destination"])
            assertEquals(parameter, query["travelmode"])
            assertEquals("navigate", query["dir_action"])
            assertFalse(query.containsKey("origin")) // Google Maps uses its current device location.
            assertTrue(url.contains("%2C"))
        }
    }
}
