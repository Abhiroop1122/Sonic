package app.runout.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.runout.soulseek.SoulseekConnection
import app.runout.soulseek.SoulseekSession
import app.runout.soulseek.SoulseekTransfer
import app.runout.soulseek.SoulseekTransferState
import com.kyant.backdrop.Backdrop

/** A light tick for keys, tabs and toggles: the room is meant to feel like hardware. */
@Composable
internal fun rememberTick(type: HapticFeedbackType = HapticFeedbackType.SegmentTick): () -> Unit {
    val haptics = LocalHapticFeedback.current
    return remember(haptics, type) { { haptics.performHapticFeedback(type) } }
}

/** Downloads waiting: grey when idle or paused, green while bytes move, red when one needs you. */
internal fun downloadsColor(transfers: List<SoulseekTransfer>): Color = when {
    transfers.any { it.state == SoulseekTransferState.FAILED } -> Room.Danger
    transfers.any {
        it.state == SoulseekTransferState.DOWNLOADING || it.state == SoulseekTransferState.VALIDATING || it.state == SoulseekTransferState.PUBLISHING
    } -> Room.Online
    else -> Room.Quiet
}

/** Green when connected, red when an account is offline or in trouble, grey otherwise. */
internal fun connectionColor(session: SoulseekSession): Color = when (session.state) {
    SoulseekConnection.CONNECTED -> Room.Online
    SoulseekConnection.ERROR -> Room.Danger
    SoulseekConnection.OFFLINE -> if (session.hasAccount) Room.Danger else Room.Quiet
    else -> Room.Quiet
}

/** A square status lamp followed by a quiet label, tappable as one; [description] reads it out when there's no label. */
@Composable
internal fun StatusDot(text: String, color: Color, tag: String, onClick: () -> Unit, description: String? = null) {
    Row(
        Modifier
            .testTag(tag)
            .semantics(mergeDescendants = true) { if (description != null) contentDescription = description }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onClick)
            .touchHeight()
            .padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // A square lamp: filled when something's live, hollow when idle.
        Canvas(Modifier.size(8.dp)) {
            if (color == Room.Quiet) drawRect(Room.Ink, style = Stroke(1.dp.toPx())) else drawRect(color)
        }
        if (text.isNotEmpty()) BasicText(text, style = Room.Label.copy(color = if (color == Room.Quiet) Room.Quiet else Room.Ink))
    }
}
