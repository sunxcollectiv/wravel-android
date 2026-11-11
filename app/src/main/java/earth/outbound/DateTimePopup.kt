//
//  DateTimePopup.kt
//  outbound
//

package earth.outbound

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.snapping.SnapLayoutInfoProvider
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.rounded.ArrowCircleRight
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.node.Ref
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** The side of a `DateTimePopup` being edited: an item's start or its end. */
enum class DateTimePopupField { Start, End }

/**
 * The picker's measurements and choices, and the clock arithmetic its tests reach.
 * They live here rather than on `DateTimePopup`, which is a composable function and
 * so can't hold them itself.
 */
object DateTimePopupMetrics {
    /** Matches the popup card's inner padding, so sliding panes reach its edges. */
    val cardPadding: Dp = 20.dp

    /** Space between each side of the header and the arrow. */
    val headerArrowSpacing: Dp = 8.dp

    /** How far short of the arrow the active side's bar stops. */
    val activeBarArrowGap: Dp = 14.dp

    /** How far in from where the card's rounded corner ends the bar starts. */
    val activeBarOuterInset: Dp = 7.dp

    /** How far a sideways swipe on the pane must travel to switch sides. */
    val swipeThreshold: Dp = 50.dp
    val cellSpacing: Dp = 3.dp

    /** Corner radius of the day cells and the hour box. */
    val gridCornerRadius: Dp = 4.dp
    val hours: List<Int> = (1..12).toList()

    /**
     * The hour row repeats 1–12 this many times and opens in the middle copy, so it
     * scrolls on in either direction as if it looped.
     */
    const val hourCycles = 100
    const val middleCycle = hourCycles / 2
    val minutes: List<Int> = listOf(0, 15, 30, 45)

    /** Width of each minute in the 1-minute strip. */
    val minuteStripCellWidth: Dp = 40.dp

    /**
     * The minute step toggle's width, with room either side of the dial to set
     * AM/PM apart from the minutes.
     */
    val minuteStepToggleWidth: Dp = 48.dp

    /** How far the dark fade reaches in from each end of the hour row. */
    val hourFadeWidth: Dp = 100.dp

    /** How dark the fade gets at the very ends, so edge hours stay faintly visible. */
    const val hourFadeOpacity = 0.6f
    val zoneFieldHeight: Dp = 44.dp
    val zoneResultRowHeight: Dp = 40.dp

    /** Space between the zone field and its floating suggestions. */
    val zoneResultsGap: Dp = 4.dp

    /** Room the suggestions keep clear of the visible area's edge before flipping. */
    val zoneResultsEdgeMargin: Dp = 16.dp

    /** `day` at the given 12-hour clock time, e.g. 12 AM is midnight and 12 PM is noon. */
    fun date(day: Instant, hour: Int, minute: Int, isPM: Boolean, calendar: Calendar = Calendar.sundayFirst): Instant {
        val hour24 = hour % 12 + if (isPM) 12 else 0
        return calendar.day(day).atTime(hour24, minute).atZone(calendar.timeZone).toInstant()
    }
}

/** An end duration on offer, in minutes, with its amount and unit as shown. */
private data class EndDurationChoice(val minutes: Int, val amount: String, val unit: String)

/** End durations offered. */
private val endDurationChoices = listOf(
    EndDurationChoice(30, "30", "m"),
    EndDurationChoice(45, "45", "m"),
    EndDurationChoice(60, "1", "h"),
    EndDurationChoice(120, "2", "h"),
    EndDurationChoice(180, "3", "h"),
)

/** The highlighted values a side opens on (see `seed`). */
private data class Seed(val day: Instant?, val hour: Int?, val minute: Int, val isPM: Boolean, val zoneQuery: String)

/**
 * The highlighted values for a side: its own date's day and time, else no day
 * (so one still has to be picked) and `defaultTime`, or no hour when that's null too.
 */
private fun seed(date: Instant?, zone: ZoneId?, defaultTime: LocalTime? = null, tripStart: Instant?): Seed {
    val parts = date?.atZone(ZoneId.systemDefault())?.toLocalTime() ?: defaultTime
    return Seed(
        day = date?.let(Calendar.sundayFirst::startOfDay),
        hour = parts?.hour?.let { if (it % 12 == 0) 12 else it % 12 },
        // As is: an off-quarter minute opens the picker in 1-minute steps.
        minute = parts?.minute ?: 0,
        isPM = (parts?.hour ?: 0) >= 12,
        zoneQuery = zone?.let { TimeZoneSearch.label(it, date ?: tripStart ?: Instant.now()) } ?: "",
    )
}

/**
 * The time an empty end opens on: `defaultHour` on the hour when given, else an
 * hour after the start; null when there's neither.
 */
private fun emptyEndTime(start: Instant?, defaultHour: Int?): LocalTime? {
    if (defaultHour != null) return LocalTime.of(defaultHour.mod(24), 0)
    return start?.plusSeconds(3600)?.atZone(ZoneId.systemDefault())?.toLocalTime()
}

/** The day and time highlighted in a pane. */
private data class PaneSelection(
    val day: Instant?,
    val hour: Int?,
    val minute: Int,
    val usesMinuteSteps: Boolean,
    val isPM: Boolean,
)

/** What the controls in a pane do when used. */
private class PaneActions(
    val selectDay: (Instant) -> Unit,
    val selectHour: (Int) -> Unit,
    val selectMinute: (Int) -> Unit,
    val selectIsPM: (Boolean) -> Unit,
    val toggleMinuteSteps: () -> Unit,
)

/** What the picker is showing: the side being edited and what's highlighted for it. */
@Stable
private class DateTimePopupState(field: DateTimePopupField, seed: Seed) {
    /** Which side the controls below the header are editing. */
    var activeField by mutableStateOf(field)

    /** What's typed in the zone field; starts as the current zone's label. */
    var zoneQuery by mutableStateOf(seed.zoneQuery)
    var isZoneFieldFocused by mutableStateOf(false)

    /**
     * Bottom edge of the zone field in the popup's visible area, to decide which way
     * the suggestions open.
     */
    var zoneFieldBottom by mutableStateOf(0.dp)

    /**
     * The highlighted day (midnight, as in the calendar grid); null until the active
     * side has a value or a day is tapped.
     */
    var selectedDay by mutableStateOf(seed.day)

    /**
     * Side of a day cell as laid out, so the hour cells can match it. Starts at a
     * typical size until the calendar is measured (or when there's no calendar).
     */
    var dayCellSize by mutableStateOf(44.dp)

    /** The highlighted hour on a 12-hour clock (1–12), if any. */
    var selectedHour by mutableStateOf(seed.hour)

    /** The highlighted minute: one of `minutes`, or any minute in 1-minute steps. */
    var selectedMinute by mutableIntStateOf(seed.minute)

    /**
     * Whether minutes go in 1-minute steps rather than quarter hours. On by itself
     * for a time that's off the quarter, so it shows as it is.
     */
    var usesMinuteSteps by mutableStateOf(seed.minute !in DateTimePopupMetrics.minutes)

    /** Whether the selected time is PM rather than AM. */
    var isPM by mutableStateOf(seed.isPM)

    /**
     * The selected day and time as one date, or null until both a day and an hour
     * are picked.
     */
    val selectedDate: Instant?
        get() {
            val day = selectedDay ?: return null
            val hour = selectedHour ?: return null
            return DateTimePopupMetrics.date(day, hour = hour, minute = selectedMinute, isPM = isPM)
        }

    val selection: PaneSelection
        get() = PaneSelection(selectedDay, selectedHour, selectedMinute, usesMinuteSteps, isPM)

    /** Highlights a side's day and time. */
    fun highlight(seed: Seed) {
        selectedDay = seed.day
        selectedHour = seed.hour
        selectedMinute = seed.minute
        if (seed.minute !in DateTimePopupMetrics.minutes) usesMinuteSteps = true
        isPM = seed.isPM
    }
}

/**
 * A centered black card for picking an item's start and end, shown over the item
 * form when either date block is tapped. A header shows start → end; tapping either
 * side switches which one the calendar, hours, minutes, AM/PM and time zone search
 * below edit. Each choice is written straight back to that side's date once both a
 * day and an hour are picked, and a picked zone to its zone, so closing keeps them.
 *
 * Opens on `field`, its current value seeding the highlighted day, hour, minute,
 * AM/PM and zone.
 */
@Composable
fun DateTimePopup(
    field: DateTimePopupField,
    /**
     * What each side of the header reads on its date line while unset, e.g.
     * "Check-in" / "Check-out".
     */
    startPlaceholder: String,
    endPlaceholder: String,
    /** Whether the end may be left out, noted as "(optional)" under its placeholder. */
    endIsOptional: Boolean = false,
    /**
     * The hour (0–23) an empty end opens on, so picking a day sets it; null opens
     * on an hour after the start instead.
     */
    defaultEndHour: Int? = null,
    /** Whether to offer setting the end as a duration after the start. */
    offersEndDurations: Boolean = false,
    /** The trip's first through last day; null when the trip's dates aren't known. */
    tripDates: ClosedRange<Instant>?,
    /** The start and end, as wall clocks on the device-zone canvas the form uses. */
    start: Instant?,
    onStartChange: (Instant?) -> Unit,
    end: Instant?,
    onEndChange: (Instant?) -> Unit,
    /** The zones the start and end are in; null when none is set. */
    startZone: ZoneId?,
    onStartZoneChange: (ZoneId?) -> Unit,
    endZone: ZoneId?,
    onEndZoneChange: (ZoneId?) -> Unit,
    /**
     * The itinerary day the form was opened from, marked with a dot; null when there
     * isn't one.
     */
    viewedDay: Instant? = null,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    val viewedMidnight = remember(viewedDay) { viewedDay?.let(Calendar.sundayFirst::startOfDay) }
    val state = remember {
        DateTimePopupState(
            field,
            seed(
                date = if (field == DateTimePopupField.Start) start else end,
                zone = if (field == DateTimePopupField.Start) startZone else endZone,
                defaultTime = if (field == DateTimePopupField.End) emptyEndTime(start, defaultEndHour) else null,
                tripStart = tripDates?.start,
            ),
        )
    }
    val openedAt = remember { Instant.now() }

    // A field of the form underneath gives up the keyboard, which would otherwise stay
    // up over the picker, typing into a field out of sight.
    LaunchedEffect(Unit) { focusManager.clearFocus() }

    // The active side's date and zone, read and written through its side.
    val isStartActive = state.activeField == DateTimePopupField.Start
    val date = if (isStartActive) start else end
    val zone = if (isStartActive) startZone else endZone
    val setDate = if (isStartActive) onStartChange else onEndChange
    val setZone = if (isStartActive) onStartZoneChange else onEndZoneChange

    /**
     * Makes `field` the side being edited, reseeding the controls from its value;
     * `date` is that value when it's newer than what this was last given.
     */
    fun activate(
        field: DateTimePopupField,
        date: Instant? = if (field == DateTimePopupField.Start) start else end,
    ) {
        if (field == state.activeField) return
        focusManager.clearFocus()
        val seed = seed(
            date = date,
            zone = if (field == DateTimePopupField.Start) startZone else endZone,
            defaultTime = if (field == DateTimePopupField.End) emptyEndTime(start, defaultEndHour) else null,
            tripStart = tripDates?.start,
        )
        state.activeField = field
        state.highlight(seed)
        state.zoneQuery = seed.zoneQuery
    }

    /**
     * Changes what's highlighted, writing the day and time to the active side once
     * both are picked.
     */
    fun select(change: DateTimePopupState.() -> Unit) {
        val before = state.selectedDate
        state.change()
        val after = state.selectedDate
        if (after != null && after != before) setDate(after)
    }

    /**
     * Sets the end `minutes` after the start and shows it, moving to the end side
     * (or refreshing its controls when already there).
     */
    fun setEnd(minutesAfterStart: Int) {
        val newEnd = (start ?: return).plusSeconds(minutesAfterStart * 60L)
        onEndChange(newEnd)
        if (state.activeField != DateTimePopupField.End) return activate(DateTimePopupField.End, date = newEnd)
        state.highlight(seed(date = newEnd, zone = endZone, tripStart = tripDates?.start))
    }

    val actions = PaneActions(
        selectDay = { day -> select { selectedDay = day } },
        selectHour = { hour -> select { selectedHour = hour } },
        selectMinute = { minute -> select { selectedMinute = minute } },
        selectIsPM = { isPM -> select { this.isPM = isPM } },
        toggleMinuteSteps = {
            select {
                usesMinuteSteps = !usesMinuteSteps
                if (!usesMinuteSteps) {
                    // The nearest quarter within the hour, so :53 goes to :45.
                    selectedMinute = DateTimePopupMetrics.minutes.minBy { abs(it - selectedMinute) }
                }
            }
        },
    )

    // The moment zone abbreviations are read at (EST vs EDT): the chosen time, else the
    // highlighted day, else the trip's first day, so an unset row in November still
    // shows EST rather than whatever today's offset is.
    val zoneReferenceDate = date ?: state.selectedDay ?: tripDates?.start ?: openedAt

    // Black with a light gray edge so the card stands out against the dimmed form.
    PopupCard(background = Color.Black, border = Color.White.copy(alpha = 0.3f), onClose = onClose, modifier = modifier) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            RangeHeader(
                activeField = state.activeField,
                selectedDay = state.selectedDay,
                start = start,
                end = end,
                startZone = startZone,
                endZone = endZone,
                startPlaceholder = startPlaceholder,
                endPlaceholder = endPlaceholder,
                endIsOptional = endIsOptional,
                onActivate = { activate(it) },
            )

            // One pane per side; switching sides pushes the new pane in from that side.
            // Clipped to the card's width, just inside its border, so panes slide in
            // from its edges.
            val paneInset = DateTimePopupMetrics.cardPadding - 1.dp
            val slideInset = with(LocalDensity.current) { paneInset.roundToPx() * 2 }
            Box(
                Modifier
                    .outset(horizontal = paneInset)
                    .clipToBounds()
                    // Swiping the pane sideways switches sides too: left to the end, right back
                    // to the start. The hour row's own scrolling still wins for drags on it.
                    .sidewaysSwipe { towardEnd ->
                        activate(if (towardEnd) DateTimePopupField.End else DateTimePopupField.Start)
                    }
                    .padding(horizontal = paneInset)
            ) {
                AnimatedContent(
                    targetState = state.activeField,
                    transitionSpec = {
                        // In from the side being switched to, the other pane leaving the opposite way.
                        val direction = if (targetState == DateTimePopupField.End) 1 else -1
                        val slide = AppAnimation.snappy<IntOffset>(0.35)
                        val fade = AppAnimation.snappy<Float>(0.35)
                        (slideInHorizontally(slide) { (it + slideInset) * direction } + fadeIn(fade))
                            .togetherWith(slideOutHorizontally(slide) { -(it + slideInset) * direction } + fadeOut(fade))
                            .using(SizeTransform(clip = false))
                    },
                    label = "pane",
                ) { field ->
                    // A pane on its way out keeps showing its own side's values.
                    val isLive = field == state.activeField
                    Pane(
                        selection = rememberWhileLive(state.selection, isLive),
                        actions = actions,
                        tripDates = tripDates,
                        viewedDay = viewedMidnight,
                        dayCellSize = state.dayCellSize,
                        onDayCellSizeChange = { state.dayCellSize = it },
                    )
                }
            }

            ZoneField(
                state = state,
                zone = zone,
                referenceDate = zoneReferenceDate,
                onZoneChange = setZone,
                // Above the band below, so floating suggestions cover it.
                modifier = Modifier.zIndex(1f),
            )

            if (offersEndDurations) {
                EndDurations(isEnabled = start != null, onSelect = { setEnd(minutesAfterStart = it) })
            }
        }
    }
}

/**
 * `current` while `isLive`, and from then on the last one it had: what something on
 * its way off screen keeps showing.
 */
@Composable
private fun <T> rememberWhileLive(current: T, isLive: Boolean): T {
    val last = remember { Ref<T>().also { it.value = current } }
    if (isLive) last.value = current
    @Suppress("UNCHECKED_CAST")
    return last.value as T
}

/**
 * Lets this reach `horizontal` out past each side of the room it's given, and
 * `bottom` below it, like negative padding.
 */
private fun Modifier.outset(horizontal: Dp = 0.dp, bottom: Dp = 0.dp): Modifier = layout { measurable, constraints ->
    val side = horizontal.roundToPx()
    val below = bottom.roundToPx()
    val placeable = measurable.measure(constraints.offset(horizontal = side * 2))
    layout(placeable.width - side * 2, (placeable.height - below).coerceAtLeast(0)) {
        placeable.place(-side, 0)
    }
}

/**
 * Reports a sideways swipe: one that travels further across than up or down, and
 * past `DateTimePopupMetrics.swipeThreshold`. Drags something inside scrolls with
 * are left to it.
 */
private fun Modifier.sidewaysSwipe(onSwipe: (towardEnd: Boolean) -> Unit): Modifier = composed {
    val currentOnSwipe by rememberUpdatedState(onSwipe)
    pointerInput(Unit) {
        val minimumDistance = 20.dp.toPx()
        val threshold = DateTimePopupMetrics.swipeThreshold.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var translation = Offset.Zero
            var isSwiping = false
            var isScrollingInside = false
            while (true) {
                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                if (change.isConsumed && !isSwiping) isScrollingInside = true
                translation = change.position - down.position
                if (!isScrollingInside && translation.getDistance() >= minimumDistance && abs(translation.x) > abs(translation.y)) {
                    isSwiping = true
                }
                // Ours from here on, so it doesn't also tap what's under the finger.
                if (isSwiping) change.consume()
            }
            if (isSwiping && abs(translation.x) > abs(translation.y) && abs(translation.x) > threshold) {
                currentOnSwipe(translation.x < 0)
            }
        }
    }
}

// MARK: - Header

/**
 * Start → end, each side showing its date and time (or its placeholder), with the
 * arrow centered between two equal halves. The side being edited is lit; tapping
 * the other switches to it.
 */
@Composable
private fun RangeHeader(
    activeField: DateTimePopupField,
    selectedDay: Instant?,
    start: Instant?,
    end: Instant?,
    startZone: ZoneId?,
    endZone: ZoneId?,
    startPlaceholder: String,
    endPlaceholder: String,
    endIsOptional: Boolean,
    onActivate: (DateTimePopupField) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(DateTimePopupMetrics.headerArrowSpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Endpoint(
            field = DateTimePopupField.Start,
            isActive = activeField == DateTimePopupField.Start,
            date = start,
            zone = startZone,
            selectedDay = selectedDay,
            placeholder = startPlaceholder,
            onActivate = onActivate,
            modifier = Modifier.weight(1f),
        )
        Icon(
            Icons.Rounded.ArrowCircleRight,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.7f),
            modifier = Modifier.size(scaledSymbolSize(AppFont.system(16f), scale = 1.2f)),
        )
        Endpoint(
            field = DateTimePopupField.End,
            isActive = activeField == DateTimePopupField.End,
            date = end,
            zone = endZone,
            selectedDay = selectedDay,
            placeholder = endPlaceholder,
            isOptional = endIsOptional,
            onActivate = onActivate,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * One side of the header: the date over the time and zone code. Unset, its label
 * takes the date line and "(optional)", if it applies, the time line, so both sides
 * keep the same two-line shape.
 */
@Composable
private fun Endpoint(
    field: DateTimePopupField,
    isActive: Boolean,
    date: Instant?,
    zone: ZoneId?,
    selectedDay: Instant?,
    placeholder: String,
    isOptional: Boolean = false,
    onActivate: (DateTimePopupField) -> Unit,
    modifier: Modifier = Modifier,
) {
    val barOpacity by animateFloatAsState(if (isActive) 1f else 0f, AppAnimation.snappy(0.35), label = "bar")
    // Insets placing the active bar a little in from the card's corner on the outer
    // side and short of the arrow on the inner side.
    val towardArrow = DateTimePopupMetrics.activeBarArrowGap - DateTimePopupMetrics.headerArrowSpacing
    val barLeading = if (field == DateTimePopupField.Start) DateTimePopupMetrics.activeBarOuterInset else towardArrow
    val barTrailing = if (field == DateTimePopupField.End) DateTimePopupMetrics.activeBarOuterInset else towardArrow

    Box(
        modifier = modifier
            .plainClickable { onActivate(field) }
            .semantics {
                contentDescription = if (field == DateTimePopupField.Start) "Start" else "End"
                selected = isActive
            }
            .background(
                Color.appForeground.copy(alpha = 0.04f),
                RoundedCornerShape(DateTimePopupMetrics.gridCornerRadius),
            )
            // The side being edited gets a white bar on the card's top edge: a little in
            // from where the card's rounded corner ends to short of the arrow.
            .drawBehind {
                if (barOpacity > 0f) {
                    drawRect(
                        Color.White.copy(alpha = barOpacity),
                        topLeft = Offset(barLeading.toPx(), -DateTimePopupMetrics.cardPadding.toPx()),
                        size = Size(size.width - barLeading.toPx() - barTrailing.toPx(), 3.dp.toPx()),
                    )
                }
            }
            .padding(vertical = 5.dp)
            .defaultMinSize(minHeight = 40.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.clearAndSetSemantics {},
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (date != null) {
                NumericText(monthDayText(date), style = AppFont.subheadline.semibold)
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    NumericText(timeText(date), style = AppFont.caption.medium, modifier = Modifier.alignByBaseline())
                    if (zone != null) {
                        Text(
                            WallClock.zoneAbbreviation(zone, date),
                            style = AppFont.caption2,
                            color = Color.appForeground.copy(alpha = 0.6f),
                            maxLines = 1,
                            modifier = Modifier.alignByBaseline(),
                        )
                    }
                }
            } else if (isActive && selectedDay != null) {
                // A day is picked but no time yet, so nothing's saved: show the day
                // and ask for the time.
                NumericText(monthDayText(selectedDay), style = AppFont.subheadline.semibold)
                Text("Select a time", style = AppFont.caption.medium, color = Color.appError, maxLines = 1)
            } else {
                ShrinkingText(
                    placeholder,
                    style = AppFont.subheadline.semibold,
                    minimumScaleFactor = 0.8f,
                    color = Color.appForeground.copy(alpha = 0.45f),
                )
                Text(
                    if (isOptional) "(optional)" else " ",
                    style = AppFont.caption.medium,
                    color = Color.appForeground.copy(alpha = 0.35f),
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * Text whose characters roll to a new value one by one as it changes, leaving the
 * ones that stay the same in place, like a date or time ticking over.
 */
@Composable
private fun NumericText(text: String, style: TextStyle, modifier: Modifier = Modifier) {
    Row(modifier) {
        text.forEachIndexed { index, character ->
            key(index) {
                AnimatedContent(
                    targetState = character,
                    transitionSpec = {
                        val slide = AppAnimation.snappy<IntOffset>(0.25)
                        val fade = AppAnimation.snappy<Float>(0.25)
                        (slideInVertically(slide) { it / 2 } + fadeIn(fade))
                            .togetherWith(slideOutVertically(slide) { -it / 2 } + fadeOut(fade))
                            .using(SizeTransform(clip = false))
                    },
                    label = "character",
                ) { shown ->
                    Text(shown.toString(), style = style, maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

// MARK: - Pane

/** The calendar and time controls for the side being edited. */
@Composable
private fun Pane(
    selection: PaneSelection,
    actions: PaneActions,
    tripDates: ClosedRange<Instant>?,
    viewedDay: Instant?,
    dayCellSize: Dp,
    onDayCellSizeChange: (Dp) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().background(Color.Black),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (tripDates != null) {
            val months = remember(tripDates) { TripCalendarGrid.months(tripDates.start, tripDates.endInclusive) }
            // Hugs the calendar until it needs to scroll.
            Box(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                TripCalendar(months, selection.day, viewedDay, actions.selectDay, onDayCellSizeChange)
            }
        } else {
            Text(
                "This trip has no dates yet.",
                style = AppFont.footnote,
                color = Color.appForeground.copy(alpha = 0.6f),
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }

        // Sets the date section apart from the time below it.
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color.appForeground.copy(alpha = 0.12f)))

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            HourRow(selectedHour = selection.hour, cellSize = dayCellSize, onSelect = actions.selectHour)
            MinuteRow(selection = selection, actions = actions)
        }
    }
}

/**
 * A section per month the trip touches: the month's name over its days, inset to
 * line up with the date and time labels.
 */
@Composable
private fun TripCalendar(
    months: List<TripCalendarGrid.Month>,
    selectedDay: Instant?,
    viewedDay: Instant?,
    onSelectDay: (Instant) -> Unit,
    onDayCellSizeChange: (Dp) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        for (month in months) {
            key(month.id) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        monthName(month.start).uppercase(),
                        style = AppFont.caption.semibold.copy(letterSpacing = 2.5.sp),
                        color = Color.appForeground.copy(alpha = 0.25f),
                        modifier = Modifier.padding(horizontal = 4.dp).fillMaxWidth().semantics { heading() },
                    )
                    CalendarDayGrid(weeks = month.grid.weeks, onDayCellSizeChange = onDayCellSizeChange) { day ->
                        CalendarDayCell(
                            day = day,
                            isSelected = day.isInTrip && selectedDay == day.date,
                            isViewed = day.date == viewedDay,
                            onSelect = { onSelectDay(day.date) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Weeks of days as rows of seven square cells, each drawn by `dayCell` (usually a
 * `CalendarDayCell`). `onDayCellSizeChange` hears the side of a cell as laid out.
 */
@Composable
fun CalendarDayGrid(
    weeks: List<List<TripCalendarGrid.Day>>,
    onDayCellSizeChange: (Dp) -> Unit = {},
    modifier: Modifier = Modifier,
    dayCell: @Composable (TripCalendarGrid.Day) -> Unit,
) {
    val density = LocalDensity.current
    val spacing = DateTimePopupMetrics.cellSpacing
    Column(
        modifier = modifier
            .fillMaxWidth()
            .onSizeChanged { size -> onDayCellSizeChange(with(density) { (size.width.toDp() - spacing * 6) / 7 }) },
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) {
        for (week in weeks) {
            Row(horizontalArrangement = Arrangement.spacedBy(spacing)) {
                for (day in week) {
                    key(day.id) {
                        Box(Modifier.weight(1f)) { dayCell(day) }
                    }
                }
            }
        }
    }
}

/**
 * A square day; trip days can be tapped to select them, filling the cell like
 * the selected hour. Days outside the trip only pad the week. `isViewed` marks the
 * day being viewed in the itinerary with a dot.
 */
@Composable
fun CalendarDayCell(
    day: TripCalendarGrid.Day,
    isSelected: Boolean,
    isViewed: Boolean = false,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val localDay = remember(day.date) { Calendar.sundayFirst.day(day.date) }
    // Weekends stand out so the week's shape reads at a glance: trip weekends get a
    // white line along the bottom, padding weekends a slightly lighter fill.
    val isWeekend = localDay.dayOfWeek == DayOfWeek.SATURDAY || localDay.dayOfWeek == DayOfWeek.SUNDAY
    val fill = if (day.isInTrip) 0.14f else if (isWeekend) 0.09f else 0.06f
    val showsWeekendLine = isWeekend && day.isInTrip && !isSelected
    val background by animateColorAsState(
        if (isSelected) Color.appForeground else Color.appForeground.copy(alpha = fill),
        AppAnimation.snappy(0.15),
        label = "fill",
    )
    val textColor by animateColorAsState(
        if (isSelected) Color.Black else Color.appForeground.copy(alpha = if (day.isInTrip) 1f else 0.3f),
        AppAnimation.snappy(0.15),
        label = "text",
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            // Not disabled as a whole, which would also fade the padding days past
            // their own styling: they just can't be tapped or read out.
            .then(
                if (day.isInTrip) {
                    Modifier
                        .plainClickable(onClick = onSelect)
                        .semantics {
                            contentDescription = dayLabel(day.date)
                            selected = isSelected
                        }
                } else {
                    Modifier.clearAndSetSemantics {}
                }
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .matchParentSize()
                .clip(RoundedCornerShape(DateTimePopupMetrics.gridCornerRadius))
                .background(background)
        ) {
            if (showsWeekendLine) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(Color.White.copy(alpha = 0.5f))
                )
            }
        }
        Text(
            localDay.dayOfMonth.toString(),
            style = (if (day.isInTrip) AppFont.subheadline.semibold else AppFont.subheadline).monospacedDigit,
            color = textColor,
            maxLines = 1,
            // Read out as the cell's full date instead.
            modifier = Modifier.clearAndSetSemantics {},
        )
        // Marks the day being viewed in the itinerary; black on the cream selected
        // fill so it stays visible there too.
        if (isViewed) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(4.dp)
                    .background(if (isSelected) Color.Black else Color.White.copy(alpha = 0.6f), CircleShape)
            )
        }
    }
}

// MARK: - Hours

/**
 * Hours 1–12 as one rounded box of cells the size of a day cell, split by thin
 * dividers. The hours repeat in a lazy row that opens on the selection in the
 * middle copy, so the box scrolls endlessly either way.
 */
@Composable
private fun HourRow(selectedHour: Int?, cellSize: Dp, onSelect: (Int) -> Unit) {
    val hours = DateTimePopupMetrics.hours
    val middle = DateTimePopupMetrics.middleCycle * hours.size
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = middle + (selectedHour ?: 1) - 1)
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val itemSize = with(density) { (cellSize + 1.dp).roundToPx() }
    val openingHour by rememberUpdatedState(selectedHour)
    var viewportWidth by remember { mutableIntStateOf(0) }

    // Opens on the selected hour in the middle copy, or at the start of that copy when
    // no hour is selected. The cells resize once the calendar is measured; this keeps
    // the selection centered then too.
    LaunchedEffect(itemSize, viewportWidth) {
        if (viewportWidth == 0) return@LaunchedEffect
        val hour = openingHour
        if (hour != null) {
            listState.scrollToCenter(middle + hour - 1, itemSize)
        } else {
            listState.scrollToItem(middle)
        }
    }

    LazyRow(
        state = listState,
        modifier = Modifier
            .fillMaxWidth()
            .height(cellSize)
            .clip(RoundedCornerShape(DateTimePopupMetrics.gridCornerRadius))
            .background(Color.appForeground.copy(alpha = 0.14f))
            .drawWithContent {
                drawContent()
                drawHourRowEdgeFades()
            }
            .onSizeChanged { viewportWidth = it.width },
        // Tame the fling: a swipe moves only a few hours, then settles on a cell edge.
        flingBehavior = rememberByFewFlingBehavior(listState, itemSize),
    ) {
        items(count = DateTimePopupMetrics.hourCycles * hours.size, key = { it }) { index ->
            Row(
                Modifier
                    .fillMaxHeight()
                    // Screen readers get one set of hours rather than every copy.
                    .then(
                        if (index / hours.size == DateTimePopupMetrics.middleCycle) Modifier else Modifier.clearAndSetSemantics {}
                    )
            ) {
                if (index > 0) {
                    Box(
                        Modifier
                            .padding(vertical = 10.dp)
                            .width(1.dp)
                            .fillMaxHeight()
                            .background(Color.appForeground.copy(alpha = 0.15f))
                    )
                }
                val hour = hours[index % hours.size]
                HourCell(hour = hour, isSelected = selectedHour == hour, width = cellSize) {
                    onSelect(hour)
                    // Bring the tapped copy to the middle, clear of the edge fades.
                    scope.launch { listState.scrollToCenter(index, itemSize, AppAnimation.snappy(0.3)) }
                }
            }
        }
    }
}

/** A tappable hour, filled edge to edge when selected. */
@Composable
private fun HourCell(hour: Int, isSelected: Boolean, width: Dp, onSelect: () -> Unit) {
    val background by animateColorAsState(
        if (isSelected) Color.appForeground else Color.Transparent,
        AppAnimation.snappy(0.15),
        label = "fill",
    )
    val textColor by animateColorAsState(
        if (isSelected) Color.Black else Color.appForeground,
        AppAnimation.snappy(0.15),
        label = "text",
    )
    Box(
        modifier = Modifier
            .width(width)
            .fillMaxHeight()
            .plainClickable(onClick = onSelect)
            .semantics {
                contentDescription = "$hour o'clock"
                selected = isSelected
            }
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            hour.toString(),
            style = AppFont.subheadline.semibold.monospacedDigit,
            color = textColor,
            maxLines = 1,
            // Read out as the cell's "o'clock" instead.
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}

/**
 * Dark gradients over both ends of the hour row, so hours fade in and out as they
 * scroll; drawn over the hours, they let taps through to them.
 */
private fun DrawScope.drawHourRowEdgeFades() {
    val width = DateTimePopupMetrics.hourFadeWidth.toPx()
    val dark = Color.Black.copy(alpha = DateTimePopupMetrics.hourFadeOpacity)
    drawRect(
        Brush.horizontalGradient(listOf(dark, Color.Transparent), startX = 0f, endX = width),
        size = Size(width, size.height),
    )
    drawRect(
        Brush.horizontalGradient(listOf(Color.Transparent, dark), startX = size.width - width, endX = size.width),
        topLeft = Offset(size.width - width, 0f),
        size = Size(width, size.height),
    )
}

/**
 * Scrolls a sideways list so item `index`, `itemSize` pixels wide, sits in its
 * middle, moving there with `animation` when one is given.
 */
private suspend fun LazyListState.scrollToCenter(index: Int, itemSize: Int, animation: AnimationSpec<Float>? = null) {
    val viewport = layoutInfo.viewportSize.width
    val leadingOffset = -(viewport - itemSize) / 2
    val item = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
    when {
        animation == null -> scrollToItem(index, leadingOffset)
        item != null -> animateScrollBy(item.offset + item.size / 2f - viewport / 2f, animation)
        else -> animateScrollToItem(index, leadingOffset)
    }
}

/**
 * A fling that carries a sideways list of `itemSize`-pixel items only a few of them
 * on, then settles with an item at the leading edge.
 */
@Composable
private fun rememberByFewFlingBehavior(listState: LazyListState, itemSize: Int): FlingBehavior {
    val provider = remember(listState, itemSize) {
        val aligned = SnapLayoutInfoProvider(listState, SnapPosition.Start)
        val furthest = itemSize * 3f
        object : SnapLayoutInfoProvider by aligned {
            override fun calculateApproachOffset(velocity: Float, decayOffset: Float): Float =
                aligned.calculateApproachOffset(velocity, decayOffset).coerceIn(-furthest, furthest)
        }
    }
    return rememberSnapFlingBehavior(provider)
}

// MARK: - Minutes

/**
 * The minutes, then AM/PM after the step toggle, as plain text across the row:
 * the selected choices in full white, the rest at half opacity. Quarter hours
 * spread out, or every minute in a scrolling strip.
 */
@Composable
private fun MinuteRow(selection: PaneSelection, actions: PaneActions) {
    // Six equal columns around the toggle: four for the minutes either way, so
    // switching steps doesn't move AM/PM.
    Row(verticalAlignment = Alignment.CenterVertically) {
        AnimatedContent(
            targetState = selection.usesMinuteSteps,
            modifier = Modifier.weight(4f),
            transitionSpec = {
                fadeIn(AppAnimation.snappy(0.2)).togetherWith(fadeOut(AppAnimation.snappy(0.2)))
            },
            label = "minutes",
        ) { usesMinuteSteps ->
            if (usesMinuteSteps) {
                EveryMinuteStrip(selectedMinute = selection.minute, onSelect = actions.selectMinute)
            } else {
                Row {
                    for (minute in DateTimePopupMetrics.minutes) {
                        TextChoice(
                            label = twoDigits(minute),
                            isSelected = selection.minute == minute,
                            description = "$minute minutes",
                            modifier = Modifier.weight(1f),
                        ) {
                            actions.selectMinute(minute)
                        }
                    }
                }
            }
        }
        MinuteStepToggle(usesMinuteSteps = selection.usesMinuteSteps, onToggle = actions.toggleMinuteSteps)
        TextChoice("AM", isSelected = !selection.isPM, modifier = Modifier.weight(1f)) { actions.selectIsPM(false) }
        TextChoice("PM", isSelected = selection.isPM, modifier = Modifier.weight(1f)) { actions.selectIsPM(true) }
    }
}

/**
 * Switches the minutes between quarter hours and single minutes: a dial whose
 * ticks get finer, lit while on. Back to quarters, the minute snaps to the nearest.
 */
@Composable
private fun MinuteStepToggle(usesMinuteSteps: Boolean, onToggle: () -> Unit) {
    val opacity by animateFloatAsState(if (usesMinuteSteps) 1f else 0.5f, AppAnimation.snappy(0.2), label = "dial")
    Box(
        modifier = Modifier
            .size(width = DateTimePopupMetrics.minuteStepToggleWidth, height = 36.dp)
            .plainClickable(onClick = onToggle)
            .semantics {
                contentDescription = "1-minute steps"
                selected = usesMinuteSteps
            },
        contentAlignment = Alignment.Center,
    ) {
        DialIcon(
            isFine = usesMinuteSteps,
            color = Color.White.copy(alpha = opacity),
            modifier = Modifier.size(scaledSymbolSize(AppFont.system(15f), scale = 1.25f)),
        )
    }
}

/**
 * A dial: a knob with a pointer, ringed by ticks from lower left round to lower
 * right. Coarse, it's an outline turned low with a few ticks; fine, it's filled and
 * turned high with many.
 */
@Composable
private fun DialIcon(isFine: Boolean, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
        val radius = size.minDimension / 2
        val stroke = radius * 0.16f
        // Angles run clockwise from the lower left, leaving the bottom open.
        fun direction(turn: Float): Offset {
            val angle = (135f + 270f * turn) * PI / 180
            return Offset(cos(angle).toFloat(), sin(angle).toFloat())
        }

        val ticks = if (isFine) 9 else 5
        for (tick in 0 until ticks) {
            val toward = direction(tick / (ticks - 1f))
            drawLine(color, center + toward * (radius * 0.8f), center + toward * (radius - stroke / 2), stroke, StrokeCap.Round)
        }

        val knob = radius * 0.5f
        val pointer = direction(if (isFine) 0.85f else 0.15f)
        if (isFine) {
            drawCircle(color, knob)
            // Cut out of the filled knob.
            drawLine(
                Color.Black,
                center + pointer * (knob * 0.3f),
                center + pointer * (knob * 0.75f),
                stroke,
                StrokeCap.Round,
                blendMode = BlendMode.Clear,
            )
        } else {
            drawCircle(color, knob - stroke / 2, style = Stroke(stroke))
            drawLine(color, center + pointer * (knob * 0.1f), center + pointer * (knob * 0.5f), stroke, StrokeCap.Round)
        }
    }
}

/**
 * Every minute, 00–59, in a strip that scrolls sideways and opens on the selected
 * one in the middle; tapping one centers it. The ends fade like the hour row's.
 */
@Composable
private fun EveryMinuteStrip(selectedMinute: Int, onSelect: (Int) -> Unit) {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = selectedMinute)
    val scope = rememberCoroutineScope()
    val itemSize = with(LocalDensity.current) { DateTimePopupMetrics.minuteStripCellWidth.roundToPx() }
    var viewportWidth by remember { mutableIntStateOf(0) }
    var hasOpened by remember { mutableStateOf(false) }

    // Opens on the selected minute, then follows it: a new side or a duration shortcut
    // can move the minute from outside.
    LaunchedEffect(selectedMinute, viewportWidth) {
        if (viewportWidth == 0) return@LaunchedEffect
        listState.scrollToCenter(selectedMinute, itemSize, if (hasOpened) AppAnimation.snappy(0.3) else null)
        hasOpened = true
    }

    LazyRow(
        state = listState,
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                val fade = 24.dp.toPx()
                drawRect(
                    Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), startX = 0f, endX = fade),
                    size = Size(fade, size.height),
                    blendMode = BlendMode.DstIn,
                )
                drawRect(
                    Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), startX = size.width - fade, endX = size.width),
                    topLeft = Offset(size.width - fade, 0f),
                    size = Size(fade, size.height),
                    blendMode = BlendMode.DstIn,
                )
            }
            .onSizeChanged { viewportWidth = it.width },
        flingBehavior = rememberByFewFlingBehavior(listState, itemSize),
    ) {
        items(count = 60, key = { it }) { minute ->
            TextChoice(
                label = twoDigits(minute),
                isSelected = selectedMinute == minute,
                description = "$minute minutes",
                modifier = Modifier.width(DateTimePopupMetrics.minuteStripCellWidth),
            ) {
                onSelect(minute)
                scope.launch { listState.scrollToCenter(minute, itemSize, AppAnimation.snappy(0.3)) }
            }
        }
    }
}

/**
 * A tappable plain-text choice, full white when selected and half opacity otherwise;
 * read out as `description` when its label alone wouldn't say what it is.
 */
@Composable
private fun TextChoice(
    label: String,
    isSelected: Boolean,
    description: String? = null,
    modifier: Modifier = Modifier,
    action: () -> Unit,
) {
    val opacity by animateFloatAsState(if (isSelected) 1f else 0.5f, AppAnimation.snappy(0.15), label = "choice")
    Box(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 36.dp)
            .plainClickable(onClick = action)
            .semantics {
                if (description != null) contentDescription = description
                selected = isSelected
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = AppFont.subheadline.semibold.monospacedDigit,
            color = Color.White.copy(alpha = opacity),
            maxLines = 1,
            modifier = if (description != null) Modifier.clearAndSetSemantics {} else Modifier,
        )
    }
}

// MARK: - Time zone

/**
 * A search field for the time zone, kept dark with a light outline. Matches float
 * in a list over the card rather than growing it: below the field, or above it
 * when there isn't room below (say, with the keyboard up). Clearing it removes the zone.
 */
@Composable
private fun ZoneField(
    state: DateTimePopupState,
    zone: ZoneId?,
    referenceDate: Instant,
    onZoneChange: (ZoneId?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    val density = LocalDensity.current
    val popupArea = LocalPopupCoordinates.current
    val popupVisibleHeight = LocalPopupVisibleHeight.current
    val shape = RoundedCornerShape(DateTimePopupMetrics.gridCornerRadius)

    // Zones matching what's typed, hidden once the text is the chosen zone's label.
    val query = state.zoneQuery
    val isSearching = state.isZoneFieldFocused && query != zone?.let { TimeZoneSearch.label(it, referenceDate) }
    val zoneResults = remember(isSearching, query, referenceDate) {
        if (isSearching) TimeZoneSearch.matches(query, referenceDate) else emptyList()
    }

    // Height of the suggestion list: its fixed-height rows plus the dividers between.
    val zoneResultsHeight = DateTimePopupMetrics.zoneResultRowHeight * zoneResults.size +
        1.dp * maxOf(zoneResults.size - 1, 0)
    // Whether the suggestion list opens above the field: when the room left below it
    // in the visible area (above the keyboard, when it's up) can't fit the list.
    val roomBelow = popupVisibleHeight - state.zoneFieldBottom - DateTimePopupMetrics.zoneResultsEdgeMargin
    val showsZoneResultsAbove = popupVisibleHeight > 0.dp &&
        roomBelow < zoneResultsHeight + DateTimePopupMetrics.zoneResultsGap

    Box(modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(DateTimePopupMetrics.zoneFieldHeight)
                .border(1.dp, Color.appForeground.copy(alpha = 0.25f), shape)
                .onGloballyPositioned { field ->
                    val area = popupArea?.takeIf { it.isAttached } ?: return@onGloballyPositioned
                    val bottom = area.localPositionOf(field, Offset.Zero).y + field.size.height
                    state.zoneFieldBottom = with(density) { bottom.toDp() }
                }
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val iconSize = scaledSymbolSize(AppFont.subheadline, scale = 1.2f)
            Icon(
                Icons.Outlined.Language,
                contentDescription = null,
                tint = Color.appForeground.copy(alpha = 0.5f),
                modifier = Modifier.size(iconSize),
            )
            PlainTextField(
                text = query,
                onTextChange = { state.zoneQuery = it },
                placeholder = "Timezone (optional)",
                modifier = Modifier.weight(1f).onFocusChanged { state.isZoneFieldFocused = it.isFocused },
                textStyle = AppFont.subheadline,
                color = Color.appForeground,
                placeholderColor = Color.appForeground.copy(alpha = 0.4f),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
            )
            if (query.isNotEmpty()) {
                Icon(
                    Icons.Rounded.Cancel,
                    contentDescription = "Clear time zone",
                    tint = Color.appForeground.copy(alpha = 0.5f),
                    modifier = Modifier
                        .plainClickable {
                            state.zoneQuery = ""
                            onZoneChange(null)
                        }
                        .size(iconSize),
                )
            }
        }

        if (zoneResults.isNotEmpty()) {
            // Laid out just clear of the field, above or below, taking no room itself.
            val clearance = DateTimePopupMetrics.zoneFieldHeight + DateTimePopupMetrics.zoneResultsGap
            ZoneResultsList(
                zoneResults = zoneResults,
                referenceDate = referenceDate,
                onSelect = { match ->
                    onZoneChange(match)
                    state.zoneQuery = TimeZoneSearch.label(match, referenceDate)
                    focusManager.clearFocus()
                },
                modifier = Modifier.layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
                    val gap = DateTimePopupMetrics.zoneResultsGap.roundToPx()
                    layout(placeable.width, 0) {
                        placeable.place(0, if (showsZoneResultsAbove) -placeable.height - gap else clearance.roundToPx())
                    }
                },
            )
        }
    }
}

/** The floating list of matching zones. */
@Composable
private fun ZoneResultsList(
    zoneResults: List<ZoneId>,
    referenceDate: Instant,
    onSelect: (ZoneId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(DateTimePopupMetrics.gridCornerRadius)
    Column(
        modifier
            .fillMaxWidth()
            .shadow(16.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
            .background(Color.white(0.08f), shape)
            .border(1.dp, Color.appForeground.copy(alpha = 0.25f), shape)
    ) {
        zoneResults.forEachIndexed { index, match ->
            key(match.id) {
                if (index > 0) {
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Color.appForeground.copy(alpha = 0.12f)))
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(DateTimePopupMetrics.zoneResultRowHeight)
                        .plainClickable { onSelect(match) }
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(TimeZoneSearch.city(match), style = AppFont.subheadline, maxLines = 1)
                    // Long ids (America/North_Dakota/New_Salem) are cut short, keeping the
                    // abbreviation after them readable.
                    Row(
                        modifier = Modifier.weight(1f).padding(start = 12.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        val detail = AppFont.caption
                        val faded = Color.appForeground.copy(alpha = 0.5f)
                        Text(
                            match.id,
                            style = detail,
                            color = faded,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Text(
                            " · ${WallClock.zoneAbbreviation(match, referenceDate)}",
                            style = detail,
                            color = faded,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

// MARK: - End durations

/**
 * Sets the end a chosen length after the start, as a lighter band across the
 * bottom of the card. The options are faded and inert until there's a start to
 * count from.
 */
@Composable
private fun EndDurations(isEnabled: Boolean, onSelect: (minutes: Int) -> Unit) {
    val corner = PopupCardMetrics.cornerRadius
    Column(
        modifier = Modifier
            // Out to the card's sides and bottom, rounding with its bottom corners.
            .outset(horizontal = DateTimePopupMetrics.cardPadding, bottom = DateTimePopupMetrics.cardPadding)
            .background(
                Color.appForeground.copy(alpha = 0.1f),
                RoundedCornerShape(bottomStart = corner, bottomEnd = corner),
            )
            .padding(horizontal = DateTimePopupMetrics.cardPadding, vertical = 16.dp)
            .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Set end time by duration:", style = AppFont.caption, color = Color.appForeground.copy(alpha = 0.5f))

        Row(
            modifier = Modifier.dimmed(!isEnabled),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (choice in endDurationChoices) {
                // In a box like the days', all as wide as a two-digit label so
                // the boxes match.
                Box(
                    modifier = Modifier
                        .plainClickable(enabled = isEnabled) { onSelect(choice.minutes) }
                        .background(
                            Color.appForeground.copy(alpha = 0.12f),
                            RoundedCornerShape(DateTimePopupMetrics.gridCornerRadius),
                        )
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    DurationLabel(amount = "00", unit = "m", modifier = Modifier.alpha(0f).clearAndSetSemantics {})
                    DurationLabel(amount = choice.amount, unit = choice.unit)
                }
            }
        }
    }
}

/** A duration as shown, the unit two sizes down from the amount. */
@Composable
private fun DurationLabel(amount: String, unit: String, modifier: Modifier = Modifier) {
    Text(
        buildAnnotatedString {
            append(amount)
            withStyle(SpanStyle(fontSize = AppFont.caption2.fontSize)) { append(unit) }
        },
        style = AppFont.footnote.semibold,
        color = Color.appForeground,
        maxLines = 1,
        modifier = modifier,
    )
}

// MARK: - Text

/** A minute as the clock shows it, e.g. "05". */
private fun twoDigits(minute: Int): String = minute.toString().padStart(2, '0')

/** A date on the device-zone canvas as its month and day, e.g. "Dec 23". */
private fun monthDayText(date: Instant): String =
    DateTimeFormatter.ofPattern("MMM d", Locale.getDefault()).format(date.atZone(ZoneId.systemDefault()))

/** A date on the device-zone canvas as its time, e.g. "4:00 PM". */
private fun timeText(date: Instant): String =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.getDefault())
        .format(date.atZone(ZoneId.systemDefault()))

/** The name of the month a day is in, e.g. "December". */
private fun monthName(date: Instant): String =
    Calendar.sundayFirst.day(date).month.getDisplayName(java.time.format.TextStyle.FULL_STANDALONE, Locale.getDefault())

/** A day read out in full, e.g. "Wednesday, December 23". */
private fun dayLabel(date: Instant): String =
    DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.getDefault()).format(date.atZone(ZoneId.systemDefault()))

@Preview
@Composable
private fun DateTimePopupPreview() {
    AppTheme {
        var start by remember { mutableStateOf<Instant?>(Instant.now()) }
        var end by remember { mutableStateOf<Instant?>(null) }
        var startZone by remember { mutableStateOf<ZoneId?>(ZoneId.systemDefault()) }
        var endZone by remember { mutableStateOf<ZoneId?>(null) }
        Box(Modifier.background(Color.white(0.3f)), contentAlignment = Alignment.Center) {
            DateTimePopup(
                field = DateTimePopupField.Start,
                startPlaceholder = "Starts",
                endPlaceholder = "Ends",
                endIsOptional = true,
                tripDates = Instant.now()..Instant.now().plusSeconds(9 * 86_400),
                start = start,
                onStartChange = { start = it },
                end = end,
                onEndChange = { end = it },
                startZone = startZone,
                onStartZoneChange = { startZone = it },
                endZone = endZone,
                onEndZoneChange = { endZone = it },
                onClose = {},
            )
        }
    }
}
