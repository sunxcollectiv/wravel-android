//
//  ItineraryPagesTests.kt
//  wravelTests
//

package app.wravel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** How the itinerary's days become the trip screen's pages: Before, the trip's own days, then After. */
class ItineraryPagesTests {

    private fun item(id: String, kind: TripItemKind = TripItemKind.Activity) =
        TripItem.sample.copy(id = id, kind = kind, title = id, displayTitle = id)

    private fun entry(id: String, kind: TripItemKind = TripItemKind.Activity, phase: ItineraryPhase? = null) =
        ItineraryEntry(item = item(id, kind), phase = phase)

    private fun day(date: String, number: Int, trip: String?, vararg entries: ItineraryEntry) =
        ItineraryDay(date = date, dayNumber = number, lodging = emptyList(), entries = entries.toList(), tripId = trip)

    @Test
    fun datesOutsideTheTripsGoToBeforeAndAfter() {
        val pages = ItineraryPages(
            listOf(
                day("2026-11-20", -5, trip = null, entry("early dinner")),
                day("2026-11-25", 0, trip = null, entry("drinks")),
                day("2026-11-26", 1, trip = "t", entry("parade")),
                day("2026-11-27", 2, trip = null),
                day("2026-11-28", 3, trip = "t"),
                day("2026-11-30", 5, trip = null, entry("brunch")),
            )
        )

        assertEquals(listOf(1, 2, 3), pages.tripDays.map { it.dayNumber })
        assertEquals(listOf("early dinner", "drinks"), pages.before.entries.map { it.item.id })
        assertEquals(listOf("brunch"), pages.after.entries.map { it.item.id })
        assertEquals(
            listOf(ItineraryDay.beforePageNumber, 1, 2, 3, ItineraryDay.afterPageNumber),
            pages.all.map { it.dayNumber },
        )
        // Each entry keeps its own date, and a page's date sits next to the trip.
        assertEquals("2026-11-20", pages.before.entryDates["early dinner||"])
        assertEquals("2026-11-25", pages.before.date)
        assertEquals("2026-11-29", pages.after.date)
    }

    @Test
    fun aTransitTouchingTheOutsideIsShownWhole() {
        val pages = ItineraryPages(
            listOf(
                day("2026-11-25", 0, trip = null, entry("red-eye", TripItemKind.Transit, ItineraryPhase.Departure)),
                day(
                    "2026-11-26", 1, trip = "t",
                    entry("red-eye", TripItemKind.Transit, ItineraryPhase.Arrival), entry("parade"),
                ),
            )
        )

        assertEquals(listOf("red-eye|departure|", "red-eye|arrival|"), pages.before.entries.map { it.id })
        assertEquals("2026-11-26", pages.before.entryDates["red-eye|arrival|"])
        // The day it lands still shows the landing.
        assertEquals(listOf("red-eye|arrival|", "parade||"), pages.tripDays[0].entries.map { it.id })
    }

    @Test
    fun beforeHasTheEvesLodgingAndAfterHasNone() {
        val pages = ItineraryPages(
            listOf(
                ItineraryDay(
                    date = "2026-11-20", dayNumber = -5, entries = emptyList(), tripId = null,
                    lodging = listOf(LodgingStay(item("motel", TripItemKind.Lodging), LodgingPhase.Staying)),
                ),
                ItineraryDay(
                    date = "2026-11-25", dayNumber = 0, entries = emptyList(), tripId = null,
                    lodging = listOf(LodgingStay(item("hotel", TripItemKind.Lodging), LodgingPhase.CheckIn)),
                ),
                day("2026-11-26", 1, trip = "t"),
                ItineraryDay(
                    date = "2026-11-27", dayNumber = 2, entries = emptyList(), tripId = null,
                    lodging = listOf(LodgingStay(item("hotel", TripItemKind.Lodging), LodgingPhase.CheckOut)),
                ),
            )
        )

        assertEquals(listOf("hotel"), pages.before.lodging.map { it.item.id })
        assertTrue(pages.after.lodging.isEmpty())
    }

    @Test
    fun withoutTripIdsEveryDateIsATripDay() {
        val pages = ItineraryPages(
            listOf(
                day("2026-11-26", 1, trip = null, entry("parade")),
                day("2026-11-27", 2, trip = null),
            )
        )

        assertEquals(2, pages.tripDays.size)
        assertTrue(pages.before.entries.isEmpty())
        assertTrue(pages.after.entries.isEmpty())
    }
}
