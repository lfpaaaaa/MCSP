package au.edu.unimelb.campuscompanion.data.remote

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * [ChatRemoteDataSource] backed by the `messages` table, the history functions in
 * supabase/migrations/20260925064500_chat_history.sql and Supabase Realtime. Row-level security
 * limits every call and every realtime event to the groups the signed-in user belongs to.
 */
class SupabaseChatDataSource(private val client: SupabaseClient) : ChatRemoteDataSource {

    override suspend fun fetchBefore(groupId: String, before: MessageCursor?, limit: Int): List<MessageRow> =
        remoteCall {
            val result = client.postgrest.rpc(
                "group_messages_before",
                buildJsonObject {
                    put("p_group_id", groupId)
                    put("p_before_created_at", before?.createdAt)
                    put("p_before_id", before?.id)
                    put("p_limit", limit)
                }
            )
            decodeRows(result.data, MessageRow.serializer())
        }

    override suspend fun fetchAfter(groupId: String, after: MessageCursor, limit: Int): List<MessageRow> =
        remoteCall {
            val result = client.postgrest.rpc(
                "group_messages_after",
                buildJsonObject {
                    put("p_group_id", groupId)
                    put("p_after_created_at", after.createdAt)
                    put("p_after_id", after.id)
                    put("p_limit", limit)
                }
            )
            decodeRows(result.data, MessageRow.serializer())
        }

    override suspend fun insertMessage(groupId: String, clientId: String, body: String): MessageRow =
        remoteCall {
            try {
                val result = client.postgrest.from("messages").insert(
                    buildJsonObject {
                        put("group_id", groupId)
                        put("client_id", clientId)
                        put("body", body)
                    }
                ) {
                    select()
                }
                decodeRow(result.data, MessageRow.serializer())
            } catch (duplicate: RestException) {
                // An earlier attempt reached the server but its response was lost.
                if (duplicate.statusCode != HTTP_CONFLICT) throw duplicate
                findOwnMessage(clientId) ?: throw duplicate
            }
        }

    override suspend fun markRead(groupId: String) {
        remoteCall {
            client.postgrest.rpc("mark_group_read", buildJsonObject { put("p_group_id", groupId) })
        }
    }

    override fun messageEvents(groupId: String): Flow<ChatEvent> = channelFlow {
        val channel = client.channel("group-messages-$groupId-${UUID.randomUUID()}")
        // The change listener has to be registered before the channel is subscribed.
        val inserts = channel.postgresChangeFlow<PostgresAction.Insert>(schema = "public") {
            table = "messages"
            filter("group_id", FilterOperator.EQ, groupId)
        }
        launch {
            inserts.collect { action ->
                val row = runCatching {
                    remoteJson.decodeFromJsonElement(MessageRow.serializer(), action.record)
                }.getOrNull()
                if (row != null) {
                    send(ChatEvent.Inserted(row))
                }
            }
        }
        launch {
            channel.status.collect { status ->
                if (status == RealtimeChannel.Status.SUBSCRIBED) {
                    send(ChatEvent.Subscribed)
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

    private suspend fun findOwnMessage(clientId: String): MessageRow? {
        val userId = client.auth.currentUserOrNull()?.id ?: return null
        val result = client.postgrest.from("messages").select {
            filter {
                eq("sender_id", userId)
                eq("client_id", clientId)
            }
        }
        return decodeRows(result.data, MessageRow.serializer()).firstOrNull()
    }

    private companion object {
        const val HTTP_CONFLICT = 409
    }
}
