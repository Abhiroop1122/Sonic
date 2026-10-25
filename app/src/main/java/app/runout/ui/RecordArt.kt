package app.runout.ui

import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.runout.model.Album
import app.runout.model.DiscStyle
import app.runout.model.Pressing
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * How a pressing looks: black for lossy files, solid colour for lossless, clear colour for hi-res.
 * Records from the Pressing Plant choose their own vinyl, including splatter, marble and picture discs.
 */
data class DiscLook(
    val base: Color,
    val alpha: Float,
    val grooveAlpha: Float,
    val style: DiscStyle? = null,
    val accent: Color = base,
    val seed: Int = 0,
)

fun discLookFor(album: Album?): DiscLook {
    val tint = album?.tint?.let { Color(it) } ?: Color(0xFF4A4A58)
    val chosen = album?.labelColor?.let { Color(it) } ?: tint
    val seed = album?.id?.hashCode() ?: 0
    return when (album?.disc) {
        DiscStyle.BLACK -> DiscLook(Color(0xFF111114), 1f, 1f, DiscStyle.BLACK)
        DiscStyle.COLOUR -> DiscLook(lerp(chosen, Color.Black, 0.15f), 1f, 0.85f, DiscStyle.COLOUR)
        DiscStyle.CLEAR -> DiscLook(lerp(chosen, Color.White, 0.1f), 0.58f, 0.75f, DiscStyle.CLEAR)
        DiscStyle.SPLATTER -> DiscLook(Color(0xFFF1F1EE), 1f, 0.55f, DiscStyle.SPLATTER, chosen, seed)
        DiscStyle.MARBLE -> DiscLook(lerp(chosen, Color.White, 0.6f), 1f, 0.6f, DiscStyle.MARBLE, chosen, seed)
        DiscStyle.PICTURE -> DiscLook(Color(0xFF1A1A1E), 1f, 0.45f, DiscStyle.PICTURE, chosen, seed)
        // Every record with a cover is pressed as a picture disc: the cover turns with the platter.
        null -> if (album?.coverPath != null) DiscLook(lerp(tint, Color.Black, 0.5f), 1f, 0.5f, DiscStyle.PICTURE, tint, seed)
        else when (album?.pressing) {
            Pressing.CLEAR -> DiscLook(lerp(tint, Color.White, 0.1f), 0.58f, 0.75f)
            Pressing.COLOUR -> DiscLook(lerp(tint, Color.Black, 0.3f), 1f, 0.85f)
            else -> DiscLook(Color(0xFF111114), 1f, 1f)
        }
    }
}

/** The SONIC sleeve's card: cream, or near-black in the dark, under the placeholder while it decodes. */
private fun noCoverGround(): Color = if (Room.dark) Color(0xFF11110F) else Color(0xFFECE7DD)

/** The SONIC sleeve for records without a cover, decoded once per theme and shared by every sleeve. */
@Composable
private fun rememberNoCoverSleeve(): ImageBitmap? {
    val dark = Room.dark
    val assets = androidx.compose.ui.platform.LocalContext.current.assets
    val px = with(androidx.compose.ui.platform.LocalDensity.current) { 240.dp.roundToPx() }
    val sleeve by androidx.compose.runtime.produceState(NO_COVER_SLEEVES[dark], dark) {
        value = NO_COVER_SLEEVES[dark] ?: kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            decodeOnboardingPicture(assets, if (dark) "intro-sleeve-dark" else "intro-sleeve", px)
        }?.also { NO_COVER_SLEEVES[dark] = it }
    }
    return sleeve
}

private val NO_COVER_SLEEVES = java.util.concurrent.ConcurrentHashMap<Boolean, ImageBitmap>()

/** A colour for albums with no cover, picked from the title so it stays the same every time. */
fun fallbackColor(album: Album?): Color {
    val palette = listOf(0xFFE8E4FF, 0xFFDDEBFF, 0xFFFFE3EC, 0xFFE2F6EC, 0xFFFFF1D6, 0xFFE9E9EE)
    val i = Math.floorMod((album?.title ?: "").hashCode(), palette.size)
    return Color(palette[i])
}

/**
 * Draws a record: grooves, the smooth gaps between tracks, the label (the album cover, turning
 * with the platter), the label print and, with [sheen], a light sheen that stays still while the
 * record spins.
 */
fun DrawScope.drawRecord(
    center: Offset,
    radius: Float,
    rotation: Float,
    look: DiscLook,
    label: ImageBitmap?,
    labelFallback: Color,
    trackBoundaries: List<Float>,
    sideText: TextLayoutResult?,
    qualityText: TextLayoutResult?,
    sheen: Boolean = true,
) {
    // Body
    drawCircle(look.base.copy(alpha = look.alpha), radius, center)
    val discClip = Path().apply { addOval(Rect(center, radius)) }
    when (look.style) {
        DiscStyle.SPLATTER -> rotate(rotation, center) {
            clipPath(discClip) {
                val rnd = Random(look.seed)
                repeat(46) {
                    val a = rnd.nextFloat() * 6.2832f
                    val d = radius * (0.12f + rnd.nextFloat() * 0.9f)
                    val p = center + Offset(cos(a) * d, sin(a) * d)
                    val c = if (it % 3 == 0) Color(0xFF111114) else look.accent
                    drawCircle(c.copy(alpha = 0.85f), radius * (0.012f + rnd.nextFloat() * 0.05f), p)
                }
            }
        }
        DiscStyle.MARBLE -> rotate(rotation, center) {
            clipPath(discClip) {
                val rnd = Random(look.seed)
                repeat(5) { i ->
                    rotate(rnd.nextFloat() * 360f, center) {
                        drawCircle(
                            Brush.sweepGradient(
                                0f to Color.Transparent,
                                0.08f + i * 0.02f to look.accent.copy(alpha = 0.55f),
                                0.2f + i * 0.03f to Color.Transparent,
                                0.55f to Color.White.copy(alpha = 0.35f),
                                0.7f to Color.Transparent,
                                1f to Color.Transparent,
                                center = center,
                            ),
                            radius = radius * (1f - i * 0.12f),
                            center = center,
                        )
                    }
                }
            }
        }
        DiscStyle.PICTURE -> if (label != null) {
            rotate(rotation, center) {
                clipPath(discClip) {
                    drawImage(
                        image = label,
                        dstOffset = IntOffset((center.x - radius).toInt(), (center.y - radius).toInt()),
                        dstSize = IntSize((radius * 2).toInt(), (radius * 2).toInt()),
                    )
                }
            }
            // The clear vinyl rim around the printed picture.
            drawCircle(Color.White.copy(alpha = 0.30f), radius * 0.982f, center, style = Stroke(radius * 0.036f))
            drawCircle(Color.Black.copy(alpha = 0.12f), radius * 0.964f, center, style = Stroke(radius * 0.004f))
        }
        else -> Unit
    }

    // Grooves, from the lead-in near the edge to the run-out near the label.
    val grooveOuter = radius * 0.975f
    val grooveInner = radius * 0.42f
    val rings = 110
    val hair = (radius / 300f).coerceAtLeast(0.5f)
    // Clear vinyl shows its grooves as darker lines; solid vinyl as faint light lines.
    val grooveColor = if (look.alpha < 1f) Color.Black else Color.White
    for (i in 0..rings) {
        val r = grooveInner + (grooveOuter - grooveInner) * i / rings
        val jitter = ((i * 37) % 11) / 10f
        val a = (0.022f + 0.022f * jitter) * look.grooveAlpha
        drawCircle(grooveColor.copy(alpha = a), r, center, style = Stroke(hair))
    }

    // Gaps between tracks show as smooth bands, like on a real record: darker than the grooves
    // either side, with a fine bright edge where the grooves stop. The 3D disc's surface maps
    // (deck/RecordMaps) put its glossy bands at the same radii.
    val playOuter = radius * 0.95f
    val playInner = radius * 0.43f
    val band = radius * 0.014f
    val pictured = look.style == DiscStyle.PICTURE && label != null
    trackBoundaries.forEach { f ->
        val r = playOuter - (playOuter - playInner) * f
        if (look.alpha < 1f || pictured) {
            drawCircle(Color.Black.copy(alpha = 0.30f), r, center, style = Stroke(band))
        } else {
            drawCircle(look.base, r, center, style = Stroke(band))
            drawCircle(Color.Black.copy(alpha = 0.22f), r, center, style = Stroke(band))
        }
        drawCircle(Color.White.copy(alpha = 0.16f), r + band / 2f, center, style = Stroke(hair))
        drawCircle(Color.White.copy(alpha = 0.10f), r - band / 2f, center, style = Stroke(hair))
    }

    // Light catching the grooves: drawn without rotation, so it stays put as the record turns.
    if (sheen) rotate(-38f, center) {
        drawCircle(
            brush = Brush.sweepGradient(
                0f to Color.Transparent,
                0.07f to Color.White.copy(alpha = 0.16f),
                0.15f to Color.Transparent,
                0.5f to Color.Transparent,
                0.57f to Color.White.copy(alpha = 0.10f),
                0.65f to Color.Transparent,
                1f to Color.Transparent,
                center = center,
            ),
            radius = grooveOuter,
            center = center,
        )
    }
    drawCircle(Color.White.copy(alpha = 0.16f), radius, center, style = Stroke(radius * 0.006f))

    // Label
    val lr = radius * 0.34f
    rotate(rotation, center) {
        val clip = Path().apply { addOval(Rect(center, lr)) }
        val pictureDisc = look.style == DiscStyle.PICTURE && label != null
        if (!pictureDisc) clipPath(clip) {
            if (label != null) {
                drawImage(
                    image = label,
                    dstOffset = IntOffset((center.x - lr).toInt(), (center.y - lr).toInt()),
                    dstSize = IntSize((lr * 2).toInt(), (lr * 2).toInt()),
                )
            } else {
                drawCircle(labelFallback, lr, center)
            }
        }
        if (!pictureDisc) drawCircle(Color.Black.copy(alpha = 0.22f), lr, center, style = Stroke(radius * 0.008f))
        else drawCircle(Color.White.copy(alpha = 0.18f), lr, center, style = Stroke(radius * 0.004f))
        sideText?.let { t ->
            val top = center + Offset(-t.size.width / 2f, -lr * 0.78f)
            drawRoundRect(
                Color.Black.copy(alpha = 0.42f),
                topLeft = top - Offset(radius * 0.02f, radius * 0.006f),
                size = androidx.compose.ui.geometry.Size(t.size.width + radius * 0.04f, t.size.height + radius * 0.012f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius * 0.03f),
            )
            drawText(t, topLeft = top)
        }
        qualityText?.let { t ->
            val top = center + Offset(-t.size.width / 2f, lr * 0.40f)
            drawRoundRect(
                Color.Black.copy(alpha = 0.42f),
                topLeft = top - Offset(radius * 0.02f, radius * 0.006f),
                size = androidx.compose.ui.geometry.Size(t.size.width + radius * 0.04f, t.size.height + radius * 0.012f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius * 0.03f),
            )
            drawText(t, topLeft = top)
        }
    }

    // Spindle hole
    drawCircle(Room.Chrome, radius * 0.024f, center)
    drawCircle(Color.Black.copy(alpha = 0.35f), radius * 0.024f, center, style = Stroke(radius * 0.005f))
}

/**
 * An album sleeve: the cover (or a plain sleeve in the album's colour if none was found), the
 * shrink-wrap sheen and, on the crate's sleeves, the hi-res sticker and nothing else. The title,
 * artist and [label] (on the platter, pull up · drag, sleeve · empty) are spoken, not printed.
 */
@Composable
fun SleeveArt(album: Album?, modifier: Modifier = Modifier, showSticker: Boolean = true, label: String? = null, missing: Int = 0) {
    val cover by rememberCover(album)
    // A record with no cover of its own wears the SONIC sleeve, cream in the light and black in the dark.
    val noArt = album != null && album.coverPath == null
    val placeholder = if (noArt) rememberNoCoverSleeve() else null
    val gap = if (missing > 0) "$missing ${if (missing == 1) "track" else "tracks"} missing" else null
    BoxWithConstraints(
        modifier
            .clipToBounds()
            .background(if (noArt) noCoverGround() else fallbackColor(album))
            .semantics { if (album != null) contentDescription = listOfNotNull(album.title, album.artist, label, gap).joinToString(" / ") },
    ) {
        val k = maxWidth / BOARD_SLEEVE
        val bitmap = cover
        if (bitmap != null) {
            Image(bitmap = bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else if (placeholder != null) {
            Image(bitmap = placeholder, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().testTag("sleeve-placeholder"))
        }
        // Shrink-wrap
        Box(
            Modifier.fillMaxSize().background(
                Brush.linearGradient(
                    0f to Color.White.copy(alpha = 0.0f),
                    0.42f to Color.White.copy(alpha = 0.0f),
                    0.5f to Color.White.copy(alpha = 0.18f),
                    0.58f to Color.White.copy(alpha = 0.0f),
                    1f to Color.White.copy(alpha = 0.06f),
                ),
            ),
        )
        // The sticker only where it'd be legible: the crate's sleeves, not the small thumbnails.
        if (k >= 0.55f && showSticker && album?.quality?.isHiRes == true) {
            HiResSticker(album.quality.shortLabel, k, Modifier.align(Alignment.TopEnd).padding(top = 6.dp * k, end = 4.dp * k))
        }
        if (k >= 0.55f && showSticker && missing > 0) {
            MissingTag(missing, k, Modifier.align(Alignment.BottomStart).padding(start = 6.dp * k, bottom = 6.dp * k))
        }
    }
}

/** A download of this record failed and wasn't fixed: a red tab, "1 MISSING", set a hair off true. */
@Composable
fun MissingTag(count: Int, k: Float = 1f, modifier: Modifier = Modifier) {
    val d = LocalDensity.current
    fun sp(v: Double) = with(d) { (v.dp * k).toSp() }
    BasicText(
        "$count MISSING",
        style = Room.Sticker.copy(color = Color.White, fontWeight = FontWeight.Bold, fontSize = sp(6.5), lineHeight = sp(7.0), letterSpacing = sp(0.6)),
        modifier = modifier.rotate(-2f).background(Room.Danger).padding(horizontal = 5.dp * k, vertical = 3.dp * k).testTag("missing-tag"),
    )
}

/** The board's front sleeve, which the stickers are sized against. */
private val BOARD_SLEEVE = 151.3.dp

/**
 * The hi-res mark, printed like an official badge: a black rectangle with a 1.5 dp white keyline,
 * "Hi-Res" over "AUDIO" in white Inter, the spec under a hairline, set a hair off true. It's
 * printed on the sleeve, so it stays black in the dark room too.
 */
@Composable
fun HiResSticker(text: String, k: Float = 1f, modifier: Modifier = Modifier) {
    val d = LocalDensity.current
    fun sp(v: Double) = with(d) { (v.dp * k).toSp() }
    Column(
        modifier
            .rotate(1f)
            .background(Color.Black)
            .padding(1.5.dp * k)
            .border(0.75.dp * k, Color.White)
            .padding(horizontal = 5.dp * k, vertical = 3.dp * k),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BasicText("Hi-Res", style = Room.Sticker.copy(color = Color.White, fontWeight = FontWeight.Bold, fontSize = sp(7.5), lineHeight = sp(8.0), letterSpacing = sp(0.1)))
        BasicText("AUDIO", style = Room.Sticker.copy(color = Color.White, fontWeight = FontWeight.Bold, fontSize = sp(5.2), lineHeight = sp(6.0), letterSpacing = sp(1.2)))
        Box(Modifier.padding(vertical = 2.dp * k).size(22.dp * k, 0.75.dp * k).background(Color.White))
        BasicText(text, style = Room.Sticker.copy(color = Color.White, fontWeight = FontWeight.Bold, fontSize = sp(6.5), lineHeight = sp(7.0)))
    }
}
