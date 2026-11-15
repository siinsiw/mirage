package app.mirage.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.mirage.R
import app.mirage.data.Mode

/*
 * "Night room" (.claude/DESIGN-NIGHT.md): three themes. Neutral surfaces,
 * and one accent - Spotify Pulse - that belongs to the active item, the live
 * dot and the "Connected" state. The tokens are that file's §2, name for name.
 */

/** The three themes. Every older theme name maps to [GRAPHITE], except white / light. */
enum class Palette(
    val key: String,
    val label: String,
    /** Page. */
    val bg: Color,
    /** Slate Card. */
    val surface: Color,
    /** Raised Slate: pressed, chips, icon wells. */
    val surface2: Color,
    /** Edge: a card's 1dp border. */
    val stroke: Color,
    /** A ghost button's or a field's border. */
    val strokeStrong: Color,
    val text: Color,
    val text2: Color,
    val text3: Color,
    val off: Color,
    val work: Color,
    val ok: Color,
    val fail: Color,
    /** Hairline: dividers. */
    val hairline: Color,
    /** Spotify Pulse: the active item, the live dot, the focus ring, "Connected". */
    val pulse: Color,
    /** Spotify Halo: the glow behind the active item and the live dot. */
    val halo: Color,
    /** Ink Glass: the fill of a floating layer, over whatever is behind it. */
    val glass: Color,
    /** The solid stand-in where nothing is behind to frost. */
    val glassSolid: Color,
    /** The brighter top edge of a floating layer. */
    val glassTop: Color,
    /** Status "Good": a success said with a check and a word, never a glow. Not Pulse. */
    val good: Color,
    val light: Boolean = false,
) {
    /**
     * The default: Midnight, a deep navy. Cards are a lighter slate of the same
     * blue, glass over the map is that slate at half strength, and "Connected"
     * is a clean signal green.
     */
    MIDNIGHT("midnight", "Midnight",
        bg = Color(0xFF0A1220), surface = Color(0xFF151F31), surface2 = Color(0xFF1F2A40),
        stroke = Color(0x17FFFFFF), strokeStrong = Color(0x29FFFFFF),
        text = Color(0xFFF3F6FB), text2 = Color(0xFFA6B1C4), text3 = Color(0xFF8792A8),
        off = Color(0xFF8792A8), work = Color(0xFFFFC857), ok = Color(0xFF3CE25C), fail = Color(0xFFFF6B7A),
        hairline = Color(0x12FFFFFF), pulse = Color(0xFF3CE25C), halo = Color(0x733CE25C),
        glass = Color(0x99192436), glassSolid = Color(0xFF172133), glassTop = Color(0x38FFFFFF),
        good = Color(0xFF6BE38B)),

    /** Night, a neutral near-black. */
    GRAPHITE("graphite", "Graphite",
        bg = Color(0xFF0A0A0C), surface = Color(0xFF101013), surface2 = Color(0xFF17171B),
        stroke = Color(0x1AFFFFFF), strokeStrong = Color(0x2EFFFFFF),
        text = Color(0xFFF4F4F6), text2 = Color(0xFFA3A3AB), text3 = Color(0xFF8A8A94),
        off = Color(0xFF8A8A94), work = Color(0xFFFFC857), ok = Color(0xFF1ED760), fail = Color(0xFFFF6B7A),
        hairline = Color(0x12FFFFFF), pulse = Color(0xFF1ED760), halo = Color(0x731ED760),
        glass = Color(0x0BFFFFFF), glassSolid = Color(0xFF151518), glassTop = Color(0x2EFFFFFF),
        good = Color(0xFF6BE38B)),

    /** A light page. */
    WHITE("white", "White",
        bg = Color(0xFFF6F6F7), surface = Color(0xFFFFFFFF), surface2 = Color(0xFFECECEF),
        stroke = Color(0x17000000), strokeStrong = Color(0x29000000),
        text = Color(0xFF16171A), text2 = Color(0xFF555862), text3 = Color(0xFF6B6E78),
        off = Color(0xFF6B6E78), work = Color(0xFFB7791F), ok = Color(0xFF139A48), fail = Color(0xFFD1434F),
        hairline = Color(0x0F000000), pulse = Color(0xFF139A48), halo = Color(0x00139A48),
        glass = Color(0x9EFFFFFF), glassSolid = Color(0xFFFFFFFF), glassTop = Color(0xFFFFFFFF),
        good = Color(0xFF2A8A4A), light = true),

    /**
     * The one theme there is now (Mirage, 2026-11-13): Midnight's design on a
     * black page, token for token the Windows app's (app/ui/midnight.css).
     */
    BLACK("black", "Black",
        bg = Color(0xFF070304), surface = Color(0xFF181213), surface2 = Color(0xFF231A1B),
        stroke = Color(0x14FFFFFF), strokeStrong = Color(0x24FFFFFF),
        text = Color(0xFFFFFFFF), text2 = Color(0xFFADA4A4), text3 = Color(0xFF857B7B),
        // Mirage keeps to black, red and white: connected is white, working a pale red.
        off = Color(0xFF857B7B), work = Color(0xFFF0A8B0), ok = Color(0xFFF4F1EF), fail = Color(0xFFFF6B7A),
        hairline = Color(0x12FFFFFF), pulse = Color(0xFFF4F1EF), halo = Color(0x59F4F1EF),
        glass = Color(0x9E180E10), glassSolid = Color(0xFF181213), glassTop = Color(0x33FFFFFF),
        good = Color(0xFFF4F1EF));

    companion object {
        /** Black, whatever an older settings file says: the other themes are gone from the app. */
        @Suppress("UNUSED_PARAMETER")
        fun of(key: String?) = BLACK
    }
}

/**
 * The palette in use. It is Compose state: switching the theme repaints every
 * screen that drew with it, with nothing passed down.
 *
 * [mode] is the connection state, set once at the root ([app.mirage.ui.Root]);
 * [state] is its colour, the one accent the app has.
 */
object Tone {
    var palette by mutableStateOf(Palette.BLACK)

    /** What the tunnel is doing, for everything that colours itself by it. */
    var mode by mutableStateOf(Mode.OFF)

    val light get() = palette.light

    val bg get() = palette.bg
    val surface get() = palette.surface
    val surface2 get() = palette.surface2
    val stroke get() = palette.stroke
    val strokeStrong get() = palette.strokeStrong
    val hairline get() = palette.hairline
    val pulse get() = palette.pulse
    val halo get() = palette.halo
    val glass get() = palette.glass
    val glassSolid get() = palette.glassSolid
    val glassTop get() = palette.glassTop
    /** The neon is rationed, and off on a white page: the edge bar and the fill carry "active" there. */
    val glows get() = !palette.light

    val text get() = palette.text
    val text2 get() = palette.text2
    val text3 get() = palette.text3

    val off get() = palette.off
    val work get() = palette.work
    val ok get() = palette.ok
    val fail get() = palette.fail
    /** Success: the small check and its word. */
    val good get() = palette.good

    /** The state colour: the accent. */
    val state get() = stateOf(mode)
    fun stateOf(m: Mode): Color = when (m) {
        Mode.OFF -> palette.off
        Mode.BUSY -> palette.work
        Mode.ON -> palette.ok
        Mode.FAIL -> palette.fail
    }

    /** Text on a fill in the state colour, and on the primary button's Mist White. */
    val ink get() = if (light) Color.White else Color(0xFF060607)

    /** Focus rings are Pulse. */
    val focus get() = palette.pulse
    val failBg get() = palette.fail.copy(alpha = 0.16f)
    /** What a press lights a control with: white on a dark page, black on a light one. */
    val wash get() = if (light) Color.Black else Color.White
}

/**
 * The pastel data colours (DESIGN-NIGHT 2): charts and badges only, never
 * chrome. Deepened on the white page so a 1.5dp stroke still reads.
 */
object Data {
    /** Surfshark; the download line. */
    val periwinkle get() = if (Tone.light) Color(0xFF4F6FD8) else Color(0xFF8AA6FF)
    /** Windscribe. */
    val aqua get() = if (Tone.light) Color(0xFF138E9A) else Color(0xFF62E6F2)
    val peach get() = if (Tone.light) Color(0xFFC8642E) else Color(0xFFFFA877)
    /** The upload line. */
    val orchid get() = if (Tone.light) Color(0xFF8E52C9) else Color(0xFFCF9BFF)
    val lemon get() = if (Tone.light) Color(0xFF9A7B00) else Color(0xFFFFE27A)
    val rose get() = if (Tone.light) Color(0xFFC94A86) else Color(0xFFFF8FC3)

    /** A provider's own colour: Surfshark periwinkle, Windscribe aqua. */
    fun provider(key: String): Color = when (key) {
        "surfshark" -> periwinkle
        "windscribe" -> aqua
        else -> Tone.text3
    }
}

/**
 * The one piece of colour chrome is allowed: a cobalt-to-violet run for the
 * primary action of a view - the main button, the slide thumb, the selected
 * segment, the top-bar pill. Never a background, never text, never two on a
 * screen. Deepened on the white page.
 */
object Accent {
    // Mirage's dark red where Midnight had cobalt to violet (2026-11-13).
    val a get() = Color(0xFF8E1622)
    val b get() = Color(0xFFC21C2E)
    /** The colour halfway along: a single-colour stand-in (a focus ring, a thin stroke). */
    val mid get() = lerp(a, b, 0.5f)
    /** The soft light the accent throws under itself on a dark page. */
    val glow get() = if (Tone.light) Color.Transparent else Color(0x66C21C2E)
    /** Left to right, as the reference's pill and thumb run. */
    fun brush(): androidx.compose.ui.graphics.Brush =
        androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(a, b))
    /** Corner to corner, for a round thing (the slide thumb, the power core). */
    fun diagonal(size: androidx.compose.ui.geometry.Size): androidx.compose.ui.graphics.Brush =
        androidx.compose.ui.graphics.Brush.linearGradient(listOf(a, b),
            start = androidx.compose.ui.geometry.Offset.Zero,
            end = androidx.compose.ui.geometry.Offset(size.width, size.height))
}

/** The line colour of the hologram globe for one state. */
data class StateTone(val line: Color)

/**
 * The globe's line colour per state: the Kootaj hologram's own cyan while
 * disconnected, then the state colour itself (amber working, green connected,
 * red failed) so the globe says the same thing the status word does. On a
 * light page the cyan is deepened so it reads as ink. Read from the current
 * palette, so call it where a palette change should recompose.
 */
fun toneOf(mode: Mode): StateTone = StateTone(
    when (mode) {
        Mode.OFF -> Color(0xFFC21C2E)
        else -> Tone.stateOf(mode)
    })

/** The globe when it is not showing a connection at all - the welcome, the setup. */
fun restTone(): StateTone = toneOf(Mode.OFF)

/** Radii (DESIGN-NIGHT 4), and the timings the motion spec names. */
object Shape {
    /** A bottom sheet's top corners, a panel. */
    val sheet = 28.dp
    /** A card. */
    val card = 28.dp
    /** A tooltip, a menu, a field. */
    val tip = 14.dp
    /** A small well, a pressed row's backplate. */
    val control = 12.dp
    /** Chips, pills, buttons. */
    val pill = 999.dp
    const val ENTER_MS = 250
    const val FADE_MS = 83
    const val SINK_MS = 167
    /** The big moments: the backdrop, a screen change. */
    const val LARGE_MS = 333
    /** cubic-bezier(0, 0, 0, 1): leaves at once, arrives slowly. */
    val ease = CubicBezierEasing(0f, 0f, 0f, 1f)
    /** cubic-bezier(0.55, 0.55, 0, 1): point to point. */
    val swing = CubicBezierEasing(0.55f, 0.55f, 0f, 1f)

    /* The motion spec's named eases (DESIGN-NIGHT 6), as their usual cubic-bezier stand-ins. */
    /** expo.out: a drawer opening. */
    val expoOut = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)
    /** power2.in: a drawer closing. */
    val power2In = CubicBezierEasing(0.55f, 0.085f, 0.68f, 0.53f)
    /** power2.out: a number settling. */
    val power2Out = CubicBezierEasing(0.25f, 0.46f, 0.45f, 0.94f)
    /** power3.out: the active glow moving. */
    val power3Out = CubicBezierEasing(0.215f, 0.61f, 0.355f, 1f)
    /** Drawer open / close, ms. */
    const val DRAWER_OPEN_MS = 300
    const val DRAWER_CLOSE_MS = 180
}

/** The 4-pt ramp. Nothing else is a gap. */
object Space {
    val x1 = 4.dp
    val x2 = 8.dp
    val x3 = 12.dp
    val x4 = 16.dp
    val x5 = 20.dp
    val x6 = 24.dp
    val x8 = 32.dp
    val x10 = 40.dp
    val x12 = 48.dp
    val x16 = 64.dp
}

/**
 * Geist (OFL, github.com/vercel/geist-font) as one static file per weight:
 * the variable font is not used because MIUI and some other skins ignore the
 * weight axis and draw every weight at the lightest. Persian has no glyphs in
 * it: text in Persian is set in [Persian] instead, through [script].
 */
val Sans = FontFamily(
    Font(R.font.geist_400, FontWeight.W400),
    Font(R.font.geist_500, FontWeight.W500),
    Font(R.font.geist_600, FontWeight.W600),
)

/**
 * Estedad (OFL, github.com/aminabedi68/Estedad; licence in assets/OFL-Estedad.txt):
 * Persian, 400 body / 500 titles / 600 hero. One variable file, its weight set
 * per entry (Android 8+; older phones draw it at 400).
 */
@OptIn(ExperimentalTextApi::class)
val Persian = FontFamily(
    Font(R.font.estedad, FontWeight.W400, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.estedad, FontWeight.W500, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.estedad, FontWeight.W600, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
)

/** Whether [s] has any Arabic-script letter in it: Persian, for the type's purposes. */
fun isPersian(s: CharSequence): Boolean = s.any { it in '\u0600'..'\u06FF' || it in '\uFB50'..'\uFDFF' || it in '\uFE70'..'\uFEFC' }

/**
 * This style, or its Persian twin when [text] is Persian: Estedad, the labels
 * half a size up, line height 1.9. For text the app does not write itself -
 * an app's name, an account's label, a server's nickname.
 */
fun TextStyle.script(text: CharSequence): TextStyle {
    if (!isPersian(text)) return this
    val size = if (fontSize.isSp && fontSize.value < 13f) 13.sp else fontSize
    return copy(fontFamily = Persian, fontSize = size,
        lineHeight = if (size.isSp) (size.value * 1.9f).sp else lineHeight, letterSpacing = 0.sp)
}

/**
 * Cinzel 500 (OFL, github.com/google/fonts; licence in assets/OFL-Cinzel.txt):
 * the MIRAGE word only, set wide. The same drawing the Windows app ships.
 */
val Cinzel = FontFamily(Font(R.font.cinzel_500, FontWeight.W500))

/**
 * Mirage's own colours, for the first-run screens (preview/DECISIONS.md):
 * black and one dark red, nothing else. [ink] is the warm white of its type.
 */
object Mirage {
    val red = Color(0xFFA3141F)
    val redHi = Color(0xFFC21C2E)
    val redLo = Color(0xFF6E0D16)
    val page = Color(0xFF060607)
    val ink = Color(0xFFEDE6E3)
    /** Body copy on the black: a warm grey. */
    val say = Color(0xFFA8A2A0)
    /** A note under a label. */
    val note = Color(0xFF9C9592)
    /** A field's caption. */
    val cap = Color(0xFFB5ACA9)
    /** The light that runs round an edge, and its bright tip. */
    val light = Color(0xFFC2253A)
    val tip = Color(0xFFFFD7DC)
    /** A bad field's line under it. */
    val bad = Color(0xFFF07A86)
    /** The top bar's mark and buttons: bright enough to read on the dark. */
    val bright = Color(0xFFE0303F)
}

/** Geist Mono: anything that is a value rather than a sentence. */
val Mono = FontFamily(
    Font(R.font.geist_mono, FontWeight.Normal),
    Font(R.font.geist_mono_medium, FontWeight.Medium),
)

/**
 * The scale (DESIGN-NIGHT 3): hierarchy by weight and colour more than size -
 * labels 12.5 Dust, body 14 Fog, card titles 15/500 Mist White, display tight.
 * No uppercase and no wide tracking. Anything that updates live is tabular.
 */
object Type {
    /** 12.5/17 - metadata, counts, helper text: Dust. */
    val caption get() = TextStyle(fontFamily = Sans, fontWeight = FontWeight.W400, fontSize = 12.5.sp,
        lineHeight = 17.sp, color = Tone.text3)
    /** 14/20 - paragraphs. */
    val body get() = TextStyle(fontFamily = Sans, fontWeight = FontWeight.W400, fontSize = 14.sp,
        lineHeight = 20.sp, color = Tone.text2)
    /** 15/21 500 - row and card titles: Mist White. */
    val strong get() = TextStyle(fontFamily = Sans, fontWeight = FontWeight.W500, fontSize = 15.sp,
        lineHeight = 21.sp, color = Tone.text)
    /** 16/22 500 - a heading inside a screen. */
    val subtitle get() = TextStyle(fontFamily = Sans, fontWeight = FontWeight.W500, fontSize = 16.sp,
        lineHeight = 22.sp, letterSpacing = (-0.005).em, color = Tone.text)
    /** 20/28 500 - screen titles, the location line on home. */
    val title get() = TextStyle(fontFamily = Sans, fontWeight = FontWeight.W500, fontSize = 20.sp,
        lineHeight = 28.sp, letterSpacing = (-0.01).em, color = Tone.text)
    /** 28/36 600 - a big value when one is needed. */
    val display get() = TextStyle(fontFamily = Sans, fontWeight = FontWeight.W600, fontSize = 28.sp,
        lineHeight = 36.sp, letterSpacing = (-0.02).em, color = Tone.text)
    /** 44/48 600, tight - the one big word on home. */
    val status get() = TextStyle(fontFamily = Sans, fontWeight = FontWeight.W600, fontSize = 44.sp,
        lineHeight = 48.sp, letterSpacing = (-0.03).em, color = Tone.text)

    /** 12/16 mono, tabular - ping, hosts, ports. */
    val mono get() = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Normal, fontSize = 12.sp,
        lineHeight = 16.sp, fontFeatureSettings = "tnum", color = Tone.text2)
    /** 14/20 mono, tabular - the timer. */
    val monoBody get() = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Normal, fontSize = 14.sp,
        lineHeight = 20.sp, fontFeatureSettings = "tnum", color = Tone.text)
    /** An address that has to be read: 14/20 mono. */
    val ip get() = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Normal, fontSize = 14.sp,
        lineHeight = 20.sp, fontFeatureSettings = "tnum", color = Tone.text)
    /** Buttons: 15/22 500. */
    val button get() = TextStyle(fontFamily = Sans, fontWeight = FontWeight.W500, fontSize = 15.sp,
        lineHeight = 22.sp, color = Tone.text)
}

/** Material's own type roles, set in Geist, so anything Material draws by itself matches. */
private fun androidx.compose.material3.Typography.inSans() = androidx.compose.material3.Typography(
    displayLarge = displayLarge.copy(fontFamily = Sans), displayMedium = displayMedium.copy(fontFamily = Sans),
    displaySmall = displaySmall.copy(fontFamily = Sans), headlineLarge = headlineLarge.copy(fontFamily = Sans),
    headlineMedium = headlineMedium.copy(fontFamily = Sans), headlineSmall = headlineSmall.copy(fontFamily = Sans),
    titleLarge = titleLarge.copy(fontFamily = Sans), titleMedium = titleMedium.copy(fontFamily = Sans),
    titleSmall = titleSmall.copy(fontFamily = Sans), bodyLarge = bodyLarge.copy(fontFamily = Sans),
    bodyMedium = bodyMedium.copy(fontFamily = Sans), bodySmall = bodySmall.copy(fontFamily = Sans),
    labelLarge = labelLarge.copy(fontFamily = Sans), labelMedium = labelMedium.copy(fontFamily = Sans),
    labelSmall = labelSmall.copy(fontFamily = Sans),
)

/**
 * Material's scheme, filled from the palette: neutral surfaces, Pulse as the
 * primary (the one accent: a switch that is on, a focused field), no tint.
 */
@Composable
fun MirageTheme(content: @Composable () -> Unit) {
    val p = Tone.palette
    val scheme = (if (p.light) lightColorScheme() else darkColorScheme()).copy(
        background = p.bg, onBackground = p.text,
        surface = p.surface, onSurface = p.text, surfaceVariant = p.surface2, onSurfaceVariant = p.text2,
        surfaceTint = Color.Transparent, surfaceBright = p.surface2, surfaceDim = p.bg,
        surfaceContainerLowest = p.bg, surfaceContainerLow = p.surface, surfaceContainer = p.surface,
        surfaceContainerHigh = p.surface2, surfaceContainerHighest = p.surface2,
        primary = Accent.mid, onPrimary = Color.White, primaryContainer = p.surface2, onPrimaryContainer = p.text,
        secondary = p.text2, onSecondary = p.bg, secondaryContainer = p.surface2, onSecondaryContainer = p.text,
        tertiary = p.text2, onTertiary = p.bg, tertiaryContainer = p.surface2, onTertiaryContainer = p.text,
        outline = p.strokeStrong, outlineVariant = p.stroke,
        error = p.fail, onError = Tone.ink, errorContainer = p.fail.copy(alpha = 0.16f), onErrorContainer = p.fail,
        inverseSurface = p.text, inverseOnSurface = p.bg, inversePrimary = p.pulse, scrim = Color.Black,
    )
    MaterialTheme(colorScheme = scheme, typography = androidx.compose.material3.Typography().inSans(), content = content)
}
