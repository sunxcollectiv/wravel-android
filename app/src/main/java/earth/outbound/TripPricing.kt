//
//  TripPricing.kt
//  outbound
//

package earth.outbound

import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * How trips are priced. The formula is fixed; the rates come from the server as a
 * `PricingSchedule`, versioned so a rate change never reprices a trip that's already
 * paid for: each trip stays on the schedule it was created under.
 */
object TripPricing {
    /**
     * A run of days charged at one rate (`BracketRead`): trip days, or extra
     * member-days. `lastDay` is null for the open-ended bracket at the end.
     */
    @Serializable
    data class Bracket(
        val firstDay: Int,
        val lastDay: Int? = null,
        val centsPerDay: Int,
    )

    /**
     * One bracket's share of a trip's price, for showing the breakdown: the trip days,
     * or extra member-days, that fell in it. Also how the server's quote lists it
     * (`BracketLineRead`).
     */
    @Serializable
    data class Line(
        val firstDay: Int,
        val lastDay: Int,
        val centsPerDay: Int,
    ) {
        val days: Int get() = lastDay - firstDay + 1
        val cents: Int get() = days * centsPerDay
    }
}

/**
 * One version of the rates (`PricingScheduleRead`), and the formula applied to them.
 *
 * Each trip day is charged at the rate of the bracket it falls in, like tax brackets.
 * That covers up to `includedMembers` people, owner included. People beyond that are
 * on the whole trip and charged by the member-day (one extra person for one day),
 * bracketed the same way over all the trip's extra member-days together: so 3 extra
 * people on a 5-day trip cost the same as 1 on a 15-day trip. Amounts are whole
 * cents. Mirrors the server's `app.services.pricing`.
 */
@Serializable
data class PricingSchedule(
    /**
     * The version, e.g. "2026-11"; what a trip's `priceSchedule` and a quote's
     * `schedule` name.
     */
    val key: String,
    /** How many people the trip's own rate covers, owner included. */
    val includedMembers: Int,
    val tripBrackets: List<TripPricing.Bracket>,
    /** Over the extra member-days: people beyond `includedMembers` × trip days. */
    val memberBrackets: List<TripPricing.Bracket>,
) {
    /** The trip brackets a trip of `days` days touches, each cut off at its last day. */
    fun breakdown(days: Int): List<TripPricing.Line> = lines(tripBrackets, days)

    /**
     * Extra member-days on a trip of `days` days with `members` people: those beyond
     * `includedMembers` × days.
     */
    fun memberDays(days: Int, members: Int): Int = maxOf(members - includedMembers, 0) * days

    /** The member brackets `memberDays` extra member-days touch, each cut off at the last. */
    fun memberBreakdown(memberDays: Int): List<TripPricing.Line> = lines(memberBrackets, memberDays)

    /**
     * The price of a trip of `days` days for up to `includedMembers` people, in
     * cents; 0 for no days.
     */
    fun priceCents(days: Int): Int = total(breakdown(days))

    /**
     * What everyone beyond `includedMembers` adds to a trip of `days` days, together,
     * in cents.
     */
    fun memberCents(days: Int, members: Int): Int =
        total(memberBreakdown(memberDays(days = days, members = members)))

    /** The price of a trip of `days` days with `members` people, in cents. */
    fun priceCents(days: Int, members: Int): Int =
        priceCents(days) + memberCents(days = days, members = members)

    /**
     * The longest trip `budgetCents` pays for with `members` people, in days; 0 if it
     * doesn't cover a single day.
     */
    fun maxDays(budgetCents: Int, members: Int): Int {
        // The open-ended brackets charge every further day, so this stops. A schedule
        // whose last brackets are free has no longest trip; cap the search.
        var days = 0
        while (days < 10_000 && priceCents(days = days + 1, members = members) <= budgetCents) {
            days += 1
        }
        return days
    }

    private fun lines(brackets: List<TripPricing.Bracket>, days: Int): List<TripPricing.Line> =
        brackets.mapNotNull { bracket ->
            if (days < bracket.firstDay) return@mapNotNull null
            val lastDay = minOf(days, bracket.lastDay ?: days)
            TripPricing.Line(firstDay = bracket.firstDay, lastDay = lastDay, centsPerDay = bracket.centsPerDay)
        }

    private fun total(lines: List<TripPricing.Line>): Int = lines.sumOf { it.cents }
}

/**
 * How many days the trip spans, counting both its first and last day, or null if
 * its dates don't parse.
 */
val Trip.dayCount: Int?
    get() {
        val (start, end) = try {
            LocalDate.parse(startDate) to LocalDate.parse(endDate)
        } catch (_: Exception) {
            return null
        }
        // A trip that ends before it starts counts as its first day alone.
        return maxOf(ChronoUnit.DAYS.between(start, end).toInt(), 0) + 1
    }

/**
 * What the trip costs for everyone on it under `schedule` (its own, see
 * `PricingStore.schedule(trip)`), in cents, or null if its dates don't parse. A
 * trip whose members haven't loaded is priced as the base trip.
 */
fun Trip.priceCents(schedule: PricingSchedule): Int? =
    dayCount?.let { schedule.priceCents(days = it, members = members?.size ?: 0) }
