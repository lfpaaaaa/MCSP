package au.edu.unimelb.campuscompanion.data.repository

import au.edu.unimelb.campuscompanion.data.remote.remoteJson
import kotlinx.serialization.decodeFromString
import org.junit.Assert.*
import org.junit.Test

class TranscriptTest {
    @Test fun queuedAndProcessingResultsKeepPolling() {
        assertTrue(FileTranscript("file", "queued").isWorking)
        assertTrue(FileTranscript("file", "processing").isWorking)
        assertFalse(FileTranscript("file", "failed").isWorking)
        assertFalse(FileTranscript("file", "ready").isWorking)
    }
    @Test fun savedTranscriptDecodesWithExtraServerLeaseFields() {
        val result = remoteJson.decodeFromString<FileTranscript>("""{"file_id":"file","status":"ready","transcript":"Welcome to class.","language":"en","lease_id":null,"updated_at":"2026-10-13"}""")
        assertEquals("Welcome to class.", result.transcript)
        assertEquals("en", result.language)
    }
}
