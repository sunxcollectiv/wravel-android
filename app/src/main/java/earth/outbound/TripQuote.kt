//
//  TripQuote.kt
//  outbound
//

package earth.outbound

import kotlinx.serialization.Serializable

/**
 * `TripQuote` from `POST /trips/quote` and `POST /trips/{id}/quote`: what saving a trip
 * would charge the user, against their credits, and how that price is made up.
 */
@Serializable
data class TripQuote(
    /**
     * Trip days and people the price is for, and how many of those people are beyond
     * the included ones.
     */
    val days: Int,
    val members: Int,
    val extraMembers: Int,
    /**
     * What the save would take from the user's credits; 0 when nothing is due (e.g. an
     * edit that doesn't add days or people). Sent back as `expectedPriceCents`.
     */
    val priceCents: Int,
    /** What an edit that lowers the price would give back instead. */
    val refundCents: Int,
    /** The user's credits now. */
    val balanceCents: Int,
    /** How much the price is over the balance; 0 when the credits cover it. */
    val shortfallCents: Int,
    /** The price's breakdown: each trip-day bracket, then each extra-member bracket. */
    val tripLines: List<TripPricing.Line>,
    val memberLines: List<TripPricing.Line>,
    /** The key of the pricing schedule the trip is priced under (see `PricingStore`). */
    val schedule: String? = null,
    /**
     * Net already paid for the trip (charges less refunds); null for a new trip or a
     * server that doesn't send it.
     */
    val paidCents: Int? = null,
    /**
     * Whether giving credits back is still possible: until a day after the trip
     * starts. Null from a server that doesn't send it.
     */
    val refundWindowOpen: Boolean? = null,
) {
    /** The balance once the price is taken. */
    val balanceAfterCents: Int get() = balanceCents - priceCents
}
