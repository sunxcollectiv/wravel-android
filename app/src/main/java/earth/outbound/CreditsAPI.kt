//
//  CreditsAPI.kt
//  outbound
//

package earth.outbound

import earth.outbound.networking.Endpoint
import earth.outbound.networking.HTTPMethod
import earth.outbound.networking.jsonBody
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable

/** The signed-in user's credits: their balance and ledger, and crediting a store purchase. */
object CreditsAPI {
    /** `CreditsRead`: the balance, and the ledger behind it, newest first. */
    @Serializable
    data class Credits(
        val balanceCents: Int,
        val entries: List<CreditLedgerEntry>,
    )

    /**
     * `CreditPurchaseCreate`: a completed Google Play purchase, as the product bought
     * and the purchase token Play issued for it. The server is to check the token with
     * the Google Play Developer API and credit the purchase once, keyed by that token.
     *
     * The endpoint is the one the iOS app sends its App Store purchases to (as
     * `{ "signed_transaction": … }`); `platform` is what tells the two apart. The
     * server needs a matching Play verification path for this body: until it has one,
     * it turns these down as unverifiable (422), which keeps the purchase unconsumed
     * for a later try (see `CreditStore`).
     */
    @Serializable
    data class PurchaseRequest(
        @EncodeDefault val platform: String = "android",
        val productId: String,
        val purchaseToken: String,
    )

    val read = Endpoint(path = "/credits", cachesResponse = true)

    /**
     * Credits a purchase and returns the credits after it. Sending one that's already
     * credited returns the credits unchanged, so a retry is safe.
     */
    fun purchase(productId: String, purchaseToken: String) = Endpoint(
        path = "/credits/purchases",
        method = HTTPMethod.POST,
        body = jsonBody(PurchaseRequest(productId = productId, purchaseToken = purchaseToken)),
    )
}
