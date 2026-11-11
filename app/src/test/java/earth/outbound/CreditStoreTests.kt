//
//  CreditStoreTests.kt
//  outboundTests
//

package earth.outbound

import android.app.Activity
import earth.outbound.networking.APIClient
import earth.outbound.networking.ResponseCache
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/**
 * Buying credits against a stand-in for Google Play (purchases that go through with
 * no sheet), with the server stubbed: a purchase is only consumed once the server
 * credits it or can never credit it; one it might yet credit is kept.
 */
class CreditStoreTests {
    /** Google Play, shared by every store in a test as it is by every launch of the app. */
    private val billing = FakeBilling()
    private val server = StubCreditsServer()
    private val activity = Activity()

    /** A store whose server answers purchases with `purchaseStatus`, with Play's products loaded. */
    private suspend fun store(purchaseStatus: Int = 200): CreditStore {
        server.purchaseStatus = purchaseStatus
        val cache = ResponseCache(File(System.getProperty("java.io.tmpdir"), "CreditStoreTests-${UUID.randomUUID()}"))
        val api = APIClient(
            baseURL = "https://stub.test".toHttpUrl(),
            session = OkHttpClient.Builder().addInterceptor(server).build(),
            cache = cache,
        )
        api.token = "token"
        val credits = CreditStore(api = api, billing = billing)
        credits.loadProducts()
        return credits
    }

    private suspend fun unfinishedProductIDs(): List<String?> = billing.unfinished().map { it.productID }

    @Test
    fun everyAmountOnOfferIsAProduct() = runBlocking {
        val credits = store()

        assertEquals(PurchasePopupMetrics.amountsCents.toSet(), credits.products.keys)
        assertEquals("$15.00", credits.products[1_500]?.displayPrice)
        assertEquals(CreditProducts.idsByCents, credits.products.mapValues { it.value.id })
    }

    @Test
    fun aCreditedPurchaseUpdatesTheBalanceAndIsFinished() = runBlocking {
        val credits = store()

        val outcome = credits.purchase(cents = 1_500, accountId = null, activity = activity)

        assertEquals(CreditPurchaseOutcome.Credited, outcome)
        assertEquals(1_500, credits.balanceCents)
        assertEquals("Credits purchased", credits.entries.firstOrNull()?.title)
        assertTrue(unfinishedProductIDs().isEmpty())
    }

    @Test
    fun aPurchaseTheServerDidntGetIsKeptAndSentAgain() = runBlocking {
        val credits = store(purchaseStatus = 503)

        val outcome = credits.purchase(cents = 500, accountId = null, activity = activity)

        assertEquals(CreditPurchaseOutcome.AwaitingCredit, outcome)
        assertEquals(listOf("earth.outbound.credits.500"), unfinishedProductIDs())

        // The server comes back: the next load sends it again and finishes it.
        server.purchaseStatus = 200
        credits.load()
        assertTrue(unfinishedProductIDs().isEmpty())
        assertEquals(1_500, credits.balanceCents)
    }

    @Test
    fun aPurchaseCreditedToAnotherAccountIsFinished() = runBlocking {
        val credits = store(purchaseStatus = 409)

        val outcome = credits.purchase(cents = 500, accountId = null, activity = activity)

        assertEquals(CreditPurchaseOutcome.Refused, outcome)
        assertNotNull(credits.errorMessage)
        assertTrue(unfinishedProductIDs().isEmpty())
    }

    @Test
    fun aPurchaseThatMayYetBeCreditedIsKeptButNotResentEveryLoad() = runBlocking {
        for (status in listOf(403, 422)) {
            val credits = store(purchaseStatus = status)

            val outcome = credits.purchase(cents = 500, accountId = null, activity = activity)

            assertEquals(CreditPurchaseOutcome.Refused, outcome)
            assertEquals(listOf("earth.outbound.credits.500"), unfinishedProductIDs())

            // Loading again doesn't send it again in this session.
            server.purchaseRequests = 0
            credits.load()
            assertEquals(0, server.purchaseRequests)

            // A new session (next launch or sign-in) tries it again.
            val next = store(purchaseStatus = 200)
            next.load()
            assertTrue(unfinishedProductIDs().isEmpty())
        }
    }

    @Test
    fun aPurchaseIsSentAsItsProductAndPlayToken() = runBlocking {
        val credits = store()

        credits.purchase(cents = 2_500, accountId = null, activity = activity)

        val body = server.lastPurchaseBody.orEmpty()
        assertTrue(body, body.contains("\"platform\":\"android\""))
        assertTrue(body, body.contains("\"product_id\":\"earth.outbound.credits.2500\""))
        assertTrue(body, body.contains("\"purchase_token\":\"${billing.lastToken}\""))
    }

    @Test
    fun aPurchaseIsTaggedWithTheAccountBuyingIt() = runBlocking {
        val credits = store()
        val accountId = UUID.randomUUID().toString()

        credits.purchase(cents = 500, accountId = accountId, activity = activity)
        assertEquals(accountId, billing.lastAccountId)

        // Only ever one of our user ids, never anything else that was passed in.
        credits.purchase(cents = 500, accountId = "someone@example.com", activity = activity)
        assertEquals(null, billing.lastAccountId)
    }

    @Test
    fun aPendingPurchaseIsCreditedOncePaidFor() = runBlocking {
        val credits = store()
        billing.paysLater = true

        val outcome = credits.purchase(cents = 500, accountId = null, activity = activity)

        assertEquals(CreditPurchaseOutcome.Pending, outcome)
        assertEquals(0, server.purchaseRequests)
        assertEquals(listOf("earth.outbound.credits.500"), unfinishedProductIDs())

        // Loading while it's still unpaid leaves it alone.
        credits.load()
        assertEquals(0, server.purchaseRequests)

        billing.payPending()
        credits.load()
        assertEquals(1, server.purchaseRequests)
        assertTrue(unfinishedProductIDs().isEmpty())
    }

    @Test
    fun backingOutOfThePurchaseSheetBuysNothing() = runBlocking {
        val credits = store()
        billing.userCancels = true

        val outcome = credits.purchase(cents = 500, accountId = null, activity = activity)

        assertEquals(CreditPurchaseOutcome.Cancelled, outcome)
        assertEquals(null, credits.purchasingCents)
        assertEquals(0, server.purchaseRequests)
        assertTrue(unfinishedProductIDs().isEmpty())
    }
}

/**
 * Stands in for Google Play: sells every credit product, and holds each purchase
 * until it's consumed.
 */
private class FakeBilling : BillingGateway {
    /** The next purchase is left waiting on payment, until `payPending`. */
    var paysLater = false

    /** The next purchase sheet is backed out of. */
    var userCancels = false
    var lastAccountId: String? = null
    var lastToken: String? = null

    private var held = listOf<CreditPurchase>()

    override val updates: Flow<CreditPurchase> = MutableSharedFlow()

    override suspend fun products(ids: List<String>): List<CreditProduct> =
        ids.map { id ->
            val cents = checkNotNull(CreditProducts.cents(id))
            CreditProduct(id = id, displayPrice = "$%d.%02d".format(cents / 100, cents % 100))
        }

    override suspend fun purchase(productID: String, accountId: String?, activity: Activity): CreditPurchase? {
        lastAccountId = accountId
        if (userCancels) return null
        val purchase = CreditPurchase(purchaseToken = UUID.randomUUID().toString(), productID = productID, isPending = paysLater)
        lastToken = purchase.purchaseToken
        held = held + purchase
        return purchase
    }

    override suspend fun unfinished(): List<CreditPurchase> = held

    override suspend fun finish(purchase: CreditPurchase) {
        held = held.filter { it.purchaseToken != purchase.purchaseToken }
    }

    fun payPending() {
        held = held.map { it.copy(isPending = false) }
    }
}

/**
 * Answers `/credits` as the server does, in its snake_case shape, and
 * `/credits/purchases` with `purchaseStatus` (the credits on success, an error
 * otherwise), counting purchase requests.
 */
private class StubCreditsServer : Interceptor {
    @Volatile
    var purchaseStatus = 200

    @Volatile
    var purchaseRequests = 0

    @Volatile
    var lastPurchaseBody: String? = null

    private val credits = """
        {"balance_cents": 1500, "entries": [{"id": "6f1c1f43-7c55-4c1e-9d0a-0c2f2b7e1a11", "kind": "purchase",
         "amount_cents": 1500, "trip_id": null, "description": null, "created_at": "2026-11-04T10:00:00.123456Z",
         "title": "Credits purchased"}]}
    """.trimIndent()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val isPurchase = request.url.encodedPath == "/credits/purchases"
        if (isPurchase) {
            purchaseRequests += 1
            lastPurchaseBody = okio.Buffer().also { request.body?.writeTo(it) }.readUtf8()
        }
        val status = if (isPurchase) purchaseStatus else 200
        val ok = status in 200..299
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(status)
            .message("")
            .body((if (ok) credits else """{"detail": "Refused"}""").toResponseBody("application/json".toMediaType()))
            .build()
    }
}
