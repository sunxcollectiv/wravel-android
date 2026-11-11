//
//  PlaceCoverPhotoTests.kt
//  outboundTests
//

package earth.outbound

import earth.outbound.networking.APIClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** How a `PlaceRead`'s server-resolved `cover_photo` decodes. */
class PlaceCoverPhotoTests {

    @Test
    fun decodesTheCoverPhotoAndItsCredits() {
        val place = decodePlace(
            coverPhoto = """
            {
              "url": "https://places.googleapis.com/v1/places/XYZ/photos/aaa/media?maxWidthPx=800&key=k",
              "attributions": [
                { "display_name": "Jane D", "uri": "https://maps.google.com/jane" },
                { "display_name": "Sam K" }
              ],
              "is_stale": false
            }
            """,
        )

        val photo = place.coverPhoto
        assertNotNull(photo)
        photo!!
        assertTrue(photo.url.startsWith("https://places.googleapis.com/v1/places/XYZ/photos/aaa/media"))
        assertEquals(listOf("Jane D", "Sam K"), photo.attributions.map { it.displayName })
        assertEquals("https://maps.google.com/jane", photo.attributions.firstOrNull()?.uri)
        assertNull(photo.attributions.lastOrNull()?.uri)
        assertEquals(false, photo.isStale)
    }

    @Test
    fun aPlaceWithoutAPhotoHasNoCover() {
        assertNull(decodePlace(coverPhoto = "null").coverPhoto)
        assertNull(decodePlace(coverPhoto = null).coverPhoto)
    }

    @Test
    fun missingAttributionsReadAsNone() {
        val place = decodePlace(coverPhoto = """{ "url": "https://example.com/p.jpg" }""")
        assertEquals(emptyList<CoverPhoto.Attribution>(), place.coverPhoto?.attributions)
    }

    @Test
    fun aCreditWithoutANameStillDecodes() {
        val place = decodePlace(
            coverPhoto = """
            { "url": "https://example.com/p.jpg", "attributions": [{ "display_name": null, "uri": null }], "is_stale": true }
            """,
        )
        assertNotNull(place.coverPhoto?.attributions?.firstOrNull())
        assertNull(place.coverPhoto?.attributions?.firstOrNull()?.displayName)
        assertEquals(true, place.coverPhoto?.isStale)
    }

    // MARK: - Helpers

    /**
     * Decodes a minimal `PlaceRead`, with `cover_photo` set to the given JSON (or left
     * out entirely when null), the way `APIClient` does.
     */
    private fun decodePlace(coverPhoto: String?): Place {
        val cover = coverPhoto?.let { """, "cover_photo": $it""" } ?: ""
        val json = """{ "id": "p1", "name": "Blue Bottle Coffee"$cover }"""
        return APIClient.json.decodeFromString<Place>(json)
    }
}
