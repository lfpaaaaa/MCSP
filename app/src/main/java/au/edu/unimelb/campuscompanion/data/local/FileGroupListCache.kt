package au.edu.unimelb.campuscompanion.data.local

import au.edu.unimelb.campuscompanion.data.remote.GroupSummaryRow
import au.edu.unimelb.campuscompanion.data.repository.GroupListCache
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/** [GroupListCache] that writes one JSON file per user into [directory] (the app's private storage). */
class FileGroupListCache(private val directory: File) : GroupListCache {

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(GroupSummaryRow.serializer())

    override fun load(userId: String): List<GroupSummaryRow>? {
        val file = fileFor(userId)
        if (!file.isFile) return null
        return runCatching { json.decodeFromString(serializer, file.readText()) }.getOrNull()
    }

    override fun save(userId: String, rows: List<GroupSummaryRow>) {
        directory.mkdirs()
        val target = fileFor(userId)
        val temporary = File(directory, "${target.name}.tmp")
        temporary.writeText(json.encodeToString(serializer, rows))
        if (!temporary.renameTo(target)) {
            target.delete()
            check(temporary.renameTo(target)) { "Could not replace ${target.name}" }
        }
    }

    /** The user id is hashed so it does not appear in file names. */
    private fun fileFor(userId: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(userId.toByteArray())
        return File(directory, digest.joinToString("") { "%02x".format(it) }.take(32) + ".json")
    }
}
