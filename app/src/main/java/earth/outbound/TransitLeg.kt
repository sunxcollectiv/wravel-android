//
//  TransitLeg.kt
//  outbound
//

package earth.outbound

import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/**
 * One leg of a transit being edited: how it moves, where it leaves from and arrives
 * at, and when, each end with its own zone. Times are canvas wall clocks, as in
 * `TripItemDraft`.
 *
 * A value: an edit makes a changed copy (which keeps its `id`), put back into the
 * draft with `TripItemDraft.updateLeg`.
 */
data class TransitLeg(
    val transitMode: TransitMode? = null,
    /** The origin, picked via the place search → sent as `place_id`. */
    val startPlace: Place? = null,
    /** The text the origin field searches on. */
    val startPlaceText: String = "",
    /** The destination → sent as `end_place_id`. */
    val endPlace: Place? = null,
    val endPlaceText: String = "",
    /** The departure; nil once cleared, which blocks saving. */
    val startsAt: Instant? = null,
    /** The arrival; nil leaves the leg without one. */
    val endsAt: Instant? = null,
    val startZone: ZoneId? = null,
    val endZone: ZoneId? = null,
    /**
     * Booking details of a saved leg, which the form doesn't edit. Kept so a save,
     * which replaces every leg, doesn't drop them.
     */
    val carrier: String? = null,
    val flightNumber: String? = null,
    val confirmationNumber: String? = null,
    /** The origin's time zone from Google. Set it with `withStartPlaceZone`. */
    val startPlaceZone: ZoneId? = null,
    /** The destination's time zone from Google. Set it with `withEndPlaceZone`. */
    val endPlaceZone: ZoneId? = null,
    val id: UUID = UUID.randomUUID(),
) {
    /**
     * A saved leg, its places counting as picked so they're sent back as they are.
     * `Mixed` never applies to a single leg, but is dropped here all the same.
     */
    constructor(segment: TransitSegment) : this(
        transitMode = segment.transitMode.takeUnless { it == TransitMode.Mixed },
        startPlace = segment.place,
        startPlaceText = segment.place?.name ?: "",
        endPlace = segment.endPlace,
        endPlaceText = segment.endPlace?.name ?: "",
        // `startsAtLocal` is a floating UTC date, so reading it in UTC gives the wall clock.
        startsAt = TripItemDraft.wallClock(segment.startsAtLocal, ZoneOffset.UTC),
        endsAt = TripItemDraft.wallClock(segment.endsAtLocal, ZoneOffset.UTC),
        startZone = segment.startTimezone,
        endZone = segment.endTimezone,
        carrier = segment.carrier,
        flightNumber = segment.flightNumber,
        confirmationNumber = segment.confirmationNumber,
    )

    /** The leg with the origin's zone from Google; a picked place's zone becomes `startZone`. */
    fun withStartPlaceZone(zone: ZoneId?): TransitLeg =
        copy(startPlaceZone = zone, startZone = zone ?: startZone)

    /** The leg with the destination's zone from Google; a picked place's zone becomes `endZone`. */
    fun withEndPlaceZone(zone: ZoneId?): TransitLeg =
        copy(endPlaceZone = zone, endZone = zone ?: endZone)

    /**
     * A new leg to follow this one: leaving from where this one arrives, when it
     * arrives (or departs, without an arrival), by the same means.
     */
    fun next(): TransitLeg = TransitLeg(
        transitMode = transitMode,
        startPlace = endPlace,
        startPlaceText = endPlaceText,
        startsAt = endsAt ?: startsAt,
        startZone = if (endsAt != null) endZone ?: startZone else startZone,
    )

    /**
     * Whether this leg arrives before it departs, comparing instants so a leg can
     * cross zones. An arrival with no zone is read in the departure's.
     */
    val endsBeforeStart: Boolean
        get() {
            val startsAt = startsAt ?: return false
            val endsAt = endsAt ?: return false
            return TripItemDraft.instant(endsAt, endZone ?: startZone ?: ZoneOffset.UTC) <
                TripItemDraft.instant(startsAt, startZone ?: ZoneOffset.UTC)
        }

    /** The leg as sent, or nil without a departure (which the server requires). */
    val request: TripItemsAPI.SegmentRequest?
        get() {
            val startsAt = startsAt ?: return null
            return TripItemsAPI.SegmentRequest(
                startsAt = TripItemDraft.naiveString(startsAt),
                startTimezone = startZone?.id,
                endsAt = endsAt?.let(TripItemDraft::naiveString),
                endTimezone = if (endsAt != null) endZone?.id else null,
                placeId = startPlace?.id,
                endPlaceId = endPlace?.id,
                transitMode = transitMode.takeUnless { it == TransitMode.Mixed },
                carrier = carrier,
                flightNumber = flightNumber,
                confirmationNumber = confirmationNumber,
            )
        }
}
