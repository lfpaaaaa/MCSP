package au.edu.unimelb.campuscompanion.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class FileRowsTest {

    @Test
    fun groupFilesAreDecodedFromPostgrestJson() {
        val body = """
            [{"id":"d2000000-0000-4000-8000-000000000002","group_id":"e5000000-0000-4000-8000-000000000005",
              "uploader_id":"b2000000-0000-4000-8000-000000000002","uploader_name":"Bob","file_name":"newer.pdf",
              "mime_type":"application/pdf","size_bytes":2000,
              "storage_path":"e5000000-0000-4000-8000-000000000005/b2000000-0000-4000-8000-000000000002/object-2",
              "is_private":true,"created_at":"2026-09-25T02:00:00+00:00"}]
        """.trimIndent()

        val file = decodeRows(body, SharedFileRow.serializer()).single().toModel()

        assertEquals("newer.pdf", file.fileName)
        assertEquals("Bob", file.uploaderName)
        assertEquals(2_000L, file.sizeBytes)
        assertTrue(file.isPrivate)
        assertEquals(Instant.parse("2026-09-25T02:00:00Z"), file.createdAt)
    }

    @Test
    fun recordsWithoutAnUploaderNameStillMapToAFile() {
        val body = """{"id":"d1","group_id":"g1","uploader_id":"u1","file_name":"a.txt","mime_type":"text/plain","size_bytes":5,"storage_path":"g1/u1/o1","is_private":false,"created_at":"2026-09-25T02:00:00+00:00"}"""

        assertEquals("Group member", decodeRow(body, SharedFileRow.serializer()).toModel().uploaderName)
    }
}
