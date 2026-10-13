package au.edu.unimelb.campuscompanion.data.repository

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
    @SerialName("error_code") val errorCode: String? = null
) {
    val isWorking: Boolean get() = status == "queued" || status == "processing"
}

/** Results live in Postgres; membership is checked on every read and request. */
object TranscriptRepository {
    suspend fun get(fileId: String): Result<FileTranscript?> = call("file_transcript", fileId)
    suspend fun request(fileId: String): Result<FileTranscript?> = call("request_file_transcript", fileId)

    private suspend fun call(function: String, fileId: String): Result<FileTranscript?> = dataResult {
        val client = SupabaseProvider.client ?: error("Transcription needs a connected backend")
        val response = client.postgrest.rpc(function, buildJsonObject { put("p_file_id", fileId) })
        remoteJson.decodeFromString(ListSerializer(FileTranscript.serializer()), response.data).firstOrNull()
    }
}
