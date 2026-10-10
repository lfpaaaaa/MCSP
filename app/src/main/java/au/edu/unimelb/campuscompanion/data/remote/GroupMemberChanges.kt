package au.edu.unimelb.campuscompanion.data.remote

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import java.util.UUID

/** Includes a refresh after reconnect. Read receipts must not repeatedly invalidate nicknames. */
internal fun groupMemberChanges(client: SupabaseClient, groupId: String): Flow<Unit> = channelFlow {
    val channel = client.channel("group-nicknames-$groupId-${UUID.randomUUID()}")
    val updates = channel.postgresChangeFlow<PostgresAction.Update>(schema = "public") {
        table = "memberships"
        filter("group_id", FilterOperator.EQ, groupId)
    }
    launch {
        val names = mutableMapOf<JsonElement, JsonElement?>()
        updates.collect { action ->
            val userId = action.record["user_id"] ?: return@collect
            val nickname = action.record["nickname"]
            if (!names.containsKey(userId) || names[userId] != nickname) {
                names[userId] = nickname
                send(Unit)
            }
        }
    }
    launch {
        channel.status.collect { status ->
            if (status == RealtimeChannel.Status.SUBSCRIBED) send(Unit)
        }
    }
    // Cover the small subscription-start gap and missed updates after a network interruption.
    launch {
        while (true) {
            delay(30_000)
            send(Unit)
        }
    }
    try {
        channel.subscribe()
        awaitCancellation()
    } finally {
        withContext(NonCancellable) { client.realtime.removeChannel(channel) }
    }
}
