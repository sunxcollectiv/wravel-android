//
//  PlaceImageCacheTests.kt
//  outboundTests
//

package earth.outbound

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.colorspace.ColorSpace
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import earth.outbound.networking.PlaceImageCache
import earth.outbound.networking.ResponseCache
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * How the in-memory cover photo cache saves repeat requests (each one a billed Google
 * Place Photo load) without remembering failures.
 */
class PlaceImageCacheTests {
    /** A cache loading from the stub, as a fresh launch of the app would have. */
    private fun cache(disk: ResponseCache? = null) = PlaceImageCache(
        session = OkHttpClient.Builder().addInterceptor(StubImageServer).build(),
        disk = disk,
        decode = { data -> if (data.isEmpty()) null else StubImage },
    )

    @Test
    fun aLoadedImageIsServedFromMemory() = runBlocking {
        val url = StubImageServer.url(status = 200)
        val cache = cache()

        assertNull(cache.cachedImage(url))
        assertNotNull(cache.image(url))
        assertNotNull(cache.image(url))

        assertNotNull(cache.cachedImage(url))
        assertEquals(1, StubImageServer.requestCount(url))
    }

    @Test
    fun concurrentLoadsShareOneRequest() = runBlocking {
        val url = StubImageServer.url(status = 200)
        val cache = cache()

        val first = async { cache.image(url) }
        val second = async { cache.image(url) }
        val images = awaitAll(first, second)

        assertTrue(images.all { it != null })
        assertEquals(1, StubImageServer.requestCount(url))
    }

    @Test
    fun aFailedLoadIsRetriedNextTime() = runBlocking {
        val url = StubImageServer.url(status = 403)
        val cache = cache()

        assertNull(cache.image(url))
        assertNull(cache.image(url))

        assertNull(cache.cachedImage(url))
        assertEquals(2, StubImageServer.requestCount(url))
    }

    @Test
    fun aDiskBackedCacheServesAPhotoAfterRelaunch() = runBlocking {
        val url = StubImageServer.url(status = 200)
        val disk = ResponseCache(File(System.getProperty("java.io.tmpdir"), "PlaceImageCacheTests-${UUID.randomUUID()}"))

        assertNotNull(cache(disk = disk).image(url))
        // A fresh cache stands in for a cold launch: nothing in memory, only on disk.
        assertNotNull(cache(disk = disk).image(url))

        assertEquals(1, StubImageServer.requestCount(url))
        assertNotNull(disk.data(PlaceImageCache.diskKey(url)))
    }

    @Test
    fun theMemoryOnlyCacheFetchesAgainAfterRelaunch() = runBlocking {
        val url = StubImageServer.url(status = 200)

        assertNotNull(cache().image(url))
        assertNotNull(cache().image(url))

        assertEquals(2, StubImageServer.requestCount(url))
    }
}

/**
 * Answers image requests locally: a few bytes standing in for a PNG, with the status
 * code named in the URL's `status` query item. Counts requests per URL; each test uses
 * its own URL, so the counts stay apart when tests run in parallel.
 */
private object StubImageServer : Interceptor {
    private val counts = ConcurrentHashMap<String, Int>()

    fun url(status: Int): String = "https://stub.test/${UUID.randomUUID()}/media?status=$status"

    fun requestCount(url: String): Int = counts[url] ?: 0

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        counts.merge(request.url.toString(), 1, Int::plus)

        val status = request.url.queryParameter("status")?.toIntOrNull() ?: 200
        val data = if (status == 200) byteArrayOf(1, 2, 3) else ByteArray(0)
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(status)
            .message("")
            .body(data.toResponseBody("image/png".toMediaType()))
            .build()
    }
}

/** What the stubbed decoder makes of a loaded photo: a 1×1 image with nothing behind it. */
private object StubImage : ImageBitmap {
    override val width = 1
    override val height = 1
    override val colorSpace: ColorSpace = ColorSpaces.Srgb
    override val hasAlpha = false
    override val config = ImageBitmapConfig.Argb8888

    override fun readPixels(buffer: IntArray, startX: Int, startY: Int, width: Int, height: Int, bufferOffset: Int, stride: Int) {}

    override fun prepareToDraw() {}
}
