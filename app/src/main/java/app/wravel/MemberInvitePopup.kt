//
//  MemberInvitePopup.kt
//  wravel
//

package app.wravel

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
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
import androidx.compose.material.icons.automirrored.rounded.ArrowBackIos
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.HowToReg
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Lock
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
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

    /** The longest name the server takes for a member. */
    const val nameMaxLength = 120

    private val emailShape = Regex("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")

    /** A loose shape check: something@something.tld, no spaces. */
    fun isValidEmail(email: String): Boolean = emailShape.matches(email)
}

/**
 * A centered card for inviting people to a trip by email, shown over the trip form.
 * Each submitted address is added to `emails` straight away and listed beneath the
 * field, so several can be entered in a row before closing. Tapping someone in the
 * list opens their member card in the same popup, to name or remove them.
 *
 * `names` is what to call people who don't have an account, by email. `accountNames`
 * is the name on each account, by email, for people who have one: theirs is shown
 * instead of one set here, so their card can't change it.
 */
@Composable
fun MemberInvitePopup(
    emails: List<String>,
    onEmailsChange: (List<String>) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    names: Map<String, String> = emptyMap(),
    onNamesChange: (Map<String, String>) -> Unit = {},
    accountNames: Map<String, String> = emptyMap(),
) {
    /** The member whose card is showing, by email; null shows the invite list. */
    var selectedEmail by rememberSaveable { mutableStateOf<String?>(null) }
    /** Whether the email field has had its first focus, so going back doesn't refocus it. */
    var hasFocusedField by rememberSaveable { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    fun showCard(email: String) {
        focusManager.clearFocus()
        selectedEmail = email
    }

    /** Back from a member card to the list, tidying the name they were given. */
    fun showList() {
        selectedEmail?.let { email ->
            val name = names[email]?.trim()
            onNamesChange(if (name.isNullOrEmpty()) names - email else names + (email to name))
        }
        focusManager.clearFocus()
        selectedEmail = null
    }

    fun remove(email: String) {
        onEmailsChange(emails.filter { it != email })
        onNamesChange(names - email)
        selectedEmail = null
    }

    BackHandler(enabled = selectedEmail != null) { showList() }

    PopupCard(onClose = onClose, modifier = modifier) {
        AnimatedContent(
            targetState = selectedEmail,
            transitionSpec = { (PopupCardMetrics.contentEnter togetherWith PopupCardMetrics.contentExit).using(null) },
            label = "member card",
        ) { email ->
            if (email != null) {
                MemberCard(
                    email = email,
                    name = names[email] ?: "",
                    // A blank name is stored as none.
                    onNameChange = { onNamesChange(if (it.isEmpty()) names - email else names + (email to it)) },
                    accountName = accountNames[email],
                    onBack = ::showList,
                    onRemove = { remove(email) },
                )
            } else {
                InviteList(
                    emails = emails,
                    onEmailsChange = onEmailsChange,
                    // The name each listed person goes by: their account's, else one set here.
                    displayNames = names + accountNames,
                    onSelect = ::showCard,
                    focusesField = !hasFocusedField,
                    onFieldFocused = { hasFocusedField = true },
                )
            }
        }
    }
}

@Composable
private fun InviteList(
    emails: List<String>,
    onEmailsChange: (List<String>) -> Unit,
    displayNames: Map<String, String>,
    onSelect: (String) -> Unit,
    focusesField: Boolean,
    onFieldFocused: () -> Unit,
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
            MemberEmailList(emails = emails, onEmailsChange = onEmailsChange, names = displayNames, onSelect = onSelect)
        }
    }

    LaunchedEffect(Unit) {
        if (focusesField) {
            focusRequester.requestFocus()
            onFieldFocused()
        }
    }
}

/**
 * Rows of invited people, each with an avatar initial, their name over their email
 * when they have one (`names`, by email), and a remove button. Tapping a row calls
 * `onSelect`. Rows fade in and out as emails are added and removed. It hugs its rows,
 * and scrolls them once they're taller than `MemberInvitePopupMetrics.listMaxHeight`
 * (or less, when `modifier` allows less), so it's safe in a card that lets its content
 * be any height.
 */
@Composable
fun MemberEmailList(
    emails: List<String>,
    onEmailsChange: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
    names: Map<String, String> = emptyMap(),
    onSelect: ((String) -> Unit)? = null,
) {
    LazyColumn(
        modifier
            .heightIn(max = MemberInvitePopupMetrics.listMaxHeight)
            .clip(RoundedCornerShape(appFieldCornerRadius))
            .background(Color.appForeground.copy(alpha = 0.08f))
    ) {
        itemsIndexed(emails, key = { _, email -> email }) { index, email ->
            val name = names[email]
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
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .plainClickable(enabled = onSelect != null) { onSelect?.invoke(email) }
                            .semantics { if (onSelect != null) onClick(label = "Open their member card") { onSelect(email); true } },
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        MemberInitial(name ?: email)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                name ?: email,
                                style = AppFont.subheadline,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (name != null) {
                                Text(
                                    email,
                                    style = AppFont.caption,
                                    color = Color.appForeground.copy(alpha = 0.6f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        // Hints that the row opens their card.
                        if (onSelect != null) {
                            Icon(
                                Icons.Rounded.ChevronRight,
                                contentDescription = null,
                                tint = Color.appForeground.copy(alpha = 0.35f),
                                modifier = Modifier.size(scaledSymbolSize(AppFont.footnote, scale = 1.4f)),
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .plainClickable { onEmailsChange(emails.filter { it != email }) }
                            .semantics { contentDescription = "Remove ${name ?: email}" },
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

/**
 * One person's card inside the invite popup: their email, a field for what to call
 * them, and a button to take them off the trip. Someone with an account goes by that
 * account's name, shown here but not editable.
 */
@Composable
private fun MemberCard(
    email: String,
    name: String,
    onNameChange: (String) -> Unit,
    accountName: String?,
    onBack: () -> Unit,
    onRemove: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val hintStyle = AppFont.footnote.copy(color = Color.appForeground.copy(alpha = 0.6f))

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PopupHeaderLabel(
            "Member",
            icon = Icons.AutoMirrored.Rounded.ArrowBackIos,
            modifier = Modifier
                .plainClickable(onClick = onBack)
                .semantics { contentDescription = "Back to members" },
        )

        Column(
            Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            MemberInitial(accountName ?: name.ifEmpty { email }, size = 56.dp)
            Text(
                email,
                style = hintStyle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (accountName != null) {
                Row(
                    modifier = Modifier.fillMaxWidth().appField(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.HowToReg,
                        contentDescription = null,
                        tint = Color.Black.copy(alpha = 0.45f),
                        modifier = Modifier.size(scaledSymbolSize(AppFont.body, scale = 1.2f)),
                    )
                    Text(
                        accountName,
                        color = Color.Black.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        Icons.Rounded.Lock,
                        contentDescription = null,
                        tint = Color.Black.copy(alpha = 0.35f),
                        modifier = Modifier.size(scaledSymbolSize(AppFont.footnote, scale = 1.2f)),
                    )
                }
                Text(
                    "They have an account, so they go by the name on it.",
                    style = hintStyle,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth().appField(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.Person,
                        contentDescription = null,
                        tint = Color.Black.copy(alpha = 0.45f),
                        modifier = Modifier.size(scaledSymbolSize(AppFont.body, scale = 1.2f)),
                    )
                    PlainTextField(
                        text = name,
                        // The server takes up to 120 characters.
                        onTextChange = { onNameChange(it.take(MemberInvitePopupMetrics.nameMaxLength)) },
                        placeholder = "Name",
                        modifier = Modifier.weight(1f).focusRequester(focusRequester),
                        color = appFieldTextColor,
                        placeholderColor = Color.Black.copy(alpha = 0.3f),
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Words,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(onDone = { onBack() }),
                    )
                }
                Text(
                    "Shown to everyone on the trip until they make an account.",
                    style = hintStyle,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }

        Text(
            "Remove from Trip",
            style = AppFont.subheadline.semibold,
            color = Color.systemRed,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .plainClickable(onClick = onRemove)
                .padding(vertical = 6.dp),
        )
    }

    // Straight to typing for someone with no name yet.
    LaunchedEffect(Unit) {
        if (accountName == null && name.isEmpty()) focusRequester.requestFocus()
    }
}

@Preview
@Composable
private fun MemberInvitePopupPreview() {
    AppTheme {
        var emails by remember { mutableStateOf(listOf("sam@example.com", "jesse@example.com")) }
        Box(Modifier.background(Color.Black), contentAlignment = Alignment.Center) {
            var names by remember { mutableStateOf(mapOf("jesse@example.com" to "Jesse")) }
            MemberInvitePopup(
                emails = emails,
                onEmailsChange = { emails = it },
                onClose = {},
                names = names,
                onNamesChange = { names = it },
                accountNames = mapOf("sam@example.com" to "Sam"),
            )
        }
    }
}
