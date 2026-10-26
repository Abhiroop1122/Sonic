package app.runout.ui.settings

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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
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
import app.runout.BuildConfig
import app.runout.soulseek.FailureKind
import app.runout.soulseek.Recovery
import app.runout.soulseek.SoulseekConnection
import app.runout.soulseek.SoulseekMessage
import app.runout.soulseek.SoulseekMessageState
import app.runout.soulseek.SoulseekPolicy
import app.runout.soulseek.SoulseekSession
import app.runout.soulseek.SoulseekTransfer
import app.runout.soulseek.SoulseekTransferState
import app.runout.ui.*
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// The help panels behind each TE Help "?" on these pages, worded as on the TE · Help panels board.
private val HelpSharing = TeHelp(
    "help · soulseek › sharing", "share selected flac folder",
    listOf(
        "other soulseek users can browse the folder you pick and download from it.",
        "only that folder is shared, never the rest of your phone. sharing helps your downloads move faster.",
    ),
)

private val HelpWifiOnly = TeHelp(
    "help · soulseek › wi-fi only", "share on wi-fi only",
    listOf(
        "on mobile data, nobody can start a download from you. an upload that's already running is allowed to finish.",
        "sharing picks up again by itself when you're back on wi-fi or ethernet.",
    ),
)

private val HelpBackground = TeHelp(
    "help · soulseek › background", "stay online in the background",
    listOf(
        "keeps you signed in to soulseek after you leave the app, so downloads and messages keep arriving.",
        "it uses a little battery and shows a quiet notification while it runs.",
    ),
)

private val HelpEncryption = TeHelp(
    "help · soulseek › security", "not encrypted",
    listOf(
        "soulseek sends your id, password and private messages without encryption.",
        "use a password you don't use anywhere else, and don't share anything private in chats.",
    ),
)

/**
 * Settings › Soulseek, below the back link: the intro, then four numbered sections (account,
 * downloads, sharing, connection) ruled in hairlines, and the amber "not encrypted" note.
 */
@Composable
fun SoulseekAccountPanel(state: RoomState, actions: RoomActions, onOffline: () -> Unit, onSignIn: () -> Unit = {}, onDownloads: () -> Unit = {}) {
    val session = state.soulseekSession
    var help by remember { mutableStateOf<TeHelp?>(null) }
    Column(Modifier.fillMaxWidth().testTag("panel:account"), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        BasicText(
            "your local flac library is yours to play without an account. sign in to search soulseek, download from soulseek or the internet archive, and message people.",
            style = Room.Label.copy(color = Room.Body, lineHeight = 15.sp),
        )
        PageSection(if (session.hasAccount) "01 account · ${session.username}" else "01 account") {
            Row(
                Modifier.fillMaxWidth().hairlineTop().hairlineBottom().padding(vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(11.dp),
            ) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(8.dp).background(if (session.hasAccount) lampColor(session) else Room.Faint))
                    BasicText(
                        when {
                            !session.hasAccount -> "not signed in"
                            session.connected -> "connected to soulseek"
                            session.error != null -> session.error.lowercase()
                            session.state == SoulseekConnection.CONNECTING -> "connecting to soulseek"
                            session.state == SoulseekConnection.RECONNECTING -> "reconnecting to soulseek"
                            else -> "connection paused"
                        },
                        style = Room.Label.copy(color = if (session.hasAccount && session.error != null) Room.Danger else Room.Ink, lineHeight = 15.sp),
                        modifier = Modifier.testTag("account-connection"),
                    )
                }
                if (session.hasAccount) {
                    if (!session.connected) TeButton("reconnect", actions::reconnectSoulseek, kind = KeyKind.OUTLINE, enabled = session.state != SoulseekConnection.RECONNECTING)
                    TeButton("sign out", actions::signOutSoulseek, kind = KeyKind.OUTLINE)
                } else {
                    // One sign-in for the whole app: the same screen as onboarding, always remembered.
                    TeButton("sign in with soulseek", onSignIn, Modifier.testTag("account-sign-in"))
                }
            }
            if (!session.hasAccount) {
                state.soulseekSavedUsername?.let { TeLink("sign in again as $it", actions::signInSavedSoulseek, tag = "account-saved") }
                TeLink("continue offline", onOffline, tag = "account-offline")
            }
        }
        NetworkNote(state, actions)
        PageSection("02 downloads") {
            HelpToggle(
                "Turtle mode",
                "One download at a time, gentler on your connection and on the people sharing.",
                state.turtleMode, actions::setTurtleMode, tag = "settings-turtle",
            )
            TeButton("downloads", onDownloads, Modifier.testTag("settings-downloads"), kind = KeyKind.OUTLINE)
        }
        PageSection("03 sharing") {
            BasicText(
                "choose a folder to share. only validated flac files in that folder are visible to other users.",
                style = Room.Label.copy(color = Room.Body, lineHeight = 15.sp),
            )
            if (state.sharedFolder == null) TeButton("choose sharing folder", actions::pickSharedFolder, Modifier.fillMaxWidth().testTag("shared-folder"), kind = KeyKind.OUTLINE)
            else FolderRow(state.sharedFolder, actions::pickSharedFolder, "shared-folder")
            HelpToggle(
                "Share selected FLAC folder",
                "Other Soulseek users can search and download files you explicitly share.",
                state.soulseekShareConsent, actions::setSoulseekSharing,
                enabled = session.hasAccount && (state.soulseekShareConsent || (session.connected && state.sharedFolder != null)),
            ) { help = HelpSharing }
            if (state.soulseekShareConsent && state.soulseekWaitingForWifi) BasicText("sharing is paused until you're on wi-fi.", style = Room.LabelQuiet.copy(lineHeight = 15.sp), modifier = Modifier.testTag("sharing-waiting-wifi"))
            else if (state.soulseekShareConsent && !state.soulseekSharing) BasicText("sharing is paused. it resumes after connection and folder checks succeed.", style = Room.LabelQuiet.copy(lineHeight = 15.sp))
            // How you share only matters once you do: greyed out until sharing is on.
            val sharing = session.hasAccount && state.soulseekShareConsent
            HelpToggle(
                "Share on Wi-Fi only",
                "Uploads stop on mobile data and start again on Wi-Fi.",
                state.soulseekWifiOnly, actions::setSoulseekWifiOnly,
                enabled = sharing,
            ) { help = HelpWifiOnly }
            Column(
                Modifier.fillMaxWidth().alpha(if (sharing) 1f else 0.4f).hairlineTop().padding(vertical = 16.dp).testTag("upload-slots"),
                verticalArrangement = Arrangement.spacedBy(11.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    BasicText("uploads at once", style = Room.Text)
                    BasicText("how many people can download from you at the same time. fewer is easier on a slow connection.", style = Room.LabelQuiet)
                }
                Segments(app.runout.soulseek.SoulseekPreferences.UPLOAD_SLOT_CHOICES, state.soulseekUploadSlots, { "$it" }, tag = { "upload-slots:$it" }) {
                    if (sharing) actions.setSoulseekUploadSlots(it)
                }
            }
            TransferTotalsBlock(state.soulseekTotals)
        }
        PageSection("04 in the background") {
            HelpToggle(
                "Stay online in the background",
                "Keeps private messages and sharing available with an ongoing notification you can disconnect from.",
                state.soulseekBackgroundConsent, actions::setSoulseekStayOnline,
                enabled = session.hasAccount && (state.soulseekBackgroundConsent || session.connected),
            ) { help = HelpBackground }
            if (state.soulseekBackgroundConsent && !state.soulseekStayOnline) BasicText("background mode is paused. check notification permission and reconnect to resume.", style = Room.LabelQuiet.copy(lineHeight = 15.sp))
        }
        // The one warning on the page: amber, a bold "!", and its "?".
        Row(Modifier.fillMaxWidth().background(Room.Warning).padding(11.dp), horizontalArrangement = Arrangement.spacedBy(11.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicText("!", style = Room.Heading.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold))
            BasicText("soulseek's protocol does not encrypt sign-in credentials or private messages.", style = Room.Label.copy(lineHeight = 15.sp), modifier = Modifier.weight(1f))
            InfoDot("encryption") { help = HelpEncryption }
        }
    }
    TeHelpSheet(help) { help = null }
}

/** A folder between hairlines: its path in 12/400 and "change →" (or "choose →") at the right. */
@Composable
internal fun FolderRow(folder: String?, onPick: () -> Unit, tag: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .hairlineTop()
            .hairlineBottom()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onPick)
            .testTag(tag)
            .padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        BasicText(folder?.let(::folderLabel) ?: "no folder yet", style = Room.Text.copy(color = if (folder == null) Room.Quiet else Room.Ink), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        BasicText(if (folder == null) "choose" else "change", style = Room.Text)
        BasicText("→", style = Room.Text)
    }
}

/** A folder picked through Android's picker, as a path: "content://…/tree/primary%3AMusic" reads "/Music". */
internal fun folderLabel(uri: String): String {
    val decoded = runCatching { android.net.Uri.decode(uri) }.getOrNull() ?: uri
    val tail = decoded.substringAfterLast("/tree/").substringAfterLast(':')
    return "/" + tail.trimStart('/')
}

/** What this account has given and taken on this phone: the uploads are the "giving something back". */
@Composable
private fun TransferTotalsBlock(totals: app.runout.soulseek.TransferTotals) {
    fun files(n: Int) = if (n == 1) "1 file" else "$n files"
    Column(Modifier.fillMaxWidth().hairlineTop().padding(vertical = 16.dp).testTag("transfer-totals"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BasicText("uploaded to others", style = Room.Text)
        BasicText("${app.runout.ui.sizeLabel(totals.uploadedBytes)} · ${files(totals.uploadedFiles)}", style = Room.Label.copy(color = Room.Body), modifier = Modifier.testTag("totals-uploaded"))
        BasicText("downloaded", style = Room.Text, modifier = Modifier.padding(top = 5.dp))
        BasicText("${app.runout.ui.sizeLabel(totals.downloadedBytes)} · ${files(totals.downloadedFiles)}", style = Room.Label.copy(color = Room.Body), modifier = Modifier.testTag("totals-downloaded"))
        val since = totals.since.takeIf { it > 0 }?.let {
            java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                .format(java.time.format.DateTimeFormatter.ofPattern("d MMMM yyyy", java.util.Locale.ENGLISH)).lowercase()
        }
        BasicText(since?.let { "on this phone since $it." } ?: "counted on this phone from your first transfer.", style = Room.LabelQuiet)
    }
}

/**
 * Whether other people can reach this phone: the router's answer when Sonic asked it to forward
 * its port. Reachable, two people behind routers can still exchange files; not, only with people
 * whose own port is open. When the router won't say yes, how to forward the port by hand.
 */
@Composable
private fun ReachabilityLine(port: app.runout.soulseek.PortState) {
    val (lamp, text) = when (port) {
        is app.runout.soulseek.PortState.Open -> Room.Online to "others can reach you: your router forwards port ${port.port} (${port.via})" + (port.external?.let { " at $it" } ?: "") + "."
        is app.runout.soulseek.PortState.BehindCgnat -> Room.Warning to "your router opened port ${port.port}, but your internet provider has its own router in front of it" +
            " (your router is ${port.router} outside" + (port.public?.let { ", the internet sees $it" } ?: "") + "), and that one lets no one in." +
            " ask your provider for a public ip address. until then you can download from people whose port is open."
        app.runout.soulseek.PortState.Mapping -> Room.Quiet to "asking your router to open a port…"
        app.runout.soulseek.PortState.MobileData -> Room.Warning to "on mobile data no one can reach you: you can download, but phone-to-phone sharing waits for wi-fi."
        is app.runout.soulseek.PortState.Closed -> Room.Warning to "others can't reach you: ${port.reason} you can still download from people whose port is open."
        app.runout.soulseek.PortState.Off -> Room.Quiet to "not connected to a network."
    }
    Column(Modifier.fillMaxWidth().hairlineTop().padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().testTag("reachability"), horizontalArrangement = Arrangement.spacedBy(11.dp), verticalAlignment = Alignment.Top) {
            Box(Modifier.padding(top = 4.dp).size(8.dp).background(lamp))
            BasicText(text, style = Room.Label.copy(color = Room.Body, lineHeight = 15.sp), modifier = Modifier.weight(1f))
        }
        if (port is app.runout.soulseek.PortState.Closed && port.port > 0 && port.local != null) {
            // Sonic keeps its port from one sign-in to the next, so a forward made by hand keeps working.
            BasicText(
                "or forward it by hand: in your router's settings, send tcp port ${port.port} to ${port.local}, this phone. " +
                    "sonic always listens on that port. reserve the address for this phone too, so the router doesn't hand it to another device.",
                style = Room.LabelQuiet.copy(lineHeight = 15.sp),
                modifier = Modifier.padding(start = 19.dp).testTag("port-forward-hint"),
            )
        }
    }
}

/** The Settings list's line for Connection: whether others can reach this phone, in a few words. */
internal fun connectionSummary(state: RoomState): String {
    if (!state.soulseekSession.connected) return if (state.soulseekSession.hasAccount) "offline" else "not signed in"
    return when (val p = state.soulseekReachability) {
        is app.runout.soulseek.PortState.Open -> "reachable · port ${p.port}"
        is app.runout.soulseek.PortState.Closed -> "not reachable" + (if (p.port > 0) " · port ${p.port}" else "")
        is app.runout.soulseek.PortState.BehindCgnat -> "blocked by your provider"
        app.runout.soulseek.PortState.MobileData -> "mobile data · not reachable"
        app.runout.soulseek.PortState.Mapping -> "checking…"
        app.runout.soulseek.PortState.Off -> "no network"
    }
}

/**
 * Settings › Connection: 01 whether others can reach you (and how to fix it when not), 02 what that
 * changes, and 03 what mobile data means for it.
 */
@Composable
internal fun ConnectionPage(state: RoomState) {
    PageSection("01 can others reach you") {
        if (state.soulseekSession.connected) ReachabilityLine(state.soulseekReachability)
        else Row(Modifier.fillMaxWidth().hairlineTop().padding(vertical = 16.dp).testTag("reachability"), horizontalArrangement = Arrangement.spacedBy(11.dp), verticalAlignment = Alignment.Top) {
            Box(Modifier.padding(top = 4.dp).size(8.dp).background(Room.Quiet))
            BasicText(
                if (state.soulseekSession.hasAccount) "connect to soulseek to check whether others can reach you." else "sign in to soulseek to check whether others can reach you.",
                style = Room.Label.copy(color = Room.Body, lineHeight = 15.sp), modifier = Modifier.weight(1f),
            )
        }
    }
    PageSection("02 why it matters") {
        BasicText(
            "to browse a shelf or download, one of the two phones has to let the other in. " +
                "when yours can be reached, people whose router is closed can still get your files, and you theirs. " +
                "when neither can, nothing goes through. sonic asks your router to open its port on its own (upnp or nat-pmp).",
            style = Room.Label.copy(color = Room.Body, lineHeight = 15.sp),
        )
    }
    PageSection("03 on mobile data") {
        BasicText(
            "carriers put every phone behind their own router, so on mobile data no one can reach you, and sonic can't change that. " +
                "you can still browse and download from anyone whose port is open, a phone at home with its port open included. " +
                "sharing waits for wi-fi when \"share on wi-fi only\" is on.",
            style = Room.Label.copy(color = Room.Body, lineHeight = 15.sp), modifier = Modifier.testTag("mobile-data-note"),
        )
    }
}
