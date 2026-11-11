//
//  TripItemFormScaffold.kt
//  outbound
//

package earth.outbound

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import earth.outbound.auth.LocalAuthStore
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** What a kind calls its start and end, e.g. a lodging's check-in and check-out. */
data class TripItemTimeLabels(
    val start: String,
    val end: String,
    /** Shown when saving without a start, which the API requires. */
    val missingStart: String,
    /** Whether the date popup lets the end be left empty. */
    val endIsOptional: Boolean = false,
    /** Whether the date popup offers setting the end as a duration after the start. */
    val offersEndDurations: Boolean = false,
)

/** The date row whose popup is open. */
private enum class TimeField { Start, End }

/**
 * The layout every trip item form shares: who it's for and who sees it, the title
 * (left out for kinds named by their place search or by the server), the kind's own
 * place fields (`places`), the start/end times (a transit's legs show their own),
 * notes, and the pinned Save/Delete bar. The per-kind forms supply their places and
 * time labels, and anything of their own to show under the times (`afterTimes`,
 * e.g. a stay's room details; nothing by default).
 *
 * The draft is a value, so the form holding it passes the current one along with
 * `updateDraft`, which applies a change to it (`{ change -> draft = change(draft) }`).
 * Fills the sheet it's shown in; `onDismiss` closes that sheet.
 */
@Composable
fun TripItemFormScaffold(
    draft: TripItemDraft,
    updateDraft: ((TripItemDraft) -> TripItemDraft) -> Unit,
    timeLabels: TripItemTimeLabels,
    /** Called with the created or updated item after a successful save. */
    onSaved: (TripItem) -> Unit,
    /** Called after the item being edited is deleted. */
    onDeleted: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    places: @Composable () -> Unit,
    afterTimes: @Composable () -> Unit = {},
) {
    val currentUserId = LocalAuthStore.current.currentUser?.id

    var showingMemberPicker by remember { mutableStateOf(false) }
    /** Which date row's popup is open, if any. */
    var editingTimeField by remember { mutableStateOf<TimeField?>(null) }
    /**
     * Which change to a booked stay is waiting on the user to confirm it's been made
     * with the property too, if any.
     */
    var confirmingBooking by remember { mutableStateOf<BookingChangePopupChange?>(null) }

    val scroll = rememberScrollState()
    /** The scrolling area and the error banner in it, so a failed save brings the banner into view. */
    var scrollArea by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var errorBanner by remember { mutableStateOf<LayoutCoordinates?>(null) }

    // Saving and deleting finish (and report back) even if the sheet is closed meanwhile.
    val currentDraft by rememberUpdatedState(draft)
    val currentOnSaved by rememberUpdatedState(onSaved)
    val currentOnDeleted by rememberUpdatedState(onDeleted)
    val currentOnDismiss by rememberUpdatedState(onDismiss)

    fun performSave() {
        OutboundApp.scope.launch {
            val saved = currentDraft.save(
                currentUserId = currentUserId,
                missingStartMessage = timeLabels.missingStart,
                update = updateDraft,
            ) ?: return@launch
            currentOnSaved(saved)
            currentOnDismiss()
        }
    }

    fun delete() {
        OutboundApp.scope.launch {
            if (!currentDraft.delete(update = updateDraft)) return@launch
            currentOnDeleted()
            currentOnDismiss()
        }
    }

    /**
     * Saves, after the user confirms the property has the change too when a booked
     * stay's dates or number of rooms change.
     */
    fun save() {
        val change = BookingChangePopupChange.edit(
            changingDates = draft.changesBookedDates,
            rooms = draft.changesBookedRoomCount,
        )
        if (change != null) {
            confirmingBooking = change
        } else {
            performSave()
        }
    }

    LaunchedEffect(Unit) { updateDraft { it.seedSelectedMembers(currentUserId = currentUserId) } }
    LaunchedEffect(Unit) { draft.prefillPlaceNames(update = updateDraft) }

    // A failed save brings the error banner to the middle of the form.
    LaunchedEffect(draft.errorScrollTrigger) {
        if (draft.errorScrollTrigger == 0) return@LaunchedEffect
        // Once the banner has been laid out.
        withFrameNanos {}
        val area = scrollArea?.takeIf { it.isAttached } ?: return@LaunchedEffect
        val banner = errorBanner?.takeIf { it.isAttached } ?: return@LaunchedEffect
        val center = area.localPositionOf(banner, Offset.Zero).y + banner.size.height / 2f
        scroll.animateScrollTo((scroll.value + center - area.size.height / 2f).roundToInt(), AppAnimation.default())
    }

    ForegroundStyle(Color.appForeground) {
        Column(
            modifier
                .fillMaxSize()
                .background(Color.sheetBackground)
                // Clear of the navigation bar, and of the keyboard while it's up; the
                // bottom bar fills the navigation bar's strip itself.
                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime).only(WindowInsetsSides.Bottom))
        ) {
            // The bar is dark whatever the theme, so its title and button draw in white.
            ForegroundStyle(Color.White) {
                SheetBar(
                    title = if (draft.isEditing) "Edit ${draft.kind.label}" else "New ${draft.kind.label}",
                    trailing = { SheetBarCloseButton(onClose = onDismiss) },
                )
            }

            Box(Modifier.weight(1f).onGloballyPositioned { scrollArea = it }) {
                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(scroll).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    // Who it's for and who else sees it read as one group, so the
                    // picker sits tight under the members row with no label of its own.
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        MembersRow(
                            summary = draft.memberSelection(currentUserId = currentUserId)
                                .summary(draft.selectedMemberIds ?: emptySet()),
                            onClick = { showingMemberPicker = true },
                        )
                        SegmentedPicker(
                            title = "Visibility",
                            options = TripItemVisibility.entries,
                            selection = draft.visibility,
                            onSelectionChange = { visibility -> updateDraft { it.copy(visibility = visibility) } },
                            label = { it.label },
                        )
                        Text(
                            draft.visibility.explanation,
                            style = AppFont.caption,
                            color = Color.appForeground.copy(alpha = 0.7f),
                        )
                    }

                    if (draft.hasTitleField) {
                        AppTextField(
                            text = draft.title,
                            onTextChange = { title -> updateDraft { it.copy(title = title) } },
                            placeholder = "Title",
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                        )
                    }

                    // Above the blocks below, so floating suggestions cover them.
                    Column(Modifier.zIndex(1f), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        places()
                    }

                    // A transit's legs show their own times, within `places`.
                    if (!draft.hasLegs) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TripItemTimeBlock(
                                title = timeLabels.start,
                                placeholder = "Add start",
                                date = draft.startsAt,
                                onDateChange = { date -> updateDraft { it.copy(startsAt = date) } },
                                zone = draft.startZone,
                                onZoneChange = { zone -> updateDraft { it.copy(startZone = zone) } },
                                onTap = { editingTimeField = TimeField.Start },
                                modifier = Modifier.weight(1f),
                            )
                            TripItemTimeBlock(
                                title = timeLabels.end,
                                placeholder = "Add end",
                                date = draft.endsAt,
                                onDateChange = { date -> updateDraft { it.copy(endsAt = date) } },
                                zone = draft.endZone,
                                onZoneChange = { zone -> updateDraft { it.copy(endZone = zone) } },
                                onTap = { editingTimeField = TimeField.End },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }

                    afterTimes()

                    // Sits right under the times (the usual cause) rather than at the
                    // bottom, where the keyboard hides it and Save looks unresponsive.
                    draft.errorMessage?.let { errorMessage ->
                        ErrorBanner(errorMessage, modifier = Modifier.onPlaced { errorBanner = it })
                    }

                    AppTextField(
                        text = draft.notes,
                        onTextChange = { notes -> updateDraft { it.copy(notes = notes) } },
                        placeholder = "Notes (optional)",
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        singleLine = false,
                        minLines = 3,
                        maxLines = 6,
                    )
                }
            }

            FormBottomBar(
                notifyLabel = if (draft.canNotifyMembers(currentUserId = currentUserId)) {
                    if (draft.isEditing) "Notify members of changes" else "Notify members"
                } else {
                    null
                },
                notifyMembers = draft.notifyMembers,
                onNotifyMembersChange = { notify -> updateDraft { it.copy(notifyMembers = notify) } },
                saveTitle = if (draft.isEditing) "Save" else "Add ${draft.kind.label}",
                canSave = draft.hasName,
                isSaving = draft.isSaving,
                onSave = ::save,
                delete = if (draft.isEditing) {
                    FormBottomBarDelete(
                        title = "Delete ${draft.kind.label}",
                        confirmation = "Delete this ${draft.kind.label.lowercase()}?",
                        message = "This can't be undone.",
                        action = ::delete,
                        // A booked stay asks in its own popup, about the real booking.
                        onRequest = if (draft.deletesBookedStay) {
                            { confirmingBooking = BookingChangePopupChange.Delete }
                        } else {
                            null
                        },
                    )
                } else {
                    null
                },
                isDeleting = draft.isDeleting,
                homeIndicatorInset = LocalSheetBottomInset.current,
                background = Color.sheetBackground,
            )
        }
    }

    // The open member picker over a dimmed form; tapping outside closes it.
    DimmedPopup(isPresented = showingMemberPicker, onClose = { showingMemberPicker = false }) {
        ItemMembersPopup(
            members = draft.members,
            currentUserId = currentUserId,
            selectedIds = draft.selectedMemberIds ?: emptySet(),
            onSelectedIdsChange = { ids -> updateDraft { it.withSelectedMemberIds(ids) } },
            onClose = { showingMemberPicker = false },
        )
    }

    // The open date popup for the tapped row over a dimmed form; tapping outside closes it.
    val shownTimeField = lastPresented(editingTimeField)
    DimmedPopup(isPresented = editingTimeField != null, onClose = { editingTimeField = null }) {
        if (shownTimeField != null) {
            // Opened anew on the other row, rather than carrying over what was picked.
            key(shownTimeField) {
                DateTimePopup(
                    field = if (shownTimeField == TimeField.Start) DateTimePopupField.Start else DateTimePopupField.End,
                    startPlaceholder = timeLabels.start,
                    endPlaceholder = timeLabels.end,
                    endIsOptional = timeLabels.endIsOptional,
                    defaultEndHour = draft.defaultEndHour,
                    offersEndDurations = timeLabels.offersEndDurations,
                    tripDates = draft.tripDates,
                    start = draft.startsAt,
                    onStartChange = { date -> updateDraft { it.copy(startsAt = date) } },
                    end = draft.endsAt,
                    onEndChange = { date -> updateDraft { it.copy(endsAt = date) } },
                    startZone = draft.startZone,
                    onStartZoneChange = { zone -> updateDraft { it.copy(startZone = zone) } },
                    endZone = draft.endZone,
                    onEndZoneChange = { zone -> updateDraft { it.copy(endZone = zone) } },
                    viewedDay = draft.viewedDay,
                    onClose = { editingTimeField = null },
                )
            }
        }
    }

    // The popup reminding that a stay's real booking isn't changed here; confirming
    // goes ahead with the save or delete.
    val shownBookingChange = lastPresented(confirmingBooking)
    DimmedPopup(isPresented = confirmingBooking != null, onClose = { confirmingBooking = null }) {
        if (shownBookingChange != null) {
            BookingChangePopup(
                change = shownBookingChange,
                onConfirm = {
                    confirmingBooking = null
                    when (shownBookingChange) {
                        is BookingChangePopupChange.Edit -> performSave()
                        BookingChangePopupChange.Delete -> delete()
                    }
                },
                onClose = { confirmingBooking = null },
            )
        }
    }
}

// MARK: - Members

/** A one-line row summarizing who the item is for; tapping it opens the picker. */
@Composable
private fun MembersRow(summary: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().plainClickable(onClick = onClick).formRow(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Members")
        Spacer(Modifier.weight(1f))
        Text(summary, color = Color.appForeground.copy(alpha = 0.75f), maxLines = 1)
    }
}

// MARK: - Visibility

/**
 * The iOS segmented control: the choices side by side on a faint, round-ended track,
 * the picked one on a white pill that slides to it. Light labels to read on the dark
 * gray sheet, dark on the white selected pill.
 */
@Composable
private fun <T> SegmentedPicker(
    title: String,
    options: List<T>,
    selection: T,
    onSelectionChange: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
) {
    val selectedIndex = options.indexOf(selection).coerceAtLeast(0)
    /** Where the pill is, in segments from the start. */
    val pillPosition by animateFloatAsState(selectedIndex.toFloat(), AppAnimation.snappy(0.3), label = "segment")

    Box(
        modifier
            .fillMaxWidth()
            .height(32.dp)
            .background(Color(red = 0.46f, green = 0.46f, blue = 0.5f, alpha = 0.12f), CircleShape)
            .padding(2.dp)
            .semantics { contentDescription = title }
            .selectableGroup()
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                // One segment wide, at the picked segment.
                .layout { measurable, constraints ->
                    val width = constraints.maxWidth / options.size
                    val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
                    layout(constraints.maxWidth, placeable.height) {
                        placeable.placeRelative((pillPosition * width).roundToInt(), 0)
                    }
                }
                .shadow(3.dp, CircleShape)
                .background(Color.White, CircleShape)
        )
        Row(
            Modifier
                .fillMaxSize()
                // A thin rule between neighbors, except either side of the pill.
                .drawBehind {
                    val width = size.width / options.size
                    for (index in 1 until options.size) {
                        val distance = abs(pillPosition - (index - 0.5f))
                        val x = if (layoutDirection == LayoutDirection.Rtl) size.width - index * width else index * width
                        drawLine(
                            color = Color.appForeground.copy(alpha = 0.2f * (distance - 0.5f).coerceIn(0f, 1f)),
                            start = Offset(x, size.height * 0.25f),
                            end = Offset(x, size.height * 0.75f),
                            strokeWidth = 1.dp.toPx(),
                        )
                    }
                }
        ) {
            options.forEachIndexed { index, option ->
                val isSelected = index == selectedIndex
                val color by animateColorAsState(
                    if (isSelected) Color.Black else Color.appForeground,
                    AppAnimation.snappy(0.3),
                    label = "segmentLabel",
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .selectable(
                            selected = isSelected,
                            interactionSource = null,
                            indication = null,
                            role = Role.Tab,
                            onClick = { onSelectionChange(option) },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label(option),
                        style = if (isSelected) AppFont.footnote.semibold else AppFont.footnote.medium,
                        color = color,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

// MARK: - Error

@Composable
private fun ErrorBanner(message: String, modifier: Modifier = Modifier) {
    SymbolLabel(
        message,
        icon = Icons.Rounded.Warning,
        style = AppFont.footnote.medium,
        color = Color.White,
        modifier = modifier
            .fillMaxWidth()
            .background(Color.systemRed.copy(alpha = 0.75f), RoundedCornerShape(appFieldCornerRadius))
            .padding(12.dp),
    )
}

/**
 * A labelled place search, the building block of each kind's place fields. The
 * label is left off when a form has just the one place.
 */
@Composable
fun TripItemPlaceField(
    label: String? = null,
    placeholder: String,
    text: String,
    onTextChange: (String) -> Unit,
    place: Place?,
    onPlaceChange: (Place?) -> Unit,
    /** Told the picked place's time zone, and nil when the selection clears. */
    onTimeZoneChange: (ZoneId?) -> Unit,
    /** Offers taking the text as typed, with no place; see `PlaceSearchField`'s `typedEntry`. */
    typedEntry: String? = null,
    onTypedEntryChange: ((String?) -> Unit)? = null,
    /** Floats the suggestions over the form; see `PlaceSearchField`'s `floatingResults`. */
    floatingResults: PlaceSearchFieldFloatingResults? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (label != null) {
            Text(label, style = formLabelStyle)
        }
        PlaceSearchField(
            placeholder = placeholder,
            text = text,
            onTextChange = onTextChange,
            resolvedPlace = place,
            onResolvedPlaceChange = onPlaceChange,
            onResolvedTimeZoneChange = onTimeZoneChange,
            typedEntry = typedEntry,
            onTypedEntryChange = onTypedEntryChange,
            floatingResults = floatingResults,
        )
    }
}

/**
 * The frame shared by the side-by-side blocks (a start and end time, a transit's
 * origin and destination): the label on top with a clear button once there's a
 * value, a thin rule, then the block's content. Tapping the block calls `onTap`.
 */
@Composable
fun TripItemBlock(
    title: String,
    /** Whether the block holds a value, showing the clear button and a lighter fill. */
    isSet: Boolean,
    onClear: () -> Unit,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    // A shade darker while empty, so an unset block reads as a slot to fill.
    val fill by animateColorAsState(
        Color.appForeground.copy(alpha = if (isSet) 0.08f else 0.04f),
        AppAnimation.default(),
        label = "fill",
    )
    Column(
        modifier
            .fillMaxWidth()
            .background(fill, RoundedCornerShape(appFieldCornerRadius))
            // A tap gesture rather than a button's press, and the clear button inside
            // stays its own target.
            .tapGesture(onTap = onTap)
            .semantics { role = Role.Button }
    ) {
        // The label and clear button sit in their own header above a thin rule,
        // a little tighter than the content below.
        Row(
            // Fixed to the clear button's height, so the header (and the block) is the
            // same height with or without it.
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp).height(22.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title.uppercase(),
                style = AppFont.caption.semibold.copy(letterSpacing = 1.sp),
                color = Color.appForeground.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(end = 4.dp),
            )
            if (isSet) {
                Icon(
                    Icons.Rounded.Cancel,
                    contentDescription = "Clear $title",
                    tint = Color.appForeground.copy(alpha = 0.5f),
                    modifier = Modifier.size(scaledSymbolSize(AppFont.body)).plainClickable(onClick = onClear),
                )
            }
        }

        Box(Modifier.fillMaxWidth().height(1.dp).background(Color.appForeground.copy(alpha = 0.12f)))

        Box(Modifier.padding(14.dp)) { content() }
    }
}

/**
 * One of the side-by-side start/end blocks: the date (or a placeholder) and the time
 * with its zone's short code. The time line always keeps its space, so both blocks
 * stay the same height whether or not they're set. Tapping the block calls `onTap`.
 */
@Composable
fun TripItemTimeBlock(
    title: String,
    placeholder: String,
    date: Instant?,
    onDateChange: (Instant?) -> Unit,
    zone: ZoneId?,
    onZoneChange: (ZoneId?) -> Unit,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TripItemBlock(
        title = title,
        isSet = date != null,
        // Clearing a time clears everything about it, its zone included.
        onClear = {
            onDateChange(null)
            onZoneChange(null)
        },
        onTap = onTap,
        modifier = modifier,
    ) {
        // The form holds wall clocks on the device-zone canvas, so that's where they're read.
        val canvas = ZoneId.systemDefault()
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            ShrinkingText(
                text = date?.let { WallClock.monthDay.withZone(canvas).format(it) } ?: placeholder,
                style = AppFont.body.semibold,
                minimumScaleFactor = 0.75f,
                color = if (date != null) Color.appForeground else Color.appForeground.copy(alpha = 0.45f),
            )

            // The time with its zone's short code beside it (e.g. "12:00 PM EST"); a
            // blank line holds its place when there's no time yet.
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (date != null) {
                    Text(
                        WallClock.hourMinute.withZone(canvas).format(date),
                        style = AppFont.subheadline.medium,
                        color = Color.appForeground.copy(alpha = 0.85f),
                        maxLines = 1,
                        modifier = Modifier.alignByBaseline(),
                    )
                    if (zone != null) {
                        Text(
                            WallClock.zoneAbbreviation(zone, date),
                            style = AppFont.caption2,
                            color = Color.appForeground.copy(alpha = 0.6f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.alignByBaseline(),
                        )
                    }
                } else {
                    Text(" ", style = AppFont.subheadline.medium, maxLines = 1)
                }
            }
        }
    }
}
