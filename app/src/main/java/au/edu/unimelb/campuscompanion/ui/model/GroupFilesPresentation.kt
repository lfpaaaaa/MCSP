package au.edu.unimelb.campuscompanion.ui.model

import au.edu.unimelb.campuscompanion.data.DataError
import au.edu.unimelb.campuscompanion.data.model.FileCatalogState
import au.edu.unimelb.campuscompanion.data.model.SharedFile
import au.edu.unimelb.campuscompanion.data.toUserMessage

enum class GroupFileFilter { All, Photos, Videos, Audio, Documents }

fun filterGroupFiles(files: List<SharedFile>, query: String, filter: GroupFileFilter): List<SharedFile> {
    val search = query.trim()
    return files.filter { file ->
        (search.isEmpty() || file.fileName.contains(search, ignoreCase = true) ||
            file.uploaderName.contains(search, ignoreCase = true)) && when (filter) {
            GroupFileFilter.All -> true
            GroupFileFilter.Photos -> file.mimeType.trim().startsWith("image/", ignoreCase = true)
            GroupFileFilter.Videos -> file.mimeType.trim().startsWith("video/", ignoreCase = true)
            GroupFileFilter.Audio -> file.mimeType.trim().startsWith("audio/", ignoreCase = true)
            GroupFileFilter.Documents -> listOf("image/", "video/", "audio/").none {
                file.mimeType.trim().startsWith(it, ignoreCase = true)
            }
        }
    }.sortedWith(compareByDescending<SharedFile> { it.createdAt }.thenByDescending { it.id })
}

data class GroupFilesPresentation(
    val files: List<SharedFile>,
    val isLoading: Boolean,
    val canBrowseFiles: Boolean,
    val statusMessage: String?,
    val errorMessage: String?,
    val emptyMessage: String?
)

/** Cached attachments remain browsable during a connection failure, while access denials hide them. */
fun presentGroupFiles(catalog: FileCatalogState, query: String, filter: GroupFileFilter): GroupFilesPresentation {
    val canBrowse = catalog.error !is DataError.Forbidden && catalog.error !is DataError.Unauthenticated &&
        catalog.error !is DataError.NotFound
    val files = if (canBrowse) filterGroupFiles(catalog.files, query, filter) else emptyList()
    val status = when {
        !canBrowse -> null
        catalog.isCached && catalog.isLoading -> "Showing saved files while checking for updates…"
        catalog.isCached -> "Showing saved files. Refresh to check for changes."
        else -> null
    }
    val error = when (catalog.error) {
        is DataError.Forbidden -> "You do not have access to this group's files. Refresh to check your membership."
        is DataError.Unauthenticated -> "Sign in again to view this group's files."
        is DataError.NotFound -> "This group or its files are no longer available. Refresh to check again."
        else -> catalog.error?.toUserMessage()?.body
    }
    val empty = when {
        !canBrowse || (!catalog.hasLoaded && !catalog.isCached) -> null
        catalog.files.isEmpty() -> "No files have been shared in this group yet."
        files.isEmpty() -> "No files match your search or filter."
        else -> null
    }
    return GroupFilesPresentation(files, catalog.isLoading, canBrowse, status, error, empty)
}
