package au.edu.unimelb.campuscompanion.data.remote

import au.edu.unimelb.campuscompanion.data.model.TimetableGroupSpec
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertTrue
import org.junit.Test

class TimetableGroupSerializationTest {
    @Test fun courseActivityIsAlwaysSentToTheServer() {
        val encoded = remoteJson.encodeToJsonElement(ListSerializer(TimetableGroupSpec.serializer()),
            listOf(TimetableGroupSpec("COMP90018", "Mobile Computing", "course")))
        assertTrue(encoded.toString().contains("\"activity\":\"course\""))
    }
}
