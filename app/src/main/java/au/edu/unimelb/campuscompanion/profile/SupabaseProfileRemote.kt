package au.edu.unimelb.campuscompanion.profile

import au.edu.unimelb.campuscompanion.auth.toAuthenticatedUser
import au.edu.unimelb.campuscompanion.data.DataError
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.storage.storage
import io.ktor.http.ContentType
import kotlinx.serialization.json.put

class SupabaseProfileRemote(private val client: SupabaseClient) : ProfileRemote {
    override fun currentUserId() = client.auth.currentUserOrNull()?.id
    override suspend fun uploadAvatar(path: String, bytes: ByteArray) {
        if (path.substringBefore('/') != currentUserId()) throw DataError.Unauthenticated()
        client.storage.from(AVATAR_BUCKET).upload(path, bytes) {
            upsert = false
            contentType = ContentType.Image.JPEG
        }
    }
    override suspend fun update(userId: String, name: String, avatar: String?) = run {
        if (currentUserId() != userId) throw DataError.Unauthenticated()
        client.auth.updateUser {
            data {
                put("display_name", name)
                avatar?.let { put("avatar_url", it) }
            }
        }.toAuthenticatedUser()
    }
    override suspend fun deleteAvatar(path: String) {
        if (path.substringBefore('/') != currentUserId()) throw DataError.Unauthenticated()
        client.storage.from(AVATAR_BUCKET).delete(path)
    }
}
