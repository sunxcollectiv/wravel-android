//
//  PricingStoreTests.kt
//  outboundTests
//

package earth.outbound

import earth.outbound.networking.APIClient
import earth.outbound.networking.ResponseCache
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.UnknownHostException
import java.util.UUID

/**
 * Loading pricing schedules from the server (stubbed): the current one, older ones by
 * key, a saved copy offline, and which schedule a trip is priced under.
 */
class PricingStoreTests {
    private val cache = ResponseCache(File(System.getProperty("java.io.tmpdir"), "PricingStoreTests-${UUID.randomUUID()}"))

    private fun api(online: Boolean = true): APIClient =
        APIClient(
            baseURL = "https://stub.test".toHttpUrl(),
            session = OkHttpClient.Builder().addInterceptor(StubPricingServer(online)).build(),
            cache = cache,
        )

    @Test
    fun theCurrentScheduleDecodesFromTheServersShape() = runBlocking {
        val store = PricingStore(api = api())

        store.loadCurrent()

        assertEquals("2026-11", store.currentKey)
        assertEquals(rates2026_11, store.current)
        assertEquals(rates2026_11, store.schedule(key = null))
    }

    @Test
    fun anOlderScheduleLoadsByKeyAlongsideTheCurrentOne() = runBlocking {
        val store = PricingStore(api = api())
        store.loadCurrent()

        store.load(key = "2025-01")

        assertEquals(4, store.schedule(key = "2025-01")?.includedMembers)
        assertEquals(400, store.schedule(key = "2025-01")?.priceCents(days = 2))
        assertEquals("2026-11", store.current?.key)
    }

    @Test
    fun eachTripUsesTheScheduleItNames() = runBlocking {
        val store = PricingStore(api = api())
        val old = Trip.sample.copy(priceSchedule = "2025-01")
        val new = Trip.sample.copy(priceSchedule = "2026-11")
        val unnamed = Trip.sample.copy(priceSchedule = null)

        store.load(trips = listOf(old, new, unnamed))

        assertEquals("2025-01", store.schedule(trip = old)?.key)
        assertEquals("2026-11", store.schedule(trip = new)?.key)
        // Not assumed to be the current one, which could misprice an older trip.
        assertNull(store.schedule(trip = unnamed))
    }

    @Test
    fun anUnknownKeyIsUnavailable() = runBlocking {
        val store = PricingStore(api = api())

        store.load(key = "1999-01")

        assertNull(store.schedule(key = "1999-01"))
        assertTrue(store.isUnavailable(key = "1999-01"))
    }

    @Test
    fun savedSchedulesAreUsedOffline() = runBlocking {
        val online = PricingStore(api = api())
        online.loadCurrent()
        online.load(key = "2025-01")

        val offline = PricingStore(api = api(online = false))
        assertEquals(rates2026_11, offline.current)
        offline.loadCurrent()
        offline.load(key = "2025-01")

        assertEquals(rates2026_11, offline.current)
        assertEquals("2025-01", offline.schedule(key = "2025-01")?.key)
        assertFalse(offline.isUnavailable(key = null))
    }

    @Test
    fun withNothingSavedOfflineTheCurrentScheduleIsUnavailable() = runBlocking {
        val store = PricingStore(api = api(online = false))

        store.loadCurrent()

        assertNull(store.current)
        assertTrue(store.isUnavailable(key = null))
    }
}

/**
 * Answers `/pricing/schedule` as the server does: the current "2026-11" schedule, or
 * "2025-01" by key; 404 for other keys. Fails every request when not `online`.
 */
private class StubPricingServer(private val online: Boolean) : Interceptor {
    private val current = """
        {"key": "2026-11", "included_members": 5,
         "trip_brackets": [{"first_day": 1, "last_day": 3, "cents_per_day": 160}, {"first_day": 4, "last_day": 9, "cents_per_day": 80},
           {"first_day": 10, "last_day": 16, "cents_per_day": 70}, {"first_day": 17, "last_day": 23, "cents_per_day": 50},
           {"first_day": 24, "last_day": 30, "cents_per_day": 30}, {"first_day": 31, "last_day": null, "cents_per_day": 20}],
         "member_brackets": [{"first_day": 1, "last_day": 10, "cents_per_day": 20}, {"first_day": 11, "last_day": 25, "cents_per_day": 15},
           {"first_day": 26, "last_day": 40, "cents_per_day": 10}, {"first_day": 41, "last_day": null, "cents_per_day": 5}]}
    """.trimIndent()

    private val older = """
        {"key": "2025-01", "included_members": 4,
         "trip_brackets": [{"first_day": 1, "last_day": null, "cents_per_day": 200}],
         "member_brackets": [{"first_day": 1, "last_day": null, "cents_per_day": 25}]}
    """.trimIndent()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!online) throw UnknownHostException("stub.test")
        val body = when (request.url.queryParameter("key")) {
            null, "2026-11" -> current
            "2025-01" -> older
            else -> null
        }
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(if (body == null) 404 else 200)
            .message("")
            .body((body ?: """{"detail": "No such price schedule"}""").toResponseBody("application/json".toMediaType()))
            .build()
    }
}
