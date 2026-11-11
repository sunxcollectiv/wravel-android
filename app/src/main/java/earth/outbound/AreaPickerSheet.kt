//
//  AreaPickerSheet.kt
//  outbound
//

package earth.outbound

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import earth.outbound.networking.APIClient
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Looks up areas matching a typed query. */
interface AreaSearching {
    suspend fun search(query: String): List<Area>
}

/** Searches areas through `GET /areas`. */
class APIAreaSearch(val api: APIClient = APIClient.shared) : AreaSearching {
    override suspend fun search(query: String): List<Area> = api.send(AreasAPI.search(query))
}

/**
 * Modal for adding an area: a search field with results beneath it. Picking a
 * result reports it through `onPick` and closes the sheet.
 *
 * Fills the sheet it's shown in; `onDismiss` closes that sheet.
 */
@Composable
fun AreaPickerSheet(
    /** Ids of areas already added, left out of the results. */
    excludedIds: Set<String> = emptySet(),
    search: AreaSearching = remember { APIAreaSearch() },
    onPick: (Area) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf(emptyList<Area>()) }
    var isSearching by remember { mutableStateOf(false) }
    /**
     * Whether a search has finished for the current text, so "no results" only shows
     * after one has actually run.
     */
    var hasSearched by remember { mutableStateOf(false) }
    var searchTask by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    /** Debounces input changes and runs a search. */
    fun onTextChanged(newValue: String) {
        searchTask?.cancel()
        hasSearched = false
        val query = newValue.trim()
        if (query.isEmpty()) {
            results = emptyList()
            isSearching = false
            return
        }

        isSearching = true
        searchTask = scope.launch {
            delay(250.milliseconds)
            val found = try {
                search.search(query)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                emptyList()
            }
            results = found
            isSearching = false
            hasSearched = true
        }
    }

    fun setText(newValue: String) {
        if (newValue == text) return
        text = newValue
        onTextChanged(newValue)
    }

    // Dragging the results puts the keyboard away.
    val dismissesKeyboard = remember(focusManager) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput) focusManager.clearFocus()
                return Offset.Zero
            }
        }
    }

    ForegroundStyle(Color.appForeground) {
        Column(
            modifier
                .fillMaxSize()
                .background(Color.appBackground)
                // Part of the trip form, so the offline pill keeps the form's wording.
                .offlinePillAtBottom(isForm = true)
        ) {
            // The bar is dark whatever the theme, so its title and button draw in white.
            ForegroundStyle(Color.White) {
                SheetBar(title = "Add Area", leading = { SheetBarButton(title = "Cancel", onClick = onDismiss) })
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .imePadding()
                    .nestedScroll(dismissesKeyboard)
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp)
                    .padding(bottom = LocalSheetBottomInset.current),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SearchField(text = text, onTextChange = ::setText, focusRequester = focusRequester)
                ResultsList(
                    visible = results.filter { it.id !in excludedIds },
                    isSearching = isSearching,
                    hasSearched = hasSearched,
                    onPick = { area ->
                        onPick(area)
                        onDismiss()
                    },
                )
            }
        }
    }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }
}

@Composable
private fun SearchField(text: String, onTextChange: (String) -> Unit, focusRequester: FocusRequester) {
    Row(
        modifier = Modifier.fillMaxWidth().appField(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val iconSize = scaledSymbolSize(AppFont.body, scale = 1.2f)
        Icon(
            Icons.Rounded.Search,
            contentDescription = null,
            tint = Color.Black.copy(alpha = 0.45f),
            modifier = Modifier.size(iconSize),
        )
        PlainTextField(
            text = text,
            onTextChange = onTextChange,
            placeholder = "Search cities and countries",
            modifier = Modifier.weight(1f).focusRequester(focusRequester),
            color = appFieldTextColor,
            placeholderColor = Color.Black.copy(alpha = 0.3f),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Words,
                autoCorrectEnabled = false,
            ),
        )
        if (text.isNotEmpty()) {
            Icon(
                Icons.Rounded.Cancel,
                contentDescription = "Clear",
                tint = Color.Black.copy(alpha = 0.35f),
                modifier = Modifier.plainClickable { onTextChange("") }.size(iconSize),
            )
        }
    }
}

@Composable
private fun ResultsList(visible: List<Area>, isSearching: Boolean, hasSearched: Boolean, onPick: (Area) -> Unit) {
    val faded = Color.appForeground.copy(alpha = 0.7f)
    if (isSearching && visible.isEmpty()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProgressView(tint = faded)
            Text("Searching areas…", style = AppFont.footnote, color = faded)
            Spacer(Modifier.weight(1f))
        }
    } else if (hasSearched && visible.isEmpty()) {
        Text(
            "No matching areas",
            style = AppFont.footnote,
            color = faded,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        )
    } else if (visible.isNotEmpty()) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(Color.appForeground.copy(alpha = 0.08f), RoundedCornerShape(appFieldCornerRadius))
        ) {
            visible.forEachIndexed { index, area ->
                key(area.id) {
                    Column(
                        modifier = Modifier.fillMaxWidth().plainClickable { onPick(area) }.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(area.name, style = AppFont.subheadline.medium)
                        area.subtitle?.let { subtitle ->
                            Text(subtitle, style = AppFont.caption, color = faded)
                        }
                    }

                    if (index < visible.size - 1) {
                        Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color.appForeground.copy(alpha = 0.15f)))
                    }
                }
            }
        }
    }
}
