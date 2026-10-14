package au.edu.unimelb.campuscompanion.data.remote

import au.edu.unimelb.campuscompanion.data.model.*
import au.edu.unimelb.campuscompanion.data.repository.GroupPinsDataSource
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.realtime.*
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

@Serializable
internal data class GroupPinRow(
    val id: String,
    val visibility: String,
    @SerialName("pinned_by_name") val pinnedByName: String,
    @SerialName("pinned_at") val pinnedAt: String,
    @SerialName("can_unpin") val canUnpin: Boolean,
    val message: MessageRow? = null,
    val file: SharedFileRow? = null
) {
    fun toModel() = GroupPin(id, pinnedByName, parseTimestamp(pinnedAt), canUnpin, if (visibility == "group") PinVisibility.Group else PinVisibility.Personal,
        message = message?.let { ChatMessage(it.id, it.clientId, it.groupId, it.senderId,
            it.senderName ?: "Member", it.body, parseTimestamp(it.createdAt), MessageStatus.Sent) },
        file = file?.toModel())
}

@Serializable
internal data class GroupPinsSnapshotRow(
    val items: List<GroupPinRow>,
    @SerialName("can_pin_for_everyone") val canPinForEveryone: Boolean
)

class SupabaseGroupPinsDataSource(private val client: SupabaseClient) : GroupPinsDataSource {
    override suspend fun fetch(groupId: String): GroupPinsSnapshot = remoteCall {
        val response = client.postgrest.rpc("group_pinned_items", buildJsonObject { put("p_group_id", groupId) })
        val row = remoteJson.decodeFromString(GroupPinsSnapshotRow.serializer(), response.data)
        GroupPinsSnapshot(row.items.map { it.toModel() }, row.canPinForEveryone)
    }
    override suspend fun pin(groupId: String, target: PinTarget, forEveryone: Boolean) {
        remoteCall {
            client.postgrest.rpc("pin_group_item", buildJsonObject {
                put("p_group_id", groupId)
                put("p_for_everyone", forEveryone)
                put("p_message_id", target.id.takeIf { target.kind == PinKind.Message })
                put("p_file_id", target.id.takeIf { target.kind == PinKind.File })
            })
        }
    }
    override suspend fun unpin(groupId: String, pinId: String) {
        remoteCall { client.postgrest.rpc("unpin_group_item", buildJsonObject {
            put("p_group_id", groupId); put("p_pin_id", pinId)
        }) }
    }
    override fun changes(groupId: String): Flow<Unit> = channelFlow {
        val channel = client.channel("group-pins-$groupId-${UUID.randomUUID()}")
        val changes = channel.postgresChangeFlow<PostgresAction>(schema = "public") {
            table = "group_pins"
            filter("group_id", FilterOperator.EQ, groupId)
        }
        launch { changes.collect { send(Unit) } }
        launch { channel.status.collect { if (it == RealtimeChannel.Status.SUBSCRIBED) send(Unit) } }
        try { channel.subscribe(); awaitCancellation() }
        finally { withContext(NonCancellable) { client.realtime.removeChannel(channel) } }
    }
}
