package app.runout.ui

import app.runout.ui.settings.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.runout.soulseek.FailureKind
import app.runout.soulseek.Recovery
import app.runout.soulseek.SoulseekConnection
import app.runout.soulseek.SoulseekMessage
import app.runout.soulseek.SoulseekMessageState
import app.runout.soulseek.SoulseekPolicy
import app.runout.soulseek.SoulseekSession
import app.runout.soulseek.SoulseekTransfer
import app.runout.soulseek.SoulseekTransferState
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

internal fun connectionLabel(session: SoulseekSession) = when(session.state) {
    SoulseekConnection.CONNECTED -> "Online"
    SoulseekConnection.CONNECTING -> "Connecting"
    SoulseekConnection.RECONNECTING -> "Reconnecting"
    SoulseekConnection.PAUSED -> "Paused"
    SoulseekConnection.ERROR -> "Account !"
    else -> if (session.hasAccount) "Offline" else "Connect"
}

/** The lamp colour for a Soulseek connection: green online, amber on the way, red on a problem. */
internal fun lampColor(session: SoulseekSession): Color = when {
    session.connected -> Room.Online
    session.state == SoulseekConnection.ERROR || session.error != null -> Room.Danger
    session.state == SoulseekConnection.CONNECTING || session.state == SoulseekConnection.RECONNECTING -> Room.Warning
    else -> Room.Faint
}

private val HelpBackgroundUsage = TeHelp(
    "help · downloads", "background usage is off",
    listOf(
        "android pauses sonic's downloads as soon as you leave the app.",
        "allow background usage in app info › battery to let them finish while the phone sleeps. paused downloads continue when you come back.",
    ),
)

/**
 * When Soulseek isn't connected: a grey tile with the connection lamp, a 12/700 caps line, the
 * reason (red when Soulseek reported a problem) and one black key to reconnect or connect.
 */
@Composable
internal fun ConnectionNotice(state: RoomState, actions: RoomActions, onAccount: () -> Unit) {
    val session = state.soulseekSession
    if(session.connected) return
    Column(
        Modifier.fillMaxWidth().padding(bottom = 22.dp).background(Room.Surface).padding(16.dp).testTag("connection-notice"),
        verticalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(8.dp).background(lampColor(session)))
            BasicText(
                if (!session.hasAccount) "YOUR LOCAL MUSIC IS ALWAYS AVAILABLE" else when (session.state) {
                    SoulseekConnection.CONNECTING -> "CONNECTING TO SOULSEEK"
                    SoulseekConnection.RECONNECTING -> "RECONNECTING TO SOULSEEK"
                    SoulseekConnection.PAUSED -> "SOULSEEK IS PAUSED"
                    SoulseekConnection.ERROR -> "SOULSEEK ACCOUNT PROBLEM"
                    else -> "SOULSEEK IS OFFLINE"
                },
                style = Room.Strong,
            )
        }
        BasicText(
            (session.error ?: "Connect Soulseek to discover FLAC, download records and send messages.").lowercase(),
            style = Room.Label.copy(color = if (session.error != null) Room.Danger else Room.Body, lineHeight = 15.sp),
        )
        if(session.hasAccount) TeButton("reconnect", actions::reconnectSoulseek, enabled = session.state != SoulseekConnection.RECONNECTING)
        else TeButton("connect soulseek", onAccount)
    }
}

/** A problem Soulseek reported: a red lamp, the words in red, and "dismiss" to put it away. */
@Composable
internal fun NetworkNote(state: RoomState, actions: RoomActions) {
    val note = state.soulseekError ?: state.soulseekSession.error ?: return
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(11.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 4.dp).size(8.dp).background(Room.Danger))
        BasicText(note.lowercase(), style = Room.Label.copy(color = Room.Danger, lineHeight = 15.sp), modifier = Modifier.weight(1f).testTag("soulseek-error"))
        ArrowLink("dismiss", actions::clearSoulseekError, tag = "soulseek-error-dismiss")
    }
}

/** Signed out on Friends: one 10/400 line about what signing in gives you, and the black key. */
@Composable
private fun SignInNotice(onAccount: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
        BasicText("sign in with soulseek to search, download flac and send private messages.", style = Room.Label.copy(color = Room.Body, lineHeight = 15.sp))
        TeButton("sign in with soulseek", onAccount)
    }
}

/** A 10/400 action set as "pause →": no box, just the word and its arrow. */
@Composable
internal fun ArrowLink(text: String, onClick: () -> Unit, enabled: Boolean = true, tag: String? = null, color: Color = Room.Ink) {
    BasicText(
        "$text →",
        style = Room.Label.copy(color = color),
        maxLines = 1,
        modifier = Modifier
            .then(if (tag != null) Modifier.testTag(tag) else Modifier)
            .alpha(if (enabled) 1f else 0.4f)
            .clickable(enabled = enabled, interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onClick),
    )
}

private fun mb(bytes: Long, gb: Boolean): String =
    if (gb) String.format(Locale.ROOT, "%.1f", bytes / 1_073_741_824.0) else String.format(Locale.ROOT, "%.1f", bytes / 1_048_576.0)

/** "73.1 / 101.2 mb · 72%", in gb once the file passes a gigabyte. */
private fun sizeLabel(bytes: Long, size: Long, progress: Float): String {
    val gb = size >= 1_073_741_824L
    return "${mb(bytes, gb)} / ${mb(size, gb)} ${if (gb) "gb" else "mb"} · ${(progress * 100).toInt()}%"
}

/** Each transfer state's lamp: black done, orange moving, grey waiting, amber paused, red failed. */
private fun stateColor(state: SoulseekTransferState): Color = when (state) {
    SoulseekTransferState.COMPLETED -> Room.Ink
    SoulseekTransferState.DOWNLOADING, SoulseekTransferState.VALIDATING, SoulseekTransferState.PUBLISHING -> Room.Signal
    SoulseekTransferState.PAUSED -> Room.Warning
    SoulseekTransferState.FAILED -> Room.Danger
    else -> Room.Faint
}

/** The Downloads page's three segments, in the owner's order. */
internal enum class DownloadGroup(val label: String, val empty: String, val colour: Color) {
    FAILED("failed", "nothing failed.", Room.Danger),
    COMPLETED("completed", "nothing finished yet.", Room.Online),
    QUEUE("in queue", "nothing in the queue.", Room.Signal),
}

internal fun downloadGroup(state: SoulseekTransferState): DownloadGroup = when (state) {
    SoulseekTransferState.COMPLETED -> DownloadGroup.COMPLETED
    SoulseekTransferState.FAILED, SoulseekTransferState.CANCELLED -> DownloadGroup.FAILED
    else -> DownloadGroup.QUEUE
}

/**
 * Downloads: three segments (failed red · completed green · in queue orange, with counts) over that group's files,
 * each under a hairline (index, name, lamp and state, size, a boxed progress bar, its keys: pause or
 * resume and remove in the queue, retry and remove when failed); then shared uploads, the
 * all-at-once controls and turtle mode. When Android's background usage is off, an amber alert
 * sits above the segments.
 */
@Composable
fun SoulseekTransfersPanel(state: RoomState, actions: RoomActions, onDig: () -> Unit = {}) {
    val restricted = rememberBackgroundRestricted()
    // The minutes until a retry, kept current only while something is waiting to retry.
    val retrying = state.soulseekTransfers.any { it.recovery == Recovery.RETRY }
    val now by produceState(System.currentTimeMillis(), retrying) {
        while (retrying) { value = System.currentTimeMillis(); kotlinx.coroutines.delay(10_000) }
    }
    var help by remember { mutableStateOf<TeHelp?>(null) }
    val signedIn = state.soulseekSession.signedIn
    Column(Modifier.fillMaxWidth().testTag("panel:transfers"), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        if (restricted) BackgroundUseNote { help = HelpBackgroundUsage }
        NetworkNote(state, actions)
        val groups = state.soulseekTransfers.groupBy { downloadGroup(it.state) }
        var picked by rememberSaveable { mutableStateOf<DownloadGroup?>(null) }
        // Opens on what needs you: the queue, then what failed, then what's done.
        val group = picked ?: listOf(DownloadGroup.QUEUE, DownloadGroup.FAILED, DownloadGroup.COMPLETED).firstOrNull { groups[it].orEmpty().isNotEmpty() } ?: DownloadGroup.QUEUE
        Segments(DownloadGroup.entries, group, { "${it.label} ${groups[it].orEmpty().size}" }, tag = { "downloads-tab:${it.name.lowercase()}" }, colour = { it.colour }) { picked = it }
        val shown = groups[group].orEmpty()
        when {
            state.soulseekTransfers.isEmpty() -> BasicText("nothing downloading.", style = Room.LabelQuiet, modifier = Modifier.testTag("downloads-empty"))
            shown.isEmpty() -> BasicText(group.empty, style = Room.LabelQuiet, modifier = Modifier.testTag("downloads-group-empty"))
            else -> Column(Modifier.fillMaxWidth()) {
                shown.forEachIndexed { i, transfer -> TransferRow(i + 1, transfer, state, now, restricted, actions, onDig) }
                if (group == DownloadGroup.FAILED) Row(Modifier.fillMaxWidth().hairlineTop().padding(top = 16.dp)) {
                    ArrowLink("remove all failed", actions::removeFailedDownloads, tag = "downloads-remove-failed")
                }
            }
        }
        if (state.soulseekUploads.isNotEmpty()) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(11.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                BasicText("shared flac uploads", style = Room.LabelQuiet)
                ArrowLink("stop sharing", { actions.setSoulseekSharing(false) }, tag = "stop-sharing")
            }
            Column(Modifier.fillMaxWidth()) {
                state.soulseekUploads.forEach { upload ->
                    Column(Modifier.fillMaxWidth().hairlineTop().padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                        BasicText(upload.fileName, style = Room.Label.copy(lineHeight = 15.sp))
                        Row(Modifier.fillMaxWidth().padding(end = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            TeStatus("${upload.username} · ${upload.state.lowercase()}", Room.Signal)
                            BasicText(sizeLabel(upload.bytes, upload.size, upload.progress), style = Room.LabelQuiet.copy(textAlign = TextAlign.End))
                        }
                        ProgressLine(upload.progress, colour = Room.Signal)
                    }
                }
            }
        }
        // Everything at once, then turtle mode: one download at a time, gently.
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                ArrowLink("pause all", actions::pauseAllDownloads, enabled = signedIn, tag = "downloads-pause-all")
                ArrowLink("resume all", actions::resumeAllDownloads, enabled = signedIn, tag = "downloads-resume-all")
            }
            HelpToggle(
                "Turtle mode",
                "One download at a time, gentler on your connection and on the people sharing.",
                state.turtleMode, actions::setTurtleMode, tag = "downloads-turtle",
            )
        }
    }
    TeHelpSheet(help) { help = null }
}

/** The minutes until [at], at least one: "4 min". */
internal fun minutesUntil(at: Long?, now: Long): String = "${(((at ?: now) - now + 59_999) / 60_000).coerceAtLeast(1)} min"

/**
 * One download under a hairline, as on the Downloads board: its file and the album it belongs to,
 * who it comes from and what it's doing (or what Runout is doing about a failure), its keys.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun TransferRow(index: Int, transfer: SoulseekTransfer, state: RoomState, now: Long, restricted: Boolean, actions: RoomActions, onDig: () -> Unit) {
    val signedIn = state.soulseekSession.signedIn
    val archive = transfer.provider == "Internet Archive"
    val shelf = transfer.albumId?.let { id -> state.albums.firstOrNull { it.id == id } }
    val (folderArtist, folderTitle) = app.runout.discovery.folderNames(transfer.directory)
    val albumTitle = shelf?.title ?: folderTitle
    val reason = transfer.error?.trimEnd('.')?.lowercase()
    Column(Modifier.fillMaxWidth().hairlineTop().padding(vertical = 16.dp).testTag("transfer:${transfer.id}"), verticalArrangement = Arrangement.spacedBy(11.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(11.dp)) {
            BasicText("%02d".format(index), style = Room.LabelQuiet)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                BasicText(transfer.fileName, style = Room.Label.copy(lineHeight = 15.sp))
                if (!archive) BasicText("from ${albumTitle.lowercase()}", style = Room.LabelQuiet, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("transfer-album:${transfer.id}"))
            }
        }
        val doing = when (transfer.recovery) {
            Recovery.RETRY -> "retrying in ${minutesUntil(transfer.retryAt, now)}"
            Recovery.SEEK -> "looking for another copy"
            null -> transfer.state.name.lowercase()
        }
        Row(Modifier.fillMaxWidth().padding(end = 12.dp), horizontalArrangement = Arrangement.spacedBy(11.dp), verticalAlignment = Alignment.CenterVertically) {
            TeStatus(
                "${transfer.username} · $doing" + (transfer.queuePosition?.let { " · place $it in their queue" } ?: ""),
                stateColor(transfer.state),
                Modifier.weight(1f),
            )
            BasicText(
                if (archive) "internet archive · ${(transfer.progress * 100).toInt()}%" else sizeLabel(transfer.bytes, transfer.size, transfer.progress),
                style = Room.LabelQuiet.copy(textAlign = TextAlign.End),
            )
        }
        ProgressLine(transfer.progress, colour = stateColor(transfer.state))
        when {
            transfer.recovery == Recovery.RETRY -> Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                ArrowLink("retry now", { actions.resumeSoulseekTransfer(transfer.id) }, enabled = signedIn, tag = "retry-now:${transfer.id}")
                ArrowLink("remove", { actions.removeSoulseekTransfer(transfer.id) }, tag = "remove:${transfer.id}")
            }
            transfer.recovery == Recovery.SEEK -> Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                ArrowLink("stop", { actions.pauseSoulseekTransfer(transfer.id) }, tag = "pause:${transfer.id}")
                ArrowLink("remove", { actions.removeSoulseekTransfer(transfer.id) }, tag = "remove:${transfer.id}")
            }
            transfer.state == SoulseekTransferState.QUEUED || transfer.state == SoulseekTransferState.DOWNLOADING -> Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                ArrowLink("pause", { actions.pauseSoulseekTransfer(transfer.id) }, tag = "pause:${transfer.id}")
                ArrowLink("remove", { actions.removeSoulseekTransfer(transfer.id) }, tag = "remove:${transfer.id}")
            }
            transfer.state == SoulseekTransferState.PAUSED -> Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                ArrowLink("resume", { actions.resumeSoulseekTransfer(transfer.id) }, enabled = signedIn, tag = "resume:${transfer.id}")
                ArrowLink("remove", { actions.removeSoulseekTransfer(transfer.id) }, tag = "remove:${transfer.id}")
            }
            transfer.state == SoulseekTransferState.FAILED || transfer.state == SoulseekTransferState.CANCELLED -> androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(22.dp), verticalArrangement = Arrangement.spacedBy(11.dp),
            ) {
                if (transfer.failure == FailureKind.FOLDER) ArrowLink("choose folder", actions::pickDownloadFolder, tag = "choose-folder:${transfer.id}")
                ArrowLink("retry", { actions.retrySoulseekTransfer(transfer.id) }, enabled = signedIn, tag = "retry:${transfer.id}")
                // Dig for the album: the folder page shows this track with a red key, ready to join the record.
                if (!archive && transfer.failure != FailureKind.FOLDER) ArrowLink("find another copy", {
                    val artist = (shelf?.artist ?: folderArtist)?.takeIf { it.isNotBlank() && !it.equals("Unknown artist", ignoreCase = true) }
                    val query = listOfNotNull(artist, albumTitle).joinToString(" ")
                    actions.setSearchOptions(state.discovery.options.copy(source = app.runout.discovery.SearchSource.SOULSEEK))
                    actions.setSearchQuery(query)
                    actions.searchSoulseek(query)
                    onDig()
                }, enabled = signedIn, tag = "find-copy:${transfer.id}")
                ArrowLink("remove", { actions.removeSoulseekTransfer(transfer.id) }, tag = "remove:${transfer.id}")
            }
            else -> Unit
        }
        val why = when {
            transfer.recovery == Recovery.RETRY -> "${reason ?: "the peer went away"}. sonic asks again by itself and keeps what's downloaded."
            transfer.recovery == Recovery.SEEK -> "${reason ?: "this copy failed"}. looking for the identical file from someone else."
            transfer.replaced != null && transfer.state != SoulseekTransferState.FAILED -> "now from ${transfer.username}: ${transfer.replaced}'s copy failed."
            transfer.state == SoulseekTransferState.FAILED && transfer.failure == FailureKind.FOLDER -> "sonic couldn't save it to your music folder. choose the folder again, then retry."
            transfer.state == SoulseekTransferState.FAILED && transfer.failure == FailureKind.BROKEN -> "the file arrived broken, so it wasn't saved."

            restricted && transfer.state == SoulseekTransferState.PAUSED && (transfer.error == null || SoulseekPolicy.isInterruption(transfer.error)) ->
                "android paused this download: background usage is off for sonic. it continues when you're back."
            // Paused while signed out, and signed back in since: the note's first half no longer applies.
            signedIn && transfer.state == SoulseekTransferState.PAUSED && transfer.error?.contains("sign in", ignoreCase = true) == true ->
                "interrupted. resume to continue."
            else -> transfer.error?.lowercase()
        }
        why?.let { BasicText(it, style = Room.Label.copy(color = if (transfer.state == SoulseekTransferState.FAILED) Room.Danger else Room.Quiet, lineHeight = 15.sp)) }
    }
}

/**
 * Friends, on its own page: your saved friends as square initials with status lamps, then your
 * chats, newest first; the black "+ new" key starts a chat by Soulseek username. Inside a chat you
 * can send a record as a card, which the other person can find on Soulseek with one tap.
 */
@Composable
fun SoulseekMessagesPanel(state: RoomState, actions: RoomActions, onAccount: () -> Unit, onDig: () -> Unit = {}, ignoreChat: String? = null) {
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    // [ignoreChat]: the chat that was open when the Friends tab was tapped. Closing it reaches the
    // room a frame late, and must not reopen it.
    LaunchedEffect(state.soulseekConversation) { state.soulseekConversation?.takeIf { it != ignoreChat }?.let { selected = it } }
    val peer = selected
    val clock = rememberClock()
    // A friend's shelf, opened from their chat: it takes the panel until closed.
    val shelf = state.soulseekShelf
    Box(Modifier.fillMaxSize().testTag("panel:messages")) {
        if (shelf != null) {
            FriendShelf(shelf, state.soulseekSession.signedIn, state.soulseekReachability, onBack = actions::closeSoulseekShelf) { dir ->
                actions.openSoulseekFolder(shelf.username, dir); onDig()
            }
        } else if (peer == null) {
            var newChat by remember { mutableStateOf(false) }
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 88.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                if (!state.soulseekSession.signedIn) SignInNotice(onAccount)
                NetworkNote(state, actions)
                LocalMessagesNote()
                val friends = state.soulseekContacts
                if (friends.isNotEmpty()) {
                    BasicText("friends · ${friends.count { it.online }} online", style = Room.LabelQuiet)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                        friends.sortedByDescending { it.online }.forEach { f ->
                            val unread = state.soulseekConversations.firstOrNull { it.username == f.username }?.unread ?: 0
                            Column(
                                Modifier
                                    .testTag("friend:${f.username}")
                                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button) { selected = f.username; actions.openSoulseekConversation(f.username) }
                                    .semantics { stateDescription = if (f.online) "Online" else "Offline" },
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(11.dp),
                            ) {
                                Avatar(f.username, online = f.online, size = 56.dp, lit = unread > 0)
                                BasicText(f.username, style = Room.Label, maxLines = 1)
                            }
                        }
                    }
                }
                BasicText("chats", style = Room.LabelQuiet)
                val chats = state.soulseekConversations.sortedByDescending { c -> c.messages.maxOfOrNull { it.timestamp } ?: 0L }
                if (chats.isEmpty()) BasicText("no chats yet. tap + new to message someone.", style = Room.LabelQuiet)
                else Column(Modifier.fillMaxWidth()) {
                    chats.forEach { c ->
                        val last = c.messages.maxByOrNull { it.timestamp }
                        val contact = state.soulseekContacts.firstOrNull { it.username == c.username }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .hairlineTop()
                                .testTag("chat:${c.username}")
                                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button) { selected = c.username; actions.openSoulseekConversation(c.username) }
                                .padding(top = 16.dp, end = 12.dp, bottom = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            Avatar(c.username, online = contact?.online, size = 40.dp)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                BasicText(c.username, style = Room.Text, maxLines = 1)
                                BasicText(last?.let { preview(it) } ?: "", style = Room.Label.copy(color = if (c.unread > 0) Room.Ink else Room.Quiet), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Column(Modifier.widthIn(min = 44.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(11.dp)) {
                                last?.let { BasicText(dayLabel(it.timestamp, clock), style = Room.LabelQuiet, maxLines = 1) }
                                if (c.unread > 0) Box(Modifier.size(8.dp).background(Room.Signal).semantics { contentDescription = "${c.unread} unread" })
                            }
                        }
                    }
                }
            }
            // Start a chat: the black "+ new" key, bottom right.
            Column(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(66.dp)
                    .alpha(if (state.soulseekSession.signedIn) 1f else 0.4f)
                    .background(Room.Ink)
                    .clickable(enabled = state.soulseekSession.signedIn, interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button) { newChat = true }
                    .semantics(mergeDescendants = true) { contentDescription = "New chat" }
                    .testTag("new-chat"),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
            ) {
                BasicText("+", style = Room.Title.copy(color = Room.OnInk, lineHeight = 22.sp))
                BasicText("new", style = Room.Label.copy(color = Room.OnInk))
            }
            if (newChat) NewChat(onDismiss = { newChat = false }) { name, friend ->
                if (friend) actions.addSoulseekContact(name)
                actions.openSoulseekConversation(name)
                selected = name
                newChat = false
            }
        } else {
            Chat(peer, state, actions, clock, onBack = { selected = null; actions.closeSoulseekConversation() }, onDig = onDig)
        }
    }
}

/**
 * Soulseek keeps no message history on its servers, so chats live only in Runout's database:
 * a 1 dp amber outline, 11 in, an 8 dp amber lamp beside one 10/400 line, like setup's public
 * folder warning.
 */
@Composable
private fun LocalMessagesNote() {
    Row(
        Modifier
            .fillMaxWidth()
            .border(1.dp, Room.Warning)
            .padding(11.dp)
            .semantics(mergeDescendants = true) { }
            .testTag("messages-local-note"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).background(Room.Warning))
        BasicText(
            "messages stay on this phone; uninstalling sonic deletes them.",
            style = Room.Label,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}

/**
 * A square initial outlined in ink (black with a white letter when there's something unread),
 * with a 9 dp status lamp in its corner: green online, white outlined offline, none for strangers.
 */
@Composable
private fun Avatar(name: String, online: Boolean?, size: Dp, lit: Boolean = false) {
    Box(Modifier.size(size).background(if (lit) Room.Ink else Room.Surface).border(1.dp, Room.Ink), contentAlignment = Alignment.Center) {
        BasicText(name.take(1).uppercase(), style = Room.Strong.copy(fontSize = if (size >= 56.dp) 16.sp else 12.sp, color = if (lit) Room.OnInk else Room.Ink))
        if (online != null) Box(
            Modifier
                .align(Alignment.BottomEnd)
                .size(9.dp)
                .background(if (online) Room.Online else Room.Background)
                .then(if (online) Modifier else Modifier.border(1.dp, Room.Ink)),
        )
    }
}

private fun preview(m: SoulseekMessage): String {
    val card = RecordCard.parse(m.text)
    val text = if (card != null) "♪ ${card.album} — ${card.artist}" else m.text
    return (if (m.outgoing) "you: " else "") + text.lineSequence().first()
}

/** The clock as the phone sets it, without am/pm: "5:14", "17:14". */
@Composable
private fun rememberClock(): (Long) -> String {
    val h24 = android.text.format.DateFormat.is24HourFormat(LocalContext.current)
    return remember(h24) { { t: Long -> SimpleDateFormat(if (h24) "H:mm" else "h:mm", Locale.getDefault()).format(Date(t)) } }
}

/** When the last message came: the time today, "yesterday", the weekday this week, else the date. */
private fun dayLabel(t: Long, clock: (Long) -> String): String {
    fun midnight(c: Calendar) = (c.clone() as Calendar).apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
    val days = Math.round((midnight(Calendar.getInstance()) - midnight(Calendar.getInstance().apply { timeInMillis = t })) / 86_400_000.0)
    return when {
        days <= 0 -> clock(t)
        days == 1L -> "yesterday"
        days < 7 -> SimpleDateFormat("EEE", Locale.getDefault()).format(Date(t)).lowercase().trimEnd('.')
        else -> SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(t)).lowercase()
    }
}

/** The New chat panel: the username field, "add as a friend", and the black start chat key. */
@Composable
private fun NewChat(onDismiss: () -> Unit, onStart: (String, Boolean) -> Unit) {
    var username by rememberSaveable { mutableStateOf("") }
    var friend by rememberSaveable { mutableStateOf(true) }
    PageSheet("new-chat-card", onDismiss, title = "new chat") {
        Field(username, { username = it.take(30) }, "who are you writing to?", label = "soulseek username")
        ToggleRow("Add as a friend", "See when they're online.", friend, { friend = it })
        TeButton("start chat", { onStart(username.trim(), friend) }, Modifier.fillMaxWidth().testTag("start-chat"), enabled = SoulseekPolicy.validUsername(username.trim()))
    }
}

/**
 * One chat: "← chats" and the person's name and lamp over an ink rule, the thread (theirs
 * orange, yours black, records as full-width grey cards), and the composer: the outlined field,
 * the send-a-record key and the black send key. The keyboard's enter key sends too.
 */
@Composable
private fun Chat(peer: String, state: RoomState, actions: RoomActions, clock: (Long) -> String, onBack: () -> Unit, onDig: () -> Unit) {
    androidx.activity.compose.BackHandler(onBack = onBack)
    PageStep(onBack)
    val conversation = state.soulseekConversations.firstOrNull { it.username == peer }
    LaunchedEffect(peer, conversation?.unread) { actions.markSoulseekRead(peer) }
    val contact = state.soulseekContacts.firstOrNull { it.username == peer }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        Row(
            Modifier.fillMaxWidth().hairlineBottom(Room.Ink).padding(bottom = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            BasicText(
                "← chats",
                style = Room.Text,
                modifier = Modifier
                    .testTag("chats-back")
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onBack),
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                BasicText(peer, style = Room.Strong, maxLines = 1)
                if (contact == null) ArrowLink("add friend", { actions.addSoulseekContact(peer) }, tag = "add-friend")
                else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(8.dp).background(if (contact.online) Room.Online else Room.Background).then(if (contact.online) Modifier else Modifier.border(1.dp, Room.Ink)))
                    BasicText(if (contact.online) "online" else "offline", style = Room.Label)
                }
            }
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState(), reverseScrolling = true),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            conversation?.messages.orEmpty().forEach { message ->
                val card = RecordCard.parse(message.text)
                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = if (message.outgoing && card == null) Alignment.End else Alignment.Start,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (card != null) RecordCardView(card, state, mine = message.outgoing) {
                        // The exact folder it came from when the card says; its preview offers
                        // a search instead if that folder or its peer is gone. Older cards search.
                        if (card.peer != null && card.folder != null && state.soulseekSession.signedIn) actions.openSoulseekFolder(card.peer, card.folder)
                        else {
                            actions.setSearchOptions(state.discovery.options.copy(source = app.runout.discovery.SearchSource.SOULSEEK))
                            actions.setSearchQuery("${card.artist} ${card.album}")
                            actions.searchSoulseek("${card.artist} ${card.album}")
                        }
                        onDig()
                    } else BasicText(
                        // Yours solid black with white 10/400; theirs solid orange with black.
                        message.text,
                        style = Room.Label.copy(color = if (message.outgoing) Room.OnInk else Room.Ink),
                        modifier = Modifier
                            .widthIn(max = 300.dp)
                            .background(if (message.outgoing) Room.Ink else Room.Signal)
                            .testTag(if (message.outgoing) "bubble:mine" else "bubble:theirs")
                            .padding(horizontal = 16.dp, vertical = 11.dp),
                    )
                    val pending = message.outgoing && message.state != SoulseekMessageState.SENT
                    BasicText(
                        clock(message.timestamp) + if (pending) " · ${message.state.name.lowercase()}" else "",
                        style = Room.LabelQuiet.copy(color = if (message.state == SoulseekMessageState.FAILED) Room.Danger else Room.Quiet),
                        modifier = if (card != null && message.outgoing) Modifier.align(Alignment.End) else Modifier,
                    )
                }
            }
        }
        // The composer: the field boxed in a 1 dp ink outline, 11 dp in, then the their-shelf key
        // and the black send key; 11 dp clear of the page's left edge and its foot.
        Column(Modifier.padding(start = 11.dp, bottom = 11.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            var text by rememberSaveable(peer) { mutableStateOf("") }
            val bytes = text.trim().toByteArray(Charsets.UTF_8).size
            val canSend = state.soulseekSession.signedIn && text.isNotBlank() && bytes <= 4096
            val send = {
                val submitted = text
                actions.sendSoulseekMessage(peer, submitted) { if (text == submitted) text = "" }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    textStyle = Room.Text,
                    cursorBrush = SolidColor(Room.Signal),
                    maxLines = 5,
                    // The keyboard's enter key sends.
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { if (canSend) send() }),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 44.dp)
                        .border(1.dp, Room.Ink)
                        .testTag("field:Private message")
                        .semantics { contentDescription = "Private message" }
                        .padding(11.dp),
                    decorationBox = { inner ->
                        Box(Modifier.heightIn(min = 22.dp), contentAlignment = Alignment.CenterStart) {
                            if (text.isEmpty()) BasicText("private message", style = Room.Text.copy(color = Room.Faint))
                            inner()
                        }
                    },
                )
                SquareKey("Browse their shelf", { actions.browseSoulseekShelf(peer) }, "browse-shelf", enabled = state.soulseekSession.signedIn, filled = false, glyphSize = 22.dp) { c ->
                    // Their shelf: a crate, open at the top, three sleeves standing in it.
                    val w = size.width
                    val line = 1.5.dp.toPx()
                    val thin = 1.dp.toPx()
                    val crate = Path().apply { moveTo(w * 0.08f, w * 0.42f); lineTo(w * 0.08f, w * 0.92f); lineTo(w * 0.92f, w * 0.92f); lineTo(w * 0.92f, w * 0.42f) }
                    drawPath(crate, c, style = Stroke(line))
                    listOf(0.28f to 0.10f, 0.50f to 0.22f, 0.72f to 0.04f).forEach { (x, top) ->
                        drawRect(c, Offset(w * (x - 0.09f), w * top), androidx.compose.ui.geometry.Size(w * 0.18f, w * (0.86f - top)), style = Stroke(thin))
                    }
                }
                SquareKey("Send message", send, "send-message", enabled = canSend, filled = true) { c ->
                    // An arrow, up.
                    val line = 1.5.dp.toPx()
                    val w = size.width
                    drawLine(c, Offset(w / 2, w * 0.1f), Offset(w / 2, w * 0.9f), line, StrokeCap.Square)
                    drawLine(c, Offset(w / 2, w * 0.1f), Offset(w * 0.18f, w * 0.42f), line, StrokeCap.Square)
                    drawLine(c, Offset(w / 2, w * 0.1f), Offset(w * 0.82f, w * 0.42f), line, StrokeCap.Square)
                }
            }
            if (bytes > 4096) BasicText("messages must contain 1–4,096 bytes of text.", style = Room.Label.copy(color = Room.Danger))
        }
    }
}

/** A 44 dp square key with a drawn glyph (16 dp unless set): black with a white glyph, or bare with an ink one. */
@Composable
private fun SquareKey(
    description: String,
    onClick: () -> Unit,
    tag: String,
    enabled: Boolean,
    filled: Boolean,
    glyphSize: Dp = 16.dp,
    glyph: androidx.compose.ui.graphics.drawscope.DrawScope.(Color) -> Unit,
) {
    Box(
        Modifier
            .size(44.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .background(if (filled) Room.Ink else Color.Transparent)
            .clickable(enabled = enabled, interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(glyphSize)) { glyph(if (filled) Room.OnInk else Room.Ink) }
    }
}

/** "FLAC · 24-bit · 96 kHz" as the tag reads it: "24/96"; else the codec, lowercase. */
private fun specShort(quality: String): Pair<String, Boolean> {
    val bits = Regex("(\\d+)-bit").find(quality)?.groupValues?.get(1)?.toIntOrNull()
    val khz = Regex("([\\d.]+) kHz").find(quality)?.groupValues?.get(1)
    if (bits == null || khz == null) return quality.lowercase() to false
    val hiRes = bits >= 24 || (khz.toDoubleOrNull() ?: 0.0) > 48.0
    return "$bits/${khz.removeSuffix(".0")}" to hiRes
}

/**
 * A record shared in a chat: a full-width grey card with the 110 dp sleeve, the title in bold,
 * the artist, the spec tag and, on cards sent to you, "find on soulseek →".
 */
@Composable
private fun RecordCardView(card: RecordCard, state: RoomState, mine: Boolean, onFind: () -> Unit) {
    val local = state.albums.firstOrNull { it.title.equals(card.album, true) && it.artist.equals(card.artist, true) }
    Row(
        Modifier.fillMaxWidth().background(Room.Surface).padding(16.dp).testTag("record-card"),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (local != null) SleeveArt(local, Modifier.size(110.dp), showSticker = false) else GeneratedSleeve(card.album, card.artist, Modifier.size(110.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            BasicText(card.album, style = Room.Label.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold), maxLines = 2, overflow = TextOverflow.Ellipsis)
            BasicText(card.artist, style = Room.Label.copy(color = Room.Body), maxLines = 1, overflow = TextOverflow.Ellipsis)
            card.quality?.let { val (spec, hiRes) = specShort(it); TeTag(spec, color = if (hiRes) Room.Signal else Room.Ink) }
            if (!mine) {
                if (local != null) BasicText("in your crate", style = Room.LabelQuiet, modifier = Modifier.testTag("find-record"))
                else if (card.peer != null && card.folder != null) ArrowLink("open folder", onFind, tag = "open-record-folder", color = Room.Accent)
                else ArrowLink("find on soulseek", onFind, tag = "find-record", color = Room.Accent)
            }
        }
    }
}

/**
 * A record sent in a Soulseek private message. It travels as ordinary text that any Soulseek
 * client shows sensibly ("♪ Album — Artist · FLAC 24/96"), plus a last line Runout recognises:
 * `runout:record?artist=…&album=…&q=…&u=…&d=…`, URL-encoded. `u` and `d` are the Soulseek peer
 * and folder you downloaded it from, so a tap opens that exact folder; older cards lack them.
 */
internal data class RecordCard(val artist: String, val album: String, val quality: String?, val peer: String? = null, val folder: String? = null) {
    fun encode(): String {
        fun e(v: String) = java.net.URLEncoder.encode(v, "UTF-8")
        val q = quality?.let { "&q=${e(it)}" } ?: ""
        val source = if (peer != null && folder != null) "&u=${e(peer)}&d=${e(folder)}" else ""
        return "♪ $album — $artist" + (quality?.let { " · $it" } ?: "") + "\nsonic:record?artist=${e(artist)}&album=${e(album)}$q$source"
    }

    companion object {
        private val line = Regex("(?:sonic|runout):record\\?artist=([^&\\s]*)&album=([^&\\s]*)(?:&q=([^&\\s]*))?(?:&u=([^&\\s]*))?(?:&d=([^&\\s]*))?")
        fun of(album: app.runout.model.Album, peer: String? = null, folder: String? = null) = RecordCard(album.artist, album.title, album.quality.label, peer, folder)
        fun parse(text: String): RecordCard? {
            val m = line.find(text) ?: return null
            fun d(v: String) = runCatching { java.net.URLDecoder.decode(v, "UTF-8") }.getOrDefault(v)
            fun group(i: Int) = m.groupValues.getOrNull(i)?.takeIf { it.isNotBlank() }?.let(::d)?.takeIf { it.isNotBlank() }
            val artist = d(m.groupValues[1]); val album = d(m.groupValues[2])
            if (artist.isBlank() || album.isBlank()) return null
            val peer = group(4); val folder = group(5)
            // A peer without a folder (or the reverse) can't open anything: treat it as an old card.
            val source = peer != null && folder != null && SoulseekPolicy.validUsername(peer)
            return RecordCard(artist, album, group(3), peer.takeIf { source }, folder.takeIf { source })
        }
    }
}

/** Whether Android's "Background usage" is off for Runout. */
@Composable
private fun rememberBackgroundRestricted(): Boolean {
    val context = LocalContext.current
    return remember {
        android.os.Build.VERSION.SDK_INT >= 28 &&
            context.getSystemService(android.app.ActivityManager::class.java)?.isBackgroundRestricted == true
    }
}

/**
 * When Android's "Background usage" is off for Runout, downloads stop the moment you leave the app
 * (they come back as "Interrupted system call"). Say so where the downloads are, in amber, with a
 * way to fix it.
 */
@Composable
private fun BackgroundUseNote(onHelp: () -> Unit) {
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxWidth()
            .background(Room.Warning)
            .padding(16.dp)
            .testTag("background-restricted"),
        verticalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
            BasicText("BACKGROUND USAGE IS OFF", style = Room.Strong)
            InfoDot("background usage", onHelp)
        }
        BasicText(
            "android stops downloads as soon as you leave the app. keep sonic open, or allow background usage: app info › battery › allow background usage. interrupted downloads continue when you come back.",
            style = Room.Label.copy(lineHeight = 15.sp),
        )
        TeButton("open app info", {
            runCatching {
                context.startActivity(
                    android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", context.packageName, null))
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }, Modifier.testTag("open-app-info"))
    }
}

/**
 * Friends › a friend › their shelf: every FLAC folder they share, from one browse of their share.
 * A folder opens in Dig's folder view, with its tracks and the key to download it.
 */
@Composable
private fun FriendShelf(shelf: app.runout.soulseek.SoulseekShelf, signedIn: Boolean, ours: app.runout.soulseek.PortState, onBack: () -> Unit, onOpen: (String) -> Unit) {
    androidx.activity.compose.BackHandler(onBack = onBack)
    PageStep(onBack)
    Column(Modifier.fillMaxSize().testTag("friend-shelf"), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        Row(Modifier.fillMaxWidth().hairlineBottom(Room.Ink).padding(bottom = 11.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            BasicText(
                "← chat", style = Room.Text,
                modifier = Modifier.testTag("shelf-back").clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onBack),
            )
            BasicText("${shelf.username}'s shelf", style = Room.Strong, maxLines = 1)
        }
        when {
            shelf.loading -> Column(Modifier.fillMaxWidth().padding(top = 44.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                DotVinylSpinner(Modifier.size(132.dp), description = "Reading their shelf")
                BasicText("reading their shelf…", style = Room.LabelQuiet)
            }
            shelf.folders.isEmpty() -> BasicText(
                shelf.problem(signedIn, ours).orEmpty(),
                style = Room.Label.copy(color = Room.Body, lineHeight = 15.sp), modifier = Modifier.testTag(if (shelf.error != null) "shelf-error" else "shelf-empty"),
            )
            else -> {
                BasicText("${shelf.folders.size} flac folders · ${shelf.folders.sumOf { it.tracks.size }} tracks", style = Room.LabelQuiet)
                androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                    items(shelf.folders.size) { i ->
                        val f = shelf.folders[i]
                        val bits = f.tracks.mapNotNull { it.bitDepth }.maxOrNull()
                        val rate = f.tracks.mapNotNull { it.sampleRate }.maxOrNull()
                        val quality = listOfNotNull(bits?.let { "$it-bit" }, rate?.let { app.runout.model.AudioQuality.formatRate(it) }).joinToString(" · ")
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .hairlineTop()
                                .testTag("shelf-folder:${f.title}")
                                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button) { onOpen(f.directory) }
                                .padding(vertical = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            BasicText(f.title.lowercase(), style = Room.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (f.parent.isNotBlank()) BasicText(f.parent.lowercase(), style = Room.LabelQuiet, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            BasicText(listOf("${f.tracks.size} tracks", quality, sizeLabel(f.size)).filter { it.isNotBlank() }.joinToString(" · "), style = Room.Label.copy(color = Room.Body))
                        }
                    }
                }
            }
        }
    }
}
