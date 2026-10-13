package au.edu.unimelb.campuscompanion.ui.model

import au.edu.unimelb.campuscompanion.data.model.SharedFile

enum class GroupFileFilter { All, Photos, Videos, Audio, Documents }

fun filterGroupFiles(files: List<SharedFile>, query: String, filter: GroupFileFilter): List<SharedFile> =
    files.filter { file ->
        (query.trim().let { it.isEmpty() || file.fileName.contains(it, ignoreCase = true) || file.uploaderName.contains(it, ignoreCase = true) }) &&
            when (filter) {
                GroupFileFilter.All -> true
                GroupFileFilter.Photos -> file.mimeType.startsWith("image/")
                GroupFileFilter.Videos -> file.mimeType.startsWith("video/")
                GroupFileFilter.Audio -> file.mimeType.startsWith("audio/")
                GroupFileFilter.Documents -> listOf("image/", "video/", "audio/").none { file.mimeType.startsWith(it) }
            }
    }.sortedWith(compareByDescending<SharedFile> { it.createdAt }.thenByDescending { it.id })
