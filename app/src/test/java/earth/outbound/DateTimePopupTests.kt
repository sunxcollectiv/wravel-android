//
//  DateTimePopupTests.kt
//  outboundTests
//

package earth.outbound

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class DateTimePopupTests {
    private val calendar = Calendar.sundayFirst
    private val day = calendar.date(LocalDate.of(2026, 12, 23))

    private fun hourAndMinute(date: Instant): List<Int> {
        val time = date.atZone(calendar.timeZone).toLocalTime()
        return listOf(time.hour, time.minute)
    }

    @Test
    fun morningAndAfternoonHours() {
        assertEquals(
            listOf(9, 15),
            hourAndMinute(DateTimePopupMetrics.date(day, hour = 9, minute = 15, isPM = false, calendar = calendar)),
        )
        assertEquals(
            listOf(15, 30),
            hourAndMinute(DateTimePopupMetrics.date(day, hour = 3, minute = 30, isPM = true, calendar = calendar)),
        )
    }

    @Test
    fun twelveAMIsMidnightAndTwelvePMIsNoon() {
        assertEquals(
            listOf(0, 0),
            hourAndMinute(DateTimePopupMetrics.date(day, hour = 12, minute = 0, isPM = false, calendar = calendar)),
        )
        assertEquals(
            listOf(12, 45),
            hourAndMinute(DateTimePopupMetrics.date(day, hour = 12, minute = 45, isPM = true, calendar = calendar)),
        )
    }

    @Test
    fun keepsTheDay() {
        val date = DateTimePopupMetrics.date(day, hour = 11, minute = 45, isPM = true, calendar = calendar)
        assertEquals(calendar.day(day), calendar.day(date))
    }
}
