//
//  WallClockTests.kt
//  outboundTests
//

package earth.outbound

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class WallClockTests {
    @Serializable
    private class Span(
        @Serializable(with = WallClockSerializer::class) val end: Instant? = null,
    )

    private fun decode(json: String): Instant? = Json.decodeFromString<Span>(json).end

    @Test
    fun optionalWallClockIsNilWhenNullOrMissing() {
        assertNull(decode("""{"end": null}"""))
        assertNull(decode("{}"))
    }

    @Test
    fun wallClockKeepsTheWrittenTimeIgnoringFractionalSeconds() {
        // Floating: the wall clock's components are held as UTC.
        val end = decode("""{"end": "2026-11-12T09:00:00.123"}""")
        assertEquals(Instant.parse("2026-11-12T09:00:00Z"), end)
    }

    private fun code(id: String, date: Instant = november): String =
        WallClock.zoneAbbreviation(ZoneId.of(id), date)

    @Test
    fun usesUSCodesAndTracksDaylightSaving() {
        assertEquals("EST", code("America/New_York"))
        assertEquals("EDT", code("America/New_York", july))
    }

    @Test
    fun findsCodesFromOtherEnglishLocales() {
        assertEquals("AEDT", code("Australia/Melbourne"))
        assertEquals("IST", code("Asia/Kolkata"))
    }

    @Test
    fun fallsBackToGMTOffsetWithoutACode() {
        assertEquals("GMT+9", code("Asia/Tokyo"))
    }

    @Test
    fun keepsBSTForLondonOnly() {
        assertEquals("BST", code("Europe/London", july))
        assertNotEquals("BST", code("Asia/Dhaka"))
    }

    private companion object {
        val november: Instant = Instant.parse("2026-11-15T12:00:00Z")
        val july: Instant = Instant.parse("2026-07-15T12:00:00Z")
    }
}
