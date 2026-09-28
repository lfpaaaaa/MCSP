package au.edu.unimelb.campuscompanion.data.remote

import au.edu.unimelb.campuscompanion.data.TravelMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RouteRequestsTest {

    @Test
    fun travelModesUseTheRouteEtaNames() {
        assertEquals(
            listOf("WALK", "TRANSIT", "DRIVE"),
            listOf(TravelMode.Walking, TravelMode.PublicTransport, TravelMode.Driving).map { it.wireName }
        )
    }

    @Test
    fun budgetAnswersSayHowLongToWait() {
        assertEquals(
            1_234L,
            quotaRetryAfterSeconds("""{"error":"quota_exceeded","scope":"daily","retry_after_seconds":1234}""")
        )
        assertEquals(3_600L, quotaRetryAfterSeconds("""{"error":"quota_exceeded"}"""))
    }

    @Test
    fun otherAnswersAreNotTreatedAsABudgetProblem() {
        assertNull(quotaRetryAfterSeconds("""{"error":"not_authenticated"}"""))
        assertNull(quotaRetryAfterSeconds("Too many requests"))
    }

    @Test
    fun routeAnswersAreDecoded() {
        val answer = remoteJson.decodeFromString(
            RouteResponseBody.serializer(),
            """{"duration_seconds":754,"distance_meters":1020,"cached":true}"""
        )

        assertEquals(RouteResponseBody(durationSeconds = 754, distanceMeters = 1_020, cached = true), answer)
    }
}
