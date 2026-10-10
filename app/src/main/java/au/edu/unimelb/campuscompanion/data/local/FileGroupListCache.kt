package au.edu.unimelb.campuscompanion.data.local

import au.edu.unimelb.campuscompanion.data.remote.GroupSummaryRow
import au.edu.unimelb.campuscompanion.data.repository.GroupListCache
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

/**
 * [GroupListCache] that keeps one JSON file per user in a directory of the app's private storage.
 * The directory is looked up on first use, so constructing the cache touches no disk.
 */
class FileGroupListCache(directoryHolder: Lazy<File>) : GroupListCache {

    constructor(directory: File) : this(lazyOf(directory))

    private val store = JsonListStore(directoryHolder, ListSerializer(GroupSummaryRow.serializer()))

    override fun load(userId: String): List<GroupSummaryRow>? = store.load(userId)

    override fun save(userId: String, rows: List<GroupSummaryRow>) = store.save(userId, rows)
}
