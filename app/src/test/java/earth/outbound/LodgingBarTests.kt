//
//  LodgingBarTests.kt
//  outboundTests
//

package earth.outbound

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** How the lodging bar is laid out on a day, and how it gets from one day to the next. */
class LodgingBarTests {

    private fun stay(id: String, phase: LodgingPhase) =
        LodgingStay(TripItem.sample.copy(id = id, kind = TripItemKind.Lodging, title = id, displayTitle = id), phase)

    /** A segment as "name fraction", floor as "-": enough to read a layout at a glance. */
    private fun describe(segments: List<LodgingBarSegment>): List<String> = segments.map { segment ->
        val name = when (val key = segment.key) {
            is LodgingBarSegment.Key.Stay -> key.id
            LodgingBarSegment.Key.Gap -> "-"
        }
        val fraction = segment.fraction
        "$name ${if (fraction == fraction.toInt().toFloat()) fraction.toInt().toString() else fraction.toString()}"
    }

    /** Moves a bar at rest on `from` to `to`. */
    private fun move(from: List<LodgingStay>, to: List<LodgingStay>, forward: Boolean = true): LodgingBarTransition =
        LodgingBarSegment.transition(from = LodgingBarSegment.resting(from), to = to, forward = forward)

    // MARK: - At rest

    @Test
    fun aDayWithNoLodgingIsAllFloorInvitingAStay() {
        val segments = LodgingBarSegment.resting(emptyList())

        assertEquals(listOf("- 1"), describe(segments))
        assertTrue(segments[0].invitesAdd)
        assertTrue(!segments[0].leadingRule && !segments[0].trailingRule)
    }

    @Test
    fun aLoneCheckInSitsAfterAQuarterOfFloor() {
        val segments = LodgingBarSegment.resting(listOf(stay("A", LodgingPhase.CheckIn)))

        assertEquals(listOf("- 0.25", "A 0.75"), describe(segments))
        assertTrue(segments[0].trailingRule)
        assertFalse(segments[0].invitesAdd)
    }

    @Test
    fun aLoneCheckOutSitsBeforeAQuarterOfFloor() {
        val segments = LodgingBarSegment.resting(listOf(stay("A", LodgingPhase.CheckOut)))

        assertEquals(listOf("A 0.75", "- 0.25"), describe(segments))
        assertTrue(segments[1].leadingRule)
    }

    @Test
    fun aChangeoverPutsTheCheckOutFirstWithADividerBetween() {
        val segments = LodgingBarSegment.resting(listOf(stay("B", LodgingPhase.CheckIn), stay("A", LodgingPhase.CheckOut)))

        assertEquals(listOf("A 0.5", "B 0.5"), describe(segments))
        assertFalse(segments[0].leadingRule)
        assertTrue(segments[1].leadingRule)
    }

    @Test
    fun aNightMidStayFillsTheBar() {
        assertEquals(listOf("A 1"), describe(LodgingBarSegment.resting(listOf(stay("A", LodgingPhase.Staying)))))
    }

    // MARK: - Changing day

    @Test
    fun checkInToStayingClosesTheFloorAndWidensTheStay() {
        val transition = move(from = listOf(stay("A", LodgingPhase.CheckIn)), to = listOf(stay("A", LodgingPhase.Staying)))

        assertEquals(listOf("- 0.25", "A 0.75"), describe(transition.staged))
        assertEquals(listOf("- 0", "A 1"), describe(transition.settled))
        assertTrue(transition.settled[0].isLeaving)
        // The stay is the same segment throughout, so it resizes rather than swaps.
        assertEquals(transition.staged[1].id, transition.settled[1].id)
        assertEquals(LodgingBarSegment.Content.Stay(stay("A", LodgingPhase.Staying)), transition.settled[1].content)
    }

    @Test
    fun stayingToCheckOutBringsFloorInFromTheTrailingEdge() {
        val transition = move(from = listOf(stay("A", LodgingPhase.Staying)), to = listOf(stay("A", LodgingPhase.CheckOut)))

        assertEquals(listOf("A 1", "- 0"), describe(transition.staged))
        assertEquals(listOf("A 0.75", "- 0.25"), describe(transition.settled))
    }

    @Test
    fun aOneNightStaySlidesAcrossBetweenTwoStretchesOfFloor() {
        val transition = move(from = listOf(stay("A", LodgingPhase.CheckIn)), to = listOf(stay("A", LodgingPhase.CheckOut)))

        // The floor before the stay goes and different floor comes in after it; the
        // stay keeps its width and shifts over.
        assertEquals(listOf("- 0.25", "A 0.75", "- 0"), describe(transition.staged))
        assertEquals(listOf("- 0", "A 0.75", "- 0.25"), describe(transition.settled))
        assertTrue(transition.settled[0].isLeaving)
        assertNotEquals(transition.settled[2].id, transition.settled[0].id)
        assertEquals(transition.staged[1].id, transition.settled[1].id)
    }

    @Test
    fun aOneNightStaySlidesBackTheOtherWay() {
        val transition = move(
            from = listOf(stay("A", LodgingPhase.CheckOut)), to = listOf(stay("A", LodgingPhase.CheckIn)), forward = false,
        )

        assertEquals(listOf("- 0", "A 0.75", "- 0.25"), describe(transition.staged))
        assertEquals(listOf("- 0.25", "A 0.75", "- 0"), describe(transition.settled))
    }

    @Test
    fun stayingToChangeoverBringsTheNextStayInFromTheTrailingEdge() {
        val transition = move(
            from = listOf(stay("A", LodgingPhase.Staying)),
            to = listOf(stay("A", LodgingPhase.CheckOut), stay("B", LodgingPhase.CheckIn)),
        )

        assertEquals(listOf("A 1", "B 0"), describe(transition.staged))
        assertEquals(listOf("A 0.5", "B 0.5"), describe(transition.settled))
        // The arriving stay is laid out at its final width from the start, held to the
        // edge that slides.
        assertEquals(0.5f, transition.staged[1].contentFraction)
        assertEquals(HorizontalEdge.Leading, transition.staged[1].pin)
    }

    @Test
    fun changeoverToStayingSlidesTheOldStayOffTheLeadingEdge() {
        val transition = move(
            from = listOf(stay("A", LodgingPhase.CheckOut), stay("B", LodgingPhase.CheckIn)),
            to = listOf(stay("B", LodgingPhase.Staying)),
        )

        assertEquals(listOf("A 0", "B 1"), describe(transition.settled))
        assertTrue(transition.settled[0].isLeaving)
        assertEquals(0.5f, transition.settled[0].contentFraction)
        assertEquals(HorizontalEdge.Trailing, transition.settled[0].pin)
        // Its divider goes with the stay it divided from.
        assertFalse(transition.settled[1].leadingRule)
    }

    @Test
    fun checkOutToNoLodgingWidensTheFloorOverTheStay() {
        val transition = move(from = listOf(stay("A", LodgingPhase.CheckOut)), to = emptyList())

        assertEquals(listOf("A 0", "- 1"), describe(transition.settled))
        assertTrue(transition.settled[1].invitesAdd)
        assertFalse(transition.settled[1].leadingRule)
        assertEquals(transition.staged[1].id, transition.settled[1].id)
    }

    @Test
    fun noLodgingToCheckInBringsTheStayInOverTheFloor() {
        val transition = move(from = emptyList(), to = listOf(stay("A", LodgingPhase.CheckIn)))

        assertEquals(listOf("- 1", "A 0"), describe(transition.staged))
        assertTrue(transition.staged[0].invitesAdd)
        assertEquals(listOf("- 0.25", "A 0.75"), describe(transition.settled))
        assertFalse(transition.settled[0].invitesAdd)
    }

    @Test
    fun goingBackFromNoLodgingBringsTheStayInFromTheLeadingEdge() {
        val transition = move(from = emptyList(), to = listOf(stay("A", LodgingPhase.CheckOut)), forward = false)

        assertEquals(listOf("A 0", "- 1"), describe(transition.staged))
        assertEquals(listOf("A 0.75", "- 0.25"), describe(transition.settled))
        assertEquals(HorizontalEdge.Trailing, transition.staged[0].pin)
    }

    @Test
    fun checkOutThenCheckInElsewhereSlidesTheFloorAcross() {
        val transition = move(from = listOf(stay("A", LodgingPhase.CheckOut)), to = listOf(stay("B", LodgingPhase.CheckIn)))

        assertEquals(listOf("A 0.75", "- 0.25", "B 0"), describe(transition.staged))
        assertEquals(listOf("A 0", "- 0.25", "B 0.75"), describe(transition.settled))
    }

    @Test
    fun jumpingBetweenUnrelatedStaysPushesInTheDirectionOfTravel() {
        val later = move(
            from = listOf(stay("A", LodgingPhase.Staying)), to = listOf(stay("C", LodgingPhase.Staying)), forward = true,
        )
        assertEquals(listOf("A 1", "C 0"), describe(later.staged))
        assertEquals(listOf("A 0", "C 1"), describe(later.settled))
        assertEquals(HorizontalEdge.Trailing, later.settled[0].pin)
        assertEquals(HorizontalEdge.Leading, later.settled[1].pin)

        val earlier = move(
            from = listOf(stay("C", LodgingPhase.Staying)), to = listOf(stay("A", LodgingPhase.Staying)), forward = false,
        )
        assertEquals(listOf("A 0", "C 1"), describe(earlier.staged))
        assertEquals(listOf("A 1", "C 0"), describe(earlier.settled))
        assertEquals(HorizontalEdge.Trailing, earlier.settled[0].pin)
        assertEquals(HorizontalEdge.Leading, earlier.settled[1].pin)
    }

    @Test
    fun theSameArrangementNeedsNoStaging() {
        val current = LodgingBarSegment.resting(listOf(stay("A", LodgingPhase.Staying)))
        val transition = LodgingBarSegment.transition(from = current, to = listOf(stay("A", LodgingPhase.Staying)), forward = true)

        assertEquals(current, transition.staged)
        assertEquals(current, transition.settled)
    }

    @Test
    fun aStayStillSlidingOffComesBackWhenTheDayIsReturnedTo() {
        // Changeover → staying at B, then back before A has finished sliding off.
        val changeover = listOf(stay("A", LodgingPhase.CheckOut), stay("B", LodgingPhase.CheckIn))
        val first = move(from = changeover, to = listOf(stay("B", LodgingPhase.Staying)))
        val back = LodgingBarSegment.transition(from = first.settled, to = changeover, forward = false)

        assertEquals(first.settled, back.staged)
        assertEquals(listOf("A 0.5", "B 0.5"), describe(back.settled))
        assertTrue(back.settled.none { it.isLeaving })
        assertEquals(first.settled.map { it.id }, back.settled.map { it.id })
    }

    @Test
    fun segmentsStillSlidingOffKeepGoingThroughAnotherChange() {
        // Check-in → staying leaves floor sliding off; moving on to check-out before
        // it's gone brings new floor in on the other side.
        val first = move(from = listOf(stay("A", LodgingPhase.CheckIn)), to = listOf(stay("A", LodgingPhase.Staying)))
        val second = LodgingBarSegment.transition(from = first.settled, to = listOf(stay("A", LodgingPhase.CheckOut)), forward = true)

        assertEquals(listOf("- 0", "A 0.75", "- 0.25"), describe(second.settled))
        assertTrue(second.settled[0].isLeaving)
        assertFalse(second.settled[2].isLeaving)
    }

    // MARK: - Without sliding

    @Test
    fun swappingKeepsIdentityOnlyForTheSameArrangement() {
        val current = LodgingBarSegment.resting(listOf(stay("A", LodgingPhase.Staying)))

        val same = LodgingBarSegment.resting(listOf(stay("A", LodgingPhase.Staying)), keepingIdentityOf = current)
        assertEquals(current.map { it.id }, same.map { it.id })

        val resized = LodgingBarSegment.resting(listOf(stay("A", LodgingPhase.CheckOut)), keepingIdentityOf = current)
        assertTrue(resized.none { it.id == current[0].id })
    }
}
