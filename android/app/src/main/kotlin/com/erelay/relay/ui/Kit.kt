package com.erelay.relay.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.erelay.relay.data.Mode
import com.erelay.relay.ui.theme.Accent
import com.erelay.relay.ui.theme.Shape
import com.erelay.relay.ui.theme.Space
import com.erelay.relay.ui.theme.Tone
import com.erelay.relay.ui.theme.Type
import com.erelay.relay.ui.theme.script
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/*
 * The Midnight kit: the parts the redesign adds on top of the Night room's -
 * the power button, the three-figure stats strip, the round flag with its
 * glass highlight, the small "Connect" pill, the accent pill of the top bar,
 * rolling digits and the page dots. Every moving part is still when the
 * phone has animations off, and nothing loops unless it is saying something
 * (connecting, connected). Connecting and disconnecting are always one press.
 *
 * Patterns taken from (adapted to Compose, no code copied):
 *  - stats strip: Tremor's "category bar / KPI" pattern (tremor.so)
 *  - rolling digits: the odometer ticker on reactbits.dev (Counter)
 *  - page dots: the stretching active pill of onboarding carousels (mobbin.com, pageflows.com)
 */

// -- light ------------------------------------------------------------------------------------

/**
 * The soft light the accent throws under whatever carries it: the outline of
 * [shape] grown a few times, each fainter, dropped a few dp. Nothing on a
 * white page.
 */
fun Modifier.accentGlow(shape: androidx.compose.ui.graphics.Shape): Modifier = drawBehind {
    if (Tone.light) return@drawBehind
    val outline = shape.createOutline(size, layoutDirection, this)
    val glow = Accent.glow
    val step = 3.dp.toPx()
    translate(top = 4.dp.toPx()) {
        for (i in 5 downTo 1) {
            val d = step * i
            scale(1f + 2 * d / size.width, 1f + 2 * d / size.height, pivot = center) {
                drawOutline(outline, glow.copy(alpha = glow.alpha * 0.16f * (1.2f - i / 6f)))
            }
        }
    }
}

/** 0..1 forever while [active], every [ms]; a still 0 otherwise, so nothing redraws for it. */
@Composable
private fun loopOf(active: Boolean, ms: Int, label: String): State<Float> {
    if (!active) return remember { mutableFloatStateOf(0f) }
    val t = rememberInfiniteTransition(label = label)
    return t.animateFloat(0f, 1f, infiniteRepeatable(tween(ms, easing = LinearEasing)), label = label)
}

// -- the power button -------------------------------------------------------------------------

/**
 * The one control the home screen is built round: a raised disc with the
 * power mark, in two faint rings.
 *
 *  off      the disc at rest, the mark in Mist White
 *  busy     an accent arc runs round the disc, the mark amber
 *  on       a green aura comes up under it and rings breathe outward, the mark green
 *  fail     the mark red, still
 *
 * A press sinks the disc and gives the confirm haptic. [size] is the whole
 * thing, rings included; the disc is half of it.
 */
@Composable
fun PowerButton(
    mode: Mode,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 184.dp,
    label: String = when (mode) {
        Mode.ON -> "Disconnect"
        Mode.BUSY -> "Cancel"
        else -> "Connect"
    },
) {
    val reduced = rememberReducedMotion()
    val on = mode == Mode.ON
    val busy = mode == Mode.BUSY
    val tint by animateColorAsState(
        when (mode) {
            Mode.ON -> Tone.ok
            Mode.BUSY -> Tone.work
            Mode.FAIL -> Tone.fail
            Mode.OFF -> Tone.text
        }, tween(if (reduced) 0 else 450, easing = Shape.ease), label = "powerTint")
    val aura by animateFloatAsState(if (on) 1f else 0f,
        tween(if (reduced) 0 else 900, easing = Shape.ease), label = "powerAura")
    val arc by animateFloatAsState(if (busy) 1f else 0f,
        tween(if (reduced) 0 else 300), label = "powerArc")
    val spin = loopOf(busy && !reduced, 1300, "powerSpin")
    val wave = loopOf(on && !reduced, 3200, "powerWave")
    val light = Tone.light
    val ok = Tone.ok

    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val c = center
            val r = this.size.minDimension / 2f
            val hair = 1.dp.toPx()
            val ring = if (light) Color.Black else Color.White
            // The two faint rings round it.
            drawCircle(ring.copy(alpha = 0.045f), r * 0.985f, c, style = Stroke(hair))
            drawCircle(ring.copy(alpha = 0.07f), r * 0.76f, c, style = Stroke(hair))
            // Connected: the aura under the disc, and rings breathing out of it.
            if (aura > 0.01f) {
                drawCircle(Brush.radialGradient(listOf(ok.copy(alpha = 0.26f * aura), Color.Transparent),
                    c, r * 0.98f), r * 0.98f, c)
                if (!reduced) for (k in 0 until 3) {
                    val p = (wave.value + k / 3f) % 1f
                    drawCircle(ok.copy(alpha = 0.32f * (1f - p) * aura), r * (0.54f + 0.44f * p), c,
                        style = Stroke(1.5.dp.toPx()))
                }
            }
            // Connecting: an accent arc chasing round the disc.
            if (arc > 0.01f) {
                rotate(spin.value * 360f, c) {
                    val rr = r * 0.62f
                    drawArc(
                        Brush.sweepGradient(listOf(Color.Transparent, Accent.a.copy(alpha = arc), Accent.b.copy(alpha = arc)), c),
                        0f, 300f, false, Offset(c.x - rr, c.y - rr), Size(rr * 2, rr * 2),
                        style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
                }
            }
            // The disc: a shadow it casts, a lit face, a brighter rim along the top.
            val d = r * 0.5f
            drawCircle(Brush.radialGradient(listOf(Color.Black.copy(alpha = if (light) 0.16f else 0.5f), Color.Transparent),
                Offset(c.x, c.y + d * 0.28f), d * 1.25f), d * 1.25f, Offset(c.x, c.y + d * 0.28f))
            val face = if (light) listOf(Color.White, Color(0xFFE3E6EE)) else listOf(Color(0xFF4B5468), Color(0xFF262D3D))
            drawCircle(Brush.radialGradient(face, Offset(c.x - d * 0.35f, c.y - d * 0.45f), d * 1.6f), d, c)
            drawCircle(Brush.verticalGradient(listOf(ring.copy(alpha = if (light) 0.10f else 0.22f), Color.Transparent),
                startY = c.y - d, endY = c.y + d * 0.2f), d - hair / 2, c, style = Stroke(hair))
            if (aura > 0.01f) drawCircle(ok.copy(alpha = 0.35f * aura), d - hair / 2, c, style = Stroke(hair))
        }
        Box(
            Modifier
                .size(size * 0.5f)
                .press(enabled, CircleShape, sink = true, sinkTo = 0.94f,
                    feel = androidx.compose.ui.hapticfeedback.HapticFeedbackType.Confirm, onClick = onClick)
                .clip(CircleShape)
                .semantics { contentDescription = label; role = Role.Button },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Glyph.power, null, Modifier.size(size * 0.19f).alpha(if (enabled) 1f else 0.45f), tint = tint)
        }
    }
}

// -- stats ----------------------------------------------------------------------------------------

/** One figure of the stats strip: a caption, a value, its unit. */
data class Stat(val caption: String, val value: String, val unit: String)

/**
 * Download, ping and upload side by side, hairlines between, each with its
 * mark: the strip on the server sheet's card. The values roll when they change.
 */
@Composable
fun StatStrip(down: Stat, ping: Stat, up: Stat, pingLevel: Int, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        StatCell(down, Modifier.weight(1f)) {
            Icon(Glyph.arrowDownLeft, null, Modifier.size(18.dp), tint = Tone.text2)
        }
        Box(Modifier.width(1.dp).height(32.dp).background(Tone.hairline))
        StatCell(ping, Modifier.weight(1f)) { SignalBars(pingLevel, Modifier.size(16.dp, 12.dp)) }
        Box(Modifier.width(1.dp).height(32.dp).background(Tone.hairline))
        StatCell(up, Modifier.weight(1f)) {
            Icon(Glyph.arrowUpRight, null, Modifier.size(18.dp), tint = Tone.text2)
        }
    }
}

@Composable
private fun StatCell(s: Stat, modifier: Modifier, mark: @Composable () -> Unit) {
    Row(modifier.padding(horizontal = Space.x1), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center) {
        mark()
        Spacer(Modifier.width(Space.x2))
        Column {
            Text(s.caption, style = Type.caption.copy(fontSize = 12.sp), maxLines = 1)
            Row(verticalAlignment = Alignment.Bottom) {
                RollingText(s.value, Type.display.copy(fontSize = 22.sp, lineHeight = 26.sp,
                    fontFeatureSettings = "tnum"))
                Text(s.unit, style = Type.caption.copy(color = Tone.text2, fontSize = 12.sp),
                    modifier = Modifier.padding(start = 2.dp, bottom = 3.dp), maxLines = 1)
            }
        }
    }
}

/** Four bars, [level] of them lit (0..4) in green, the rest a faint stub: how quick an answer is. */
@Composable
fun SignalBars(level: Int, modifier: Modifier = Modifier, color: Color = Tone.ok) {
    val dim = Tone.text3.copy(alpha = 0.35f)
    Canvas(modifier) {
        val n = 4
        val gap = size.width * 0.12f
        val w = (size.width - gap * (n - 1)) / n
        for (i in 0 until n) {
            val h = size.height * (0.35f + 0.65f * (i + 1) / n)
            drawRoundRect(if (i < level) color else dim, Offset(i * (w + gap), size.height - h), Size(w, h),
                androidx.compose.ui.geometry.CornerRadius(w / 3))
        }
    }
}

/** How many bars a round trip earns: under 80 ms all four, then fewer, none when there is no answer. */
fun barsFor(ms: Int?): Int = when {
    ms == null || ms <= 0 -> 0
    ms < 80 -> 4
    ms < 160 -> 3
    ms < 300 -> 2
    else -> 1
}

/**
 * Text whose characters roll when they change - a digit slides up out of its
 * slot and the next one up into it - the way an odometer turns. Characters
 * that stay the same stay still. Plain text when animations are off.
 */
@Composable
fun RollingText(text: String, style: TextStyle, modifier: Modifier = Modifier) {
    if (rememberReducedMotion()) {
        Text(text, style = style, modifier = modifier, maxLines = 1)
        return
    }
    Row(modifier) {
        for ((i, ch) in text.withIndex()) {
            // Keyed by position from the end, so "9" -> "10" rolls the units, not every slot.
            androidx.compose.runtime.key(text.length - i) {
                AnimatedContent(ch, transitionSpec = {
                    val up = targetState > initialState
                    (slideInVertically(tween(260, easing = Shape.ease)) { if (up) it else -it } + fadeIn(tween(200))) togetherWith
                        (slideOutVertically(tween(220)) { if (up) -it else it } + fadeOut(tween(160)))
                }, label = "roll") { c ->
                    Text(c.toString(), style = style, maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

// -- flags and pills --------------------------------------------------------------------------

/**
 * A country's flag as a glass bead: the round flag, a highlight across its
 * upper half and a darker lower rim, so it sits on the card like the
 * reference's rather than as a flat sticker. [code] empty draws the globe.
 */
@Composable
fun FlagDisc(code: String, size: Dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size)) {
        Flag(code, size)
        Box(Modifier.size(size).clip(CircleShape).drawBehind {
            drawCircle(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.28f), Color.Transparent),
                startY = 0f, endY = this.size.height * 0.55f))
            drawCircle(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.22f)),
                startY = this.size.height * 0.55f, endY = this.size.height))
        })
    }
}

/**
 * The small pill at a row's end - "Connect" in the reference's server list.
 * [current] is the row in use: it carries the accent run instead.
 */
@Composable
fun ConnectPill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    current: Boolean = false,
    enabled: Boolean = true,
) {
    val pill = RoundedCornerShape(Shape.pill)
    Box(
        modifier
            .heightIn(min = 40.dp)
            .press(enabled, pill, sink = true, onClick = onClick)
            .clip(pill)
            .then(if (current) Modifier.background(Accent.brush()) else
                Modifier.background(if (Tone.light) Tone.surface2 else Color.White.copy(alpha = 0.07f)))
            .padding(horizontal = 18.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = Type.button.copy(fontSize = 14.sp,
            color = (if (current) Color.White else Tone.text).copy(alpha = if (enabled) 1f else 0.5f)).script(text),
            maxLines = 1)
    }
}

/**
 * The top bar's accent pill - the reference's "Premium": an icon and a word on
 * the accent run, its glow under it. Here it carries the way out.
 */
@Composable
fun AccentPill(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector?, onClick: () -> Unit,
               modifier: Modifier = Modifier, enabled: Boolean = true, trailing: androidx.compose.ui.graphics.vector.ImageVector? = null) {
    val pill = RoundedCornerShape(Shape.pill)
    Row(
        modifier
            .heightIn(min = 44.dp)
            .press(enabled, pill, sink = true, onClick = onClick)
            .accentGlow(pill)
            .clip(pill)
            .background(Accent.brush(), alpha = if (enabled) 1f else 0.5f)
            .drawWithContent {
                drawContent()
                // A glassy top edge.
                drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.18f), Color.Transparent),
                    endY = size.height * 0.5f))
            }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(17.dp), tint = Color.White)
            Spacer(Modifier.width(Space.x2))
        }
        Text(text, style = Type.button.copy(color = Color.White, fontSize = 15.sp).script(text), maxLines = 1,
            overflow = TextOverflow.Ellipsis)
        if (trailing != null) {
            Spacer(Modifier.width(6.dp))
            Icon(trailing, null, Modifier.size(14.dp), tint = Color.White.copy(alpha = 0.8f))
        }
    }
}

/**
 * Onboarding's page dots: the current one stretches into a short accent pill,
 * the rest stay small dots; the change slides between them.
 */
@Composable
fun PageDots(count: Int, index: Int, modifier: Modifier = Modifier) {
    val reduced = rememberReducedMotion()
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        for (i in 0 until count) {
            val spec: androidx.compose.animation.core.AnimationSpec<Dp> =
                if (reduced) tween(0) else spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow)
            val w by animateDpAsState(if (i == index) 22.dp else 6.dp, spec, label = "dot")
            Box(Modifier.size(w, 6.dp).clip(RoundedCornerShape(Shape.pill)).then(
                if (i == index) Modifier.background(Accent.brush()) else Modifier.background(Tone.text3.copy(alpha = 0.4f))))
        }
    }
}

/**
 * The status line under the timer: a shield and a few words in the state's
 * colour - "Secured connection" in green when it is.
 */
@Composable
fun SecureLine(mode: Mode, modifier: Modifier = Modifier) {
    val reduced = rememberReducedMotion()
    val (word, color) = when (mode) {
        Mode.ON -> "Secured connection" to Tone.ok
        Mode.BUSY -> "Securing your connection" to Tone.work
        Mode.FAIL -> "Could not connect" to Tone.fail
        Mode.OFF -> "Not protected" to Tone.text3
    }
    val tint by animateColorAsState(color, tween(if (reduced) 0 else 400), label = "secure")
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(if (mode == Mode.ON) Glyph.shieldCheck else Glyph.shieldOff, null, Modifier.size(15.dp), tint = tint)
        Spacer(Modifier.width(6.dp))
        AnimatedContent(word, transitionSpec = {
            fadeIn(tween(if (reduced) 0 else 250)) togetherWith fadeOut(tween(if (reduced) 0 else 120))
        }, label = "secureWord") { w ->
            Text(w, style = Type.caption.copy(color = tint, fontSize = 13.5.sp), maxLines = 1)
        }
    }
}

// -- the dotted sphere ------------------------------------------------------------------------

/** [n] points spread evenly over the unit sphere (a Fibonacci lattice): x, y, z one after another. */
private fun lattice(n: Int): FloatArray {
    val out = FloatArray(n * 3)
    val golden = (Math.PI * (3.0 - kotlin.math.sqrt(5.0))).toFloat()
    for (i in 0 until n) {
        val y = 1f - (i + 0.5f) / n * 2f
        val r = kotlin.math.sqrt(1f - y * y)
        val phi = i * golden
        out[i * 3] = kotlin.math.cos(phi) * r
        out[i * 3 + 1] = y
        out[i * 3 + 2] = kotlin.math.sin(phi) * r
    }
    return out
}

/**
 * The home screen's planet in miniature, for the screens before there is a
 * map: a sphere of dots, lit from the upper left, turning slowly (and by
 * [turn] radians more - onboarding hands it the pager's position, so a swipe
 * turns the world). A cap of its dots facing the viewer changes from white to
 * the accent and then to green as [lit] goes 0..1: the same idea as a
 * country's dots taking its flag's colours. Still when animations are off.
 */
@Composable
fun DotSphere(modifier: Modifier = Modifier, turn: Float = 0f, lit: Float = 0f, count: Int = 640) {
    val reduced = rememberReducedMotion()
    val spin = loopOf(!reduced, 48_000, "sphereSpin")
    val pts = remember(count) { lattice(count) }
    val ink = if (Tone.light) Color(0xFF2A3346) else Color(0xFFDCE6FA)
    val ok = Tone.ok
    val a0 = Accent.a
    val a1 = Accent.b
    val glow = Accent.glow
    Canvas(modifier) {
        val r = size.minDimension / 2f * 0.9f
        val c = center
        val ang = spin.value * 2f * Math.PI.toFloat() + turn
        val ca = kotlin.math.cos(ang)
        val sa = kotlin.math.sin(ang)
        val tilt = -0.42f
        val ct = kotlin.math.cos(tilt)
        val st = kotlin.math.sin(tilt)
        // A soft light behind it, and the limb's faint ring.
        drawCircle(Brush.radialGradient(listOf(glow, Color.Transparent), c, r * 1.5f), r * 1.5f, c)
        drawCircle(ink.copy(alpha = 0.06f), r, c, style = Stroke(1.dp.toPx()))
        val unit = 1.dp.toPx()
        for (i in 0 until pts.size / 3) {
            val x = pts[i * 3]
            val y = pts[i * 3 + 1]
            val z = pts[i * 3 + 2]
            val x1 = x * ca + z * sa
            val z1 = -x * sa + z * ca
            val y2 = y * ct - z1 * st
            val z2 = y * st + z1 * ct
            val depth = (z2 + 1f) / 2f
            // The cap: the dots around a fixed spot on the sphere, so it turns with it.
            val home = x * 0.28f + y * 0.38f + z * 0.88f
            val cap = (((home - 0.80f) / 0.12f).coerceIn(0f, 1f)) * lit
            val tint = if (lit < 0.5f) androidx.compose.ui.graphics.lerp(a0, a1, lit * 2f)
                else androidx.compose.ui.graphics.lerp(a1, ok, (lit - 0.5f) * 2f)
            val col = androidx.compose.ui.graphics.lerp(ink, tint, cap)
            val back = if (z2 < 0f) 0.22f else 1f
            drawCircle(col.copy(alpha = (0.12f + 0.88f * depth * depth) * back),
                radius = unit * (0.9f + 1.5f * depth + 0.8f * cap),
                center = Offset(c.x + x1 * r, c.y - y2 * r))
        }
    }
}

/** A value and its unit in one line, the unit smaller: "164" "ms". */
fun valueWithUnit(value: String, unit: String, unitSize: Float = 0.62f) = buildAnnotatedString {
    append(value)
    withStyle(SpanStyle(fontSize = unitSize.em)) { append(unit) }
}
