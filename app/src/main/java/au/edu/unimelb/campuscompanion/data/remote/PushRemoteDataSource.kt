package au.edu.unimelb.campuscompanion.data.remote

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Registration of this device for push notifications. Implementations throw
 * [au.edu.unimelb.campuscompanion.data.DataError] on failure.
 */
interface PushRemoteDataSource {
    /** Registers the device's messaging token for the signed-in user. */
    suspend fun registerToken(token: String)

    /** Stops notifications to this token; only its owner can remove it. */
    suspend fun unregisterToken(token: String)
}

/** [PushRemoteDataSource] backed by the register_device_token and unregister_device_token functions. */
class SupabasePushDataSource(private val client: SupabaseClient) : PushRemoteDataSource {

    override suspend fun registerToken(token: String) {
        remoteCall {
            client.postgrest.rpc("register_device_token", buildJsonObject { put("p_token", token) })
        }
    }

    override suspend fun unregisterToken(token: String) {
        remoteCall {
            client.postgrest.rpc("unregister_device_token", buildJsonObject { put("p_token", token) })
        }
    }
}
