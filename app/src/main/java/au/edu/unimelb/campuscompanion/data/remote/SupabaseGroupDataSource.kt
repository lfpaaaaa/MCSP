package au.edu.unimelb.campuscompanion.data.remote

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * [GroupRemoteDataSource] backed by the database functions in
 * supabase/migrations/20260925053000_group_collaboration.sql. The server works out the acting user
 * from the session, and row-level security limits every call to the user's own groups.
 */
class SupabaseGroupDataSource(private val client: SupabaseClient) : GroupRemoteDataSource {

    override suspend fun fetchMyGroups(): List<GroupSummaryRow> = remoteCall {
        val result = client.postgrest.rpc("my_group_summaries")
        decodeRows(result.data, GroupSummaryRow.serializer())
    }

    override suspend fun createGroup(name: String, courseCode: String?): GroupRow = remoteCall {
        val result = client.postgrest.rpc(
            "create_group",
            buildJsonObject {
                put("p_name", name)
                put("p_course_code", courseCode)
            }
        )
        decodeRow(result.data, GroupRow.serializer())
    }

    override suspend fun fetchMembers(groupId: String): List<GroupMemberRow> = remoteCall {
        val result = client.postgrest.rpc(
            "group_members",
            buildJsonObject { put("p_group_id", groupId) }
        )
        decodeRows(result.data, GroupMemberRow.serializer())
    }

    override suspend fun deleteMembership(groupId: String, userId: String): Int = remoteCall {
        val result = client.postgrest.from("memberships").delete {
            select()
            filter {
                eq("group_id", groupId)
                eq("user_id", userId)
            }
        }
        countRows(result.data)
    }

    override suspend fun createInvite(groupId: String): InviteRow = remoteCall {
        val result = client.postgrest.rpc(
            "create_group_invite",
            buildJsonObject { put("p_group_id", groupId) }
        )
        decodeRow(result.data, InviteRow.serializer())
    }

    override suspend fun joinWithToken(token: String): GroupRow = remoteCall {
        val result = client.postgrest.rpc(
            "join_group_with_token",
            buildJsonObject { put("p_token", token) }
        )
        decodeRow(result.data, GroupRow.serializer())
    }
}
