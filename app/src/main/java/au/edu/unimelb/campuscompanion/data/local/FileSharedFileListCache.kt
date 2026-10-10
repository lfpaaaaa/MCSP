package au.edu.unimelb.campuscompanion.data.local

import au.edu.unimelb.campuscompanion.data.remote.SharedFileRow
import au.edu.unimelb.campuscompanion.data.repository.SharedFileListCache
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

/**
 * [SharedFileListCache] that keeps one JSON file per user and group in a directory of the app's
 * private storage. The directory is looked up on first use, so constructing the cache touches no disk.
 */
class FileSharedFileListCache(directoryHolder: Lazy<File>) : SharedFileListCache {

    constructor(directory: File) : this(lazyOf(directory))

    private val store = JsonListStore(directoryHolder, ListSerializer(SharedFileRow.serializer()))

    override fun load(userId: String, groupId: String): List<SharedFileRow>? =
        store.load(keyFor(userId, groupId))

    override fun save(userId: String, groupId: String, rows: List<SharedFileRow>) =
        store.save(keyFor(userId, groupId), rows)

    private fun keyFor(userId: String, groupId: String) = "$userId/$groupId"
}
