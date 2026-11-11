//
//  ResponseCacheTests.kt
//  outboundTests
//

package earth.outbound.networking

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID

/**
 * How the API client saves responses to show offline: only for endpoints that ask,
 * only on success, and gone once cleared.
 */
class ResponseCacheTests {
    @Serializable
    private data class Payload(val name: String)

    private val directory = File(System.getProperty("java.io.tmpdir"), "ResponseCacheTests-${UUID.randomUUID()}")
    private val cache = ResponseCache(directory)

    /**
     * Answers API requests locally: paths under `/ok/` get a JSON payload, anything else
     * a 500.
     */
    private val server = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val ok = request.path.orEmpty().startsWith("/ok/")
                return MockResponse()
                    .setResponseCode(if (ok) 200 else 500)
                    .setHeader("Content-Type", "application/json")
                    .setBody(if (ok) """{"name": "live"}""" else """{"detail": "boom"}""")
            }
        }
    }

    @Before
    fun startServer() {
        server.start()
    }

    @After
    fun stopServer() {
        server.shutdown()
        directory.deleteRecursively()
    }

    private fun client(): APIClient {
        val client = APIClient(baseURL = server.url("/"), session = OkHttpClient(), cache = cache)
        client.token = "token"
        return client
    }

    @Test
    fun aSuccessfulResponseIsSavedAndReadBack() = runBlocking {
        val endpoint = Endpoint(path = "/ok/${UUID.randomUUID()}", cachesResponse = true)
        val api = client()

        assertNull(api.cached<Payload>(endpoint))
        val live = api.send<Payload>(endpoint)

        assertEquals(live, api.cached<Payload>(endpoint))
        assertNotNull(api.cachedDate(endpoint))
    }

    @Test
    fun endpointsThatDontAskAreNotSaved() = runBlocking {
        val endpoint = Endpoint(path = "/ok/${UUID.randomUUID()}")
        val api = client()

        api.send<Payload>(endpoint)

        assertNull(api.cached<Payload>(endpoint))
    }

    @Test
    fun aFailedRequestKeepsTheEarlierCopy() = runBlocking {
        val id = UUID.randomUUID().toString()
        val api = client()
        val saved = Payload(name = "saved")
        api.storeCached(saved, Endpoint(path = "/fail/$id", cachesResponse = true))

        val failure = runCatching { api.send<Payload>(Endpoint(path = "/fail/$id", cachesResponse = true)) }
        assertTrue(failure.exceptionOrNull() is APIError)

        assertEquals(saved, api.cached<Payload>(Endpoint(path = "/fail/$id")))
    }

    @Test
    fun clearingRemovesEverything() = runBlocking {
        val endpoint = Endpoint(path = "/ok/${UUID.randomUUID()}", cachesResponse = true)
        val api = client()
        api.send<Payload>(endpoint)

        cache.removeAll()

        assertNull(api.cached<Payload>(endpoint))
    }

    @Test
    fun clearingAllButSomeKeepsOnlyThose() {
        // A photo URL long enough to be saved under a hashed name.
        val photo = "photo:https://images.unsplash.com/photo-1514395462725?" + "ixid=abc&".repeat(20)
        for (key in listOf("/trips", "/trips/kept/itinerary", "/trips/gone/itinerary", photo, photo + "old")) {
            cache.store(key.encodeToByteArray(), key)
        }

        cache.removeAll(except = setOf("/trips", "/trips/kept/itinerary", photo))

        assertNotNull(cache.data("/trips"))
        assertNotNull(cache.data("/trips/kept/itinerary"))
        assertNotNull(cache.data(photo))
        assertNull(cache.data("/trips/gone/itinerary"))
        assertNull(cache.data(photo + "old"))
    }
}
