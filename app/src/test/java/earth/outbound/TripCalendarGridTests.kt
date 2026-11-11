//
//  TripCalendarGridTests.kt
//  outboundTests
//

package earth.outbound

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class TripCalendarGridTests {
    private val calendar = Calendar.sundayFirst

    private fun day(year: Int, month: Int, day: Int): Instant =
        calendar.date(LocalDate.of(year, month, day))

    private fun dayNumbers(week: List<TripCalendarGrid.Day>): List<Int> =
        week.map { calendar.day(it.date).dayOfMonth }

    @Test
    fun midweekTripFitsOneFullWeek() {
        // Nov 3, 2026 is a Tuesday.
        val grid = TripCalendarGrid(start = day(2026, 11, 3), end = day(2026, 11, 6), calendar = calendar)
        assertEquals(1, grid.weeks.size)
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7), dayNumbers(grid.weeks[0]))
        assertEquals(listOf(false, false, true, true, true, true, false), grid.weeks[0].map { it.isInTrip })
    }

    @Test
    fun tripSpanningWeeksPadsBothEnds() {
        // Saturday Nov 7 through Monday Nov 9.
        val grid = TripCalendarGrid(start = day(2026, 11, 7), end = day(2026, 11, 9), calendar = calendar)
        assertEquals(2, grid.weeks.size)
        assertTrue(grid.weeks.all { it.size == 7 })
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7), dayNumbers(grid.weeks[0]))
        assertEquals(listOf(8, 9, 10, 11, 12, 13, 14), dayNumbers(grid.weeks[1]))
        assertEquals(3, grid.weeks.flatten().count { it.isInTrip })
    }

    @Test
    fun paddingCrossesMonthBoundaries() {
        // Tuesday Dec 1 back to Sunday Nov 29; Thursday Dec 31 on to Saturday Jan 2.
        val grid = TripCalendarGrid(start = day(2026, 12, 1), end = day(2026, 12, 31), calendar = calendar)
        assertEquals(day(2026, 11, 29), grid.weeks.firstOrNull()?.firstOrNull()?.date)
        assertEquals(day(2027, 1, 2), grid.weeks.lastOrNull()?.lastOrNull()?.date)
        assertTrue(grid.weeks.all { it.size == 7 })
    }

    @Test
    fun fullSundayToSaturdayTripHasNoPadding() {
        val grid = TripCalendarGrid(start = day(2026, 11, 1), end = day(2026, 11, 7), calendar = calendar)
        assertEquals(1, grid.weeks.size)
        assertTrue(grid.weeks[0].all { it.isInTrip })
    }

    @Test
    fun singleMonthTripIsOneSection() {
        val months = TripCalendarGrid.months(start = day(2026, 11, 11), end = day(2026, 11, 19), calendar = calendar)
        assertEquals(listOf(day(2026, 11, 1)), months.map { it.start })
        assertEquals(9, months[0].grid.weeks.flatten().count { it.isInTrip })
    }

    @Test
    fun tripAcrossMonthsSplitsIntoSections() {
        // Dec 23, 2026 through Jan 14, 2027.
        val months = TripCalendarGrid.months(start = day(2026, 12, 23), end = day(2027, 1, 14), calendar = calendar)
        assertEquals(listOf(day(2026, 12, 1), day(2027, 1, 1)), months.map { it.start })

        val december = months[0].grid.weeks.flatten()
        assertEquals(day(2026, 12, 23), december.filter { it.isInTrip }.map { it.date }.firstOrNull())
        assertEquals(day(2026, 12, 31), december.filter { it.isInTrip }.map { it.date }.lastOrNull())
        // The last December week runs into January, which pads it outside the trip.
        assertEquals(day(2027, 1, 2), december.lastOrNull()?.date)
        assertEquals(false, december.lastOrNull()?.isInTrip)

        val january = months[1].grid.weeks.flatten()
        assertEquals(day(2027, 1, 1), january.filter { it.isInTrip }.map { it.date }.firstOrNull())
        assertEquals(day(2027, 1, 14), january.filter { it.isInTrip }.map { it.date }.lastOrNull())
        assertEquals(day(2026, 12, 27), january.firstOrNull()?.date)
        assertEquals(false, january.firstOrNull()?.isInTrip)
        assertTrue(months.all { month -> month.grid.weeks.all { it.size == 7 } })
    }

    @Test
    fun tripTouchingThreeMonthsHasThreeSections() {
        val months = TripCalendarGrid.months(start = day(2026, 10, 30), end = day(2026, 12, 2), calendar = calendar)
        assertEquals(listOf(day(2026, 10, 1), day(2026, 11, 1), day(2026, 12, 1)), months.map { it.start })
        assertEquals(30, months[1].grid.weeks.flatten().count { it.isInTrip })
    }

    @Test
    fun endBeforeStartIsOneDay() {
        val grid = TripCalendarGrid(start = day(2026, 11, 4), end = day(2026, 11, 2), calendar = calendar)
        assertEquals(1, grid.weeks.size)
        assertEquals(listOf(day(2026, 11, 4)), grid.weeks[0].filter { it.isInTrip }.map { it.date })
    }
}
