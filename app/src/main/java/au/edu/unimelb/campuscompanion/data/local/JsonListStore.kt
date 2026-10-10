package au.edu.unimelb.campuscompanion.data.local

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/**
 * One JSON file per key in a directory of the app's private storage: the on-device copy of a list
 * the server returned, written atomically and read back on the next start. The directory is
 * looked up on first use, so constructing a store touches no disk, and the key is hashed so it
 * does not appear in file names.
 */
internal class JsonListStore<T>(
    private val directoryHolder: Lazy<File>,
    private val serializer: KSerializer<List<T>>
) {
    private val directory: File
        get() = directoryHolder.value

    private val json = Json { ignoreUnknownKeys = true }

    /** The saved list of [key], or null when there is none or it cannot be read. */
    fun load(key: String): List<T>? {
        val file = fileFor(key)
        if (!file.isFile) return null
        return runCatching { json.decodeFromString(serializer, file.readText()) }.getOrNull()
    }

    /** Replaces the saved list of [key]; the write is atomic so a crash cannot leave half a file. */
    fun save(key: String, items: List<T>) {
        directory.mkdirs()
        val target = fileFor(key)
        val temporary = File(directory, "${target.name}.tmp")
        temporary.writeText(json.encodeToString(serializer, items))
        if (!temporary.renameTo(target)) {
            target.delete()
            check(temporary.renameTo(target)) { "Could not replace ${target.name}" }
        }
    }

    private fun fileFor(key: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
        return File(directory, digest.joinToString("") { "%02x".format(it) }.take(32) + ".json")
    }
}
