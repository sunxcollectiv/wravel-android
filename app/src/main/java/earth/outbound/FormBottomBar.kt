//
//  FormBottomBar.kt
//  outbound
//

package earth.outbound

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.CheckBoxOutlineBlank
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.offset
import earth.outbound.networking.Connectivity

/** What deleting is called and confirmed with, and what it does. */
class FormBottomBarDelete(
    /** The button, e.g. "Delete Trip". */
    val title: String,
    /** The confirmation's question, e.g. "Delete this trip?". */
    val confirmation: String,
    val message: String,
    val action: () -> Unit,
    /**
     * Asks in the caller's own popup instead of the system-style confirmation, which
     * `confirmation`, `message` and `action` are then left to.
     */
    val onRequest: (() -> Unit)? = null,
)

/**
 * The bar's measurements. They live here rather than on `FormBottomBar`, which is a
 * composable function and so can't hold them itself.
 */
object FormBottomBarMetrics {
    /** How far Save sits above the screen's bottom edge when it's last. */
    val saveBottomGap: Dp = 50.dp
}

/**
 * The bar pinned under the trip and trip item forms, so it stays in reach however far
 * the form scrolls: an optional notify checkbox, Save, and Delete under it when
 * there's something to delete (after a confirmation, since it can't be undone).
 * Offline, Save and Delete are disabled but the form stays editable, since the
 * connection is often back in a moment; a pill above the bar says why.
 *
 * Put it at the bottom of a form that keeps clear of the navigation bar, and pass
 * that bar's height as `homeIndicatorInset`: the bar fills the strip with its
 * `background` and sits partway into it.
 */
@Composable
fun FormBottomBar(
    /** The notify checkbox's label; null hides the checkbox. */
    notifyLabel: String? = null,
    notifyMembers: Boolean,
    onNotifyMembersChange: (Boolean) -> Unit,
    saveTitle: String,
    /** Whether the form is complete enough to save, e.g. it has a title. */
    canSave: Boolean,
    isSaving: Boolean,
    onSave: () -> Unit,
    /** Null hides Delete, e.g. for something not saved yet. */
    delete: FormBottomBarDelete? = null,
    isDeleting: Boolean,
    /**
     * The sheet's bottom inset (the navigation bar's strip), measured by the form
     * so the bar can sit partway into it.
     */
    homeIndicatorInset: Dp,
    background: Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            // Just above the bar, over the end of the form, and over anything the form
            // opens on top.
            .offlinePillAnchor(spacing = 12.dp, isForm = true)
            // Down through the navigation bar's strip under the bar too.
            .drawBehind {
                drawRect(background, size = Size(size.width, size.height + homeIndicatorInset.toPx()))
            }
            // With Delete last, halfway into the navigation bar's strip: closer to the edge,
            // but no further than the empty space under Delete's words, so they stay clear
            // of a tall bar. With Save last, a flat 50dp above the screen's bottom edge on
            // every device.
            .bottomPadding(
                if (delete != null) {
                    -min(homeIndicatorInset / 2, deleteBottomSpace)
                } else {
                    FormBottomBarMetrics.saveBottomGap - homeIndicatorInset
                }
            )
            .padding(horizontal = 24.dp)
            .padding(top = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (notifyLabel != null) {
            NotifyMembersCheckbox(
                label = notifyLabel,
                notifyMembers = notifyMembers,
                onNotifyMembersChange = onNotifyMembersChange,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        SaveButton(saveTitle = saveTitle, isSaving = isSaving, enabled = !isSaving && !isDeleting && canSave, onSave = onSave)
        if (delete != null) {
            DeleteButton(delete = delete, isDeleting = isDeleting, enabled = !isSaving && !isDeleting)
        }
    }
}

/** The empty space under Delete's line of words, inside its tap area. */
private val deleteBottomSpace = 12.dp

@Composable
private fun NotifyMembersCheckbox(
    label: String,
    notifyMembers: Boolean,
    onNotifyMembersChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val iconSize = scaledSymbolSize(AppFont.title3)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .plainClickable { onNotifyMembersChange(!notifyMembers) }
            .semantics { selected = notifyMembers },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (notifyMembers) Icons.Rounded.CheckBox else Icons.Rounded.CheckBoxOutlineBlank,
            contentDescription = null,
            modifier = Modifier.size(iconSize),
        )
        Text(label, style = AppFont.subheadline)
    }
}

@Composable
private fun SaveButton(saveTitle: String, isSaving: Boolean, enabled: Boolean, onSave: () -> Unit) {
    ProminentFormButton(
        title = saveTitle,
        onClick = onSave,
        // Its prominent style already greys out when disabled, offline included.
        enabled = enabled && Connectivity.shared.isConnected,
        isWorking = isSaving,
    )
}

@Composable
private fun DeleteButton(delete: FormBottomBarDelete, isDeleting: Boolean, enabled: Boolean) {
    /** Showing the confirmation before deleting. */
    var confirmingDelete by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .requiresConnection()
            .inlineDeleteText()
            .plainClickable(enabled = enabled) {
                val onRequest = delete.onRequest
                if (onRequest != null) {
                    onRequest()
                } else {
                    confirmingDelete = true
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (isDeleting) {
            ProgressView(tint = Color.appError)
        } else {
            Text(delete.title, style = inlineDeleteTextStyle)
        }
    }

    ConfirmationDialog(
        title = delete.confirmation,
        isPresented = confirmingDelete,
        onDismiss = { confirmingDelete = false },
        message = delete.message,
        actions = listOf(DialogAction("Delete", isDestructive = true, action = delete.action)),
    )
}

/**
 * Pads the bottom by `padding`, which may be negative: the content then hangs that
 * far out past this element's bottom edge.
 */
private fun Modifier.bottomPadding(padding: Dp): Modifier = layout { measurable, constraints ->
    val paddingPx = padding.roundToPx()
    val placeable = measurable.measure(constraints.offset(vertical = -paddingPx))
    layout(placeable.width, constraints.constrainHeight(placeable.height + paddingPx)) {
        placeable.place(0, 0)
    }
}
