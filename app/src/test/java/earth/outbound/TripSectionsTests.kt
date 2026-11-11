//
//  TripSectionsTests.kt
//  outboundTests
//

package earth.outbound

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class TripSectionsTests {
    private fun trip(id: String, start: String, end: String): Trip =
        Trip(id = id, title = id, startDate = start, endDate = end, createdAt = Instant.now(), updatedAt = Instant.now())

    @Test
    fun upcomingIsSoonestFirstAndPastIsMostRecentFirst() {
        val trips = listOf(
            trip("later", "2026-12-01", "2026-12-05"),
            trip("old", "2026-01-01", "2026-01-05"),
            trip("soon", "2026-11-01", "2026-11-03"),
            trip("recent", "2026-09-01", "2026-09-05"),
        )
        val (upcoming, past) = Trip.upcomingAndPast(trips, today = "2026-10-23")
        assertEquals(listOf("soon", "later"), upcoming.map { it.id })
        assertEquals(listOf("recent", "old"), past.map { it.id })
    }

    @Test
    fun tripEndingTodayIsStillUpcoming() {
        val (upcoming, past) = Trip.upcomingAndPast(listOf(trip("now", "2026-10-20", "2026-10-23")), today = "2026-10-23")
        assertEquals(listOf("now"), upcoming.map { it.id })
        assertTrue(past.isEmpty())
    }
}
