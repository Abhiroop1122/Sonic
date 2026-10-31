package app.runout.ui.settings

import app.runout.library.RecordUsage
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.runout.library.FolderUsage
import app.runout.library.StorageKind
import app.runout.library.StorageReport
import app.runout.library.StorageState
import app.runout.ui.*

/**
 * Settings › Storage, the page two fingers find on Settings: your music beside the whole phone, then
 * the music and download folders, each by kind (hi-res and cd-quality flac, other audio, pictures, unfinished downloads, the rest) with
 * its records largest first, and Sonic's own files. It measures when it opens; nothing is deleted
 * here but Sonic's cache and the records you choose.
 */
@Composable
internal fun StoragePage(state: RoomState, actions: RoomActions) {
    // The record whose delete key was pressed, waiting on the warning.
    var confirm by remember { mutableStateOf<RecordUsage?>(null) }
    LaunchedEffect(Unit) { if (state.storage !is StorageState.Measuring) actions.analyzeStorage() }
    Column(Modifier.fillMaxWidth().testTag("panel:storage"), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        // The way back, as the way in: two fingers, this time down.
        Row(Modifier.fillMaxWidth().testTag("storage-back-hint"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            androidx.compose.foundation.Canvas(Modifier.size(20.dp)) { drawTwoFingerSwipe(Room.Quiet) }
            BasicText("swipe down with two fingers to go back to settings", style = Room.LabelQuiet.copy(lineHeight = 15.sp))
        }
        when (val s = state.storage) {
            is StorageState.Done -> Report(s.report, actions) { confirm = it }
            is StorageState.Measuring -> Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                DotVinylSpinner(Modifier.size(96.dp), description = "Measuring your folders")
                BasicText(if (s.files == 0) "measuring your folders…" else "measuring… ${"%,d".format(s.files)} files", style = Room.LabelQuiet, modifier = Modifier.testTag("storage-measuring"))
            }
            StorageState.Idle -> Unit
        }
    }
    confirm?.let { record -> DeleteWarning(record, onDelete = { confirm = null; actions.deleteRecordFolder(record) }, onKeep = { confirm = null }) }
}

/**
 * The warning before a record's folder goes: the theme turned over (white on a dark phone, black on
 * a light one) under a red rule, the record, the red line, and the two keys.
 */
@Composable
private fun DeleteWarning(record: RecordUsage, onDelete: () -> Unit, onKeep: () -> Unit) {
    PageSheet("delete-warning", onKeep, inverted = true) {
        BasicText("delete record", style = Room.Label.copy(color = Room.InverseBody))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BasicText(record.title.lowercase(), style = Room.Title.copy(color = Room.OnInk), maxLines = 2, overflow = TextOverflow.Ellipsis)
            record.artist?.let { BasicText(it.lowercase(), style = Room.Label.copy(color = Room.InverseBody)) }
        }
        BasicText("your album will be deleted.", style = Room.Strong.copy(color = Room.InverseDanger), modifier = Modifier.testTag("delete-warning-line"))
        BasicText(
            "its folder and every file in it (${bytes(record.bytes)}) are removed from your phone, not just from sonic. this can't be undone.",
            style = Room.Label.copy(color = Room.InverseBody, lineHeight = 15.sp),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SheetKey("keep it", Room.OnInk, Room.Ink, Modifier.weight(1f).testTag("delete-keep"), outline = true, onClick = onKeep)
            SheetKey("delete", Color.White, Room.InverseDanger, Modifier.weight(1f).testTag("delete-confirm"), onClick = onDelete)
        }
    }
}

/** A key in the turned-over sheet, where the room's own keys would vanish ink on ink. */
@Composable
private fun SheetKey(text: String, ink: Color, fill: Color, modifier: Modifier, outline: Boolean = false, onClick: () -> Unit) {
    val tick = rememberTick()
    Box(
        modifier
            .background(fill)
            .then(if (outline) Modifier.border(1.dp, ink) else Modifier)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button) { tick(); onClick() }
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) { BasicText(text, style = Room.Text.copy(color = ink), maxLines = 1) }
}

@Composable
private fun Report(r: StorageReport, actions: RoomActions, onDelete: (RecordUsage) -> Unit) {
    r.device?.let { PhoneShare(it, r.music?.bytes ?: 0L, r.downloads?.bytes ?: 0L) }
    PageSection("02 music folder" + (r.music?.label?.let { " · ${it.lowercase()}" } ?: "")) {
        r.music?.let { Folder(it, "storage-music", onDelete) } ?: Quiet("no music folder yet. choose one in library.")
    }
    PageSection("03 download folder" + (r.downloads?.label?.let { " · ${it.lowercase()}" } ?: "")) {
        when {
            r.downloadsInMusic -> Quiet("downloads land inside your music folder, so they're counted above.")
            r.downloads != null -> Folder(r.downloads, "storage-downloads", onDelete)
            else -> Quiet("no download folder yet. choose one in library.")
        }
    }
    PageSection("04 sonic") {
        StatsStrip(listOf(bytes(r.app.covers) to "covers", bytes(r.app.cache) to "cache", bytes(r.app.data) to "data"))
        Quiet("covers are the sleeves sonic has kept; data is its library list, chats and settings. the cache is safe to clear.")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TeButton("clear cache", actions::clearCache, Modifier.weight(1f).testTag("storage-clear-cache"), kind = KeyKind.OUTLINE, enabled = r.app.cache > 0)
            TeButton("measure again", actions::analyzeStorage, Modifier.weight(1f).testTag("storage-again"), kind = KeyKind.OUTLINE)
        }
    }
}

/**
 * A folder: its strip, its bar by kind with a legend line each, and its records largest first (five,
 * then all of them), each with a delete key when the folder is the record's own.
 */
@Composable
private fun Folder(f: FolderUsage, tag: String, onDelete: (RecordUsage) -> Unit) {
    var all by remember(f.label) { mutableStateOf(false) }
    if (f.error != null) { BasicText(f.error, style = Room.Label.copy(color = Room.Danger, lineHeight = 15.sp)); return }
    Column(Modifier.fillMaxWidth().testTag(tag), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        StatsStrip(listOf(bytes(f.bytes) to "size", "%,d".format(f.files) to "files", "${f.albums}" to "records"))
        val kinds = StorageKind.entries.mapNotNull { k -> f.byKind[k]?.takeIf { it > 0 }?.let { k to it } }
        if (kinds.isNotEmpty()) {
            UsageBar(kinds.map { (k, b) -> b to colourOf(k) })
            kinds.forEach { (k, b) -> Legend(colourOf(k), k.label, b, f.bytes) }
        }
        if (f.largest.isNotEmpty()) {
            BasicText("records, largest first", style = Room.LabelQuiet, modifier = Modifier.padding(top = 12.dp))
            Column(Modifier.fillMaxWidth()) {
                (if (all) f.largest else f.largest.take(SHOWN)).forEachIndexed { i, rec ->
                    Row(
                        Modifier.fillMaxWidth().hairlineTop().padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        BasicText("%02d".format(i + 1), style = Room.LabelQuiet)
                        Column(Modifier.weight(1f)) {
                            BasicText(rec.title.lowercase(), style = Room.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            rec.artist?.let { BasicText(it.lowercase(), style = Room.LabelQuiet, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        }
                        BasicText(bytes(rec.bytes), style = Room.Label)
                        if (rec.deletable) TrashKey(rec.title) { onDelete(rec) }
                    }
                }
            }
            if (f.largest.size > SHOWN) TeLink(
                if (all) "fewer" else "all ${f.largest.size} records",
                { all = !all }, tag = "$tag-all",
            )
        }
    }
}

/** The kind's colour: orange for hi-res, ink for cd-quality, then quieter for what isn't music. */
@Composable
private fun colourOf(k: StorageKind): Color = when (k) {
    StorageKind.FLAC_HI_RES -> Room.Signal
    StorageKind.FLAC_CD -> Room.Ink
    StorageKind.OTHER_AUDIO -> Room.Accent
    StorageKind.PICTURES -> Room.Quiet
    StorageKind.PARTIAL -> Room.Warning
    StorageKind.OTHER -> Room.Hairline
}

/**
 * 01 phone: your music beside the whole phone, in one line and one bar: the music, the downloads,
 * everything else on the phone, and what's free.
 */
@Composable
private fun PhoneShare(d: app.runout.library.DeviceUsage, music: Long, downloads: Long) {
    PageSection("01 phone") {
        val used = (d.total - d.free).coerceAtLeast(0L)
        val others = (used - music - downloads).coerceAtLeast(0L)
        val yours = music + downloads
        BasicText(
            "your music takes ${bytes(yours)} of ${bytes(d.total)} · ${percent(yours, d.total)}. ${bytes(d.free)} free.",
            style = Room.Text.copy(lineHeight = 17.sp),
            modifier = Modifier.testTag("storage-phone"),
        )
        UsageBar(listOf(music to Room.Signal, downloads to Room.Accent, others to Room.Quiet, d.free to Room.HairlineSoft))
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOfNotNull(
                Room.Signal to "music",
                (Room.Accent to "downloads").takeIf { downloads > 0 },
                Room.Quiet to "everything else",
                Room.HairlineSoft to "free",
            ).forEach { (c, label) ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(8.dp).background(c))
                    BasicText(label, style = Room.LabelQuiet)
                }
            }
        }
    }
}

/** A 10 dp bar in a 1 dp ink outline, its parts side by side in proportion. */
@Composable
private fun UsageBar(parts: List<Pair<Long, Color>>) {
    val total = parts.sumOf { it.first }.coerceAtLeast(1L)
    Row(Modifier.fillMaxWidth().height(12.dp).hairlineTop(Room.Ink).hairlineBottom(Room.Ink)) {
        parts.filter { it.first > 0 }.forEach { (b, c) ->
            Box(Modifier.weight((b.toFloat() / total).coerceAtLeast(0.004f)).fillMaxHeight().background(c))
        }
    }
}

/** A swatch, the name, its size and its share. */
@Composable
private fun Legend(colour: Color, label: String, b: Long, of: Long) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(8.dp).background(colour))
        BasicText(label, style = Room.Label, modifier = Modifier.weight(1f))
        BasicText(bytes(b), style = Room.Label)
        BasicText(percent(b, of), style = Room.LabelQuiet, modifier = Modifier.padding(start = 4.dp))
    }
}

/** Numbers over labels, ruled in ink above and below, as the library's scan strip. */
@Composable
private fun StatsStrip(items: List<Pair<String, String>>) {
    Row(Modifier.fillMaxWidth().hairlineTop(Room.Ink).hairlineBottom(Room.Ink)) {
        items.forEach { (value, label) ->
            Column(Modifier.weight(1f).padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BasicText(value, style = Room.Title.copy(lineHeight = androidx.compose.ui.unit.TextUnit.Unspecified), maxLines = 1)
                BasicText(label, style = Room.LabelQuiet)
            }
        }
    }
}

@Composable
private fun Quiet(text: String) = BasicText(text, style = Room.Label.copy(color = Room.Body, lineHeight = 15.sp))

/** "41.2 gb", "512 mb", "980 kb": decimal units, as the phone's own settings count. */
internal fun bytes(b: Long): String = when {
    b >= 1_000_000_000L -> "%.1f gb".format(b / 1e9)
    b >= 1_000_000L -> "%.0f mb".format(b / 1e6)
    b >= 1_000L -> "%.0f kb".format(b / 1e3)
    else -> "$b b"
}

private fun percent(b: Long, of: Long): String = if (of <= 0) "" else {
    val p = b * 100.0 / of
    if (p in 0.01..0.99) "<1%" else "%.0f%%".format(p)
}

/** How many records a folder lists before "all n records". */
private const val SHOWN = 5

/** Lucide's trash-2, a 20 dp glyph in a 40 dp touch square, saying which record it deletes. */
@Composable
private fun TrashKey(title: String, onClick: () -> Unit) {
    val tick = rememberTick()
    Box(
        Modifier
            .size(40.dp)
            .semantics { contentDescription = "Delete $title" }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button) { tick(); onClick() }
            .testTag("delete:$title"),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.Canvas(Modifier.size(16.dp)) {
            val k = size.width / 24f
            val st = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.8f * k, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
            val c = Room.Danger
            drawLine(c, androidx.compose.ui.geometry.Offset(3f * k, 6f * k), androidx.compose.ui.geometry.Offset(21f * k, 6f * k), st.width, androidx.compose.ui.graphics.StrokeCap.Round)
            val lid = androidx.compose.ui.graphics.Path().apply { moveTo(8f * k, 6f * k); lineTo(8f * k, 4f * k); quadraticTo(8f * k, 2f * k, 10f * k, 2f * k); lineTo(14f * k, 2f * k); quadraticTo(16f * k, 2f * k, 16f * k, 4f * k); lineTo(16f * k, 6f * k) }
            drawPath(lid, c, style = st)
            val can = androidx.compose.ui.graphics.Path().apply { moveTo(19f * k, 6f * k); lineTo(19f * k, 20f * k); quadraticTo(19f * k, 22f * k, 17f * k, 22f * k); lineTo(7f * k, 22f * k); quadraticTo(5f * k, 22f * k, 5f * k, 20f * k); lineTo(5f * k, 6f * k) }
            drawPath(can, c, style = st)
            drawLine(c, androidx.compose.ui.geometry.Offset(10f * k, 11f * k), androidx.compose.ui.geometry.Offset(10f * k, 17f * k), st.width, androidx.compose.ui.graphics.StrokeCap.Round)
            drawLine(c, androidx.compose.ui.geometry.Offset(14f * k, 11f * k), androidx.compose.ui.geometry.Offset(14f * k, 17f * k), st.width, androidx.compose.ui.graphics.StrokeCap.Round)
        }
    }
}
