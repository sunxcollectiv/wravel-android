//
//  TripFormView.kt
//  outbound
//

package earth.outbound

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.AltRoute
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Luggage
import androidx.compose.material.icons.rounded.RemoveCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import earth.outbound.auth.AuthStore
import earth.outbound.auth.LocalAuthStore
import earth.outbound.networking.APIClient
import earth.outbound.networking.APIError
import earth.outbound.networking.localizedDescription
import earth.outbound.networking.orJsonNull
import java.time.DateTimeException
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** The date row whose calendar is open. */
private enum class DateField { Start, End }

/**
 * The form's fields, and what saving and deleting do with them. Held apart from the
 * composable so a save or delete under way finishes, and reports back, even if the
 * sheet is closed meanwhile.
 *
 * `segmentEdge` is the side of `segmentOf` the new trip goes on: `Leading` for
 * days before it, `Trailing` for days after. Its dates start on the adjacent day.
 */
@Stable
private class TripFormState(
    /** The trip being edited, or null when creating a new one. */
    val trip: Trip?,
    /** The trip a new segment is grouped with, or null for an ordinary trip. */
    val segmentOf: Trip?,
    segmentEdge: HorizontalEdge,
    private val auth: AuthStore,
    private val credits: CreditStore,
) {
    /** Called with the created or updated trip after a successful save. */
    var onSaved: (Trip) -> Unit = {}

    /** Called after the trip being edited is deleted. */
    var onDeleted: () -> Unit = {}

    /**
     * Closes the sheet the form is in. Does nothing once the form is off screen, so a
     * save that finishes after the sheet was closed doesn't close whatever the screen
     * has opened since.
     */
    var dismiss: () -> Unit = {}

    private val defaultDate = segmentOf?.let { adjacentDate(it, segmentEdge) } ?: LocalDate.now()

    var title by mutableStateOf(trip?.title ?: "")
    var startDate by mutableStateOf(trip?.let { apiDate(it.startDate) } ?: defaultDate)
    var endDate by mutableStateOf(trip?.let { apiDate(it.endDate) } ?: defaultDate)
    var description by mutableStateOf(trip?.description ?: "")

    /** The areas the trip covers, shown as chips. */
    var areas by mutableStateOf(trip?.areas ?: emptyList())

    /**
     * Ids of areas marked for removal. They stay as chips, faded, until the trip is
     * saved without them, so a mistaken tap can be undone.
     */
    var areaIdsToRemove by mutableStateOf(emptySet<String>())
    var showingAreaPicker by mutableStateOf(false)

    /**
     * `memberEmails` as the trip had it when the form opened, so an edit only sends
     * the roster when it changed. The owner is always kept by the server, so they
     * aren't listed or sent.
     */
    private val originalMemberEmails: List<String> =
        (trip?.members ?: emptyList()).filter { it.user?.id != trip?.ownerId }.map { it.email }

    /** Emails of everyone on the trip besides the owner, as edited in the invite popup. */
    var memberEmails by mutableStateOf(originalMemberEmails)
    var showingMemberInvite by mutableStateOf(false)

    /** Whether saving an edit tells the trip's other members what changed. */
    var notifyMembers by mutableStateOf(false)
    var activeField by mutableStateOf<DateField?>(null)
    var isSaving by mutableStateOf(false)
    var isDeleting by mutableStateOf(false)

    /** Showing the delete popup. */
    var confirmingDelete by mutableStateOf(false)
    var errorMessage by mutableStateOf<String?>(null)

    /**
     * Whether an edited trip's own dates are still loading. A trip from the list that
     * has extensions carries its whole group's span instead, which saving would write
     * over the trip's real dates, so saving waits for them.
     */
    var isLoadingOwnDates by mutableStateOf(trip?.extensions?.isEmpty() == false)

    /**
     * A save that costs or refunds credits, waiting on the user: to confirm the charge
     * or refund, or to buy credits first when they're short.
     */
    var pendingCharge by mutableStateOf<PendingCharge?>(null)

    private val api = APIClient.shared

    val isEditing: Boolean get() = trip != null

    val navigationTitle: String
        get() = when {
            isEditing -> "Edit Trip"
            segmentOf != null -> "Add trip extension"
            else -> "New Trip"
        }

    /** Only edits can notify (creating a trip can't), and only when someone else is on it. */
    val canNotifyMembers: Boolean get() = isEditing && memberEmails.isNotEmpty()

    /** Only the owner can delete a trip; organizers can edit it but not delete it. */
    val canDelete: Boolean
        get() {
            val userId = auth.currentUser?.id ?: return false
            return trip != null && trip.ownerId == userId
        }

    val canSave: Boolean get() = title.trim().isNotEmpty() && !isLoadingOwnDates

    /**
     * Replaces the group-wide dates the list gave with the trip's own (see
     * `isLoadingOwnDates`).
     */
    suspend fun loadOwnDates() {
        if (!isLoadingOwnDates || trip == null) return
        try {
            val own = api.send<Trip>(TripsAPI.read(trip.id))
            val start = apiDate(own.startDate)
            val end = apiDate(own.endDate)
            if (start != null && end != null) {
                startDate = start
                endDate = end
            }
            isLoadingOwnDates = false
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Saving stays off rather than risk writing the group's span over the trip.
            errorMessage = "Couldn't load this trip's dates. Close and try again."
        }
    }

    fun delete() {
        if (trip == null) return
        errorMessage = null
        isDeleting = true
        OutboundApp.scope.launch {
            try {
                api.sendIgnoringResponse(TripsAPI.delete(trip.id))
                // The refund, if any, is on the balance now.
                OutboundApp.scope.launch { credits.load() }
                onDeleted()
                dismiss()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                errorMessage = error.localizedDescription
            } finally {
                isDeleting = false
            }
        }
    }

    /**
     * The `area_ids` to send: the final selection, without any chips marked for
     * removal. The server replaces the trip's areas with exactly this list.
     */
    private val areaIdsToSave: List<String>
        get() = areas.map { it.id }.filter { it !in areaIdsToRemove }

    /**
     * The `members` to send, or null to leave the roster alone when nothing changed.
     * The server replaces the roster with this list, so people already on the trip
     * keep their current role; new ones get the default.
     */
    private val membersToSave: List<TripsAPI.MemberRequest>?
        get() {
            if (memberEmails == originalMemberEmails) return null
            val roles = mutableMapOf<String, TripRole>()
            for (member in trip?.members ?: emptyList()) roles.putIfAbsent(member.email, member.role)
            return memberEmails.map { TripsAPI.MemberRequest(email = it, role = roles[it]) }
        }

    /** Marks an area's chip for removal, or takes the mark back. */
    fun toggleRemoval(area: Area) {
        areaIdsToRemove = if (area.id in areaIdsToRemove) areaIdsToRemove - area.id else areaIdsToRemove + area.id
    }

    /** "Traveling Solo" until someone is invited, then a count of the others. */
    val membersSummary: String
        get() = when (val count = memberEmails.size) {
            0 -> "Traveling Solo"
            1 -> "You and 1 other"
            else -> "You and $count others"
        }

    /**
     * The owner's name when an organizer other than the owner is editing, since any
     * refund for the trip goes to the owner. Null for the owner, and for a new trip
     * (whoever creates it owns it).
     */
    val ownerNameIfNotPayer: String?
        get() {
            val ownerId = trip?.ownerId ?: return null
            if (ownerId == auth.currentUser?.id) return null
            val owner = trip.members?.firstOrNull { it.user?.id == ownerId }
            return owner?.user?.displayName ?: owner?.email ?: "the trip's owner"
        }

    fun confirmCharge(charge: PendingCharge) {
        pendingCharge = null
        isSaving = true
        OutboundApp.scope.launch {
            try {
                commit(charge.body, charge.quote)
            } finally {
                isSaving = false
            }
        }
    }

    /**
     * Credits were bought to cover `charge`: quote it again, so the user confirms the
     * charge against the new balance.
     */
    fun quoteAfterPurchase(charge: PendingCharge) {
        pendingCharge = null
        isSaving = true
        OutboundApp.scope.launch {
            try {
                quoteThenSave(charge.body)
            } finally {
                isSaving = false
            }
        }
    }

    /** Sets the start, moving the end along when it would otherwise come before it. */
    fun pickStartDate(date: LocalDate) {
        if (date == startDate) return
        startDate = date
        if (endDate < date) endDate = date
        activeField = null
    }

    fun pickEndDate(date: LocalDate) {
        if (date == endDate) return
        endDate = date
        activeField = null
    }

    fun save() {
        val name = title.trim()
        if (name.isEmpty()) return
        if (endDate < startDate) {
            errorMessage = "End date can't be before the start date."
            return
        }
        errorMessage = null
        isSaving = true
        val trimmedDescription = description.trim()
        val body = TripsAPI.CreateRequest(
            title = name,
            description = trimmedDescription.ifEmpty { null }.orJsonNull(),
            startDate = apiDateFormatter.format(startDate),
            endDate = apiDateFormatter.format(endDate),
            // Seed the trip's days with the device's zone on create; PATCH doesn't take it.
            timezone = if (isEditing) null else ZoneId.systemDefault().id,
            areaIds = areaIdsToSave,
            members = membersToSave,
            notifyMembers = if (canNotifyMembers && notifyMembers) true else null,
            extendsTripId = if (isEditing) null else segmentOf?.id,
        )
        OutboundApp.scope.launch {
            try {
                quoteThenSave(body)
            } finally {
                isSaving = false
            }
        }
    }

    /**
     * Asks the server what the save would cost before making it. Saves that
     * neither cost nor refund go straight through; otherwise the user confirms the
     * charge or refund, or buys credits first when they're short (see `ChargePopup`).
     */
    private suspend fun quoteThenSave(body: TripsAPI.CreateRequest, retrying: Boolean = false) {
        try {
            val endpoint = if (trip != null) TripsAPI.quote(trip.id, body) else TripsAPI.quote(body)
            val quote = api.send<TripQuote>(endpoint)
            if (quote.priceCents == 0 && quote.refundCents == 0) {
                commit(body, quote, retrying = retrying)
            } else {
                pendingCharge = PendingCharge(body = body, quote = quote)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            errorMessage = error.localizedDescription
        }
    }

    /**
     * Saves the trip at the price `quote` showed. If the price or the user's credits
     * changed since (409, 402), quotes again once to show the new numbers.
     */
    private suspend fun commit(body: TripsAPI.CreateRequest, quote: TripQuote, retrying: Boolean = false) {
        val priced = body.copy(expectedPriceCents = quote.priceCents)
        try {
            val endpoint = if (trip != null) TripsAPI.update(trip.id, priced) else TripsAPI.create(priced)
            val saved = api.send<Trip>(endpoint)
            if (quote.priceCents > 0 || quote.refundCents > 0) {
                // What was charged or refunded is on the balance now.
                OutboundApp.scope.launch { credits.load() }
            }
            onSaved(saved)
            dismiss()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (error is APIError.Server && !retrying && (error.status == 402 || error.status == 409)) {
                quoteThenSave(body.copy(expectedPriceCents = null), retrying = true)
            } else {
                errorMessage = error.localizedDescription
            }
        }
    }

    private companion object {
        /** The day just before `trip` starts (`Leading`) or just after it ends (`Trailing`). */
        fun adjacentDate(trip: Trip, edge: HorizontalEdge): LocalDate? {
            val isLeading = edge == HorizontalEdge.Leading
            val date = apiDate(if (isLeading) trip.startDate else trip.endDate) ?: return null
            return date.plusDays(if (isLeading) -1 else 1)
        }

        /** Formats a picked date as the API's "yyyy-MM-dd" calendar date, in the user's timezone. */
        val apiDateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT)

        /** One of the API's "yyyy-MM-dd" calendar dates, or null when it isn't one. */
        fun apiDate(string: String): LocalDate? =
            try {
                LocalDate.parse(string, apiDateFormatter)
            } catch (_: DateTimeException) {
                null
            }
    }
}

/**
 * Modal for manually creating or editing a trip. Collects a name, start/end dates,
 * and an optional description. Timezone and locations come later, derived from trip
 * items. Pass an existing `trip` to edit it; omit it to create a new one. Pass
 * `segmentOf` instead to create a segment: a trip of the user's own, grouped with
 * that one and just before or after it.
 *
 * `segmentEdge` is the side of `segmentOf` the new trip goes on: `Leading` for
 * days before it, `Trailing` for days after. Its dates start on the adjacent day.
 *
 * Fills the sheet it's shown in; `onDismiss` closes that sheet, and is called by the
 * close button and after a successful save or delete.
 */
@Composable
fun TripFormView(
    /** The trip being edited, or null when creating a new one. */
    trip: Trip? = null,
    /** The trip a new segment is grouped with, or null for an ordinary trip. */
    segmentOf: Trip? = null,
    segmentEdge: HorizontalEdge = HorizontalEdge.Trailing,
    /**
     * Which part of a group the edited trip is, shown at the top of the form; null
     * shows nothing (see `TripGroupPart.forEditing`).
     */
    groupPart: TripGroupPart? = null,
    /** Called with the created or updated trip after a successful save. */
    onSaved: (Trip) -> Unit = {},
    /** Called after the trip being edited is deleted. */
    onDeleted: () -> Unit = {},
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val auth = LocalAuthStore.current
    val credits = LocalCreditStore.current
    val form = remember(trip?.id, segmentOf?.id, segmentEdge) {
        TripFormState(trip, segmentOf, segmentEdge, auth, credits)
    }
    SideEffect {
        form.onSaved = onSaved
        form.onDeleted = onDeleted
        form.dismiss = onDismiss
    }
    DisposableEffect(form) {
        onDispose { form.dismiss = {} }
    }

    LaunchedEffect(form) { form.loadOwnDates() }

    ForegroundStyle(Color.appForeground) {
        Column(
            modifier
                .fillMaxSize()
                .background(Color.appBackground)
                // Clear of the navigation bar, and of the keyboard while it's up (the sheet
                // already ends above it); the bottom bar fills the navigation bar's strip
                // itself. So the bar rides up over the keyboard with the form scrolling in
                // the room left, keeping Save in reach while typing, as the item forms do.
                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime).only(WindowInsetsSides.Bottom))
        ) {
            // The bar is dark whatever the theme, so its title and button draw in white.
            ForegroundStyle(Color.White) {
                SheetBar(title = form.navigationTitle, trailing = { SheetBarCloseButton(onClose = onDismiss) })
            }

            Column(
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                if (segmentOf != null) {
                    SegmentHelp()
                }
                if (groupPart != null && form.isEditing) {
                    // The saved name, so the note doesn't change while the name is edited.
                    GroupPartNote(part = groupPart, name = trip?.title ?: form.title)
                }

                AppTextField(
                    text = form.title,
                    onTextChange = { form.title = it },
                    placeholder = "Trip name",
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                )

                AreaChips(form)

                MembersSection(summary = form.membersSummary, onClick = { form.showingMemberInvite = true })

                DatesSection(form)

                AppTextField(
                    text = form.description,
                    onTextChange = { form.description = it },
                    placeholder = "Description (optional)",
                    singleLine = false,
                    minLines = 3,
                    maxLines = 6,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                )

                form.errorMessage?.let { message ->
                    Text(message, style = AppFont.footnote, color = Color.systemRed, modifier = Modifier.fillMaxWidth())
                }
            }

            FormBottomBar(
                notifyLabel = if (form.canNotifyMembers) "Notify members of changes" else null,
                notifyMembers = form.notifyMembers,
                onNotifyMembersChange = { form.notifyMembers = it },
                saveTitle = if (form.isEditing) "Save" else if (segmentOf != null) "Add Segment" else "Add Trip",
                canSave = form.canSave,
                isSaving = form.isSaving,
                onSave = form::save,
                delete = if (form.canDelete) {
                    FormBottomBarDelete(
                        title = "Delete Trip",
                        confirmation = "Delete this trip?",
                        message = "Everything on it is deleted for everyone on the trip. This can't be undone.",
                        action = form::delete,
                        onRequest = { form.confirmingDelete = true },
                    )
                } else {
                    null
                },
                isDeleting = form.isDeleting,
                homeIndicatorInset = LocalSheetBottomInset.current,
                background = Color.appBackground,
            )
        }
    }

    AppSheet(
        isPresented = form.showingAreaPicker,
        onDismiss = { form.showingAreaPicker = false },
        background = Color.appBackground,
    ) {
        AreaPickerSheet(
            excludedIds = form.areas.map { it.id }.toSet(),
            onPick = { form.areas += it },
            onDismiss = { form.showingAreaPicker = false },
        )
    }

    // The open invite popup over a dimmed form; tapping outside closes it.
    DimmedPopup(isPresented = form.showingMemberInvite, onClose = { form.showingMemberInvite = false }) {
        MemberInvitePopup(
            emails = form.memberEmails,
            onEmailsChange = { form.memberEmails = it },
            onClose = { form.showingMemberInvite = false },
        )
    }

    ChargePopup(form)

    // Asks before deleting, with the credits it refunds, over the dimmed form.
    DimmedPopup(isPresented = form.confirmingDelete && trip != null, onClose = { form.confirmingDelete = false }) {
        if (trip != null) {
            DeleteTripPopup(
                tripId = trip.id,
                onDelete = {
                    form.confirmingDelete = false
                    form.delete()
                },
                onClose = { form.confirmingDelete = false },
            )
        }
    }
}

/** Explains what saving a segment does, above the form. */
@Composable
private fun SegmentHelp() {
    FormNote(icon = Icons.Outlined.Info) {
        Text(
            "Adding a trip extension creates a new segment with it's own members that you manage. Members of the original trip get limited visibility into its itinerary.",
            style = AppFont.subheadline,
        )
    }
}

/** Says which trip in the group is being edited. */
@Composable
private fun GroupPartNote(part: TripGroupPart, name: String) {
    FormNote(icon = if (part == TripGroupPart.Primary) Icons.Rounded.Luggage else Icons.AutoMirrored.Rounded.AltRoute) {
        Text(
            buildAnnotatedString {
                append(if (part == TripGroupPart.Primary) "You're editing the primary trip, " else "You're editing the trip extension ")
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(name) }
                append(".")
            },
            style = AppFont.subheadline,
        )
    }
}

/** A note above the form's fields: an icon beside its text, on a faint rounded fill. */
@Composable
private fun FormNote(icon: ImageVector, text: @Composable () -> Unit) {
    ForegroundStyle(Color.appForeground.copy(alpha = 0.85f)) {
        Row(
            modifier = Modifier.fillMaxWidth().formRow(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(scaledSymbolSize(AppFont.subheadline)))
            text()
        }
    }
}

/** A chip per area, then a dashed chip for adding another. */
@Composable
private fun AreaChips(form: TripFormState) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Areas", style = AppFont.subheadline.semibold)
        FlowLayout(spacing = 8.dp) {
            for (area in form.areas) {
                key(area.id) {
                    AreaChip(
                        area = area,
                        isMarked = area.id in form.areaIdsToRemove,
                        onToggle = { form.toggleRemoval(area) },
                    )
                }
            }
            Row(
                modifier = Modifier
                    .plainClickable { form.showingAreaPicker = true }
                    .dashedOutline(Color.appForeground.copy(alpha = 0.7f), cornerRadius = 8.dp, dash = 5.dp)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Rounded.Add,
                    contentDescription = null,
                    modifier = Modifier.size(scaledSymbolSize(AppFont.subheadline)),
                )
                Text("Add area", style = AppFont.subheadline.medium)
            }
        }
    }
}

/** An area's chip, with a button that toggles marking it for removal. */
@Composable
private fun AreaChip(area: Area, isMarked: Boolean, onToggle: () -> Unit) {
    val color = Color.Black.copy(alpha = 0.85f)
    Row(
        modifier = Modifier
            .alpha(if (isMarked) 0.8f else 1f)
            .background(Color.White.copy(alpha = 0.9f), RoundedCornerShape(8.dp))
            .padding(start = 12.dp, end = 8.dp)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(area.name, style = AppFont.subheadline.medium, color = color)
        Icon(
            if (isMarked) Icons.Rounded.RemoveCircle else Icons.Rounded.Close,
            contentDescription = if (isMarked) "Keep ${area.name}" else "Remove ${area.name}",
            tint = color,
            // Same footprint for both icons so the chip doesn't change width.
            modifier = Modifier.plainClickable(onClick = onToggle).size(scaledSymbolSize(AppFont.footnote, scale = 1.25f)),
        )
    }
}

/** A one-line row summarizing who's on the trip; tapping it opens the invite popup. */
@Composable
private fun MembersSection(summary: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().plainClickable(onClick = onClick).formRow(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Members")
        Spacer(Modifier.weight(1f))
        Text(summary, color = Color.appForeground.copy(alpha = 0.75f), maxLines = 1)
    }
}

/** The start and end dates, each a row that opens a calendar under itself. */
@Composable
private fun DatesSection(form: TripFormState) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Column(Modifier.fillMaxWidth().formRow()) {
            DateRow(title = "Start", date = form.startDate, field = DateField.Start, form = form)
            InlineCalendar(isShowing = form.activeField == DateField.Start) {
                DateCalendar(date = form.startDate, onDateChange = form::pickStartDate)
            }
            Box(
                Modifier
                    .padding(vertical = 8.dp)
                    .fillMaxWidth()
                    .height(0.5.dp)
                    .background(Color.appForeground.copy(alpha = 0.2f))
            )
            DateRow(title = "End", date = form.endDate, field = DateField.End, form = form)
            InlineCalendar(isShowing = form.activeField == DateField.End) {
                // The end can't come before the start.
                DateCalendar(date = form.endDate, earliest = form.startDate, onDateChange = form::pickEndDate)
            }
        }

        Text(
            "These should be based on your arrival and departure times at the expected location.",
            style = AppFont.caption,
            color = Color.appForeground.copy(alpha = 0.7f),
        )
    }
}

/** A tappable row showing a date; tapping toggles the inline calendar below it. */
@Composable
private fun DateRow(title: String, date: LocalDate, field: DateField, form: TripFormState) {
    val isActive = form.activeField == field
    Row(
        modifier = Modifier.fillMaxWidth().plainClickable { form.activeField = if (isActive) null else field },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title)
        Spacer(Modifier.weight(1f))
        Text(longDateFormatter.format(date), color = if (isActive) Color.appAccent else Color.appForeground)
    }
}

/**
 * Opens and closes a date row's calendar, easing the rows below out of its way. The
 * gap above it opens and closes with it.
 */
@Composable
private fun InlineCalendar(isShowing: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = isShowing,
        enter = expandVertically(AppAnimation.default()) + fadeIn(AppAnimation.default()),
        exit = shrinkVertically(AppAnimation.default()) + fadeOut(AppAnimation.default()),
    ) {
        Box(Modifier.padding(top = 8.dp)) { content() }
    }
}

/**
 * A month of days to pick a date from, in the style of the date popup's calendar:
 * the month over its weeks, with arrows to move between months. Days before
 * `earliest` can't be picked.
 */
@Composable
private fun DateCalendar(date: LocalDate, earliest: LocalDate? = null, onDateChange: (LocalDate) -> Unit) {
    val calendar = remember { Calendar.sundayFirst }
    var month by remember { mutableStateOf(YearMonth.from(date)) }
    val earliestMonth = earliest?.let(YearMonth::from)

    Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                monthYearFormatter.format(month),
                style = AppFont.subheadline.semibold,
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp).semantics { heading() },
            )
            MonthButton(
                icon = Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
                label = "Previous month",
                enabled = earliestMonth == null || month > earliestMonth,
                onClick = { month = month.minusMonths(1) },
            )
            MonthButton(
                icon = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                label = "Next month",
                onClick = { month = month.plusMonths(1) },
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(DateTimePopupMetrics.cellSpacing)) {
            for (offset in 0L..6L) {
                Text(
                    calendar.firstWeekday.plus(offset)
                        .getDisplayName(java.time.format.TextStyle.SHORT, Locale.getDefault())
                        .uppercase(),
                    style = AppFont.caption2.semibold,
                    color = Color.appForeground.copy(alpha = 0.5f),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        AnimatedContent(
            targetState = month,
            modifier = Modifier.clipToBounds(),
            transitionSpec = {
                // Later months come in from the trailing side, earlier ones from the leading.
                val direction = if (targetState > initialState) 1 else -1
                val slide = AppAnimation.snappy<IntOffset>(0.3)
                (slideInHorizontally(slide) { it * direction } + fadeIn(AppAnimation.snappy(0.3)))
                    .togetherWith(slideOutHorizontally(slide) { -it * direction } + fadeOut(AppAnimation.snappy(0.3)))
            },
            label = "month",
        ) { shown ->
            val weeks = remember(shown, earliest) {
                TripCalendarGrid(
                    start = calendar.date(shown.atDay(1)),
                    end = calendar.date(shown.atEndOfMonth()),
                    calendar = calendar,
                ).weeks.map { week ->
                    // Days before the earliest only pad the month, like its neighbors' days.
                    week.map { day -> if (earliest != null && calendar.day(day.date) < earliest) day.copy(isInTrip = false) else day }
                }
            }
            CalendarDayGrid(weeks = weeks) { day ->
                CalendarDayCell(
                    day = day,
                    isSelected = day.isInTrip && calendar.day(day.date) == date,
                    onSelect = { onDateChange(calendar.day(day.date)) },
                )
            }
        }
    }
}

/** An arrow that moves the calendar a month back or on. */
@Composable
private fun MonthButton(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .dimmed(!enabled, opacity = 0.3f)
            .plainClickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(scaledSymbolSize(AppFont.title3)))
    }
}

/**
 * The charge or refund confirmation, or the purchase popup when the user's credits
 * fall short, over the dimmed form.
 */
@Composable
private fun ChargePopup(form: TripFormState) {
    val closeCharge = { form.pendingCharge = null }
    val charge = lastPresented(form.pendingCharge)
    DimmedPopup(isPresented = form.pendingCharge != null, onClose = closeCharge) {
        when {
            charge == null -> {}
            charge.needsCredits -> PurchasePopup(
                onClose = closeCharge,
                shortfall = charge.quote,
                refundsGoTo = form.ownerNameIfNotPayer,
                onPurchased = { form.quoteAfterPurchase(charge) },
            )
            charge.isRefund -> RefundConfirmationPopup(
                tripTitle = charge.body.title,
                quote = charge.quote,
                refundsGoTo = form.ownerNameIfNotPayer,
                onConfirm = { form.confirmCharge(charge) },
                onClose = closeCharge,
            )
            else -> ChargeConfirmationPopup(
                tripTitle = charge.body.title,
                quote = charge.quote,
                refundsGoTo = form.ownerNameIfNotPayer,
                onConfirm = { form.confirmCharge(charge) },
                onClose = closeCharge,
            )
        }
    }
}

/** A date in full, e.g. "November 10, 2026". */
private val longDateFormatter: DateTimeFormatter
    get() = DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(Locale.getDefault())

/** A month with its year, e.g. "November 2026". */
private val monthYearFormatter: DateTimeFormatter
    get() = DateTimeFormatter.ofPattern("LLLL yyyy", Locale.getDefault())

/** A trip save that costs or refunds credits, with the quote shown for it. */
private data class PendingCharge(
    val body: TripsAPI.CreateRequest,
    val quote: TripQuote,
) {
    /** Whether the user has to buy credits before they can save. */
    val needsCredits: Boolean get() = quote.shortfallCents > 0

    /** Whether the save gives credits back instead of costing any, e.g. shorter dates. */
    val isRefund: Boolean get() = quote.priceCents == 0 && quote.refundCents > 0
}

/**
 * Which part of a group a trip is: the one the group grew from, or an extension
 * added before or after it.
 */
enum class TripGroupPart {
    Primary,
    Extension;

    companion object {
        /**
         * What to tell the user editing `edited`, in a group led by `primary` with
         * `extensions`: nothing unless they organize one of the extensions, since only then
         * is it unclear which trip the form is for. So an organizer of the primary trip
         * who hasn't added on to it sees nothing.
         */
        fun forEditing(edited: Trip, primary: Trip, extensions: List<Trip>, userId: String?): TripGroupPart? {
            if (userId == null || extensions.isEmpty()) return null
            val organizesAnExtension = extensions.any { trip ->
                trip.members?.any { it.user?.id == userId && it.role == TripRole.Organizer } == true
            }
            if (!organizesAnExtension) return null
            return if (edited.id == primary.id) TripGroupPart.Primary else TripGroupPart.Extension
        }
    }
}
