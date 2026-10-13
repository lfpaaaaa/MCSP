package au.edu.unimelb.campuscompanion.data

import android.content.Context
import android.net.Uri
import java.io.File

/** Only remove recordings created by this app, never a file from an external picker. */
internal fun discardLocalRecording(context: Context, uriText: String) {
    val uri = Uri.parse(uriText)
    if (uri.scheme != "content" || uri.authority != "${context.packageName}.fileprovider" ||
        uri.pathSegments.firstOrNull() != "recordings") return
    val name = uri.lastPathSegment ?: return
    if (!Regex("recording-[A-Za-z0-9-]+\\.m4a").matches(name)) return
    val folder = File(context.filesDir, "recordings").canonicalFile
    val file = File(folder, name).canonicalFile
    if (file.parentFile == folder) file.delete()
}
