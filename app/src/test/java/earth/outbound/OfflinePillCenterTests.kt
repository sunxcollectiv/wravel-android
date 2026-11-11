//
//  OfflinePillCenterTests.kt
//  outboundTests
//

package earth.outbound

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.util.UUID

/**
 * How the offline pill picks its spot and words from the screens on display: the
 * latest screen wins, popups only move it, and screens on their way out are skipped.
 */
class OfflinePillCenterTests {
    private fun placement(
        bottomY: Float,
        isForm: Boolean = false,
        savedDate: Instant? = null,
        keepsScreenBelow: Boolean = false,
    ) = OfflinePillCenter.Placement(
        bottomY = bottomY,
        isForm = isForm,
        savedDate = savedDate,
        showsWhenOnline = false,
        isHidden = false,
        keepsScreenBelow = keepsScreenBelow,
    )

    @Test
    fun nothingPlacedMeansNoPill() {
        assertNull(OfflinePillCenter().current)
    }

    @Test
    fun theLatestScreenWins() {
        val center = OfflinePillCenter()
        center.set(placement(800f), UUID.randomUUID())
        center.set(placement(700f, isForm = true), UUID.randomUUID())

        assertEquals(700f, center.current?.bottomY)
        assertEquals(true, center.current?.isForm)
    }

    @Test
    fun updatingAScreenKeepsItsPlaceInLine() {
        val center = OfflinePillCenter()
        val below = UUID.randomUUID()
        center.set(placement(800f), below)
        center.set(placement(700f), UUID.randomUUID())
        center.set(placement(780f), below)

        assertEquals(700f, center.current?.bottomY)
    }

    @Test
    fun removingTheTopScreenFallsBackToTheOneBelow() {
        val center = OfflinePillCenter()
        val top = UUID.randomUUID()
        center.set(placement(800f), UUID.randomUUID())
        center.set(placement(700f), top)
        center.remove(top)

        assertEquals(800f, center.current?.bottomY)
    }

    @Test
    fun aPopupMovesThePillButKeepsTheWordsBelow() {
        val center = OfflinePillCenter()
        center.set(placement(700f, isForm = true), UUID.randomUUID())
        center.set(placement(824f, keepsScreenBelow = true), UUID.randomUUID())

        assertEquals(824f, center.current?.bottomY)
        assertEquals(true, center.current?.isForm)
    }

    @Test
    fun aDetailSheetKeepsTheSavedDateBelow() {
        val saved = Instant.ofEpochSecond(1_000)
        val center = OfflinePillCenter()
        center.set(placement(780f, savedDate = saved), UUID.randomUUID())
        center.set(placement(824f, keepsScreenBelow = true), UUID.randomUUID())

        assertEquals(saved, center.current?.savedDate)
    }

    @Test
    fun screensOnTheirWayOutAreSkipped() {
        val center = OfflinePillCenter()
        center.set(placement(780f), UUID.randomUUID())
        // A sheet sliding off the bottom reports a spot below the screen.
        center.set(placement(950f, isForm = true), UUID.randomUUID())

        val target = center.current { it.bottomY > 874f }
        assertEquals(780f, target?.bottomY)
        assertEquals(false, target?.isForm)
    }
}
