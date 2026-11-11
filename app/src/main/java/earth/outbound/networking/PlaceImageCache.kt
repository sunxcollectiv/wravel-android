//
//  PlaceImageCache.kt
//  outbound
//

package earth.outbound.networking

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Loads place cover photos and keeps them in memory for the app session, so a card
 * rebuilt by a day swipe or a return to the trip shows its photo instantly instead
 * of re-requesting it (each load of a cover `url` is a billed Google Place Photo
 * request).
 *
 * `shared` is memory only, by design: Google's terms don't allow storing Places
 * photos, so nothing is written to disk (the HTTP session keeps no response cache
 * either), and the least recently shown images are dropped once memory fills up. A
 * cold launch fetches again.
 *
 * `areaPhotos` also saves to disk, so trip covers still show offline: they're
 * Unsplash photos, which may be kept on the device (still loaded from Unsplash's
 * own URLs). They're saved alongside the saved trips and cleared with them on
 * sign-out.
 */
class PlaceImageCache(
    private val session: OkHttpClient = APIClient.sharedSession,
    /** Where loaded images are also saved, if anywhere; null keeps them in memory only. */
    private val disk: ResponseCache? = null,
    /** Turns a response's bytes into an image, or null if they aren't one. */
    private val decode: (ByteArray) -> ImageBitmap? = ::decodeBitmap,
) {
    // Roughly 100 MB of decoded bitmaps; an 800px cover is a few MB.
    private val images = ImageMemory(costLimit = 100L * 1024 * 1024)

    /** Loads in flight, so cards showing the same place share one request. */
    private val inFlight = mutableMapOf<String, Deferred<ImageBitmap?>>()

    /** Loads outlive the card that asked, so the others waiting on one still get it. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The image for `url` if it's already loaded this session. */
    fun cachedImage(url: String): ImageBitmap? = images.get(url)

    /**
     * The image for `url`, from memory, from disk, or loaded now. Null when the request or
     * decoding fails (e.g. a 403 once the photo reference expires); failures aren't
     * remembered, so a later view tries again.
     */
    suspend fun image(url: String): ImageBitmap? {
        val task = synchronized(inFlight) {
            cachedImage(url)?.let { return it }
            inFlight.getOrPut(url) {
                scope.async {
                    val image = load(url)
                    synchronized(inFlight) {
                        inFlight.remove(url)
                        if (image != null) images.set(image, url)
                    }
                    image
                }
            }
        }
        return task.await()
    }

    private suspend fun load(url: String): ImageBitmap? {
        val key = diskKey(url)
        disk?.data(key)?.let(decode)?.let { return it }
        val data = try {
            session.newCall(Request.Builder().url(url).build()).await().use { response ->
                if (response.code == 200) response.body?.bytes() else null
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        } ?: return null
        // Decoded here, off the main thread, rather than on first draw mid-scroll.
        val image = decode(data) ?: return null
        disk?.store(data, key)
        return image
    }

    companion object {
        val shared: PlaceImageCache by lazy { PlaceImageCache() }

        /** For areas' Unsplash photos, kept on disk as well as in memory. */
        val areaPhotos: PlaceImageCache by lazy { PlaceImageCache(disk = ResponseCache.shared) }

        /** What a photo is saved under on disk. */
        fun diskKey(url: String): String = "photo:$url"
    }
}

/** Decodes downloaded bytes into a bitmap that's ready to draw. */
private fun decodeBitmap(data: ByteArray): ImageBitmap? =
    BitmapFactory.decodeByteArray(data, 0, data.size)?.asImageBitmap()?.also { it.prepareToDraw() }

/**
 * Images by URL, up to `costLimit` bytes of them: past that, the ones shown longest
 * ago go first.
 */
private class ImageMemory(private val costLimit: Long) {
    private val images = LinkedHashMap<String, ImageBitmap>(16, 0.75f, true)
    private var totalCost = 0L

    @Synchronized
    fun get(url: String): ImageBitmap? = images[url]

    @Synchronized
    fun set(image: ImageBitmap, url: String) {
        images.put(url, image)?.let { totalCost -= it.memoryCost }
        totalCost += image.memoryCost
        val eldest = images.entries.iterator()
        while (totalCost > costLimit && images.size > 1) {
            totalCost -= eldest.next().value.memoryCost
            eldest.remove()
        }
    }
}

/** Approximate decoded size in bytes, for the cache's cost limit. */
private val ImageBitmap.memoryCost: Long
    get() = 4L * width * height
