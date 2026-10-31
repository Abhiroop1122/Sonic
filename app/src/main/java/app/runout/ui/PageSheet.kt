package app.runout.ui

import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.layout.Row
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * Where a full page (Dig, Friends) draws its bottom sheets: over the whole page, header and system
 * bars included, so the shade is one even layer instead of a box inside the content's padding.
 */
internal class PageSheetHost {
    var sheet by mutableStateOf<(@Composable () -> Unit)?>(null)
}

internal val LocalPageSheetHost = compositionLocalOf<PageSheetHost?> { null }

/** Draws the sheet a page's content asked for, if any. Call last inside the page's root Box. */
@Composable
internal fun PageSheetLayer(host: PageSheetHost) {
    host.sheet?.invoke()
}

/**
 * A sheet of the room's paper (or, [inverted], of its ink) that rises from the bottom of the page over a shade. Tapping the shade or Back
 * closes it. Inside a full page it's drawn by the page itself (see [PageSheetHost]); anywhere else
 * it's drawn in place.
 */
@Composable
internal fun PageSheet(tag: String, onDismiss: () -> Unit, title: String? = null, inverted: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val host = LocalPageSheetHost.current
    val sheet: @Composable () -> Unit = { SheetBody(tag, onDismiss, title, inverted, content) }
    if (host == null) {
        sheet()
        return
    }
    // Hand the latest content to the page on every recomposition, and take it back when closed.
    SideEffect { host.sheet = sheet }
    DisposableEffect(host) { onDispose { if (host.sheet != null) host.sheet = null } }
}

@Composable
private fun SheetBody(tag: String, onDismiss: () -> Unit, title: String?, inverted: Boolean, content: @Composable ColumnScope.() -> Unit) {
    BackHandler(onBack = onDismiss)
    val shown = remember { MutableTransitionState(false).apply { targetState = true } }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        // A sibling of the card, so the card's own controls stay separate for TalkBack.
        AnimatedVisibility(shown, enter = fadeIn()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Room.Scrim)
                    .testTag("sheet-shade")
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
            )
        }
        AnimatedVisibility(shown, enter = slideInVertically { it } + fadeIn()) {
            Column(
                Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    // Inverted, for a warning: the theme's ink as its paper, under a red rule.
                    .background(if (inverted) Room.Ink else Room.Background)
                    // The TE panel: a 2 dp ink rule on top, 22 dp in (33 at the foot), 22 dp between parts.
                    .hairlineTop(if (inverted) Room.InverseDanger else Room.Ink, 2.dp)
                    .pointerInput(Unit) { detectTapGestures { } }
                    .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                    .padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 32.dp)
                    .testTag(tag),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                if (title != null) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    BasicText(sentence(title), style = Room.Title, modifier = Modifier.weight(1f))
                    CloseKey(onDismiss)
                }
                content()
            }
        }
    }
}
