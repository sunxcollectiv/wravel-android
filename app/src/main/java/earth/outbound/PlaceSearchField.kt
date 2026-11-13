//
//  PlaceSearchField.kt
//  outbound
//

package earth.outbound

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.PinDrop
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import earth.outbound.networking.APIClient
import earth.outbound.networking.GooglePlacesClient
import earth.outbound.networking.PlaceSuggestion
import earth.outbound.networking.localizedDescription
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.ZoneId
import kotlin.math.acos
import kotlin.time.Duration.Companion.milliseconds

/** How a `PlaceSearchField`'s floating suggestions are laid out. */
data class PlaceSearchFieldFloatingResults(
    /**
     * The list's width; nil for the field's own. Wider lets a narrow field (say,
     * half a row) open a list as wide as its row.
     */
    val width: Dp? = null,
    /** The field edge a wider list lines up with. */
    val edge: Edge = Edge.Leading,
) {
    enum class Edge { Leading, Trailing }
}

/** Where a `PlaceSearchField` looks for places. */
enum class PlaceSearchSource {
    /** A broad Google Places search, registering the pick in our DB. */
    Google,

    /** Only the airports in our own DB, by IATA code or name. */
    Airports,
}

/**
 * A text field that doubles as a place search. Typing shows suggestions; picking one
 * fills the field and reports the internal `Place` back through
 * `onResolvedPlaceChange` (its `id` is what a trip item references as `place_id`).
 *
 * By default it searches Google Places, and a pick registers the place in our DB via
 * `POST /places` (whether or not the surrounding form is ultimately submitted). With
 * the `Airports` source it searches only the airports already in our DB, so a pick
 * is the place itself and needs no registering.
 */
@Composable
fun PlaceSearchField(
    placeholder: String,
    text: String,
    onTextChange: (String) -> Unit,
    /**
     * The internal place created from the current selection, or `null` when none is
     * selected (e.g. the user typed free text instead of picking a suggestion).
     */
    resolvedPlace: Place?,
    onResolvedPlaceChange: (Place?) -> Unit,
    /**
     * Told the selected place's IANA time zone, when callers want it (optional so
     * other uses of this field don't have to take it). Preferred from Google, with
     * the backend place's zone as a fallback; nil whenever the selection clears.
     */
    onResolvedTimeZoneChange: ((ZoneId?) -> Unit)? = null,
    /**
     * With `onTypedEntryChange` supplied, the suggestions open with a row for taking
     * the text exactly as typed, with no place behind it; choosing it sets this to
     * that text. Cleared, like `resolvedPlace`, as soon as the user types again.
     */
    typedEntry: String? = null,
    onTypedEntryChange: ((String?) -> Unit)? = null,
    /**
     * When set, the suggestions float over the content below while the field is
     * focused, instead of pushing it down.
     */
    floatingResults: PlaceSearchFieldFloatingResults? = null,
    /** Where suggestions come from. */
    source: PlaceSearchSource = PlaceSearchSource.Google,
    modifier: Modifier = Modifier,
) {
    var suggestions by remember { mutableStateOf(listOf<Suggestion>()) }
    var isSearching by remember { mutableStateOf(false) }
    var searchTask by remember { mutableStateOf<Job?>(null) }
    var selected by remember { mutableStateOf<Suggestion?>(null) }
    /**
     * The last search that came back, to tell "no matches" from "not searched yet"
     * (or a search that failed, which shows its error instead).
     */
    var searchedQuery by remember { mutableStateOf<String?>(null) }
    var isResolving by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    /** Whether the "use as typed" row is up: from typing until a row is chosen. */
    var offeringTypedEntry by remember { mutableStateOf(false) }
    /**
     * Whether the user is editing the field. Only typing in it searches and clears
     * the current selection: a change to `text` from outside (e.g. prefilling an
     * edit form) arrives as a new `text`, not through the field, and does neither.
     */
    var isFocused by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val places = GooglePlacesClient.shared
    val api = APIClient.shared

    // Read when a search or a save finishes, by which time the caller may have new ones.
    val currentOnResolvedPlaceChange by rememberUpdatedState(onResolvedPlaceChange)
    val currentOnResolvedTimeZoneChange by rememberUpdatedState(onResolvedTimeZoneChange)

    // Putting the keyboard away ends the editing, as it does on iOS, which closes
    // floating suggestions.
    val density = LocalDensity.current
    val keyboardInsets = WindowInsets.ime
    val isKeyboardUp by remember(keyboardInsets, density) { derivedStateOf { keyboardInsets.getBottom(density) > 0 } }
    LaunchedEffect(isKeyboardUp) {
        if (!isKeyboardUp && isFocused) focusManager.clearFocus()
    }

    val trimmedText = text.trim()
    val showsTypedEntryRow = onTypedEntryChange != null && offeringTypedEntry && trimmedText.isNotEmpty()
    // Our airport list is limited, so an airport search that finds nothing says so,
    // rather than leaving the field looking unresponsive.
    val showsNoMatches = source == PlaceSearchSource.Airports && isFocused && !isSearching &&
        suggestions.isEmpty() && searchedQuery == trimmedText

    fun clearSuggestions() {
        searchTask?.cancel()
        suggestions = emptyList()
        searchedQuery = null
        isSearching = false
    }

    // A different source makes the open suggestions stale.
    LaunchedEffect(source) { clearSuggestions() }

    /** Runs the query against the field's source. */
    suspend fun search(query: String): List<Suggestion> = when (source) {
        PlaceSearchSource.Google -> places.autocomplete(query).map(::Suggestion)
        PlaceSearchSource.Airports -> api.send<List<Place>>(PlacesAPI.searchAirports(query)).map(::Suggestion)
    }

    fun unfocus() {
        if (isFocused) focusManager.clearFocus()
    }

    /** Takes the typed text, then debounces and runs a place search on it. */
    fun onTextChanged(newValue: String) {
        onTextChange(newValue)

        // Typing invalidates any previously selected/resolved place.
        selected = null
        onResolvedPlaceChange(null)
        onResolvedTimeZoneChange?.invoke(null)
        onTypedEntryChange?.invoke(null)
        offeringTypedEntry = true
        errorText = null

        // Earlier suggestions stay up until the new search answers.
        searchTask?.cancel()
        searchedQuery = null
        val query = newValue.trim()
        if (query.codePointCount(0, query.length) < 2) {
            clearSuggestions()
            return
        }

        isSearching = true
        searchTask = scope.launch {
            // Debounce so we don't fire a request on every keystroke.
            delay(300.milliseconds)
            try {
                suggestions = search(query)
                searchedQuery = query
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                suggestions = emptyList()
                // Said rather than swallowed, so a failing search (say, no Google key
                // in Secrets.json) doesn't look like one that found nothing.
                errorText = error.localizedDescription
            }
            isSearching = false
        }
    }

    /**
     * Sends a picked Google place to `POST /places` so the backend registers it in our
     * places DB, and keeps the returned internal place.
     */
    fun register(googlePlace: PlaceSuggestion, suggestion: Suggestion) {
        isResolving = true
        scope.launch {
            // Whether the user changed the selection while this was in flight.
            fun isStale() = selected?.id != suggestion.id
            try {
                val place = api.send<Place>(PlacesAPI.create(googlePlaceId = googlePlace.placeId))
                if (isStale()) return@launch
                currentOnResolvedPlaceChange(place)

                // Pull the zone straight from Google (reliable immediately), falling
                // back to the backend place's zone if Google doesn't report one.
                if (currentOnResolvedTimeZoneChange != null) {
                    val googleZone = try {
                        places.placeTimeZone(placeId = googlePlace.placeId)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        null
                    }
                    if (isStale()) return@launch
                    currentOnResolvedTimeZoneChange?.invoke(googleZone ?: place.timeZone)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (!isStale()) errorText = error.localizedDescription
            } finally {
                isResolving = false
            }
        }
    }

    /**
     * Keeps the internal place behind the chosen suggestion: one from our own DB as is,
     * or a Google one once it's registered.
     */
    fun resolve(suggestion: Suggestion) {
        onResolvedPlaceChange(null)
        onResolvedTimeZoneChange?.invoke(null)
        errorText = null

        when (val origin = suggestion.origin) {
            // Already in our DB, so there's nothing to register.
            is Suggestion.Origin.Internal -> {
                onResolvedPlaceChange(origin.place)
                onResolvedTimeZoneChange?.invoke(origin.place.timeZone)
            }
            is Suggestion.Origin.Google -> register(origin.prediction, suggestion)
        }
    }

    /**
     * Fills the text from a chosen suggestion, dismisses the list, and resolves its
     * internal place.
     */
    fun select(suggestion: Suggestion) {
        searchTask?.cancel()
        onTextChange(suggestion.mainText)
        selected = suggestion
        offeringTypedEntry = false
        suggestions = emptyList()
        isSearching = false
        // A narrow floating field would stay scrolled to the name's end while focused.
        if (floatingResults != null) unfocus()
        resolve(suggestion)
    }

    /**
     * Takes the text as typed instead of a suggestion, e.g. for a place Google
     * doesn't know or a name that's no place at all.
     */
    fun useTypedEntry() {
        searchTask?.cancel()
        if (text != trimmedText) onTextChange(trimmedText)
        onTypedEntryChange?.invoke(trimmedText)
        offeringTypedEntry = false
        suggestions = emptyList()
        isSearching = false
        unfocus()
    }

    val results: @Composable () -> Unit = {
        Results(
            suggestions = suggestions,
            isSearching = isSearching,
            searchingText = if (source == PlaceSearchSource.Airports) "Searching airports…" else "Searching places…",
            noMatchesText = "No airports match \"$trimmedText\"".takeIf { showsNoMatches },
            typedEntry = trimmedText.takeIf { showsTypedEntryRow },
            isFloating = floatingResults != null,
            onSelect = ::select,
            onUseTypedEntry = ::useTypedEntry,
        )
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FieldWithFloatingResults(
            width = floatingResults?.width,
            edge = floatingResults?.edge ?: PlaceSearchFieldFloatingResults.Edge.Leading,
            field = {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        // The icon is part of the field: tapping it starts typing too.
                        .tapGesture {
                            focusRequester.requestFocus()
                            keyboard?.show()
                        }
                        .appField(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PlainTextField(
                        text = text,
                        onTextChange = ::onTextChanged,
                        placeholder = placeholder,
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(focusRequester)
                            .onFocusChanged { isFocused = it.isFocused },
                        color = appFieldTextColor,
                        placeholderColor = Color.Black.copy(alpha = 0.3f),
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Words,
                            autoCorrectEnabled = false,
                        ),
                    )
                    Box(
                        Modifier
                            .size(width = 14.dp, height = 19.dp)
                            .background(Color.Black.copy(alpha = 0.4f), LocationPin)
                            .clearAndSetSemantics {}
                    )
                }
            },
            // Hung below the field, so it covers what's below.
            results = if (floatingResults != null && isFocused) results else null,
        )

        if (floatingResults == null) {
            results()
        }
        Status(
            typedEntry = typedEntry,
            isResolving = isResolving,
            resolvedPlace = resolvedPlace,
            selectedDetail = selected?.secondaryText,
            errorText = errorText,
            // One line beside a neighboring field, so the two stay level.
            maxLines = if (floatingResults == null) Int.MAX_VALUE else 1,
        )
    }
}

/** One row in the suggestions: a Google prediction, or a place from our own DB. */
private data class Suggestion(
    val id: String,
    val mainText: String,
    val secondaryText: String?,
    val origin: Origin,
) {
    sealed interface Origin {
        data class Google(val prediction: PlaceSuggestion) : Origin
        data class Internal(val place: Place) : Origin
    }

    constructor(google: PlaceSuggestion) : this(
        id = "google:${google.placeId}",
        mainText = google.mainText,
        secondaryText = google.secondaryText,
        origin = Origin.Google(google),
    )

    /**
     * An airport reads as its name over its code and where it is, e.g.
     * "Los Angeles International Airport" over "LAX · Los Angeles, United States".
     */
    constructor(place: Place) : this(
        id = "place:${place.id}",
        mainText = place.name ?: place.code ?: "Unnamed place",
        secondaryText = listOfNotNull(place.code, place.address).filter { it.isNotEmpty() }
            .joinToString(" · ").ifEmpty { null },
        origin = Origin.Internal(place),
    )
}

/** The gap between the field and suggestions floating below it. */
private val floatingResultsGap = 8.dp

/**
 * The field, with `results` (when there are any to float) hung just below it at
 * their own height: they take no room, so they cover what's under the field. They're
 * `width` wide, or the field's own width without one, lined up with the field's `edge`.
 */
@Composable
private fun FieldWithFloatingResults(
    width: Dp?,
    edge: PlaceSearchFieldFloatingResults.Edge,
    field: @Composable () -> Unit,
    results: (@Composable () -> Unit)?,
) {
    Layout(contents = listOf(field, results ?: {})) { (fieldMeasurables, resultsMeasurables), constraints ->
        val fieldPlaceables = fieldMeasurables.map { it.measure(constraints) }
        val fieldWidth = fieldPlaceables.maxOfOrNull { it.width } ?: 0
        val fieldHeight = fieldPlaceables.maxOfOrNull { it.height } ?: 0
        val resultsWidth = width?.roundToPx() ?: fieldWidth
        val resultsPlaceables = resultsMeasurables.map { it.measure(Constraints.fixedWidth(resultsWidth)) }
        layout(fieldWidth, fieldHeight) {
            fieldPlaceables.forEach { it.placeRelative(0, 0) }
            resultsPlaceables.forEach {
                it.placeRelative(
                    x = if (edge == PlaceSearchFieldFloatingResults.Edge.Trailing) fieldWidth - it.width else 0,
                    y = fieldHeight + floatingResultsGap.roundToPx(),
                    zIndex = 1f,
                )
            }
        }
    }
}

/**
 * The autocomplete suggestions list shown beneath the field, opening with the row
 * for taking `typedEntry` as it is when that's offered, and saying `noMatchesText`
 * when a search found nothing. Nothing while there's nothing to show.
 */
@Composable
private fun Results(
    suggestions: List<Suggestion>,
    isSearching: Boolean,
    searchingText: String,
    noMatchesText: String?,
    typedEntry: String?,
    isFloating: Boolean,
    onSelect: (Suggestion) -> Unit,
    onUseTypedEntry: () -> Unit,
) {
    if (!isSearching && suggestions.isEmpty() && typedEntry == null && noMatchesText == null) return
    val shape = RoundedCornerShape(appFieldCornerRadius)
    Column(
        Modifier
            .fillMaxWidth()
            .then(
                if (isFloating) {
                    Modifier
                        .shadow(16.dp, shape, clip = false, ambientColor = Color.Black, spotColor = Color.Black)
                        // Opaque when floating, so the form doesn't show through.
                        .background(Color.sheetBackground, shape)
                        // Taps between its rows stop here, not at the form underneath.
                        .pointerInput(Unit) {}
                } else {
                    Modifier
                }
            )
            // Clipped so a row's own background keeps to the rounded corners.
            .clip(shape)
            .background(Color.appForeground.copy(alpha = 0.08f))
    ) {
        if (typedEntry != null) {
            TypedEntryRow(typedEntry, onClick = onUseTypedEntry)
            if (isSearching || suggestions.isNotEmpty()) {
                ResultsDivider()
            }
        }

        if (isSearching && suggestions.isEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ProgressView()
                Text(
                    searchingText,
                    style = AppFont.footnote,
                    color = Color.appForeground.copy(alpha = 0.7f),
                )
                Spacer(Modifier.weight(1f))
            }
        }

        if (noMatchesText != null) {
            Text(
                noMatchesText,
                style = AppFont.footnote,
                color = Color.appForeground.copy(alpha = 0.7f),
                modifier = Modifier.fillMaxWidth().padding(14.dp),
            )
        }

        suggestions.forEachIndexed { index, suggestion ->
            Column(
                modifier = Modifier.fillMaxWidth().plainClickable { onSelect(suggestion) }.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(suggestion.mainText, style = AppFont.subheadline.medium)
                suggestion.secondaryText?.let { secondary ->
                    Text(secondary, style = AppFont.caption, color = Color.appForeground.copy(alpha = 0.7f))
                }
            }

            if (index < suggestions.size - 1) {
                ResultsDivider()
            }
        }
    }
}

@Composable
private fun ResultsDivider() {
    Hairline(opacity = 0.15f)
}

/** The row for taking the text as typed, with no place behind it. */
@Composable
private fun TypedEntryRow(typedEntry: String, onClick: () -> Unit) {
    // One wrapping line, with only the typed text at full strength.
    Text(
        buildAnnotatedString {
            append("Use ")
            withStyle(SpanStyle(color = Color.appForeground)) { append(typedEntry) }
            append(" without map location")
        },
        style = AppFont.caption.medium,
        color = Color.appForeground.copy(alpha = 0.5f),
        modifier = Modifier
            .fillMaxWidth()
            .plainClickable(onClick = onClick)
            // A shade lighter than the suggestions, to set it apart from the places.
            .background(Color.appForeground.copy(alpha = 0.06f))
            .padding(14.dp),
    )
}

/** Small confirmation row reflecting the result of saving the picked place. */
@Composable
private fun Status(
    typedEntry: String?,
    isResolving: Boolean,
    resolvedPlace: Place?,
    /** The picked suggestion's second line, to show until the place has an address. */
    selectedDetail: String?,
    errorText: String?,
    maxLines: Int,
) {
    when {
        typedEntry != null ->
            StatusLabel("No map location", Icons.Rounded.TextFields, Color.appForeground.copy(alpha = 0.8f), maxLines)
        isResolving ->
            StatusLabel("Saving place…", Icons.Rounded.PinDrop, Color.appForeground.copy(alpha = 0.7f), maxLines)
        resolvedPlace != null ->
            StatusLabel(
                resolvedPlace.address ?: selectedDetail ?: "Place saved",
                Icons.Rounded.CheckCircle,
                Color.appForeground.copy(alpha = 0.8f),
                maxLines,
            )
        errorText != null ->
            StatusLabel(errorText, Icons.Rounded.Warning, Color.systemRed, maxLines)
    }
}

@Composable
private fun StatusLabel(text: String, icon: ImageVector, tint: Color, maxLines: Int) {
    SymbolLabel(
        text,
        icon = icon,
        style = AppFont.caption,
        color = tint,
        maxLines = maxLines,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * A map marker: a round head tapering to a point at the bottom, with a hole in the
 * middle (the path fills even-odd, so the hole stays clear).
 */
object LocationPin : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val radius = size.width / 2
        val center = Offset(size.width / 2, radius)
        val tip = Offset(size.width / 2, size.height)
        // Where the straight sides from the tip meet the head, either side of straight down.
        val spread = Math.toDegrees(acos((radius / (tip.y - center.y)).toDouble())).toFloat()

        val path = Path().apply {
            fillType = PathFillType.EvenOdd
            moveTo(tip.x, tip.y)
            // Up one side, over the top of the head, and down the other.
            arcTo(
                rect = Rect(center = center, radius = radius),
                startAngleDegrees = 90f - spread,
                sweepAngleDegrees = -(360f - 2 * spread),
                forceMoveTo = false,
            )
            close()
            addOval(Rect(center = center, radius = radius * 0.4f))
        }
        return Outline.Generic(path)
    }
}
