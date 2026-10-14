package au.edu.unimelb.campuscompanion.data.repository

import au.edu.unimelb.campuscompanion.R
import au.edu.unimelb.campuscompanion.data.DataError
import au.edu.unimelb.campuscompanion.ui.model.MAX_TRANSCRIPT_LENGTH
import au.edu.unimelb.campuscompanion.ui.text.UiText
import au.edu.unimelb.campuscompanion.auth.SupabaseProvider
import au.edu.unimelb.campuscompanion.data.remote.dataResult
import au.edu.unimelb.campuscompanion.data.remote.remoteJson
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
data class FileTranscript(
    @SerialName("file_id") val fileId: String,
    val status: String,
    val transcript: String? = null,
    val language: String? = null,
    @SerialName("error_code") val errorCode: String? = null,
    val revision: Int = 0,
    @SerialName("edited_at") val editedAt: String? = null
) {
    val isWorking: Boolean get() = status == "queued" || status == "processing"
}

/** Results live in Postgres; membership is checked on every read and request. */
object TranscriptRepository {
    suspend fun get(fileId: String): Result<FileTranscript?> = call("file_transcript", fileId)
    suspend fun request(fileId: String): Result<FileTranscript?> = call("request_file_transcript", fileId)

    suspend fun update(fileId: String, text: String, expectedRevision: Int): Result<FileTranscript> = dataResult {
        if (text.length > MAX_TRANSCRIPT_LENGTH || expectedRevision < 0) {
            throw DataError.Validation(UiText(R.string.transcript_edit_invalid))
        }
        val client = SupabaseProvider.client ?: error("Transcription needs a connected backend")
        val response = client.postgrest.rpc("update_file_transcript", buildJsonObject {
            put("p_file_id", fileId)
            put("p_transcript", text)
            put("p_expected_revision", expectedRevision)
        })
        remoteJson.decodeFromString(ListSerializer(FileTranscript.serializer()), response.data).single()
    }

    private suspend fun call(function: String, fileId: String): Result<FileTranscript?> = dataResult {
        val client = SupabaseProvider.client ?: error("Transcription needs a connected backend")
        val response = client.postgrest.rpc(function, buildJsonObject { put("p_file_id", fileId) })
        remoteJson.decodeFromString(ListSerializer(FileTranscript.serializer()), response.data).firstOrNull()
    }
}
