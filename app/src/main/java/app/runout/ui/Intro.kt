package app.runout.ui

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.runout.R
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * The intro, keyed to "TE · Onboarding · Intro storyboard (3 s)" (times in ms):
 *   0     the sleeve arrives from the right and stops in the middle (by 600);
 *   300   the disc rises out of it, spinning up a little;
 *   600   the sleeve leaves, the RG-1 fades up and the disc flies to the spindle;
 *   1200  it lands with a small thud, its shadow settling;
 *   1300  the tonearm swings in from its rest and, at 1650, drops into the groove (1800);
 *   1800  the grooves catch a light sweep;
 *   2400  RUNOUT types out in dot-matrix with the orange cursor, then holds.
 * The deck is the real render: the empty and playing RG-1, a loaded RG-1 with the arm lifted out,
 * and the arm itself as a sprite that turns about its pivot.
 */
private const val TYPE_AT = 2400
private const val LETTER_MS = 100
private const val INTRO_MS = 3000
private const val HOLD_MS = 1000
/** The storyboard plays this much slower than its timings above: unhurried, about 7 s in all. */
private const val PACE = 1.8f
/**
 * The owner's record's printed label, as a share of its diameter (intro-disc's is 39 %); drawn over
 * the deck renders' own (35 %), which it covers.
 */
private const val LABEL = 0.39f

/** The renders' pixel grid: every position below is in these units. */
private const val SRC_W = 2064f
private const val SRC_H = 1200f

/** The sleeve at 0.0 s and at 0.6 s, the disc at 0.6 s, the record on the platter, the arm. */
private const val SLEEVE = 1032f
private val SLEEVE_FROM = Offset(1069.5f, 281.25f)
private val SLEEVE_AT = Offset(487.9f, 281.25f)
private const val DISC = 900.6f
private val DISC_AT = Offset(1013.2f, 150.3f)

/**
 * Where a set of deck renders puts the record and the arm: the record's centre and size on the
 * platter, the arm picture's place, its pivot, its turn from rest onto the lead-in groove and the
 * stretch the camera adds.
 */
private class IntroGeometry(
    val recordCentre: Offset, val recordW: Float, val recordH: Float,
    val armTopLeft: Offset, val armW: Float, val armH: Float,
    val armPivot: Offset, val armSwing: Float, val armReach: Float,
) {
    companion object {
        /** The white / teal renders (res/drawable-nodpi/onboarding_deck_*), the arm cropped to itself. */
        val WhiteTeal = IntroGeometry(Offset(608f, 515f), 744f, 660f, Offset(1001f, 85f), 207f, 717f, Offset(1042f, 182f), 36f, 1.088f)
        /**
         * The dark and mono white RG-1's (assets/onboarding/deck-*-dark, -mono), rendered from the
         * room's own deck by DeckCapture, which logged these points through its camera. Their arm
         * picture is the whole frame.
         */
        val Captured = IntroGeometry(Offset(607.4f, 493.3f), 763f, 684f, Offset.Zero, SRC_W, SRC_H, Offset(1039.5f, 170.2f), 26.8f, 1.031f)
    }
}

/** The intro's pictures. */
private class IntroArt(
    val geometry: IntroGeometry,
    val empty: ImageBitmap,
    val loaded: ImageBitmap,
    val playing: ImageBitmap,
    val arm: ImageBitmap,
    val sleeve: ImageBitmap,
    val disc: ImageBitmap,
    /** When the disc is the owner's art (intro-disc), its label, laid over the deck renders' own. */
    val label: ImageBitmap? = null,
)

/**
 * The first thing a new listener sees (and, with Flags › Intro on launch, every opening), about
 * seven unhurried seconds on the room's paper, white or OLED black: a sleeve arrives, its disc
 * slides out and lands on the RG-1's platter, the tonearm swings in and drops, the grooves catch
 * the light, and RUNOUT types itself out in dot-matrix. Tap anywhere to skip. With system
 * animations off it hands over straight away and draws nothing.
 */
@Composable
fun RunoutIntro(onFinished: () -> Unit, modifier: Modifier = Modifier, deck: DeckSkin = DeckSkin.WHITE_TEAL) {
    val context = LocalContext.current
    val finish by rememberUpdatedState(onFinished)
    val animationsOff = remember {
        runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }.getOrDefault(1f) == 0f
    }
    val clock = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        if (animationsOff) { finish(); return@LaunchedEffect }
        // Runs on the frame clock, the hold included, so a tap or a test can step through it.
        clock.animateTo((INTRO_MS + HOLD_MS).toFloat(), tween(((INTRO_MS + HOLD_MS) * PACE).toInt(), easing = LinearEasing))
        finish()
    }
    if (animationsOff) {
        Box(modifier.fillMaxSize().background(Room.Background))
        return
    }
    // The sleeve and its record: the owner's art from assets/onboarding (intro-sleeve, intro-disc)
    // when it's there, else the drawn ones.
    val ownSleeve = rememberOnboardingPicture("intro-sleeve", 360.dp)
    val ownDisc = rememberOnboardingPicture("intro-disc", 360.dp)
    // The renders of the deck the room shows: the dark, mono white and neon RG-1's captured from the
    // room's own deck (assets/onboarding/deck-*-dark, -mono, -neon), read before the first frame so the intro never
    // opens on another deck; the white / teal ones built in.
    val px = with(LocalDensity.current) { 600.dp.roundToPx() }
    val captured = remember(deck) {
        introDeckPictures(deck)?.map { decodeOnboardingPicture(context.assets, it, px) }?.takeIf { it.all { p -> p != null } }
    }
    val art = IntroArt(
        if (captured != null) IntroGeometry.Captured else IntroGeometry.WhiteTeal,
        captured?.get(0) ?: ImageBitmap.imageResource(R.drawable.onboarding_deck_empty),
        captured?.get(1) ?: ImageBitmap.imageResource(R.drawable.onboarding_deck_loaded),
        captured?.get(2) ?: ImageBitmap.imageResource(R.drawable.onboarding_deck_playing),
        captured?.get(3) ?: ImageBitmap.imageResource(R.drawable.onboarding_deck_arm),
        ownSleeve ?: ImageBitmap.imageResource(R.drawable.onboarding_sleeve),
        ownDisc ?: ImageBitmap.imageResource(R.drawable.onboarding_disc),
        label = ownDisc,
    )
    val letters by remember { derivedStateOf { if (clock.value < TYPE_AT) 0 else ((clock.value - TYPE_AT) / LETTER_MS).toInt() + 1 } }
    // The cursor is solid while typing, then blinks through the hold.
    val cursor by remember {
        derivedStateOf {
            val ms = clock.value
            ms >= TYPE_AT - LETTER_MS && (ms < INTRO_MS || ((ms - INTRO_MS) / 250f).toInt() % 2 == 0)
        }
    }
    val lineIn by remember { derivedStateOf { phase(clock.value, 2900f, 3200f) } }
    // Over the room the deck is drawn exactly where the room's is, so it is already in its place
    // when you arrive; on its own, as wide as the screen and 132 dp down.
    val spot = LocalDeckSpot.current
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(Room.Background)
            .clearAndSetSemantics {
                testTag = "onboarding-intro"
                contentDescription = "Sonic. A record drops onto the platter. Tap to skip."
                role = Role.Button
                onClick { finish(); true }
            }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { finish() },
    ) {
        val roomDeck = spot?.rect
        Canvas(Modifier.fillMaxSize()) {
            val deck = roomDeck ?: Rect(Offset(0f, 132.dp.toPx()), Size(size.width, size.width * SRC_H / SRC_W))
            drawNeedleDrop(art, clock.value, deck)
        }
        // In landscape the room's deck is on the left band: the wordmark goes in the room's column beside it.
        val beside = roomDeck != null && maxWidth > maxHeight
        val besideStart = with(LocalDensity.current) { roomDeck?.right?.toDp() } ?: 0.dp
        // The wordmark block of TE · Onboarding · Intro: 44 / 22 / 33 / 22 in, 16 apart.
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + if (beside) WindowInsetsSides.End else WindowInsetsSides.Horizontal))
                .padding(start = 22.dp + if (beside) besideStart else 0.dp, end = 22.dp, top = 44.dp, bottom = 33.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Wordmark(66.sp, letters = letters, cursor = cursor, reserveCursor = true)
            BasicText("every record you own. one room to play them.", style = OnboardingBody, modifier = Modifier.fillMaxWidth().alpha(lineIn))
            Row(Modifier.fillMaxWidth().padding(top = 22.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                BasicText("a record drops onto the platter", style = Room.LabelQuiet)
                BasicText("tap to skip →", style = Room.Label)
            }
        }
    }
}

/** 0 → 1 over [a, b] ms, eased in and out; clamped outside it. */
private fun phase(t: Float, a: Float, b: Float): Float {
    val x = ((t - a) / (b - a)).coerceIn(0f, 1f)
    return x * x * (3f - 2f * x)
}

/** 0 → 1 over [a, b] ms, decelerating: for things that arrive. */
private fun arrive(t: Float, a: Float, b: Float): Float {
    val x = ((t - a) / (b - a)).coerceIn(0f, 1f)
    return 1f - (1f - x) * (1f - x) * (1f - x)
}

private fun lerp(a: Float, b: Float, f: Float) = a + (b - a) * f
private fun lerp(a: Offset, b: Offset, f: Float) = Offset(lerp(a.x, b.x, f), lerp(a.y, b.y, f))

/**
 * Draws the intro at [ms], the renders filling [frame] (whose shape is theirs, and the room's deck
 * view's: 1.72 to 1).
 */
private fun DrawScope.drawNeedleDrop(art: IntroArt, ms: Float, frame: Rect) {
    val k = frame.width / SRC_W
    fun at(p: Offset) = Offset(frame.left + p.x * k, frame.top + p.y * k)
    val deckOffset = IntOffset(frame.left.roundToInt(), frame.top.roundToInt())
    val deckSize = IntSize(frame.width.roundToInt(), (SRC_H * k).roundToInt())
    fun deck(image: ImageBitmap, alpha: Float) {
        if (alpha > 0f) drawImage(image, dstOffset = deckOffset, dstSize = deckSize, alpha = alpha.coerceIn(0f, 1f))
    }

    val g = art.geometry
    val deckIn = phase(ms, 600f, 950f)
    val landed = phase(ms, 1150f, 1300f)
    val swing = phase(ms, 1300f, 1650f)
    val drop = phase(ms, 1650f, 1800f)

    // The deck: empty, then loaded with the arm lifted out, then the real playing render.
    if (landed < 1f) deck(art.empty, deckIn)
    if (drop < 1f) deck(art.loaded, deckIn * landed)
    if (landed > 0f && drop < 1f) {
        val pivot = at(g.armPivot)
        val reach = lerp(1f, g.armReach, swing)
        withTransform({
            rotate(g.armSwing * swing, pivot)
            scale(reach, reach, pivot)
        }) {
            val tl = at(g.armTopLeft)
            drawImage(
                art.arm,
                dstOffset = IntOffset(tl.x.roundToInt(), tl.y.roundToInt()),
                dstSize = IntSize((g.armW * k).roundToInt(), (g.armH * k).roundToInt()),
                alpha = landed,
            )
        }
    }
    deck(art.playing, drop)
    // The owner's record keeps its own label once it's down, turned as it landed.
    if (landed > 0f) art.label?.let { drawLabel(it, at(g.recordCentre), g.recordW * k, g.recordH * k, LANDED_TURN, alpha = landed) }
    if (ms >= 1800f) drawGrooveLight(at(g.recordCentre), g.recordW * k / 2f, g.recordH * k / 2f, ms)

    // The disc: rises out of the sleeve, flies to the spindle, lands and hands over to the deck.
    val rise = phase(ms, 300f, 600f)
    val fly = phase(ms, 600f, 1150f)
    if (rise > 0f && landed < 1f) {
        val sleeveCentre = SLEEVE_FROM.let { lerp(it, SLEEVE_AT, arrive(ms, 0f, 600f)) } + Offset(SLEEVE / 2f, SLEEVE / 2f)
        val start = lerp(sleeveCentre, DISC_AT, rise)
        // A small arc on the way over, and the platter's perspective as it comes down.
        val centre = lerp(start, g.recordCentre, fly) - Offset(0f, 160f * sin(PI.toFloat() * fly))
        val thud = 1f - 0.035f * sin(PI.toFloat() * phase(ms, 1150f, 1250f))
        val w = lerp(DISC, g.recordW, fly) * k * thud
        val h = lerp(DISC, g.recordH, fly) * k * thud
        val spin = rise * 40f + fly * 160f
        val c = at(centre)
        // Its shadow drops away from it in flight and settles under it on landing.
        val lift = sin(PI.toFloat() * fly)
        if (fly > 0f) {
            drawOval(
                Color.Black.copy(alpha = 0.10f * lift * (1f - landed)),
                topLeft = c + Offset(-w / 2f, -h / 2f + 70f * k * lift),
                size = androidx.compose.ui.geometry.Size(w, h),
            )
        }
        withTransform({
            scale(1f, h / w, c)
            rotate(spin, c)
        }) {
            drawImage(
                art.disc,
                dstOffset = IntOffset((c.x - w / 2f).roundToInt(), (c.y - w / 2f).roundToInt()),
                dstSize = IntSize(w.roundToInt(), w.roundToInt()),
                alpha = 1f - landed,
            )
        }
    }

    // The sleeve: arrives from the right, stops in the middle, then drops away as the deck comes up.
    val leave = phase(ms, 600f, 1000f)
    if (leave < 1f) {
        val p = lerp(SLEEVE_FROM, SLEEVE_AT, arrive(ms, 0f, 600f)) + Offset(0f, 700f * leave)
        val tl = at(p)
        drawImage(
            art.sleeve,
            dstOffset = IntOffset(tl.x.roundToInt(), tl.y.roundToInt()),
            dstSize = IntSize((SLEEVE * k).roundToInt(), (SLEEVE * k).roundToInt()),
            alpha = 1f - leave,
        )
    }
}

/** How far the disc has turned by the time it lands (rise × 40° + fly × 160°). */
private const val LANDED_TURN = 200f

/**
 * The centre [LABEL] of [disc] as a printed label on a record [w] × [h] px (the platter's
 * perspective) at [c], turned [degrees]; drawn a hair larger so the render's own label can't show.
 */
private fun DrawScope.drawLabel(disc: ImageBitmap, c: Offset, w: Float, h: Float, degrees: Float, alpha: Float) {
    val side = minOf(disc.width, disc.height)
    val src = (side * LABEL).roundToInt()
    val d = w * LABEL * 1.04f
    val clip = androidx.compose.ui.graphics.Path().apply { addOval(androidx.compose.ui.geometry.Rect(c, d / 2f)) }
    withTransform({
        scale(1f, h / w, c)
        rotate(degrees, c)
    }) {
        clipPath(clip) {
            drawImage(
                disc,
                srcOffset = IntOffset((disc.width - src) / 2, (disc.height - src) / 2),
                srcSize = IntSize(src, src),
                dstOffset = IntOffset((c.x - d / 2f).roundToInt(), (c.y - d / 2f).roundToInt()),
                dstSize = IntSize(d.roundToInt(), d.roundToInt()),
                alpha = alpha.coerceIn(0f, 1f),
            )
        }
    }
}

/**
 * Light running round the grooves of the record on the platter: a soft white sweep that passes
 * once and leaves a faint sheen, kept off the label.
 */
private fun DrawScope.drawGrooveLight(c: Offset, rx: Float, ry: Float, ms: Float) {
    val glow = phase(ms, 1800f, 2400f)
    val angle = -60f + 300f * phase(ms, 1800f, 3000f)
    val inner = rx * 0.40f
    val outer = rx * 0.97f
    val band = outer - inner
    scale(1f, ry / rx, c) {
        rotate(angle, c) {
            drawCircle(
                Brush.sweepGradient(
                    0f to Color.Transparent,
                    0.08f to Color.White.copy(alpha = 0.30f * glow),
                    0.16f to Color.Transparent,
                    0.5f to Color.Transparent,
                    0.58f to Color.White.copy(alpha = 0.18f * glow),
                    0.66f to Color.Transparent,
                    1f to Color.Transparent,
                    center = c,
                ),
                radius = inner + band / 2f,
                center = c,
                style = Stroke(band),
            )
        }
    }
}

/**
 * A deck's captured renders in assets/onboarding, in [IntroArt]'s order (empty, loaded, playing,
 * arm); null for white / teal, whose renders are built in.
 */
internal fun introDeckPictures(deck: DeckSkin): List<String>? {
    val suffix = when (deck) {
        DeckSkin.DARK -> "dark"
        DeckSkin.MONO_WHITE -> "mono"
        DeckSkin.NEON -> "neon"
        else -> return null
    }
    return listOf("empty", "loaded", "playing", "arm").map { "deck-$it-$suffix" }
}
