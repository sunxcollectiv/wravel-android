//
//  TripPricingTests.kt
//  outboundTests
//

package earth.outbound

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The formula, on a copy of the server's "2026-11" rates (the schedules themselves
 * come from the server). Expected prices match the server's `app.services.pricing`.
 */
class TripPricingTests {
    private val rates = rates2026_11

    @Test
    fun priceForLength() {
        val cases = listOf(
            0 to 0, 1 to 160, 3 to 480, 4 to 560, 7 to 800, 9 to 960, 10 to 1030,
            14 to 1310, 16 to 1450, 17 to 1500, 21 to 1700, 23 to 1800, 24 to 1830,
            30 to 2010, 31 to 2030, 45 to 2310, 60 to 2610, 90 to 3210,
        )
        for ((days, cents) in cases) {
            assertEquals("$days days", cents, rates.priceCents(days = days))
        }
    }

    @Test
    fun eachExtraDayNeverCostsMoreThanTheOneBefore() {
        val perDay = (1..60).map { rates.priceCents(days = it) - rates.priceCents(days = it - 1) }
        assertTrue(perDay.zipWithNext().all { (day, next) -> day >= next })
    }

    @Test
    fun bracketsRunBackToBack() {
        val brackets = rates.tripBrackets
        assertEquals(1, brackets.firstOrNull()?.firstDay)
        assertNull(brackets.lastOrNull()?.lastDay)
        for ((bracket, next) in brackets.zipWithNext()) {
            assertEquals(next.firstDay, bracket.lastDay?.let { it + 1 })
        }
    }

    @Test
    fun breakdownCutsOffAtTheLastDay() {
        assertEquals(
            listOf(
                TripPricing.Line(firstDay = 1, lastDay = 3, centsPerDay = 160),
                TripPricing.Line(firstDay = 4, lastDay = 5, centsPerDay = 80),
            ),
            rates.breakdown(days = 5),
        )
        assertEquals(TripPricing.Line(firstDay = 31, lastDay = 35, centsPerDay = 20), rates.breakdown(days = 35).lastOrNull())
    }

    @Test
    fun priceWithMembers() {
        val cases = listOf(
            // (days, members, cents)
            Triple(3, 5, 480), Triple(3, 6, 540), Triple(3, 10, 755),
            Triple(7, 8, 1165), Triple(9, 6, 1140), Triple(10, 10, 1655),
            Triple(14, 7, 1765), Triple(16, 9, 2145), Triple(30, 10, 3135),
            Triple(45, 6, 2910), Triple(90, 10, 5835),
        )
        for ((days, members, cents) in cases) {
            assertEquals("$days days, $members members", cents, rates.priceCents(days = days, members = members))
        }
    }

    @Test
    fun membersUpToTheIncludedCountCostNothingExtra() {
        for (members in 0..rates.includedMembers) {
            assertEquals(rates.priceCents(days = 12), rates.priceCents(days = 12, members = members))
        }
    }

    @Test
    fun extraMembersAreChargedByTheMemberDay() {
        val cases = listOf(
            // (extra people, days, cents): bracketed over extra people × days together.
            Triple(1, 1, 20), Triple(1, 10, 200), Triple(2, 5, 200), Triple(3, 5, 275),
            Triple(1, 15, 275), Triple(1, 40, 575), Triple(5, 9, 600),
        )
        for ((extra, days, cents) in cases) {
            assertEquals(cents, rates.memberCents(days = days, members = rates.includedMembers + extra))
            assertEquals(extra * days, rates.memberDays(days = days, members = rates.includedMembers + extra))
        }
    }

    @Test
    fun theSameMemberDaysCostTheSame() {
        val five = rates.includedMembers
        // 3 extra people for 5 days, or 1 for 15: 15 member-days either way.
        assertEquals(rates.memberCents(days = 15, members = five + 1), rates.memberCents(days = 5, members = five + 3))
        // 1 extra person for 10 days, or 2 for 5.
        assertEquals(rates.memberCents(days = 5, members = five + 2), rates.memberCents(days = 10, members = five + 1))
    }

    @Test
    fun memberBreakdownRunsOverTheMemberDays() {
        assertEquals(
            listOf(
                TripPricing.Line(firstDay = 1, lastDay = 10, centsPerDay = 20),
                TripPricing.Line(firstDay = 11, lastDay = 15, centsPerDay = 15),
            ),
            rates.memberBreakdown(memberDays = 15),
        )
        assertTrue(rates.memberBreakdown(memberDays = 0).isEmpty())
    }

    @Test
    fun maxDaysForBudget() {
        val cases = listOf(
            // (budget, members, days)
            Triple(0, 5, 0), Triple(159, 5, 0), Triple(160, 5, 1), Triple(500, 5, 3),
            Triple(1_500, 5, 17), Triple(2_500, 5, 54), Triple(5_000, 5, 179), Triple(1_500, 10, 8),
        )
        for ((budgetCents, members, days) in cases) {
            assertEquals("$budgetCents cents, $members members", days, rates.maxDays(budgetCents = budgetCents, members = members))
        }
    }

    @Test
    fun maxDaysIsTheLongestTripTheBudgetCovers() {
        for (budget in 160..5_000 step 35) {
            val days = rates.maxDays(budgetCents = budget, members = 5)
            assertTrue(rates.priceCents(days = days, members = 5) <= budget)
            assertTrue(rates.priceCents(days = days + 1, members = 5) > budget)
        }
    }

    @Test
    fun purchasePopupSaysWhatAnAmountPaysFor() {
        assertEquals("Usable for a 17-day trip with up to 5 members", PurchasePopupMetrics.usableText(cents = 1_500, members = 5, schedule = rates))
        assertEquals("Usable for a 3-day trip with up to 5 members", PurchasePopupMetrics.usableText(cents = 500, members = 5, schedule = rates))
        assertEquals("Not enough for a 1-day trip with 5 members", PurchasePopupMetrics.usableText(cents = 100, members = 5, schedule = rates))
        assertEquals("Usable for 3 separate 7-day trips with up to 5 members", PurchasePopupMetrics.usableText(cents = 2_500, members = 5, schedule = rates))
        assertEquals("Usable for 6 separate 7-day trips with up to 5 members", PurchasePopupMetrics.usableText(cents = 5_000, members = 5, schedule = rates))
    }

    @Test
    fun memberBracketsRunBackToBack() {
        val brackets = rates.memberBrackets
        assertEquals(1, brackets.firstOrNull()?.firstDay)
        assertNull(brackets.lastOrNull()?.lastDay)
        for ((bracket, next) in brackets.zipWithNext()) {
            assertEquals(next.firstDay, bracket.lastDay?.let { it + 1 })
        }
    }

    @Test
    fun tripPriceCountsItsMembers() {
        var trip = Trip.sample.copy(startDate = "2026-11-01", endDate = "2026-11-07")
        assertEquals(800, trip.priceCents(schedule = rates))

        trip = trip.copy(
            members = (1..8).map {
                TripMember(id = "m$it", email = "m$it@example.com", user = null, role = if (it == 1) TripRole.Organizer else TripRole.Member)
            },
        )
        // 3 extra people × 7 days = 21 member-days: 10 × $0.20 + 11 × $0.15.
        assertEquals(1165, trip.priceCents(schedule = rates))
    }

    @Test
    fun tripDayCountIncludesBothEnds() {
        var trip = Trip.sample.copy(startDate = "2026-11-01", endDate = "2026-11-05")
        assertEquals(5, trip.dayCount)
        assertEquals(640, trip.priceCents(schedule = rates))

        trip = trip.copy(endDate = "2026-11-01")
        assertEquals(1, trip.dayCount)

        // Across the US daylight saving change, which a 23- or 25-hour day mustn't skew.
        trip = trip.copy(startDate = "2026-10-31", endDate = "2026-11-02")
        assertEquals(3, trip.dayCount)
    }
}

/** The server's "2026-11" schedule, as `GET /pricing/schedule` sends it. */
internal val rates2026_11 = PricingSchedule(
    key = "2026-11",
    includedMembers = 5,
    tripBrackets = listOf(
        TripPricing.Bracket(firstDay = 1, lastDay = 3, centsPerDay = 160),
        TripPricing.Bracket(firstDay = 4, lastDay = 9, centsPerDay = 80),
        TripPricing.Bracket(firstDay = 10, lastDay = 16, centsPerDay = 70),
        TripPricing.Bracket(firstDay = 17, lastDay = 23, centsPerDay = 50),
        TripPricing.Bracket(firstDay = 24, lastDay = 30, centsPerDay = 30),
        TripPricing.Bracket(firstDay = 31, lastDay = null, centsPerDay = 20),
    ),
    memberBrackets = listOf(
        TripPricing.Bracket(firstDay = 1, lastDay = 10, centsPerDay = 20),
        TripPricing.Bracket(firstDay = 11, lastDay = 25, centsPerDay = 15),
        TripPricing.Bracket(firstDay = 26, lastDay = 40, centsPerDay = 10),
        TripPricing.Bracket(firstDay = 41, lastDay = null, centsPerDay = 5),
    ),
)
