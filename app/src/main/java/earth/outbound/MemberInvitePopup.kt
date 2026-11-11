//
//  MemberInvitePopup.kt
//  outbound
//

package earth.outbound

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * What the invite popup checks an address against. It lives here rather than on
 * `MemberInvitePopup`, which is a composable function and so can't hold it itself.
 */
object MemberInvitePopupMetrics {
    /** The tallest the email list gets before it scrolls. */
    val listMaxHeight: Dp = 240.dp

    private val emailShape = Regex("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")

    /** A loose shape check: something@something.tld, no spaces. */
    fun isValidEmail(email: String): Boolean = emailShape.matches(email)
}

/**
 * A centered card for inviting people to a trip by email, shown over the trip form.
 * Each submitted address is added to `emails` straight away and listed beneath the
 * field, so several can be entered in a row before closing.
 */
@Composable
fun MemberInvitePopup(
    emails: List<String>,
    onEmailsChange: (List<String>) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by rememberSaveable { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val focusRequester = remember { FocusRequester() }
    val trimmedText = text.trim()

    /** Adds the typed address to the list, keeping focus so the next one can be typed. */
    fun add() {
        val email = trimmedText.lowercase()
        if (email.isEmpty()) return
        focusRequester.requestFocus()
        if (!MemberInvitePopupMetrics.isValidEmail(email)) {
            errorMessage = "That doesn't look like an email address."
            return
        }
        if (email in emails) {
            errorMessage = "$email is already on the list."
            return
        }
        onEmailsChange(emails + email)
        text = ""
    }

    PopupCard(onClose = onClose, modifier = modifier) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            PopupHeaderLabel("Add Members", icon = Icons.Rounded.Group)

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // The field, with Add at its end.
                Row(
                    modifier = Modifier.fillMaxWidth().appField(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.Email,
                        contentDescription = null,
                        tint = Color.Black.copy(alpha = 0.45f),
                        modifier = Modifier.size(scaledSymbolSize(AppFont.body, scale = 1.2f)),
                    )
                    PlainTextField(
                        text = text,
                        onTextChange = {
                            text = it
                            errorMessage = null
                        },
                        placeholder = "Email address",
                        modifier = Modifier.weight(1f).focusRequester(focusRequester),
                        color = appFieldTextColor,
                        placeholderColor = Color.Black.copy(alpha = 0.3f),
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false,
                            keyboardType = KeyboardType.Email,
                            imeAction = ImeAction.Done,
                        ),
                        // Done adds the address and keeps the keyboard for the next; with
                        // nothing typed it puts the keyboard away.
                        keyboardActions = KeyboardActions(
                            onDone = { if (trimmedText.isEmpty()) defaultKeyboardAction(ImeAction.Done) else add() },
                        ),
                    )
                    Text(
                        "Add",
                        style = AppFont.subheadline.semibold,
                        color = Color.appAccent,
                        modifier = Modifier
                            .dimmed(trimmedText.isEmpty())
                            .plainClickable(enabled = trimmedText.isNotEmpty(), onClick = ::add),
                    )
                }
                errorMessage?.let { message ->
                    Text(
                        message,
                        style = AppFont.footnote,
                        color = Color.appForeground.copy(alpha = 0.75f),
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }

            // The invited emails, scrolling once there are more than fit in the card.
            if (emails.isEmpty()) {
                Text(
                    "Invited members will appear here.",
                    style = AppFont.footnote,
                    color = Color.appForeground.copy(alpha = 0.6f),
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            } else {
                MemberEmailList(emails = emails, onEmailsChange = onEmailsChange)
            }
        }
    }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }
}

/**
 * Rows of invited emails, each with an avatar initial and a remove button. Rows fade
 * in and out as emails are added and removed. It hugs its rows, and scrolls them once
 * they're taller than `MemberInvitePopupMetrics.listMaxHeight` (or less, when
 * `modifier` allows less), so it's safe in a card that lets its content be any height.
 */
@Composable
fun MemberEmailList(
    emails: List<String>,
    onEmailsChange: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier
            .heightIn(max = MemberInvitePopupMetrics.listMaxHeight)
            .clip(RoundedCornerShape(appFieldCornerRadius))
            .background(Color.appForeground.copy(alpha = 0.08f))
    ) {
        itemsIndexed(emails, key = { _, email -> email }) { index, email ->
            Column(
                Modifier.animateItem(
                    fadeInSpec = AppAnimation.snappy(0.2),
                    placementSpec = AppAnimation.snappy(0.2),
                    fadeOutSpec = AppAnimation.snappy(0.2),
                )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MemberInitial(email)
                    Text(
                        email,
                        style = AppFont.subheadline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        // Stops a little short of the remove button.
                        modifier = Modifier.weight(1f).padding(end = 20.dp),
                    )
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .plainClickable { onEmailsChange(emails.filter { it != email }) }
                            .semantics { contentDescription = "Remove $email" },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = null,
                            tint = Color.appForeground.copy(alpha = 0.7f),
                            modifier = Modifier.size(scaledSymbolSize(AppFont.footnote, scale = 1.25f)),
                        )
                    }
                }

                if (index < emails.size - 1) {
                    Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color.appForeground.copy(alpha = 0.15f)))
                }
            }
        }
    }
}

@Preview
@Composable
private fun MemberInvitePopupPreview() {
    AppTheme {
        var emails by remember { mutableStateOf(listOf("sam@example.com")) }
        Box(Modifier.background(Color.Black), contentAlignment = Alignment.Center) {
            MemberInvitePopup(emails = emails, onEmailsChange = { emails = it }, onClose = {})
        }
    }
}
