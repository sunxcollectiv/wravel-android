//
//  Overlay.kt
//  outbound
//

package earth.outbound

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalContext
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.currentCompositionLocalContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import java.util.UUID

/**
 * Where things that cover the whole screen are drawn: sheets, popups, confirmations.
 *
 * SwiftUI presents those from wherever they're declared (`.sheet`, `.overlay` on a
 * full-screen view) and they cover the screen. Compose draws a child inside its
 * parent's bounds, so here anything declared with `Overlay` is drawn by the
 * `OverlayHost` at the root of the app instead, over the screens and in the order
 * each was presented. Everything stays in one window, so the offline pill, drawn
 * last, is never hidden by any of them.
 */
@Stable
class OverlayHostState {
    /** In the order they were declared, which keeps each one's place in the composition. */
    internal val entries = mutableStateListOf<OverlayEntry>()
    private var presentations = 0

    internal fun nextOrder(): Int {
        presentations += 1
        return presentations
    }

    /** The overlay going back closes: the last presented of those open that handle it. */
    internal val backEntry: OverlayEntry?
        get() = entries.filter { it.isPresented && it.handlesBack }.maxByOrNull { it.order }
}

@Stable
internal class OverlayEntry(
    /** Tells this overlay's saved state from the others', and survives the activity being recreated. */
    val id: String,
    content: @Composable () -> Unit,
    locals: CompositionLocalContext,
) {
    var content by mutableStateOf(content)
    var locals by mutableStateOf(locals)
    var isPresented by mutableStateOf(false)

    /** When this was last presented, which stacks it over those presented before. */
    var order by mutableIntStateOf(0)

    var handlesBack by mutableStateOf(false)

    /** Only called when going back, so a new one doesn't need to redraw anything. */
    var onBack: (() -> Unit)? = null
}

val LocalOverlayHost = staticCompositionLocalOf<OverlayHostState?> { null }

/** Hosts the app's screens with everything they present over themselves on top. */
@Composable
fun OverlayHost(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val state = remember { OverlayHostState() }
    CompositionLocalProvider(LocalOverlayHost provides state) {
        Box(modifier.fillMaxSize()) {
            content()
            for (entry in state.entries) {
                key(entry.id) { OverlayLayer(entry) }
            }
        }
        OverlayBackHandler(state)
    }
}

/**
 * One overlay's content over the whole screen. Stacked by `zIndex` rather than by
 * where it comes in the composition, so presenting one over another doesn't move
 * either and they keep their state.
 */
@Composable
private fun OverlayLayer(entry: OverlayEntry) {
    CompositionLocalProvider(entry.locals) {
        Box(Modifier.fillMaxSize().zIndex(entry.order.toFloat())) { entry.content() }
    }
}

/**
 * Going back closes the overlay on top. `BackHandler`s answer newest first by when
 * they joined the composition, not by what's showing, so the overlays share this one,
 * which joins again each time another overlay comes out on top: it then goes before
 * the handlers of the screens underneath, and after any inside the overlay itself
 * (e.g. a page pushed within a sheet), which are composed once it's open.
 */
@Composable
private fun OverlayBackHandler(state: OverlayHostState) {
    val top = state.backEntry
    key(top?.order) {
        BackHandler(enabled = top != null) { top?.onBack?.invoke() }
    }
}

/**
 * Draws `content` over the whole screen rather than in place, keeping the theme and
 * stores of where it's declared.
 *
 * Keep it in the composition while it's closed and animate inside `content` (e.g. an
 * `AnimatedVisibility` on `isPresented`), so it can animate out; `isPresented` only
 * says when it was opened, which puts it above whatever was already showing. Taken
 * out of the composition (its screen went away, or its row scrolled out of a lazy
 * list), it's gone at once.
 *
 * `onBack` is what going back does while it's presented and on top, usually closing
 * it; null leaves going back to whatever is underneath.
 */
@Composable
fun Overlay(isPresented: Boolean, onBack: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val host = LocalOverlayHost.current
    if (host == null) {
        // No host, e.g. in a preview: draw in place.
        BackHandler(enabled = isPresented && onBack != null) { onBack?.invoke() }
        Box(Modifier.fillMaxSize()) { content() }
        return
    }
    val id = rememberSaveable { UUID.randomUUID().toString() }
    val locals = currentCompositionLocalContext
    val entry = remember(host, id) { OverlayEntry(id, content, locals) }

    // Written as this composes: the host draws its entries after the screens, and an
    // overlay declared inside another after the one it's in, so each reads these
    // later in the same pass.
    entry.content = content
    entry.locals = locals
    entry.onBack = onBack
    entry.handlesBack = onBack != null
    if (entry.isPresented != isPresented) {
        entry.isPresented = isPresented
        if (isPresented) entry.order = host.nextOrder()
    }

    DisposableEffect(host, entry) {
        host.entries.add(entry)
        onDispose { host.entries.remove(entry) }
    }
}
