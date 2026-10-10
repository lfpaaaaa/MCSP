package au.edu.unimelb.campuscompanion.data

import android.content.Context
import java.io.File
import java.security.MessageDigest
import java.time.Instant

/**
 * The last calendar that was downloaded successfully, kept in the app's private storage so the
 * Schedule screen, the departure reminders and the course groups work without a connection.
 * The calendar is stored as the bytes the server sent and parsed again on each start, which
 * keeps the weekly occurrences correct as the days go by. One entry is kept per signed-in user.
 */
class TimetableCache(private val directory: File) {

    constructor(context: Context) : this(File(context.applicationContext.filesDir, DIRECTORY_NAME))

    /** A saved calendar: where it came from, its bytes and when it was saved. */
    class Entry(val url: String, val bytes: ByteArray, val savedAt: Instant)

    /** The saved calendar of [userId], or null when there is none or it cannot be read. */
    fun load(userId: String): Entry? {
        val calendar = calendarFile(userId)
        val metadata = metadataFile(userId)
        if (!calendar.isFile || !metadata.isFile) return null
        return runCatching {
            val lines = metadata.readLines()
            val url = lines.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return null
            val savedAt = lines.getOrNull(1)?.toLongOrNull()?.let(Instant::ofEpochMilli) ?: return null
            Entry(url = url, bytes = calendar.readBytes(), savedAt = savedAt)
        }.getOrNull()
    }

    /** Replaces the saved calendar of [userId]; the write is atomic so a crash cannot leave half a file. */
    fun save(userId: String, url: String, bytes: ByteArray, savedAt: Instant = Instant.now()) {
        require(url.isNotBlank()) { "The calendar URL cannot be blank" }
        directory.mkdirs()
        writeAtomically(calendarFile(userId), bytes)
        writeAtomically(metadataFile(userId), "$url\n${savedAt.toEpochMilli()}\n".toByteArray())
    }

    /** Removes the saved calendar of [userId], for example when the subscription URL is removed. */
    fun clear(userId: String) {
        metadataFile(userId).delete()
        calendarFile(userId).delete()
    }

    private fun calendarFile(userId: String) = File(directory, "${keyFor(userId)}.ics")

    private fun metadataFile(userId: String) = File(directory, "${keyFor(userId)}.meta")

    private fun writeAtomically(target: File, bytes: ByteArray) {
        val temporary = File(target.parentFile, "${target.name}.tmp")
        temporary.writeBytes(bytes)
        if (!temporary.renameTo(target)) {
            target.delete()
            check(temporary.renameTo(target)) { "Could not replace ${target.name}" }
        }
    }

    private companion object {
        const val DIRECTORY_NAME = "timetable"

        /** The user id is hashed so it does not appear in file names. */
        fun keyFor(userId: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(userId.toByteArray())
                .joinToString("") { "%02x".format(it) }
                .take(32)
    }
}
