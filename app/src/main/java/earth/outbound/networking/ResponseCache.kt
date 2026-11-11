//
//  ResponseCache.kt
//  outbound
//

package earth.outbound.networking

import earth.outbound.OutboundApp
import java.io.File
import java.security.MessageDigest
import java.time.Instant

/**
 * Keeps the last good response of selected GET requests on disk (the trip list and
 * each itinerary), plus the area photos on their covers, so trips can be opened with
 * no connection: in airplane mode, or somewhere with no signal.
 *
 * Responses are stored as the server's raw JSON, so they decode exactly like a live
 * one. Kept in the app's files rather than its cache directory, which the system may
 * purge right when a traveler needs it (the app opts out of backups altogether).
 * Holds one user's data, so it's cleared on sign-out.
 *
 * Responses are saved from background threads as they arrive while screens read and
 * clear from the main one, so changes take turns; a read needs no turn, as a file
 * only ever appears whole.
 */
class ResponseCache(private val directory: File) {
    /** The saved response for `key`, if there is one. */
    fun data(key: String): ByteArray? =
        try {
            file(key).takeIf { it.isFile }?.readBytes()
        } catch (_: Exception) {
            null
        }

    /** When the response for `key` was saved. */
    fun savedDate(key: String): Instant? =
        file(key).takeIf { it.isFile }?.let { Instant.ofEpochMilli(it.lastModified()) }

    /**
     * Saves `data` as the response for `key`, replacing any earlier one. Best-effort:
     * a failed write just leaves the older copy.
     */
    @Synchronized
    fun store(data: ByteArray, key: String) {
        try {
            directory.mkdirs()
            // Written beside it and moved into place, so a reader never sees half a file.
            val target = file(key)
            val temporary = File(directory, target.name + ".tmp")
            temporary.writeBytes(data)
            if (!temporary.renameTo(target)) temporary.delete()
        } catch (_: Exception) {
            // Offline viewing just falls back to the previous copy, if any.
        }
    }

    /** Deletes everything saved, e.g. when the user signs out. */
    @Synchronized
    fun removeAll() {
        directory.deleteRecursively()
    }

    /**
     * Deletes everything saved except what's under `keys`, e.g. to drop trips the
     * user is no longer on and photos that have been replaced.
     */
    @Synchronized
    fun removeAll(except: Set<String>) {
        val kept = except.map { file(it).name }.toSet()
        val files = directory.listFiles() ?: return
        for (file in files) {
            if (file.name !in kept) file.delete()
        }
    }

    /**
     * A file per key, named after it with anything but letters, digits and dashes
     * replaced, e.g. "/trips/abc/itinerary" -> "_trips_abc_itinerary". Keys too long
     * for a file name (photo URLs) are hashed instead.
     */
    private fun file(key: String): File {
        var name = key.map { if (it.isLetterOrDigit() || it == '-') it else '_' }.joinToString("")
        if (name.length > 120) {
            name = MessageDigest.getInstance("SHA-256").digest(key.encodeToByteArray())
                .joinToString("") { "%02x".format(it) }
        }
        return File(directory, name)
    }

    companion object {
        val shared: ResponseCache by lazy { ResponseCache(File(OutboundApp.context.filesDir, "ResponseCache")) }
    }
}
