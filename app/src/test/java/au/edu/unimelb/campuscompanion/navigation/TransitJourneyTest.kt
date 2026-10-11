package au.edu.unimelb.campuscompanion.navigation

import au.edu.unimelb.campuscompanion.data.model.GeoPoint
import au.edu.unimelb.campuscompanion.sensing.location.LocationSample
import org.junit.Assert.*
import org.junit.Test

class TransitJourneyTest {
    // One degree of latitude is roughly 111 km; this northbound leg is 1.1 km long.
    private val leg = TransitLeg("19", "North Coburg", "Start", GeoPoint(0.0, 0.0), "Campus", GeoPoint(.01, 0.0), listOf(GeoPoint(0.0, 0.0), GeoPoint(.01, 0.0)))
    private fun fix(latitude: Double, time: Long, longitude: Double = 0.0, accuracy: Float = 5f) = LocationSample(latitude, longitude, accuracy, time)

    @Test fun `vehicle movement from boarding stop arms and alerts once before arrival`() {
        val trip = TransitJourney(leg)
        val outputs = (0..9).map { i -> val time = 10_000L * i; trip.update(fix(i * .001, time), time, true) }
        assertEquals(BoardingState.Riding, outputs[3].state)
        assertEquals(1, outputs.count { it.alert })
        assertTrue(outputs.first { it.alert }.remainingMeters!! > 100)
        assertEquals(BoardingState.Alight, outputs.last().state)
    }
    @Test fun `walking past the stop does not arm reminder`() {
        val trip = TransitJourney(leg)
        repeat(30) { i ->
            val t = i * 10_000L
            val result = trip.update(fix(.008 + i * .00005, t), t, false)
            assertEquals(BoardingState.Waiting, result.state)
            assertFalse(result.alert)
        }
    }
    @Test fun `speed alone without observed boarding stop does not identify a vehicle`() {
        val trip = TransitJourney(leg)
        repeat(7) { i -> val t = i * 10_000L; assertFalse(trip.update(fix(.003 + i * .001, t), t, true).alert) }
    }
    @Test fun `explicit boarding supports starting after boarding`() {
        val trip = TransitJourney(leg)
        trip.confirmBoarding()
        val updates = listOf(.004, .005, .006, .007, .008).mapIndexed { i, lat ->
            val t = i * 10_000L
            trip.update(fix(lat, t), t, false)
        }
        assertEquals(1, updates.count { it.alert })
    }
    @Test fun `stale inaccurate and out of order fixes never trigger`() {
        val trip = TransitJourney(leg)
        trip.confirmBoarding()
        assertFalse(trip.update(fix(.005, 0), 60_000, true).alert)
        assertFalse(trip.update(fix(.006, 60_000, accuracy = 80f), 60_000, true).alert)
        trip.update(fix(.007, 70_000), 70_000, true)
        assertFalse(trip.update(fix(.008, 60_000), 70_000, true).alert)
        assertFalse(trip.update(fix(.009, 90_000, accuracy = Float.NaN), 90_000, true).alert)
    }
    @Test fun `parallel road is off route and suppresses reminders`() {
        val trip = TransitJourney(leg)
        trip.confirmBoarding()
        repeat(4) { i ->
            val t = i * 10_000L
            val result = trip.update(fix(.006 + .001 * i, t, longitude = .005), t, true)
            assertTrue(result.offRoute)
            assertFalse(result.alert)
        }
    }
    @Test fun `remaining distance follows loop instead of straight line to stop`() {
        val path = listOf(GeoPoint(0.0, 0.0), GeoPoint(.01, 0.0), GeoPoint(.01, .01), GeoPoint(0.0, .01), GeoPoint(0.0, .001))
        val match = matchPath(GeoPoint(.001, 0.0), path)!!
        assertTrue(match.remaining > 3_000)
        assertTrue(match.distanceFromPath < 1)
    }
    @Test fun `new leg has independent alert state`() {
        val first = TransitJourney(leg).apply { confirmBoarding() }
        listOf(.005, .006, .007).forEachIndexed { i, lat -> first.update(fix(lat, i * 10_000L), i * 10_000L, true) }
        val next = TransitJourney(leg)
        assertEquals(BoardingState.Waiting, next.update(fix(.007, 40_000), 40_000, true).state)
    }
    @Test fun `known Google encoded polyline decodes correctly`() {
        val points = decodeRoutePolyline("_p~iF~ps|U_ulLnnqC_mqNvxq`@")
        assertEquals(listOf(GeoPoint(38.5, -120.2), GeoPoint(40.7, -120.95), GeoPoint(43.252, -126.453)), points)
    }
    @Test(expected = IllegalArgumentException::class) fun `truncated polyline is rejected`() { decodeRoutePolyline("_p~iF") }
    @Test fun `transit route parser retains each transfer and stop coordinates`() {
        val raw = """{"routes":[{"duration":"1200s","distanceMeters":2500,"polyline":{"encodedPolyline":"??o}@?"},"legs":[{"steps":[
        {"travelMode":"WALK","navigationInstruction":{"instructions":"Walk to the stop"}},
        {"travelMode":"TRANSIT","polyline":{"encodedPolyline":"??o}@?"},"transitDetails":{"headsign":"Campus","transitLine":{"nameShort":"19"},"stopDetails":{
        "departureStop":{"name":"A","location":{"latLng":{"latitude":0.0,"longitude":0.0}}},
        "arrivalStop":{"name":"B","location":{"latLng":{"latitude":0.01,"longitude":0.0}}}}}},
        {"travelMode":"TRANSIT","polyline":{"encodedPolyline":"o}@?o}@?"},"transitDetails":{"transitLine":{"name":"Train"},"stopDetails":{
        "departureStop":{"name":"B","location":{"latLng":{"latitude":0.01,"longitude":0.0}}},
        "arrivalStop":{"name":"C","location":{"latLng":{"latitude":0.02,"longitude":0.0}}}}}}
        ]}]}]}"""
        val route = parseGoogleRoutes(raw).single()
        assertEquals(1200L, route.durationSeconds)
        assertEquals(listOf("19", "Train"), route.transitLegs.map { it.line })
        assertEquals(listOf("B", "C"), route.transitLegs.map { it.arrivalName })
        assertEquals(GeoPoint(.01, 0.0), route.transitLegs.first().arrival)
        assertEquals(listOf("Walk to the stop"), route.instructions)
    }
}
