package app.runout.ui

import android.graphics.Bitmap
import android.graphics.Paint
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import app.runout.model.Album
import app.runout.model.Source
import com.kyant.backdrop.Backdrop
import kotlinx.coroutines.CompletableDeferred
import java.io.File
import kotlin.math.roundToInt

/**
 * The TE dialog card, centred over the grey scrim: white, a 2 dp ink outline, 22 dp in all round,
 * 16 dp between parts, 22 dp from the screen's edges. Back or a tap outside dismisses it.
 */
@Composable
internal fun TeCard(tag: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    BackHandler(onBack = onDismiss)
    Box(Modifier.fillMaxSize().zIndex(25f), contentAlignment = Alignment.Center) {
        // The scrim is a sibling behind the card, not its parent: a clickable parent would merge the
        // whole card into one "dismiss" button for TalkBack.
        Box(
            Modifier
                .fillMaxSize()
                .background(Room.Scrim)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        )
        Column(
            Modifier
                .padding(22.dp)
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .background(Room.Background)
                .border(2.dp, Room.Ink)
                .pointerInput(Unit) { detectTapGestures { } }
                // Taller than the screen (a phone on its side): the card scrolls inside its outline.
                .verticalScroll(rememberScrollState())
                .padding(22.dp)
                .testTag(tag),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content,
        )
    }
}

/** The record at the top of a card: its 66 dp sleeve, the title in 16/700 and the artist in 10/400. */
@Composable
private fun CardRecord(record: Album, artist: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        SleeveArt(record, Modifier.size(66.dp), showSticker = false)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            BasicText(record.title.uppercase(), style = Room.Album.copy(fontSize = 16.sp), maxLines = 2)
            BasicText(artist.lowercase(), style = Room.Label.copy(color = Room.Body), maxLines = 1)
        }
    }
}

/**
 * The record's back cover, opened by a long-press on its sleeve or its disc: format, licence, where it
 * came from, the running order by side and its total time. The share key in the corner sends the same
 * sheet as a picture; under it, quiet links put the record back, fetch missing tracks or remove it.
 */
@Composable
internal fun RecordDetails(
    record: Album,
    @Suppress("UNUSED_PARAMETER") backdrop: Backdrop,
    onDismiss: () -> Unit,
    pulled: Boolean = false,
    onPutBack: () -> Unit = {},
    /** Tracks of this record whose download failed. */
    missing: Int = 0,
    onFetchMissing: () -> Unit = {},
    onRemove: () -> Unit = {},
    /** The sheet drawn as a picture: no share key, no links, no close key. */
    onSharePicture: (ImageBitmap) -> Unit = {},
) {
    // The picture is the whole sheet with every track: an uncapped copy is laid out over the card only
    // while sharing, recorded into this layer and never drawn on screen.
    val picture = rememberGraphicsLayer()
    var capturing by remember { mutableStateOf(false) }
    var recorded by remember { mutableStateOf(CompletableDeferred<Unit>()) }
    val share by rememberUpdatedState(onSharePicture)
    LaunchedEffect(capturing) {
        if (!capturing) return@LaunchedEffect
        // Drawn on the next frame; the effect is cancelled with the card if it never is.
        recorded.await()
        val bitmap = runCatching { picture.toImageBitmap() }.getOrNull()
        capturing = false
        bitmap?.let(share)
    }
    TeCard("record-details", onDismiss) {
        Box(Modifier.fillMaxWidth()) {
            RecordSheet(record, capped = true, headerEnd = 28.dp)
            ShareKey(Modifier.align(Alignment.TopEnd).offset(x = 10.dp, y = (-10).dp)) {
                if (!capturing) { recorded = CompletableDeferred(); capturing = true }
            }
            if (capturing) Box(Modifier.matchParentSize().wrapContentHeight(Alignment.Top, unbounded = true)) {
                Box(
                    Modifier
                        .clearAndSetSemantics {}
                        .drawWithContent {
                            picture.record { this@drawWithContent.drawContent() }
                            recorded.complete(Unit)
                        },
                ) {
                    RecordSheet(record, capped = false, headerEnd = SHARE_MARK_WIDTH + 8.dp)
                    // The intro's dotted wordmark, small in the corner: the card's own ink, its dot orange.
                    BrandText(brandMark(), Room.Display.copy(fontSize = 13.sp, lineHeight = 13.sp), Modifier.align(Alignment.TopEnd).padding(top = 4.dp))
                }
            }
        }
        val removable = record.source != Source.FOLDER
        if (pulled || missing > 0 || removable) FlowRow(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            if (pulled) TeLink("put back", onPutBack, tag = "details:put-back")
            if (missing > 0) TeLink(
                if (missing == 1) "fetch missing track" else "fetch $missing missing tracks",
                onFetchMissing,
                tag = "details:fetch-missing",
                color = Room.Danger,
            )
            if (removable) TeLink("remove record", onRemove, tag = "details:remove", color = Room.Danger)
        }
        TeButton("close", onDismiss, Modifier.fillMaxWidth(), kind = KeyKind.OUTLINE)
    }
}

/** Room kept clear for the wordmark in the shared picture's top-right corner. */
private val SHARE_MARK_WIDTH = 64.dp

/** What the details card and its picture share: the record, its tags and notes, the running order and the total. */
@Composable
private fun RecordSheet(record: Album, capped: Boolean, modifier: Modifier = Modifier, headerEnd: Dp = 0.dp) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        CardRecord(record, listOfNotNull(record.artist, record.year?.toString()).joinToString(" · "), Modifier.padding(end = headerEnd))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(11.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SpecTag(record.quality.label)
            app.runout.model.Licences.label(record.license)?.let { TeTag(it.lowercase(), color = Room.Accent) }
        }
        record.unavailableReason?.let { BasicText(it.lowercase(), style = Room.Label.copy(color = Room.Danger, lineHeight = 15.sp)) }
        provenance(record)?.let { BasicText(it.lowercase(), style = Room.Label.copy(color = Room.Accent, lineHeight = 15.sp)) }
        val sides = remember(record.id) { app.runout.model.SideSplitter.split(record.tracks) }
        val scroll = rememberScrollState()
        Column(Modifier.fillMaxWidth().then(if (capped) Modifier.heightIn(max = 300.dp).verticalScroll(scroll) else Modifier)) {
            sides.forEach { side ->
                side.tracks.forEachIndexed { i, track ->
                    // An ink rule where a new side begins, hairlines between songs.
                    Row(
                        Modifier.fillMaxWidth().hairlineTop(if (i == 0 && side.index > 0) Room.Ink else Room.HairlineSoft).padding(top = 11.dp, bottom = 11.dp, end = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(11.dp),
                    ) {
                        BasicText("${side.letter.lowercaseChar()}${i + 1}", style = Room.LabelQuiet)
                        BasicText(track.title.lowercase(), style = Room.Label, maxLines = 1, modifier = Modifier.weight(1f))
                        BasicText(clock(track.durationMs), style = Room.LabelQuiet.copy(textAlign = TextAlign.End), modifier = Modifier.width(44.dp))
                    }
                }
            }
        }
        // The whole record's running time, under an ink rule.
        Row(
            Modifier
                .fillMaxWidth()
                .hairlineTop(Room.Ink)
                .padding(top = 11.dp, end = 12.dp)
                .testTag("record-total")
                .semantics(mergeDescendants = true) {},
            horizontalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            BasicText("total", style = Room.Label, modifier = Modifier.weight(1f))
            BasicText(runningTime(record.tracks.sumOf { it.durationMs.coerceAtLeast(0) }), style = Room.Label.copy(textAlign = TextAlign.End))
        }
    }
}

/** Share as a picture: Lucide's share (an arrow up out of a box), 20 dp in a 40 dp target. */
@Composable
private fun ShareKey(modifier: Modifier = Modifier, onClick: () -> Unit) {
    val tick = rememberTick()
    Box(
        modifier
            .size(40.dp)
            .testTag("details:share")
            .semantics { contentDescription = "Share as a picture" }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button) { tick(); onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(20.dp)) {
            val k = size.width / 24f
            val s = Stroke(width = 1.8f * k, cap = StrokeCap.Round, join = StrokeJoin.Round)
            val box = Path().apply {
                moveTo(4f * k, 12f * k)
                lineTo(4f * k, 20f * k)
                quadraticTo(4f * k, 22f * k, 6f * k, 22f * k)
                lineTo(18f * k, 22f * k)
                quadraticTo(20f * k, 22f * k, 20f * k, 20f * k)
                lineTo(20f * k, 12f * k)
            }
            drawPath(box, Room.Ink, style = s)
            val arrow = Path().apply {
                moveTo(16f * k, 6f * k)
                lineTo(12f * k, 2f * k)
                lineTo(8f * k, 6f * k)
                moveTo(12f * k, 2f * k)
                lineTo(12f * k, 15f * k)
            }
            drawPath(arrow, Room.Ink, style = s)
        }
    }
}

/**
 * The shared picture of a record's sheet: on white, 22 dp all round and a 2 dp ink outline, like the
 * card on screen. Written as a PNG to [file].
 */
internal fun writeRecordPicture(sheet: ImageBitmap, density: Float, file: File): File {
    val drawn = sheet.asAndroidBitmap()
    val src = if (drawn.config == Bitmap.Config.HARDWARE) drawn.copy(Bitmap.Config.ARGB_8888, false) else drawn
    val margin = 22f * density
    val stroke = 2f * density
    val out = Bitmap.createBitmap((src.width + 2 * margin).roundToInt(), (src.height + 2 * margin).roundToInt(), Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(out)
    canvas.drawColor(Room.Background.toArgb())
    canvas.drawBitmap(src, margin, margin, null)
    val outline = Paint().apply { style = Paint.Style.STROKE; strokeWidth = stroke; color = Room.Ink.toArgb() }
    canvas.drawRect(stroke / 2f, stroke / 2f, out.width - stroke / 2f, out.height - stroke / 2f, outline)
    file.parentFile?.mkdirs()
    file.outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
    return file
}

private fun clock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

/** A whole record's length: "39:00", or "1:02:33" once it passes an hour. */
internal fun runningTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** The spec as a TE Tag: 1 dp ink outline, 3 × 6 inside, 10/400, lowercase. */
@Composable
internal fun SpecTag(text: String, modifier: Modifier = Modifier) = TeTag(text.lowercase(), modifier)
