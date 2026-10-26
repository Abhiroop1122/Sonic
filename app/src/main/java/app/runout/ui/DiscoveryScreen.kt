package app.runout.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.runout.discovery.*
import app.runout.soulseek.*
import java.util.Locale

/*
 * Dig, drawn to the TE · Dig boards in runout-te.pen: the underlined search field with its square
 * black key, the two-cell source switch, then either the big grey "?" hint, the albums on this
 * device, or Soulseek's album folders as two compact lines each. The field, the switch and the
 * results head stay put; only the list under them scrolls. The page head is the shell's.
 */

/** The peers help, from the TE · Help panels board. */
private val PeersHelp = TeHelp(
    "help · dig › results",
    "peers",
    listOf(
        "the number of people sharing this exact album folder.",
        "more peers usually means a faster, more reliable download. green: at least one is online now.",
    ),
)

/** The free upload slots help, from the TE · Help panels board. */
private val FreeSlotsHelp = TeHelp(
    "help · dig › filters",
    "free upload slots only",
    listOf(
        "shows only people who can send to you right now.",
        "others put you in a queue; that album may wait minutes or hours to start.",
    ),
)

/** Dig's body: search, source, then the hint or the results; the black "sort" key floats bottom right. */
@Composable
internal fun DiscoveryScreen(state: RoomState, actions: RoomActions, onAccount: () -> Unit, onCue: () -> Unit) {
    val discovery = state.discovery
    val options = discovery.options
    val session = state.soulseekSession
    // Two places to dig: this device, or Soulseek. (An older "all sources" choice reads as Soulseek.)
    val source = if (options.source == SearchSource.LOCAL) SearchSource.LOCAL else SearchSource.SOULSEEK
    var filters by rememberSaveable { mutableStateOf(false) }
    var help by remember { mutableStateOf<TeHelp?>(null) }
    val keyboard = LocalSoftwareKeyboardController.current
    val searching = discovery.online.phase == SearchPhase.SEARCHING
    val online = source == SearchSource.SOULSEEK && session.connected
    val submit = { if (discovery.query.trim().isNotEmpty()) actions.searchSoulseek(discovery.query) }
    val peers = remember(discovery.remote) { peerCounts(discovery.remote) }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().testTag("screen:search"), verticalArrangement = Arrangement.spacedBy(22.dp)) {
            SearchField(
                query = discovery.query,
                onChange = actions::setSearchQuery,
                onSubmit = { if (online && !searching) submit() else if (source == SearchSource.LOCAL) keyboard?.hide() },
            ) {
                if (source == SearchSource.LOCAL) {
                    // This device is searched as you type; the key just puts the keyboard away.
                    SearchKey(searching = false, enabled = discovery.query.isNotBlank(), onSearch = { keyboard?.hide() }, onStop = {})
                } else {
                    SearchKey(searching, enabled = online && discovery.query.trim().toByteArray().size >= 2, onSearch = submit, onStop = actions::stopSoulseekSearch)
                }
            }
            Segments(
                listOf(SearchSource.LOCAL, SearchSource.SOULSEEK),
                source,
                { if (it == SearchSource.LOCAL) "on this device" else "soulseek" },
                onSelect = { actions.setSearchOptions(options.copy(source = it, kind = SearchKind.ALBUMS)) },
            )
            if (source == SearchSource.SOULSEEK && !session.connected) SignedOutNote(session, actions, onAccount)
            when {
                discovery.query.isBlank() -> Hint(Modifier.weight(1f))
                source == SearchSource.LOCAL -> LocalResults(discovery, actions, onCue, Modifier.weight(1f))
                discovery.online.query.isNotBlank() -> RemoteResults(discovery, peers, session.connected, actions, submit, { help = PeersHelp }, Modifier.weight(1f))
                else -> Hint(Modifier.weight(1f), "press the search key to dig soulseek for these words.")
            }
        }
        // Filters & sort: the black "sort" key, bottom right above the nav.
        if (source == SearchSource.SOULSEEK) FiltersKey(Modifier.align(Alignment.BottomEnd).padding(end = 2.dp, bottom = 8.dp)) { filters = true }
        // The page holds one sheet at a time: the help steps in front of the filters and hands back.
        if (filters && help == null) FiltersCard(options, actions, onHelp = { help = FreeSlotsHelp }) { filters = false }
        TeHelpSheet(help) { help = null }
    }
}

internal fun plural(n: Int, word: String) = if (n == 1) "1 $word" else "$n ${word}s"

/** The folder's own name, as peers sharing "the exact album folder" would all have it. */
private fun folderKey(directory: String) = directory.replace('\\', '/').substringAfterLast('/').trim().lowercase(Locale.ROOT)

/** For each folder name: how many people share it, and whether any of them has a free slot. */
private fun peerCounts(remote: List<RemoteAlbum>): Map<String, Pair<Int, Boolean>> =
    remote.groupBy { folderKey(it.directory) }.mapValues { (_, folders) -> folders.map { it.username }.distinct().size to folders.any { it.available } }

/**
 * TE search field: the 16/400 query (placeholder in #B2B2B2) and the 44 dp black key at the right,
 * on one 1 dp ink rule.
 */
@Composable
private fun SearchField(query: String, onChange: (String) -> Unit, onSubmit: () -> Unit, key: @Composable () -> Unit) {
    val placeholder = "Search albums or artists"
    Row(
        Modifier.fillMaxWidth().height(44.dp).hairlineBottom(Room.Ink),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = query,
            onValueChange = onChange,
            singleLine = true,
            textStyle = Room.Heading,
            cursorBrush = SolidColor(Room.Signal),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
            modifier = Modifier
                .weight(1f)
                .testTag("field:$placeholder")
                .semantics { contentDescription = placeholder },
            decorationBox = { inner ->
                Box {
                    if (query.isEmpty()) BasicText(placeholder.lowercase(), style = Room.Heading.copy(color = Room.Faint), maxLines = 1)
                    inner()
                }
            },
        )
        key()
    }
}

/** The square black key at the end of the field: a search glass, and while searching, a stop square. */
@Composable
private fun SearchKey(searching: Boolean, enabled: Boolean, onSearch: () -> Unit, onStop: () -> Unit) {
    val tick = rememberTick()
    val on = searching || enabled
    Box(
        Modifier
            .size(44.dp)
            .background(Room.Ink, RectangleShape)
            .clickable(enabled = on, interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button) { tick(); if (searching) onStop() else onSearch() }
            .semantics { contentDescription = if (searching) "Stop searching" else "Search Soulseek" }
            .testTag(if (searching) "stop-search" else "submit-search"),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(20.dp)) {
            if (searching) {
                val s = size.width * 0.5f
                drawRect(Room.OnInk, Offset((size.width - s) / 2, (size.height - s) / 2), Size(s, s))
            } else drawNavIcon(Panel.DIG, Room.OnInk)
        }
    }
}

/**
 * A row of equal cells in a 1 dp ink outline; the chosen one is ink with paper-coloured 10/400 type. With
 * [colour], each cell has its own: the chosen one filled with it, the others lettered in it.
 */
@Composable
internal fun <T> Segments(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    tag: (T) -> String? = { null },
    colour: (T) -> Color = { Room.Ink },
    onSelect: (T) -> Unit,
) {
    val tick = rememberTick()
    Row(Modifier.fillMaxWidth().border(1.dp, Room.Ink)) {
        options.forEach { o ->
            val on = o == selected
            Box(
                Modifier
                    .weight(1f)
                    .background(if (on) colour(o) else Room.Background)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Tab) { tick(); onSelect(o) }
                    .semantics { this.selected = on }
                    .then(tag(o)?.let { Modifier.testTag(it) } ?: Modifier)
                    .padding(vertical = 11.dp),
                contentAlignment = Alignment.Center,
            ) {
                // On ink, the paper colour; on a signal colour, white in either theme.
                BasicText(label(o), style = Room.Label.copy(color = if (!on) colour(o) else if (colour(o) == Room.Ink) Room.OnInk else Color.White), maxLines = 1)
            }
        }
    }
}

/** Where the empty page's mark sits: low in the free space, about 60 % down rather than centred. */
private val LowInSpace = BiasAlignment(horizontalBias = -1f, verticalBias = 0.2f)

/** The empty area under the field: one mark and its line, set low; clipped when the keyboard squeezes it. */
@Composable
private fun LowMark(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Box(modifier.fillMaxWidth().clipToBounds(), contentAlignment = LowInSpace) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
    }
}

/** Nothing typed yet: the big grey "?" and one line on what Dig finds. */
@Composable
private fun Hint(modifier: Modifier, text: String = "albums only, always flac. try an artist and an album title.") {
    LowMark(modifier) {
        BasicText("?", style = Room.Display.copy(fontSize = 132.sp, color = Room.Hairline))
        BasicText(text, style = Room.Text.copy(color = Room.Body, lineHeight = 17.sp), modifier = Modifier.fillMaxWidth())
    }
}

/** A Soulseek search under way with nothing back yet: the dotted vinyl spinning where the "?" sits. */
@Composable
private fun Digging(query: String, modifier: Modifier) {
    LowMark(modifier) {
        DotVinylSpinner(Modifier.size(132.dp))
        BasicText("digging soulseek for “${query.trim()}”…", style = Room.Label.copy(color = Room.Body, lineHeight = 15.sp), modifier = Modifier.fillMaxWidth())
    }
}

/** Soulseek is offline or signed out: a 10/400 line and the key that connects. */
@Composable
private fun SignedOutNote(session: SoulseekSession, actions: RoomActions, onAccount: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
        val line = if (session.hasAccount) session.error ?: "your account is kept. reconnect to search online." else "sign in to soulseek to dig its album folders."
        BasicText(line, style = Room.Label.copy(color = Room.Body, lineHeight = 15.sp))
        TeButton(
            if (session.hasAccount) "reconnect soulseek" else "connect soulseek",
            { if (session.hasAccount) actions.reconnectSoulseek() else onAccount() },
            Modifier.fillMaxWidth(),
            enabled = session.state != SoulseekConnection.RECONNECTING,
        )
    }
}

/** Albums on this device: the count, pinned, then one hairlined row per album; only the rows scroll. */
@Composable
private fun LocalResults(discovery: DiscoveryState, actions: RoomActions, onCue: () -> Unit, modifier: Modifier) {
    val albums = discovery.local.filter { it.track == null }.ifEmpty { discovery.local.distinctBy { it.album.id }.map { it.copy(track = null) } }
    Column(modifier.fillMaxWidth()) {
        BasicText("on this device · ${plural(albums.size, "album")}", style = Room.LabelQuiet, modifier = Modifier.padding(bottom = 22.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("local-results"), contentPadding = PaddingValues(bottom = 22.dp)) {
            items(albums, key = { "local:${it.id}" }) { hit -> LocalAlbumCard(hit, actions, onCue) }
            if (albums.isEmpty()) item("no-local") {
                BasicText("no albums here match. try fewer words, or dig on soulseek.", style = Room.Label.copy(color = Room.Body))
            } else item("local-end") { Box(Modifier.fillMaxWidth().height(1.dp).background(Room.Hairline)) }
        }
    }
}

/**
 * An album on this device: the 88 dp sleeve, then title 12/400, artist 10/400, the spec tag
 * (orange when hi-res) and "put on the platter →", which cues it on the platter at side A.
 */
@Composable
private fun LocalAlbumCard(hit: LocalMatch, actions: RoomActions, onCue: () -> Unit) {
    val tick = rememberTick()
    val cue = { tick(); actions.cueTrack(hit.album, hit.track?.id); onCue() }
    val quality = hit.album.quality
    val short = quality.shortLabel
    val spec = if (short.equals(quality.codec, ignoreCase = true)) quality.codec.lowercase() else "${quality.codec.lowercase()} · $short"
    Row(
        Modifier
            .fillMaxWidth()
            .hairlineTop()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = cue)
            .padding(vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SleeveArt(hit.album, Modifier.size(88.dp), showSticker = false)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(11.dp)) {
            BasicText(hit.album.title.lowercase(), style = Room.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            BasicText(hit.album.artist.lowercase(), style = Room.Label.copy(color = Room.Body), maxLines = 1, overflow = TextOverflow.Ellipsis)
            TeTag(spec, color = if (quality.isHiRes) Room.Signal else Room.Ink)
            // The record goes on the platter cued; the deck plays it.
            Row(
                Modifier
                    .testTag("cue-local:${hit.id}")
                    .semantics(mergeDescendants = true) {}
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = cue),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText("put on the platter", style = Room.Text)
                BasicText("→", style = Room.Text)
            }
        }
    }
}

/**
 * Soulseek's album folders: the count with "peers ?", the search's state in one 10/400 line, and
 * any key the state needs (retry, broaden), all pinned; then two compact lines per folder, the only
 * part that scrolls. Until the first folder arrives, the dotted vinyl spins in the list's place.
 */
@Composable
private fun RemoteResults(
    discovery: DiscoveryState,
    peers: Map<String, Pair<Int, Boolean>>,
    connected: Boolean,
    actions: RoomActions,
    submit: () -> Unit,
    onPeersHelp: () -> Unit,
    modifier: Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(bottom = 22.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth().padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicText("soulseek · ${plural(discovery.remote.size, "album folder")}", style = Room.LabelQuiet, modifier = Modifier.weight(1f))
                // The "?" box sits in a 32 dp touch target: 8 dp of it are already the gap.
                BasicText("peers", style = Room.LabelQuiet, modifier = Modifier.offset(x = 2.dp))
                InfoDot("peers", onPeersHelp)
            }
            BasicText(searchStatus(discovery), style = Room.Label.copy(color = Room.Body), modifier = Modifier.testTag("search-status"))
            if (discovery.online.limited) BasicText("showing the first 15,000 flac matches. refine your search for a closer shortlist.", style = Room.Label.copy(color = Room.Body))
            if (discovery.online.widened) BasicText(
                "no one answered these exact words: soulseek hides searches for some names. sonic searched again with one word left out, and keeps only folders that match all of them.",
                style = Room.Label.copy(color = Room.Body), modifier = Modifier.testTag("search-widened"),
            )
            val retry = discovery.online.phase in setOf(SearchPhase.FAILED, SearchPhase.INTERRUPTED)
            val broaden = discovery.remote.isEmpty() && discovery.online.phase == SearchPhase.COMPLETE
            if (retry || broaden) Row(Modifier.padding(top = 5.dp), horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                if (retry) TeButton("retry search", submit, kind = KeyKind.OUTLINE, enabled = connected)
                if (broaden) TeButton("broaden search terms", actions::broadenSearch, kind = KeyKind.OUTLINE)
            }
        }
        if (discovery.online.phase == SearchPhase.SEARCHING && discovery.remote.isEmpty()) {
            Digging(discovery.online.query, Modifier.weight(1f))
        } else {
            // Room under the last row for the floating 66 dp "sort" key and its 22 dp gap, so it never covers the last size.
            LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("remote-results"), contentPadding = PaddingValues(bottom = 66.dp + 22.dp)) {
                itemsIndexed(discovery.remote, key = { _, it -> "folder:${it.id}" }) { i, group ->
                    val (count, free) = peers[folderKey(group.directory)] ?: (1 to group.available)
                    RemoteAlbumCard(i + 1, group, count, free, enabled = connected) { actions.previewSoulseekFolder(group.tracks.first()) }
                }
                if (discovery.remote.isNotEmpty()) item("online-end") { Box(Modifier.fillMaxWidth().height(1.dp).background(Room.Hairline)) }
            }
        }
    }
}

/**
 * An album folder on Soulseek, two lines and no art. Line one: index, title 12/400 and the size.
 * Line two (under the title): artist, the spec (orange when 24-bit) and the peers. The whole row
 * opens the folder preview.
 */
@Composable
private fun RemoteAlbumCard(index: Int, group: RemoteAlbum, peers: Int, free: Boolean, enabled: Boolean, onPreview: () -> Unit) {
    val tick = rememberTick()
    val hiRes = (group.bits ?: 0) >= 24
    Column(
        Modifier
            .fillMaxWidth()
            .hairlineTop()
            .clickable(enabled = enabled, interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button) { tick(); onPreview() }
            .testTag("preview-folder:${group.id}")
            .padding(vertical = 11.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(end = 12.dp), horizontalArrangement = Arrangement.spacedBy(11.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicText("%02d".format(index), style = Room.LabelQuiet, modifier = Modifier.width(22.dp))
            BasicText(group.album.lowercase(), style = Room.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            BasicText(sizeLabel(group.bytes), style = Room.Label.copy(textAlign = TextAlign.End), maxLines = 1, modifier = Modifier.width(64.dp))
        }
        Row(Modifier.fillMaxWidth().padding(start = 33.dp, end = 12.dp), horizontalArrangement = Arrangement.spacedBy(11.dp), verticalAlignment = Alignment.CenterVertically) {
            group.parent?.let { BasicText(it.lowercase(), style = Room.Label.copy(color = Room.Body), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 120.dp)) }
            BasicText(
                listOfNotNull(qualityShort(group.bits, group.rate), plural(group.tracks.size, "track")).joinToString(" · "),
                style = Room.Label.copy(color = if (hiRes) Room.Signal else Room.Quiet),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            PeerMarks(group, peers, free, Modifier.width(64.dp))
        }
    }
}

/** The peers column: a 6 dp square (green when one has a free slot) and the count, with words for TalkBack. */
@Composable
private fun PeerMarks(group: RemoteAlbum, peers: Int, free: Boolean, modifier: Modifier) {
    val words = listOfNotNull(
        plural(peers, "peer"),
        if (free) "upload slot free" else "peer busy",
        group.queue?.takeIf { it > 0 }?.let { "$it queued" },
        if (group.speed > 0) "${sizeLabel(group.speed)} per second" else null,
    ).joinToString(", ")
    Row(
        modifier.semantics(mergeDescendants = true) { contentDescription = words },
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).background(if (free) Room.Online else Room.Faint))
        BasicText("$peers", style = Room.LabelQuiet, maxLines = 1)
    }
}

/** A stand-in sleeve for a folder we can't see the cover of yet: colours from its name, set like a label. */
@Composable
internal fun GeneratedSleeve(title: String, artist: String?, modifier: Modifier = Modifier) {
    val seed = (title + artist.orEmpty()).hashCode()
    val hue = ((seed and 0x7fffffff) % 360).toFloat()
    val a = Color.hsl(hue, 0.55f, 0.62f)
    val b = Color.hsl((hue + 40f) % 360f, 0.5f, 0.38f)
    Box(modifier.background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(a, b)), RectangleShape)) {
        BasicText(
            title.take(18).uppercase(),
            style = Room.Label.copy(fontSize = 6.5.sp, letterSpacing = 0.6.sp, color = Color.White.copy(alpha = 0.92f), fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
            maxLines = 3,
            modifier = Modifier.padding(5.dp),
        )
    }
}

private fun qualityShort(bits: Int?, rate: Int?): String? =
    if (bits == null || rate == null) null else "$bits/${if (rate % 1000 == 0) (rate / 1000).toString() else String.format(Locale.ROOT, "%.1f", rate / 1000.0)}"

/** The black 66 dp "sort" key: the sliders glyph over its 10/400 label. */
@Composable
private fun FiltersKey(modifier: Modifier, onClick: () -> Unit) {
    val tick = rememberTick()
    Column(
        modifier
            .size(66.dp)
            .background(Room.Ink)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button) { tick(); onClick() }
            .semantics(mergeDescendants = true) { contentDescription = "Filters and sort" }
            .testTag("search-filters"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
    ) {
        Canvas(Modifier.size(20.dp)) { drawNavIcon(Panel.SETTINGS, Room.OnInk) }
        BasicText("sort", style = Room.Label.copy(color = Room.OnInk))
    }
}

/** The filters & sort panel: sort and reported quality as outlined cell rows, the free-slots switch, and "done". */
@Composable
private fun FiltersCard(options: DiscoveryOptions, actions: RoomActions, onHelp: () -> Unit, onDismiss: () -> Unit) {
    PageSheet("search-filter-card", onDismiss, title = "filters & sort") {
        Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
            BasicText("sort", style = Room.LabelQuiet)
            Segments(listOf(SearchSort.MATCH, SearchSort.QUALITY, SearchSort.AVAILABILITY), options.sort, ::sortLabel) { actions.setSearchOptions(options.copy(sort = it)) }
        }
        Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
            BasicText("reported flac quality", style = Room.LabelQuiet)
            Segments(SearchQuality.entries, options.quality, ::qualityLabel) { actions.setSearchOptions(options.copy(quality = it)) }
        }
        FreeSlotsRow(options.availableOnly, { actions.setSearchOptions(options.copy(availableOnly = it)) }, onHelp)
        TeButton("done", onDismiss, Modifier.fillMaxWidth().testTag("search-filters-done"))
    }
}

private fun sortLabel(sort: SearchSort) = when (sort) {
    SearchSort.MATCH -> "best match"
    SearchSort.QUALITY -> "quality"
    SearchSort.AVAILABILITY -> "peers"
}

private fun qualityLabel(quality: SearchQuality) = when (quality) {
    SearchQuality.ANY -> "any"
    SearchQuality.CD -> "16-bit"
    SearchQuality.HI_RES -> "24-bit+"
    SearchQuality.UNKNOWN -> "unknown"
}

/**
 * The TE toggle row for "free upload slots only", whose "?" opens the help panel rather than a
 * line underneath.
 */
@Composable
private fun FreeSlotsRow(checked: Boolean, onChange: (Boolean) -> Unit, onHelp: () -> Unit) {
    val title = "Free upload slots only"
    val toggled = rememberTick(if (checked) androidx.compose.ui.hapticfeedback.HapticFeedbackType.ToggleOff else androidx.compose.ui.hapticfeedback.HapticFeedbackType.ToggleOn)
    Row(
        Modifier
            .fillMaxWidth()
            .hairlineTop()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Switch) { toggled(); onChange(!checked) }
            .semantics { stateDescription = if (checked) "On" else "Off" }
            .testTag("toggle:$title")
            .padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicText(title.lowercase(), style = Room.Text)
                InfoDot(title, onHelp)
            }
            BasicText("peer availability is advertised and can change.", style = Room.LabelQuiet)
        }
        Switch(checked)
    }
}

private fun searchStatus(state: DiscoveryState): String {
    if (normalize(state.query) != normalize(state.online.query)) return "press the search key to look for these new words."
    return when (state.online.phase) {
        SearchPhase.SEARCHING -> "listening for peers… results appear as they arrive."
        SearchPhase.COMPLETE -> if (state.remote.isEmpty()) "no matching flac albums with these filters. try fewer words or change filters." else "search complete. tap an album for its track list."
        SearchPhase.CANCELLED -> "search stopped. matches already received are kept."
        SearchPhase.FAILED, SearchPhase.INTERRUPTED -> state.online.error ?: "search interrupted. your results are kept."
        else -> "ready to search."
    }
}

/** "2.4 mb", "300 mb": lower case, one decimal under a hundred. */
internal fun sizeLabel(bytes: Long): String {
    fun f(v: Double, unit: String) = (if (v >= 100) String.format(Locale.ROOT, "%.0f", v) else String.format(Locale.ROOT, "%.1f", v)) + " $unit"
    return when {
        bytes >= 1_073_741_824 -> f(bytes / 1_073_741_824.0, "gb")
        bytes >= 1_048_576 -> f(bytes / 1_048_576.0, "mb")
        else -> String.format(Locale.ROOT, "%.0f kb", bytes / 1024.0)
    }
}

private fun trackDetails(track: SoulseekSearchResult) = "flac · " + listOf(
    track.bitDepth?.let { "$it-bit" } ?: "bit depth unknown",
    track.sampleRate?.let { String.format(Locale.ROOT, "%.1f khz", it / 1000.0) } ?: "sample rate unknown",
    sizeLabel(track.size),
).joinToString(" · ")

private fun duration(seconds: Int?) = seconds?.takeIf { it > 0 }?.let { "${it / 60}:${(it % 60).toString().padStart(2, '0')}" }.orEmpty()

/**
 * A peer's complete album folder, shown before anything downloads: "← results", the sleeve with
 * the title in 16/700 caps, artist, spec tag and whose it is, the running order under hairlines,
 * then where it keeps and the black download key.
 */
@Composable
internal fun FolderPreview(preview: SoulseekFolderPreview, state: RoomState, actions: RoomActions, onMessage: (String) -> Unit = {}) {
    BackHandler(onBack = actions::closeSoulseekFolder)
    PageStep(actions::closeSoulseekFolder)
    val (artist, album) = folderNames(preview.directory)
    val folder = preview.directory.substringAfterLast('/')
    val bits = preview.files.mapNotNull { it.bitDepth }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
    val rate = preview.files.mapNotNull { it.sampleRate }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
    val peers = remember(state.discovery.remote, preview.directory) { peerCounts(state.discovery.remote)[folderKey(preview.directory)]?.first ?: 0 }
    val ready = !preview.loading && preview.error == null && preview.files.isNotEmpty()
    // Part of this album already on the shelf, or downloads of it that broke: each track says where
    // it stands, and only what's missing or broken downloads, into the same album and folder.
    val shelf = remember(state.albums, preview) { shelfMatch(preview, state.albums) }
    val states = preview.files.associate { it.id to fileState(it, shelf, state.soulseekTransfers) }
    val partial = shelf != null || states.values.any { it.second != null }
    val aimAt = shelf?.takeIf { it.source == app.runout.model.Source.SOULSEEK }?.id
    val missing = preview.files.filter { states[it.id]?.first == FileState.MISSING || states[it.id]?.first == FileState.BROKEN }
    val canGet = ready && state.downloadFolder != null && state.soulseekSession.connected
    Column(Modifier.fillMaxSize().testTag("screen:folder-preview"), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        BackLink(actions::closeSoulseekFolder)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            GeneratedSleeve(album, artist, Modifier.size(110.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                BasicText(album.uppercase(), style = Room.Heading.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, lineHeight = 19.sp), maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
                artist?.let { BasicText(it.lowercase(), style = Room.Label.copy(color = Room.Body), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                if (!preview.loading && preview.files.isNotEmpty()) {
                    TeTag(
                        listOfNotNull("flac", qualityShort(bits, rate), sizeLabel(preview.files.sumOf { it.size })).joinToString(" · "),
                        color = if ((bits ?: 0) >= 24) Room.Signal else Room.Ink,
                    )
                }
                // The peer, small: whose folder it is, and a way to message them.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BasicText(listOfNotNull("from ${preview.username}", peers.takeIf { it > 1 }?.let { plural(it, "peer") }).joinToString(" · "), style = Room.LabelQuiet, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    MessageKey(enabled = state.soulseekSession.connected) { onMessage(preview.username) }
                }
            }
        }
        if (preview.loading) WaitingMark("getting the full track list…", "Loading folder")
        preview.error?.let {
            BasicText(it, style = Room.Label.copy(color = Room.Danger))
            // The folder is gone or its peer is away (a record card opens folders by name): look
            // for the same record from anyone instead.
            ArrowLink("search soulseek for it instead", {
                val query = listOfNotNull(artist, album).joinToString(" ")
                actions.closeSoulseekFolder()
                actions.setSearchOptions(state.discovery.options.copy(source = SearchSource.SOULSEEK))
                actions.setSearchQuery(query)
                actions.searchSoulseek(query)
            }, enabled = state.soulseekSession.signedIn, tag = "folder-search-instead", color = Room.Accent)
        }
        if (preview.files.isNotEmpty()) LazyColumn(Modifier.weight(1f, fill = false).fillMaxWidth()) {
            itemsIndexed(preview.files, key = { _, f -> f.id }) { i, file ->
                val (number, title) = cleanTrackName(file.fileName, folder, artist, i + 1)
                Row(
                    Modifier.fillMaxWidth().hairlineTop(Room.HairlineSoft).padding(top = 11.dp, bottom = 11.dp, end = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(11.dp),
                ) {
                    BasicText(number, style = Room.LabelQuiet, modifier = Modifier.width(20.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        BasicText(title.lowercase(), style = Room.Label, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        // Only a track that differs from the album says what it is.
                        if ((file.bitDepth != null && file.bitDepth != bits) || (file.sampleRate != null && file.sampleRate != rate)) {
                            BasicText(trackDetails(file), style = Room.LabelQuiet)
                        }
                    }
                    BasicText(duration(file.durationSec), style = Room.LabelQuiet.copy(textAlign = TextAlign.End), maxLines = 1, modifier = Modifier.width(44.dp))
                    if (partial) states[file.id]?.let { (s, t) -> TrackState(s, t, enabled = canGet) { actions.downloadSoulseekTracks(listOf(file), aimAt) } }
                }
            }
        }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(11.dp)) {
            if (state.downloadFolder == null) TeButton("choose download folder", actions::pickDownloadFolder, Modifier.fillMaxWidth(), kind = KeyKind.OUTLINE)
            else BasicText(
                when {
                    shelf != null && aimAt != null -> "joins ${shelf.title} in ${state.downloadFolder}"
                    else -> "keeps in ${state.downloadFolder}"
                }.lowercase(),
                style = Room.LabelQuiet,
            )
            // Only once the whole list is in: until then nothing can be counted as missing.
            if (partial && ready) {
                val have = states.values.count { it.first == FileState.HAVE }
                BasicText("you have $have of ${preview.files.size}" + (if (missing.isNotEmpty()) " · ${missing.size} missing or broken" else ""), style = Room.LabelQuiet, modifier = Modifier.testTag("folder-have"))
                TeButton(
                    if (missing.isEmpty()) "nothing missing" else "download ${plural(missing.size, "missing track")}",
                    { actions.downloadSoulseekTracks(missing, aimAt) },
                    Modifier.fillMaxWidth().testTag("confirm-folder"),
                    enabled = canGet && missing.isNotEmpty(),
                )
            } else TeButton(
                "download ${plural(preview.files.size, "flac track")}",
                actions::confirmSoulseekFolder,
                Modifier.fillMaxWidth().testTag("confirm-folder"),
                enabled = canGet,
            )
        }
    }
}

/** Where one track of a folder stands against the shelf and the downloads. */
internal enum class FileState { HAVE, GETTING, BROKEN, MISSING }

private val gettingStates = setOf(
    SoulseekTransferState.QUEUED, SoulseekTransferState.DOWNLOADING, SoulseekTransferState.PAUSED,
    SoulseekTransferState.VALIDATING, SoulseekTransferState.PUBLISHING,
)

/** Letters and digits only, lower case: "New Kid in Town" and "new-kid-in-town" are one key. */
internal fun nameKey(s: String): String = s.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

/**
 * A track as a key, from a file name as Runout saves it ("1a2b3c4d5e 02-New Kid In Town.flac") or a
 * peer shares it ("02 - New Kid in Town.flac"): the number, extension and punctuation dropped.
 */
internal fun trackKey(fileName: String): String = nameKey(cleanTrackName(fileName.replace(SAVED_PREFIX, ""), "", null, 0).second)
private val SAVED_PREFIX = Regex("^[0-9a-f]{10} ")

/** What an album on the shelf is called, track by track: by tag title and by file name. */
private fun shelfKeys(album: app.runout.model.Album): Set<String> = album.tracks.flatMapTo(HashSet()) { listOf(nameKey(it.title), trackKey(it.fileName)) }

/**
 * The album on the shelf this folder belongs to: Runout's own download of this very folder; else
 * one with the folder's title (and artist, when both are known), whoever shared it; else the album
 * sharing at least two of its track titles.
 */
internal fun shelfMatch(preview: SoulseekFolderPreview, albums: List<app.runout.model.Album>): app.runout.model.Album? {
    val own = SoulseekPolicy.albumId(preview.username, preview.directory)
    albums.firstOrNull { it.id == own }?.let { return it }
    val keys = preview.files.mapTo(HashSet()) { trackKey(it.fileName) }
    fun overlap(a: app.runout.model.Album) = shelfKeys(a).count { it in keys }
    val (folderArtist, folderTitle) = folderNames(preview.directory)
    val title = nameKey(folderTitle)
    val artist = folderArtist?.let(::nameKey)?.takeIf { it.isNotBlank() }
    val named = albums.filter { a ->
        val k = nameKey(a.title)
        val by = nameKey(a.artist).takeIf { it.isNotBlank() && it != "unknownartist" }
        k.length >= 3 && (title.contains(k) || k.contains(title)) && (artist == null || by == null || artist.contains(by) || by.contains(artist))
    }
    named.maxByOrNull(::overlap)?.let { return it }
    if (keys.isEmpty()) return null
    val needed = minOf(2, keys.size)
    return albums.map { it to overlap(it) }.filter { it.second >= needed }.maxByOrNull { it.second }?.first
}

/** One file of the folder: on the shelf, downloading now, broken (failed or cancelled), or missing. */
internal fun fileState(file: SoulseekSearchResult, shelf: app.runout.model.Album?, transfers: List<SoulseekTransfer>): Pair<FileState, SoulseekTransfer?> {
    val t = transfers.firstOrNull { it.username == file.username && it.path == file.path && it.size == file.size }
    val have = shelf != null && trackKey(file.fileName) in shelfKeys(shelf)
    val s = when {
        t != null && t.state in gettingStates -> FileState.GETTING
        have -> FileState.HAVE
        t != null && (t.state == SoulseekTransferState.FAILED || t.state == SoulseekTransferState.CANCELLED) -> FileState.BROKEN
        else -> FileState.MISSING
    }
    return s to t
}

/**
 * The cell after a track's length: a green tick for one you have, its progress while it downloads
 * (‖ when paused in the queue), and a ↓ key for one that's missing (ink) or broke (red), which downloads just that track.
 */
@Composable
private fun TrackState(state: FileState, transfer: SoulseekTransfer?, enabled: Boolean, onDownload: () -> Unit) {
    val tick = rememberTick()
    Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
        when (state) {
            FileState.HAVE -> BasicText("✓", style = Room.Label.copy(color = Room.Online), modifier = Modifier.semantics { contentDescription = "In your library" })
            FileState.GETTING -> BasicText(
                when {
                    transfer?.state == SoulseekTransferState.PAUSED -> "‖"
                    transfer != null && transfer.progress > 0f -> "${(transfer.progress * 100).toInt()}%"
                    else -> "…"
                },
                style = Room.LabelQuiet, maxLines = 1,
                modifier = Modifier.semantics { contentDescription = if (transfer?.state == SoulseekTransferState.PAUSED) "Paused in your downloads" else "Downloading" },
            )
            FileState.BROKEN, FileState.MISSING -> {
                val colour = if (state == FileState.BROKEN) Room.Danger else Room.Ink
                Box(
                    Modifier
                        .size(28.dp)
                        .border(1.dp, colour)
                        .alpha(if (enabled) 1f else 0.4f)
                        .clickable(enabled = enabled, interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button) { tick(); onDownload() }
                        .semantics { contentDescription = if (state == FileState.BROKEN) "Download this track again" else "Download this track" }
                        .testTag("get-track"),
                    contentAlignment = Alignment.Center,
                ) { BasicText("↓", style = Room.Label.copy(color = colour)) }
            }
        }
    }
}

/** "← results", 12/400 ink, the first thing in the body. */
@Composable
private fun BackLink(onBack: () -> Unit) {
    val tick = rememberTick()
    BasicText(
        "← results",
        style = Room.Text,
        modifier = Modifier
            .testTag("preview-back")
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button) { tick(); onBack() },
    )
}

/** A small message key after the peer's name: the square speech glyph of the nav's friends cell. */
@Composable
private fun MessageKey(enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(32.dp)
            .clickable(enabled = enabled, interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Message this peer" }
            .testTag("message-peer"),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(14.dp)) { drawNavIcon(Panel.FRIENDS, if (enabled) Room.Ink else Room.Faint) }
    }
}

private val innerNumber = Regex("\\s[-–]\\s+(\\d{1,3})(?:\\s*[-_.)]\\s*|\\s+)")
private val leadingNumber = Regex("^\\s*(?:cd\\s*\\d+\\s*[-_. ]\\s*)?(\\d{1,3})(?:\\s*[-_.)]\\s*|\\s+)")

/**
 * "Air - Moon Safari - 01 La femme d'argent.flac" → ("01", "La femme d'argent"). Drops the
 * extension, any artist/album prefix the file repeats, and the track number (shown separately).
 */
internal fun cleanTrackName(fileName: String, album: String, artist: String?, fallbackNumber: Int): Pair<String, String> {
    var name = fileName.substringAfterLast('/').substringBeforeLast('.')
    // Strip a repeated "Artist - Album - " (or either alone) from the front.
    val prefixes = listOfNotNull(album, artist, artist?.let { "$it - $album" }).map { it.trim() }.filter { it.length >= 2 }.sortedByDescending { it.length }
    for (p in prefixes) {
        if (name.startsWith(p, ignoreCase = true)) name = name.substring(p.length).trimStart(' ', '-', '_', '.', '–')
    }
    var m = leadingNumber.find(name)
    // Still "Album - 01 Title" (e.g. the folder had a year the file doesn't)? Take it from the
    // last " - NN " in the name.
    if (m == null) innerNumber.findAll(name).lastOrNull()?.let { inner ->
        name = name.substring(inner.range.first + inner.value.indexOf(inner.groupValues[1]))
        m = leadingNumber.find(name)
    }
    val number = m?.groupValues?.get(1)?.padStart(2, '0') ?: fallbackNumber.toString().padStart(2, '0')
    if (m != null) name = name.substring(m.range.last + 1)
    // "Artist - Title" left over after the number: keep the title.
    if (artist != null && name.startsWith("$artist - ", ignoreCase = true)) name = name.substring(artist.length + 3)
    return number to name.trim().ifBlank { fileName }
}
