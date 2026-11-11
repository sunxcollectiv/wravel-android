//
//  TripCalendarGrid.kt
//  outbound
//

package earth.outbound

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The days of a trip laid out as full Sunday–Saturday weeks. Days before the start
 * and after the end pad the first and last weeks so every row has seven days; those
 * padding days are marked as outside the trip.
 *
 * Builds the weeks covering `start` through `end` (inclusive, by calendar day).
 * An `end` before `start` is treated as a one-day trip.
 */
class TripCalendarGrid(start: Instant, end: Instant, calendar: Calendar = Calendar.sundayFirst) {
    data class Day(
        /** Midnight of the day in the grid's calendar. */
        val date: Instant,
        val isInTrip: Boolean,
    ) {
        val id: Instant get() = date
    }

    val weeks: List<List<Day>>

    init {
        val firstDay = calendar.day(start)
        val lastDay = maxOf(firstDay, calendar.day(end))
        // Back up to the Sunday on or before the start, and forward to the Saturday on
        // or after the end.
        val leading = (firstDay.dayOfWeek.value - calendar.firstWeekday.value + 7) % 7
        val trailing = 6 - (lastDay.dayOfWeek.value - calendar.firstWeekday.value + 7) % 7
        val gridEnd = lastDay.plusDays(trailing.toLong())

        weeks = generateSequence(firstDay.minusDays(leading.toLong())) { it.plusDays(1) }
            .takeWhile { it <= gridEnd }
            .map { Day(date = calendar.date(it), isInTrip = it in firstDay..lastDay) }
            .chunked(7)
            .toList()
    }

    /**
     * One calendar month of a trip: the month's first day and a grid of just the trip
     * days in that month (days of neighboring months pad the weeks, outside the trip).
     */
    class Month(val start: Instant, val grid: TripCalendarGrid) {
        val id: Instant get() = start
    }

    companion object {
        /** Splits `start` through `end` into a grid per calendar month it touches, in order. */
        fun months(start: Instant, end: Instant, calendar: Calendar = Calendar.sundayFirst): List<Month> {
            val firstDay = calendar.day(start)
            val lastDay = maxOf(firstDay, calendar.day(end))

            val months = mutableListOf<Month>()
            var monthStart = firstDay.withDayOfMonth(1)
            while (monthStart <= lastDay) {
                val nextMonth = monthStart.plusMonths(1)
                val monthEnd = nextMonth.minusDays(1)
                val grid = TripCalendarGrid(
                    start = calendar.date(maxOf(firstDay, monthStart)),
                    end = calendar.date(minOf(lastDay, monthEnd)),
                    calendar = calendar,
                )
                months.add(Month(start = calendar.date(monthStart), grid = grid))
                monthStart = nextMonth
            }
            return months
        }
    }
}

/**
 * How instants are read as calendar days: the Gregorian calendar in a zone, with the
 * weekday its weeks start on.
 */
data class Calendar(val timeZone: ZoneId, val firstWeekday: DayOfWeek) {
    /** The calendar day `date` falls on. */
    fun day(date: Instant): LocalDate = date.atZone(timeZone).toLocalDate()

    /** Midnight at the start of `day`. */
    fun date(day: LocalDate): Instant = day.atStartOfDay(timeZone).toInstant()

    /** Midnight at the start of the day `date` falls on. */
    fun startOfDay(date: Instant): Instant = date(day(date))

    companion object {
        /** Gregorian in the device zone with weeks starting on Sunday, regardless of locale. */
        val sundayFirst: Calendar
            get() = Calendar(timeZone = ZoneId.systemDefault(), firstWeekday = DayOfWeek.SUNDAY)
    }
}
