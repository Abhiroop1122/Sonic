package app.runout.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.runout.R

/**
 * The room's palette and type, after Teenage Engineering's catalogue: black ink on white (or white
 * on true black in the dark theme), hairline rules, square corners, no shadows, and one dot-matrix
 * face for every word. Colour is a signal,
 * never a fill: orange for what's playing, blue for links, green for online, amber for warnings,
 * red for errors. The 3D deck is the only rich object on screen.
 */
object Room {
    /**
     * Dark (OLED) or light. Every colour below reads it, so the room repaints when the theme
     * changes; MainActivity sets it from Settings › Flags › theme and the phone's own setting.
     */
    var dark by mutableStateOf(false)

    private val palette: Palette get() = if (dark) Palette.Dark else Palette.Light

    val Background: Color get() = palette.background
    val Surface: Color get() = palette.surface
    val Ink: Color get() = palette.ink
    /** What sits on an ink fill (a black key, a toast): white in light, black in dark. */
    val OnInk: Color get() = palette.background
    val NearBlack: Color get() = palette.nearBlack
    val Body: Color get() = palette.body
    val Quiet: Color get() = palette.quiet
    val Faint: Color get() = palette.faint
    val Hairline: Color get() = palette.hairline
    val HairlineSoft: Color get() = palette.hairlineSoft
    val Chrome: Color get() = Hairline

    /** Links and information. */
    val Accent: Color get() = palette.accent
    /** What's playing or active: Teenage Engineering orange, the same in both themes. */
    val Signal = Color(0xFFF05A24)
    val SignalLight = Color(0xFFF7A07F)
    val SignalDark = Color(0xFFB8421A)
    val Online: Color get() = palette.online
    val Warning = Color(0xFFFAB413)
    val Danger: Color get() = palette.danger
    /** Behind every panel and dialog. */
    val Scrim: Color get() = palette.scrim
    /** The other theme's red and body grey, for what's set on an ink fill (the delete warning). */
    val InverseDanger: Color get() = (if (dark) Palette.Light else Palette.Dark).danger
    val InverseBody: Color get() = (if (dark) Palette.Light else Palette.Dark).body

    /** The two palettes. Dark is true black for OLED: lit pixels are only type, rules and signal. */
    private class Palette(
        val background: Color, val surface: Color, val ink: Color, val nearBlack: Color,
        val body: Color, val quiet: Color, val faint: Color, val hairline: Color, val hairlineSoft: Color,
        val accent: Color, val online: Color, val danger: Color, val scrim: Color,
    ) {
        companion object {
            val Light = Palette(
                background = Color(0xFFFFFFFF), surface = Color(0xFFF5F5F5), ink = Color(0xFF000000), nearBlack = Color(0xFF0F0E12),
                body = Color(0xFF4D4D4D), quiet = Color(0xFF767676), faint = Color(0xFFB2B2B2), hairline = Color(0xFFCCCCCC), hairlineSoft = Color(0xFFE5E5E5),
                accent = Color(0xFF0071BB), online = Color(0xFF006837), danger = Color(0xFFB81D13), scrim = Color(0x99767676),
            )
            val Dark = Palette(
                background = Color(0xFF000000), surface = Color(0xFF111111), ink = Color(0xFFFFFFFF), nearBlack = Color(0xFFF2F1F5),
                body = Color(0xFFB3B3B3), quiet = Color(0xFF8C8C8C), faint = Color(0xFF4D4D4D), hairline = Color(0xFF333333), hairlineSoft = Color(0xFF1A1A1A),
                accent = Color(0xFF4DA6FF), online = Color(0xFF2FBF71), danger = Color(0xFFFF5040), scrim = Color(0xCC000000),
            )
        }
    }

    /** The grotesk, kept for the stickers printed on sleeves. */
    val Inter = FontFamily(
        Font(R.font.inter_regular, FontWeight.Normal),
        Font(R.font.inter_medium, FontWeight.Medium),
        Font(R.font.inter_semibold, FontWeight.SemiBold),
        Font(R.font.inter_bold, FontWeight.Bold),
        Font(R.font.inter_extrabold, FontWeight.ExtraBold),
    )

    /** The interface face; see [TeType]. */
    val Dot: FontFamily get() = TeType.family

    // The type scale of runout-te.pen: Array at two weights, 400, and 700 (the dotted cut) only from 22 up.
    /** label / 11 / 400: the smallest size anything is set in. */
    val Label: TextStyle get() = TextStyle(fontFamily = Dot, fontSize = 11.sp, fontWeight = FontWeight.Normal, color = Ink)
    val LabelQuiet: TextStyle get() = Label.copy(color = Quiet)
    /** body / 12 / 400: row titles, keys, links. */
    val Text: TextStyle get() = Label.copy(fontSize = 12.sp)
    /** 12 / 400 in ink: the side on the top bar, a chat head. Hierarchy comes from size and colour, not weight. */
    val Strong: TextStyle get() = Label.copy(fontSize = 12.sp)
    /** heading / 16 / 400: fields and headings. */
    val Heading: TextStyle get() = Label.copy(fontSize = 16.sp)
    /** 18 / 400: the album under the crate. */
    val Album: TextStyle get() = Label.copy(fontSize = 18.sp)
    /** title / 22 / 700: panel and dialog titles. */
    val Title: TextStyle get() = Label.copy(fontSize = 22.sp, lineHeight = 25.sp, fontWeight = FontWeight.Bold)
    val Brand: TextStyle get() = Label.copy(fontWeight = FontWeight.Bold)
    /** display / 44 / 700: the screen titles. */
    val Display: TextStyle get() = Label.copy(fontSize = 44.sp, fontWeight = FontWeight.Bold)
    /** The stickers on the shrink-wrap are printed in Inter. */
    val Sticker: TextStyle get() = TextStyle(fontFamily = Inter, fontWeight = FontWeight.ExtraBold, color = Ink)
}

/**
 * The dot-matrix face. Array (Indian Type Foundry, via Fontshare) when the build carries it in
 * assets/fonts/licensed: its licence lets the app embed it, not a public repository share it, so it
 * stays out of git. Otherwise Silkscreen (SIL Open Font License), which the repository ships.
 */
object TeType {
    private const val LICENSED = "fonts/licensed"
    private const val FALLBACK = "fonts/silkscreen.ttf"

    @Volatile
    var family: FontFamily = FontFamily.Monospace
        private set

    /** Whether Array is in this build (shown in About). */
    var isArray = false
        private set

    fun init(context: Context) {
        if (family != FontFamily.Monospace) return
        val assets = context.applicationContext.assets
        val licensed = runCatching { assets.list(LICENSED)?.toSet() }.getOrNull().orEmpty()
        family = if ("Array-Regular.ttf" in licensed) {
            isArray = true
            FontFamily(
                // The design sets only 400 and 700; anything heavier than 500 takes the dotted Bold.
                listOfNotNull(
                    Font("$LICENSED/Array-Regular.ttf", assets, FontWeight.Normal),
                    if ("Array-Bold.ttf" in licensed) Font("$LICENSED/Array-Bold.ttf", assets, FontWeight.Bold) else null,
                ),
            )
        } else {
            runCatching { FontFamily(Font(FALLBACK, assets)) }.getOrDefault(FontFamily.Monospace)
        }
    }
}
