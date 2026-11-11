//
//  CreditStore.kt
//  outbound
//

package earth.outbound

import android.app.Activity
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingClient.ProductType
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.consumePurchase
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import earth.outbound.networking.APIClient
import earth.outbound.networking.APIError
import earth.outbound.networking.localizedDescription
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The Google Play products credits are sold as: one consumable per amount on offer.
 * They must match the in-app products set up in the Play Console (and share their
 * ids with the iOS app's App Store products).
 */
object CreditProducts {
    /** Product ids by the credits they buy, in cents. */
    val idsByCents: Map<Int, String> = mapOf(
        500 to "earth.outbound.credits.500",
        1_500 to "earth.outbound.credits.1500",
        2_500 to "earth.outbound.credits.2500",
        5_000 to "earth.outbound.credits.5000",
    )

    fun cents(productID: String): Int? =
        idsByCents.entries.firstOrNull { it.value == productID }?.key
}

/** How buying credits ended, for the popup to react to. */
enum class CreditPurchaseOutcome {
    /** Paid for and credited; the balance is up to date. */
    Credited,

    /**
     * Paid for, but the server hasn't credited it yet. It's kept and sent again on
     * the next load, so the credits aren't lost.
     */
    AwaitingCredit,

    /** Waiting on payment (e.g. cash at a shop, a parent's approval); it's credited once paid. */
    Pending,
    Cancelled,

    /** Paid for, but the server refused to credit it (`errorMessage` says why). */
    Refused,
    Failed,
}

/** One of the `CreditProducts` as Google Play sells it. */
data class CreditProduct(
    val id: String,
    /** The price in the buyer's currency, formatted by Play, e.g. "$15.00". */
    val displayPrice: String,
)

/** A purchase Google Play holds that hasn't been consumed yet. */
data class CreditPurchase(
    /** Play's token for the purchase: what the server verifies, and what it's credited once by. */
    val purchaseToken: String,
    val productID: String?,
    /** Whether Play is still waiting on the payment; there's nothing to credit until it isn't. */
    val isPending: Boolean = false,
)

/** A failure on Google Play's side, with a message fit to show. */
class BillingError(message: String) : Exception(message)

/**
 * What `CreditStore` needs from Google Play Billing. `PlayBilling` is the real thing;
 * tests stand in a fake.
 */
interface BillingGateway {
    /** The products with `ids` that Play offers this user. */
    suspend fun products(ids: List<String>): List<CreditProduct>

    /**
     * Runs Play's purchase flow for a product over `activity`, tagging the purchase
     * with `accountId`. Returns the purchase (which may still be pending), or null if
     * the user backed out.
     */
    suspend fun purchase(productID: String, accountId: String?, activity: Activity): CreditPurchase?

    /** Every purchase not consumed yet; empty when Play can't be asked. */
    suspend fun unfinished(): List<CreditPurchase>

    /** Purchases that complete outside `purchase`, as they come in. */
    val updates: Flow<CreditPurchase>

    /** Consumes a purchase, so it's done with and the product can be bought again. */
    suspend fun finish(purchase: CreditPurchase)
}

/**
 * Loads and holds the signed-in user's credits, and buys more through Google Play.
 *
 * A purchase is only consumed with Play once the server has credited it, so one
 * the server didn't get (offline, an error) is sent again from Play's unconsumed
 * purchases until it's credited. One the server refuses isn't sent again on every
 * load (see `deliver`).
 */
class CreditStore(
    private val api: APIClient = APIClient.shared,
    private val billing: BillingGateway = PlayBilling.shared,
) {
    var credits by mutableStateOf<CreditsAPI.Credits?>(null)
        private set

    /** Google Play's products by the credits they buy, once loaded. */
    var products by mutableStateOf(mapOf<Int, CreditProduct>())
        private set

    /** The amount being bought, while a purchase is underway. */
    var purchasingCents by mutableStateOf<Int?>(null)
        private set
    var errorMessage by mutableStateOf<String?>(null)

    private var isObservingTransactions = false

    /**
     * Purchases the server refused but that may yet be credited (see `deliver`): left
     * unconsumed, but not sent again until the next launch or sign-in, which starts a
     * new store.
     */
    private val heldPurchaseTokens = mutableSetOf<String>()

    init {
        credits = api.cached(CreditsAPI.read)
    }

    val balanceCents: Int? get() = credits?.balanceCents
    val entries: List<CreditLedgerEntry> get() = credits?.entries ?: emptyList()

    /** Fetches the balance and ledger, after sending any purchase not yet credited. */
    suspend fun load() {
        deliverUnfinished()
        try {
            credits = api.send<CreditsAPI.Credits>(CreditsAPI.read)
            errorMessage = null
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            errorMessage = error.localizedDescription
        }
    }

    /** Fetches the products from Google Play, unless they're already here. */
    suspend fun loadProducts() {
        if (products.isNotEmpty()) return
        try {
            val loaded = billing.products(CreditProducts.idsByCents.values.toList())
            products = loaded.mapNotNull { product ->
                CreditProducts.cents(product.id)?.let { it to product }
            }.toMap()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            errorMessage = error.localizedDescription
        }
    }

    /**
     * Buys the product for `cents` of credits and has the server credit it. Play's
     * purchase sheet is shown over `activity`. `accountId` (the signed-in user's id)
     * tags the purchase as theirs, so the server won't credit it to anyone else.
     */
    suspend fun purchase(cents: Int, accountId: String?, activity: Activity): CreditPurchaseOutcome {
        if (purchasingCents != null) return CreditPurchaseOutcome.Failed
        loadProducts()
        val product = products[cents]
        if (product == null) {
            errorMessage = "Credits can't be purchased right now. Please try again later."
            return CreditPurchaseOutcome.Failed
        }
        purchasingCents = cents
        try {
            val purchase = billing.purchase(product.id, accountId?.takeIf(::isUUID), activity)
                ?: return CreditPurchaseOutcome.Cancelled
            return deliver(purchase)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            errorMessage = error.localizedDescription
            return CreditPurchaseOutcome.Failed
        } finally {
            purchasingCents = null
        }
    }

    /**
     * Credits purchases that complete outside `purchase(cents)`, e.g. a pending one
     * paid for later, or one interrupted by the app closing. Runs for as long as the
     * calling coroutine does; call it once from a long-lived screen.
     */
    suspend fun observeTransactions() {
        if (isObservingTransactions) return
        isObservingTransactions = true
        try {
            deliverUnfinished()
            billing.updates.collect { deliver(it) }
        } finally {
            isObservingTransactions = false
        }
    }

    private suspend fun deliverUnfinished() {
        for (purchase in billing.unfinished()) {
            if (purchase.purchaseToken in heldPurchaseTokens) continue
            deliver(purchase)
        }
    }

    /**
     * Sends a purchase to the server and, once it's credited, consumes it. Play's own
     * word that it was paid for isn't checked here: the server verifies the token.
     */
    private suspend fun deliver(purchase: CreditPurchase): CreditPurchaseOutcome {
        val productID = purchase.productID?.takeIf { CreditProducts.cents(it) != null }
            ?: return CreditPurchaseOutcome.Failed
        // Not paid for yet: nothing to credit. It comes back through `updates`, or
        // the next load, once it is. (A refunded or revoked one never shows up here:
        // Play drops it, and the server learns of refunds from Play.)
        if (purchase.isPending) return CreditPurchaseOutcome.Pending
        try {
            val updated = api.send<CreditsAPI.Credits>(
                CreditsAPI.purchase(productId = productID, purchaseToken = purchase.purchaseToken)
            )
            credits = updated
            api.storeCached(updated, CreditsAPI.read)
            billing.finish(purchase)
            errorMessage = null
            return CreditPurchaseOutcome.Credited
        } catch (error: CancellationException) {
            throw error
        } catch (error: APIError) {
            return handleRefusal(error, purchase)
        } catch (_: Exception) {
            return CreditPurchaseOutcome.AwaitingCredit
        }
    }

    /**
     * What to do with a purchase the server didn't credit, by why. Only a purchase that
     * can never be credited here is consumed; consuming one that could be would lose
     * what the user paid for.
     */
    private suspend fun handleRefusal(error: APIError, purchase: CreditPurchase): CreditPurchaseOutcome {
        if (error !is APIError.Server) return CreditPurchaseOutcome.AwaitingCredit
        return when (error.status) {
            409 -> {
                // Already credited to another account: done, and sending it again changes
                // nothing.
                billing.finish(purchase)
                errorMessage = "These credits were already added to another account."
                CreditPurchaseOutcome.Refused
            }
            403, 422 -> {
                // 403: bought for another account, whose owner may sign in here later.
                // 422: couldn't be verified, which a misconfigured server can cause too.
                // Kept unconsumed, but not sent again until the next launch or sign-in.
                heldPurchaseTokens.add(purchase.purchaseToken)
                errorMessage = error.serverMessage ?: "Your purchase couldn't be added to this account."
                CreditPurchaseOutcome.Refused
            }
            // Unreachable, unauthorized or a server error: worth trying again.
            else -> CreditPurchaseOutcome.AwaitingCredit
        }
    }

    /** Whether `id` is a UUID, as our user ids are: the only thing a purchase is tagged with. */
    private fun isUUID(id: String): Boolean =
        try {
            UUID.fromString(id)
            true
        } catch (_: IllegalArgumentException) {
            false
        }
}

/**
 * Google Play Billing behind `BillingGateway`: one client for the app, connected on
 * demand (Play drops the connection whenever it likes, so every call checks).
 */
class PlayBilling(context: Context) : BillingGateway {
    private val client = BillingClient.newBuilder(context)
        .setListener(::purchasesUpdated)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .build()

    /** So two callers arriving together share one connection attempt. */
    private val connecting = Mutex()

    /** Play's own records of the products loaded so far, which its purchase flow wants back. */
    private val details = mutableMapOf<String, ProductDetails>()

    /** The purchase flow on screen, waiting for Play to say how it ended. */
    @Volatile
    private var activePurchase: CompletableDeferred<CreditPurchase?>? = null

    private val purchaseUpdates = MutableSharedFlow<CreditPurchase>(extraBufferCapacity = 16)

    override val updates: Flow<CreditPurchase> get() = purchaseUpdates

    override suspend fun products(ids: List<String>): List<CreditProduct> {
        connect()
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(ids.map { id ->
                QueryProductDetailsParams.Product.newBuilder().setProductId(id).setProductType(ProductType.INAPP).build()
            })
            .build()
        val result = client.queryProductDetails(params)
        check(result.billingResult)
        val loaded = result.productDetailsList.orEmpty()
        synchronized(details) { loaded.forEach { details[it.productId] = it } }
        return loaded.map { product ->
            CreditProduct(id = product.productId, displayPrice = product.oneTimePurchaseOfferDetails?.formattedPrice ?: "")
        }
    }

    override suspend fun purchase(productID: String, accountId: String?, activity: Activity): CreditPurchase? {
        connect()
        val product = synchronized(details) { details[productID] } ?: throw BillingError(unavailableMessage)
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(product).build())
            )
            .apply { if (accountId != null) setObfuscatedAccountId(accountId) }
            .build()

        val outcome = CompletableDeferred<CreditPurchase?>()
        activePurchase = outcome
        try {
            check(withContext(Dispatchers.Main) { client.launchBillingFlow(activity, params) })
            return outcome.await()
        } finally {
            activePurchase = null
        }
    }

    override suspend fun unfinished(): List<CreditPurchase> =
        try {
            connect()
            val result = client.queryPurchasesAsync(
                QueryPurchasesParams.newBuilder().setProductType(ProductType.INAPP).build()
            )
            check(result.billingResult)
            result.purchasesList.map(::creditPurchase)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // No Play Store, or it can't be reached: the next load asks again.
            emptyList()
        }

    /**
     * Best-effort: a purchase that couldn't be consumed stays unfinished and is sent to
     * the server again, which answers with the credits unchanged, and consumed then.
     */
    override suspend fun finish(purchase: CreditPurchase) {
        try {
            connect()
            client.consumePurchase(ConsumeParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build())
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
        }
    }

    /**
     * Play reporting on purchases: how the flow on screen ended, or one that completed
     * on its own (a pending purchase paid for, one made on another device).
     */
    private fun purchasesUpdated(result: BillingResult, purchases: List<Purchase>?) {
        val active = activePurchase
        when (result.responseCode) {
            BillingResponseCode.OK -> purchases.orEmpty().map(::creditPurchase).forEach { purchase ->
                if (active?.complete(purchase) != true) purchaseUpdates.tryEmit(purchase)
            }
            BillingResponseCode.USER_CANCELED -> active?.complete(null)
            else -> active?.completeExceptionally(BillingError(message(result)))
        }
    }

    private fun creditPurchase(purchase: Purchase) = CreditPurchase(
        purchaseToken = purchase.purchaseToken,
        productID = purchase.products.firstOrNull(),
        isPending = purchase.purchaseState != Purchase.PurchaseState.PURCHASED,
    )

    private suspend fun connect(): Unit = connecting.withLock {
        if (client.isReady) return@withLock
        suspendCancellableCoroutine { continuation ->
            client.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    if (!continuation.isActive) return
                    if (result.responseCode == BillingResponseCode.OK) {
                        continuation.resume(Unit)
                    } else {
                        continuation.resumeWithException(BillingError(message(result)))
                    }
                }

                // The next call connects again.
                override fun onBillingServiceDisconnected() {}
            })
        }
    }

    private fun check(result: BillingResult) {
        if (result.responseCode != BillingResponseCode.OK) throw BillingError(message(result))
    }

    /** What to tell the user about a failure; Play's own messages are for developers. */
    private fun message(result: BillingResult): String = when (result.responseCode) {
        BillingResponseCode.ITEM_ALREADY_OWNED ->
            "Your last purchase of this amount is still being added to your account."
        BillingResponseCode.BILLING_UNAVAILABLE,
        BillingResponseCode.SERVICE_UNAVAILABLE,
        BillingResponseCode.SERVICE_DISCONNECTED,
        BillingResponseCode.NETWORK_ERROR ->
            "Google Play can't be reached right now. Please try again later."
        else -> unavailableMessage
    }

    companion object {
        val shared: PlayBilling by lazy { PlayBilling(OutboundApp.context) }

        private const val unavailableMessage = "Credits can't be purchased right now. Please try again later."
    }
}

/** The app's `CreditStore`, provided to the signed-in screens. */
val LocalCreditStore = staticCompositionLocalOf<CreditStore> { error("No CreditStore provided") }
