package au.edu.unimelb.campuscompanion.data.remote

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import io.github.jan.supabase.storage.UploadStatus
import io.github.jan.supabase.storage.storage
import io.github.jan.supabase.storage.uploadAsFlow
import io.ktor.http.ContentType
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

/**
 * [FileRemoteDataSource] backed by the private `group-files` bucket and the `shared_files` table,
 * both set up in supabase/migrations/20260925070000_group_files.sql.
 */
class SupabaseFileDataSource(private val client: SupabaseClient) : FileRemoteDataSource {

    private val bucket
        get() = client.storage.from(BUCKET)

    override suspend fun fetchFiles(groupId: String): List<SharedFileRow> = remoteCall {
        val result = client.postgrest.rpc("group_files", buildJsonObject { put("p_group_id", groupId) })
        decodeRows(result.data, SharedFileRow.serializer())
    }

    override fun uploadObject(path: String, bytes: ByteArray, mimeType: String): Flow<Long> =
        bucket
            .uploadAsFlow(path, bytes) {
                upsert = false
                contentType = runCatching { ContentType.parse(mimeType) }.getOrNull()
            }
            .mapNotNull { status -> (status as? UploadStatus.Progress)?.totalBytesSend }
            .catch { error -> throw error.toDataError() }

    override suspend fun insertFileRecord(record: NewFileRecord): SharedFileRow = remoteCall {
        val result = client.postgrest.from("shared_files").insert(
            buildJsonObject {
                put("group_id", record.groupId)
                put("file_name", record.fileName)
                put("mime_type", record.mimeType)
                put("size_bytes", record.sizeBytes)
                put("storage_path", record.storagePath)
                put("is_private", record.isPrivate)
            }
        ) {
            select()
        }
        decodeRow(result.data, SharedFileRow.serializer())
    }

    override suspend fun deleteFileRecord(fileId: String): Int = remoteCall {
        val result = client.postgrest.from("shared_files").delete {
            select()
            filter { eq("id", fileId) }
        }
        countRows(result.data)
    }

    override suspend fun deleteObject(path: String) {
        remoteCall { bucket.delete(path) }
    }

    override suspend fun createSignedUrl(path: String, expiresInSeconds: Long): String = remoteCall {
        bucket.createSignedUrl(path, expiresInSeconds.seconds)
    }

    override fun fileChanges(groupId: String): Flow<Unit> = channelFlow {
        val channel = client.channel("group-files-$groupId-${UUID.randomUUID()}")
        // The change listener has to be registered before the channel is subscribed.
        val inserts = channel.postgresChangeFlow<PostgresAction.Insert>(schema = "public") {
            table = "shared_files"
            filter("group_id", FilterOperator.EQ, groupId)
        }
        launch {
            inserts.collect { send(Unit) }
        }
        launch {
            channel.status.collect { status ->
                if (status == RealtimeChannel.Status.SUBSCRIBED) {
                    send(Unit)
                }
            }
        }
        try {
            channel.subscribe()
            awaitCancellation()
        } finally {
            withContext(NonCancellable) {
                client.realtime.removeChannel(channel)
            }
        }
    }

    private companion object {
        const val BUCKET = "group-files"
    }
}
