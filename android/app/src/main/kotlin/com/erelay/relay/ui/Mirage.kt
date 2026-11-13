package com.erelay.relay.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.erelay.relay.ui.theme.Cinzel
import com.erelay.relay.ui.theme.Mirage
import com.erelay.relay.ui.theme.Sans
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.roundToInt

/*
 * Mirage's first-run look on Android, piece for piece the Windows app's
 * (app/ui/intro.css; the decisions are in preview/DECISIONS.md): black, one
 * dark red, the astrolabe eye, the star chart turning behind it.
 *
 * The weak-phone rule holds throughout: what moves is a rotation, a
 * translation or an alpha on a layer of its own. The star chart is drawn once
 * into an offscreen layer and only turned; the lights that run round an edge
 * redraw one small control. With animations off (rememberReducedMotion)
 * nothing turns and every change is a swap.
 */

// -- time ---------------------------------------------------------------------------------------

/**
 * An angle in degrees that keeps turning at [degPerSec] while [running].
 * Read it in a draw or layer block, so a frame redraws only what uses it.
 */
@Composable
fun rememberTurn(degPerSec: Float, running: Boolean, start: Float = 0f): State<Float> {
    val angle = remember { mutableFloatStateOf(start) }
    val speed by rememberUpdatedState(degPerSec)
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        var last = -1L
        while (true) {
            withFrameNanos { now ->
                if (last >= 0L) angle.floatValue = (angle.floatValue + (now - last) / 1_000_000_000f * speed) % 360f
                last = now
            }
        }
    }
    return angle
}

// -- the page -----------------------------------------------------------------------------------

/**
 * The star chart: an astrolabe's rings, its degrees, a tilted orbit and a few
 * stars, in faint red (Windows' `#mi-chart`, 520 units across). Drawn once to
 * an offscreen layer; [turn] only rotates that layer.
 */
@Composable
fun MiChart(modifier: Modifier, turn: State<Float>) {
    Spacer(
        modifier
            .graphicsLayer {
                rotationZ = turn.value
                compositingStrategy = CompositingStrategy.Offscreen
            }
            .drawWithCache {
                val s = size.minDimension / 520f
                val c = size.center
                val red = Mirage.red.copy(alpha = 0.26f)
                val thin = Stroke(1f * s)
                val dashed = Stroke(1f * s, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f * s, 6f * s)))
                val degrees = Stroke(7f * s, pathEffect = PathEffect.dashPathEffect(floatArrayOf(1f * s, 3.73f * s)))
                val orbit = Path().apply {
                    addOval(Rect(c.x - 200f * s, c.y - 118f * s, c.x + 200f * s, c.y + 118f * s))
                }
                val at = { x: Float, y: Float -> Offset(c.x + x * s, c.y + y * s) }
                val line = Path().apply {
                    val p = listOf(at(-150f, -90f), at(-95f, -130f), at(40f, -205f), at(130f, -140f))
                    moveTo(p[0].x, p[0].y)
                    for (q in p.drop(1)) lineTo(q.x, q.y)
                }
                val stars = listOf(
                    Triple(-150f, -90f, 1.6f), Triple(-95f, -130f, 1.1f), Triple(40f, -205f, 1.3f),
                    Triple(130f, -140f, 1.2f), Triple(175f, 60f, 1.4f), Triple(-90f, 160f, 1.1f),
                    Triple(-205f, 30f, 1.2f), Triple(95f, 185f, 1.5f),
                )
                onDrawBehind {
                    drawCircle(red, 80f * s, c, style = thin)
                    drawCircle(red, 122f * s, c, style = thin)
                    drawCircle(red, 172f * s, c, style = dashed)
                    drawCircle(red, 236f * s, c, style = thin)
                    drawCircle(red, 226f * s, c, style = degrees)
                    rotate(-23f, c) { drawPath(orbit, red, style = thin) }
                    for (k in 0 until 12) rotate(k * 30f, c) {
                        drawLine(red, Offset(c.x, c.y - 80f * s), Offset(c.x, c.y - 236f * s), 0.8f * s)
                    }
                    drawPath(line, Mirage.ink.copy(alpha = 0.18f), style = Stroke(0.6f * s))
                    for ((x, y, r) in stars) drawCircle(Mirage.ink.copy(alpha = 0.55f), r * s, at(x, y))
                }
            },
    )
}

/**
 * Where a page's eye is, in the page, for the chart to centre on - Windows'
 * introPlaceChart. [MiPage] reads it; the eye reports it through [mark].
 */
class ChartAnchor {
    var page by mutableStateOf(Offset.Zero)
    var eye by mutableStateOf<Offset?>(null)

    /** Put on the eye (or whatever the chart turns round). */
    fun mark(): Modifier = Modifier.onGloballyPositioned { eye = it.boundsInRoot().center }
}

/**
 * Mirage's black page: a faint red light from the foot (or the head, [glowTop]),
 * the star chart turning round [anchor]'s eye, the edges shaded, then the
 * content. Everything but the chart is still; the chart is one turning layer.
 * [fade] runs the chart out into the black below its middle (the add-account
 * page, so the form sits on quiet ground).
 */
@Composable
fun MiPage(
    anchor: ChartAnchor,
    turn: State<Float>,
    modifier: Modifier = Modifier,
    chart: Dp = 560.dp,
    glowTop: Boolean = false,
    fade: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier
            .fillMaxSize()
            .onGloballyPositioned { anchor.page = it.boundsInRoot().topLeft }
            .drawWithCache {
                val w = size.width
                val h = size.height
                val glow = Brush.radialGradient(
                    listOf(Color(0xFF8C101C).copy(alpha = 0.33f), Color.Transparent),
                    center = Offset(w / 2f, if (glowTop) -h * 0.1f else h * 1.08f),
                    radius = max(w * 0.9f, h * 0.42f),
                )
                val shine = Brush.radialGradient(
                    listOf(Color.White.copy(alpha = 0.03f), Color.Transparent),
                    center = Offset(w / 2f, -h * 0.05f), radius = max(w * 0.7f, h * 0.3f),
                )
                onDrawBehind {
                    drawRect(Mirage.page)
                    drawRect(glow)
                    drawRect(shine)
                }
            },
    ) {
        val density = LocalDensity.current
        val side = with(density) { chart.roundToPx() }
        // The chart, placed with its middle on the eye; drawn over the page's
        // own edges, so it is not clipped to them.
        Box(
            Modifier
                .fillMaxSize()
                .layout { m, c ->
                    val p = m.measure(Constraints.fixed(side, side))
                    layout(c.maxWidth, c.maxHeight) {
                        val eye = anchor.eye
                        val x = if (eye != null) eye.x - anchor.page.x else c.maxWidth / 2f
                        val y = if (eye != null) eye.y - anchor.page.y else c.maxHeight * 0.3f
                        p.place((x - side / 2f).roundToInt(), (y - side / 2f).roundToInt())
                    }
                },
        ) {
            MiChart(Modifier.fillMaxSize(), turn)
        }
        // Run out into the black: the page's own colour laid over the chart
        // below its middle - the same as masking it, without a second
        // offscreen layer redrawn every time the chart turns.
        if (fade) Spacer(
            Modifier
                .fillMaxSize()
                .drawWithCache {
                    val ink = Brush.verticalGradient(0.22f to Color.Transparent, 0.5f to Mirage.page)
                    onDrawBehind { drawRect(ink) }
                },
        )
        // The edges shaded: a soft vignette over the chart, under the content.
        Spacer(
            Modifier
                .fillMaxSize()
                .drawWithCache {
                    val v = Brush.radialGradient(
                        0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.6f),
                        center = Offset(size.width / 2f, size.height * 0.4f),
                        radius = max(size.width, size.height) * 0.72f,
                    )
                    onDrawBehind { drawRect(v) }
                },
        )
        content()
    }
}

// -- the mark -----------------------------------------------------------------------------------

/**
 * The astrolabe eye (preview/logo/mirage-eye.svg), drawn in its own 120-unit
 * box: the upper lid, the lower lid as a dashed reflection, a ring of 24
 * degrees, an inner ring, the pupil cut by a horizon line, a bead at the
 * upper right. Every moving part is a lambda read while drawing: [open] the
 * lids (the upper one opens from its middle out), [ring] the degree ring's
 * turn, [bead] the bead's turn round the iris, [pupil] the pupil's scale.
 */
@Composable
fun MirageEye(
    modifier: Modifier,
    open: () -> Float = { 1f },
    ring: () -> Float = { 0f },
    bead: () -> Float = { 0f },
    pupil: () -> Float = { 1f },
) {
    Spacer(
        modifier.drawWithCache {
            val k = size.minDimension / 120f
            val ox = (size.width - 120f * k) / 2f
            val oy = (size.height - 120f * k) / 2f
            val top = Path().apply { moveTo(22f, 60f); quadraticTo(60f, 20f, 98f, 60f) }
            val low = Path().apply { moveTo(22f, 60f); quadraticTo(60f, 100f, 98f, 60f) }
            val measure = PathMeasure().apply { setPath(top, false) }
            val len = measure.length
            val part = Path()
            val lid = Stroke(4f, cap = StrokeCap.Round)
            val reflection = Stroke(3f, cap = StrokeCap.Round,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(1.5f, 4.5f)))
            val degrees = Stroke(3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(0.9f, 3.03f)))
            val mid = Offset(60f, 60f)
            onDrawBehind {
                translate(ox, oy) {
                    scale(k, Offset.Zero) {
                        val o = open().coerceIn(0f, 1f)
                        if (o >= 0.999f) {
                            drawPath(top, Mirage.red, style = lid)
                        } else if (o > 0.001f) {
                            part.reset()
                            measure.getSegment(len * (0.5f - o / 2f), len * (0.5f + o / 2f), part, true)
                            drawPath(part, Mirage.red, style = lid)
                        }
                        if (o > 0.001f) drawPath(low, Mirage.redLo.copy(alpha = o), style = reflection)
                        rotate(ring(), mid) { drawCircle(Mirage.red, 15f, mid, style = degrees) }
                        drawCircle(Mirage.red, 11.5f, mid, style = Stroke(1.4f))
                        val p = pupil()
                        if (p > 0.01f) drawCircle(Mirage.red, 6f * p, mid)
                        drawRect(Mirage.page, Offset(44f, 59.4f), Size(32f, 1.2f))
                        rotate(bead(), mid) { drawCircle(Mirage.red, 2.4f, Offset(70.6f, 49.4f)) }
                    }
                }
            }
        },
    )
}

/**
 * MIRAGE in Cinzel 500, tracked 0.42em, with its reflection under it - the
 * mirage - which shivers a little ([shimmer]). The tracking adds space after
 * the last letter too, so the word is pushed back by as much to stay centred.
 */
@Composable
fun MirageWord(size: TextUnit, modifier: Modifier = Modifier, shimmer: Boolean = true) {
    val style = TextStyle(fontFamily = Cinzel, fontWeight = FontWeight.W500, fontSize = size,
        lineHeight = size, letterSpacing = 0.42.em, color = Mirage.ink, textAlign = TextAlign.Center)
    val pad = with(LocalDensity.current) { (size.toPx() * 0.42f).toDp() }
    val wobble = if (shimmer) {
        rememberInfiniteTransition(label = "mirage").animateFloat(-1f, 1f,
            infiniteRepeatable(tween(2600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "wobble")
    } else null
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("MIRAGE", style = style, maxLines = 1, softWrap = false, modifier = Modifier.padding(start = pad))
        Text("MIRAGE", style = style, maxLines = 1, softWrap = false,
            modifier = Modifier
                .padding(start = pad)
                .graphicsLayer {
                    scaleY = -0.7f
                    val w = wobble?.value ?: 0f
                    translationX = w * 1.2.dp.toPx()
                    alpha = 0.2f + 0.04f * w
                    compositingStrategy = CompositingStrategy.Offscreen
                }
                .drawWithContent {
                    drawContent()
                    // Strongest where it meets the word: the layer is flipped,
                    // so that is its own bottom edge.
                    drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)),
                        blendMode = BlendMode.DstIn)
                })
    }
}

// -- light round an edge ------------------------------------------------------------------------

/** The light's colours round the clock: dark most of the way, then red, a pale tip, gone. */
private fun edgeLight(center: Offset) = Brush.sweepGradient(
    0f to Color.Transparent, 0.82f to Color.Transparent, 0.965f to Mirage.light,
    0.99f to Mirage.tip, 1f to Color.Transparent, center = center)

/**
 * A rim with a light running round it: the rim in [rim], the light clipped to
 * it and turned by [angle] (in degrees, read while drawing) at [alpha], then
 * the face [inset] in from the edge. Windows' ring / spin / face, in one draw.
 */
private fun Modifier.rimmed(
    radius: Dp,
    inset: Dp,
    rim: () -> Color,
    face: List<Color>,
    angle: () -> Float,
    alpha: () -> Float,
    fill: () -> Color = { Color.Transparent },
): Modifier = drawWithCache {
    val r = radius.toPx().coerceAtMost(size.minDimension / 2f)
    val i = inset.toPx()
    val outer = Path().apply { addRoundRect(RoundRect(Rect(Offset.Zero, size), CornerRadius(r))) }
    val faceTopLeft = Offset(i, i)
    val faceSize = Size(size.width - 2 * i, size.height - 2 * i)
    val faceCorner = CornerRadius((r - i).coerceAtLeast(0f))
    val faceBrush = Brush.verticalGradient(face, startY = i, endY = size.height - i)
    val light = edgeLight(size.center)
    val reach = max(size.width, size.height)
    onDrawBehind {
        drawPath(outer, rim())
        val a = alpha()
        if (a > 0.01f) clipPath(outer) { rotate(angle(), center) { drawCircle(light, reach, center, alpha = a) } }
        drawRoundRect(faceBrush, faceTopLeft, faceSize, faceCorner)
        val f = fill()
        if (f.alpha > 0.01f) drawRoundRect(f, faceTopLeft, faceSize, faceCorner)
    }
}

// -- controls -----------------------------------------------------------------------------------

/**
 * The "turning bead" button: black, with a red light running round its edge,
 * faster under a finger and faster still while [busy]. It gives a little when
 * pressed. [done] fills it red with a check.
 */
@Composable
fun OrbitButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
    done: Boolean = false,
    enabled: Boolean = true,
    icon: ImageVector? = Glyph.arrowRight,
    paused: Boolean = false,
) {
    val reduced = rememberReducedMotion()
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val speed = when { busy -> 330f; pressed -> 240f; else -> 95f }
    val turn = rememberTurn(speed, running = !reduced && !paused && !done, start = 200f)
    val fill by animateFloatAsState(if (done) 1f else 0f, tween(if (reduced) 0 else 420), label = "done")
    Box(
        modifier
            .heightIn(min = 52.dp)
            .press(enabled = enabled && !busy && !done, shape = RoundedCornerShape(26.dp), sink = true,
                source = source, onClick = onClick)
            .rimmed(26.dp, 1.5.dp, { Color(0xFF2B1418) }, listOf(Color(0xFF1F1B1D), Color(0xFF0E0C0D)),
                angle = { turn.value }, alpha = { if (enabled) 1f else 0.35f },
                fill = { Mirage.red.copy(alpha = fill) }),
        contentAlignment = Alignment.Center,
    ) {
        Row(Modifier.padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center) {
            when {
                busy -> {
                    Spinner(turn)
                    Spacer(Modifier.width(10.dp))
                }
                done -> {
                    Icon(Glyph.check, null, Modifier.size(18.dp), tint = Mirage.ink)
                    Spacer(Modifier.width(10.dp))
                }
            }
            Text(text, style = TextStyle(fontFamily = Sans, fontWeight = FontWeight.W500, fontSize = 15.sp,
                color = Mirage.ink.copy(alpha = if (enabled) 1f else 0.5f)), maxLines = 1)
            if (!busy && !done && icon != null) {
                Spacer(Modifier.width(10.dp))
                Icon(icon, null, Modifier.size(18.dp), tint = Color(0xFFD0283C))
            }
        }
    }
}

/** A small ring with a gap, turning: the bead button's "working". */
@Composable
private fun Spinner(turn: State<Float>) {
    Spacer(
        Modifier
            .size(16.dp)
            .graphicsLayer { rotationZ = turn.value * 3f }
            .drawBehind {
                drawArc(Mirage.ink, 0f, 270f, false, style = Stroke(1.8.dp.toPx(), cap = StrokeCap.Round))
            },
    )
}

/**
 * One choice on the welcome page, "black stone with a lit edge": 62dp, a
 * narrow red rim; a press runs the light once round it and the arrow leans
 * forward.
 */
@Composable
fun StoneRow(title: String, note: String, mark: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val reduced = rememberReducedMotion()
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val run = remember { Animatable(0f) }
    // Launched outside the key, so lifting the finger does not freeze the light mid-run.
    val scope = rememberCoroutineScope()
    LaunchedEffect(pressed) {
        if (pressed && !reduced) scope.launch {
            run.snapTo(0f)
            run.animateTo(1f, tween(900, easing = FastOutSlowInEasing))
        }
    }
    val lean by animateFloatAsState(if (pressed) 1f else 0f, tween(if (pressed) 90 else 260), label = "lean")
    Box(
        modifier
            .fillMaxWidth()
            .height(62.dp)
            .press(shape = RoundedCornerShape(16.dp), sink = true, sinkTo = 0.98f, source = source, onClick = onClick)
            .rimmed(16.dp, 1.dp, { Color(0xFF2A1A1D) }, listOf(Color(0xFF1C181A), Color(0xFF0E0C0D)),
                angle = { -90f + 360f * run.value },
                alpha = { val v = run.value; if (v <= 0f || v >= 1f) 0f else minOf(1f, (1f - v) * 3f) }),
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(36.dp)
                    .drawBehind {
                        drawCircle(Brush.radialGradient(listOf(Color(0xFF3A1C21), Color(0xFF1A0F11)),
                            center = Offset(size.width * 0.35f, size.height * 0.3f), radius = size.minDimension * 0.75f))
                        drawCircle(Color(0x73C31C2E), size.minDimension / 2f - 0.5.dp.toPx(),
                            style = Stroke(1.dp.toPx()))
                    },
                contentAlignment = Alignment.Center,
            ) { Icon(mark, null, Modifier.size(20.dp), tint = Color(0xFFE8DCD9)) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = TextStyle(fontFamily = Sans, fontWeight = FontWeight.W500, fontSize = 15.sp,
                    lineHeight = 20.sp, color = Color(0xFFF4F1EF)), maxLines = 1)
                Text(note, style = TextStyle(fontFamily = Sans, fontSize = 12.5.sp, lineHeight = 17.sp,
                    color = Mirage.note), maxLines = 1, softWrap = false,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            Icon(Glyph.chevRight, null, Modifier.size(18.dp).graphicsLayer { translationX = lean * 3.dp.toPx() },
                tint = Mirage.redHi)
        }
    }
}

/**
 * A field on black stone: 48dp, a red rim that warms when focused - the
 * light runs once round it as it takes the focus - and turns bright red,
 * shaking once, when [bad] has something to say. The line says it under the
 * field, with a mark, and goes as soon as the field is typed in.
 */
@Composable
fun StoneField(
    label: String,
    value: String,
    onValue: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    secret: Boolean = false,
    bad: String? = null,
    keyboard: KeyboardType = KeyboardType.Text,
) {
    val reduced = rememberReducedMotion()
    var shown by remember { mutableStateOf(false) }
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val run = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(focused) {
        if (focused && !reduced) scope.launch {
            run.snapTo(0f)
            run.animateTo(1f, tween(900, easing = FastOutSlowInEasing))
        }
    }
    val shake = remember { Animatable(0f) }
    LaunchedEffect(bad) {
        if (bad != null && !reduced) for (x in listOf(8f, -6f, 4f, -2f, 0f)) shake.animateTo(x, tween(50))
    }
    val rim = when {
        bad != null -> Color(0xFFB0202F)
        focused -> Color(0xFF6E1A26)
        else -> Color(0xFF2A1A1D)
    }
    Column(modifier.fillMaxWidth().graphicsLayer { translationX = shake.value * density }) {
        Text(label, style = TextStyle(fontFamily = Sans, fontSize = 12.sp, color = Mirage.cap),
            modifier = Modifier.padding(start = 2.dp, bottom = 6.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .rimmed(12.dp, 1.dp, { rim }, listOf(Color(0xFF141113), Color(0xFF0C0A0B)),
                    angle = { -90f + 360f * run.value },
                    alpha = { val v = run.value; if (v <= 0f || v >= 1f) 0f else minOf(1f, (1f - v) * 3f) })
                .padding(start = 14.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val style = TextStyle(fontFamily = Sans, fontSize = 14.sp, color = Color(0xFFF4F1EF))
            Box(Modifier.weight(1f)) {
                if (value.isEmpty()) Text(placeholder, style = style.copy(color = Color(0xFF6E6765)), maxLines = 1)
                BasicTextField(
                    value = value, onValueChange = onValue, singleLine = true, textStyle = style,
                    cursorBrush = SolidColor(Mirage.redHi), interactionSource = source,
                    visualTransformation = if (secret && !shown) PasswordVisualTransformation()
                        else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else keyboard,
                        autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (secret) {
                IconButton(if (shown) Glyph.eyeOff else Glyph.eye, if (shown) "Hide" else "Show",
                    { shown = !shown }, quiet = true, size = 36.dp, tint = Color(0xFFB5ACA9))
            }
        }
        if (bad != null) {
            Row(Modifier.padding(top = 6.dp, start = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Glyph.alert, null, Modifier.size(14.dp), tint = Mirage.bad)
                Spacer(Modifier.width(6.dp))
                Text(bad, style = TextStyle(fontFamily = Sans, fontSize = 12.sp, lineHeight = 17.sp, color = Mirage.bad))
            }
        }
    }
}

/** A plain-text action under the bead button: Cancel, Close, Use another account. */
@Composable
fun MiTextButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .heightIn(min = 44.dp)
            .press(shape = RoundedCornerShape(22.dp), onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = TextStyle(fontFamily = Sans, fontSize = 13.sp, color = Mirage.note)) }
}

/** Where you are in the intro: short upright strokes, like an astrolabe's degrees, the current one red. */
@Composable
fun StepTicks(count: Int, index: Int, modifier: Modifier = Modifier) {
    Row(modifier.height(20.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        repeat(count) { i ->
            val on by animateFloatAsState(if (i == index) 1f else 0f, tween(300), label = "tick")
            Spacer(
                Modifier
                    .width(2.dp)
                    .height(18.dp)
                    .graphicsLayer { scaleY = 0.55f + 0.45f * on }
                    .drawBehind {
                        drawRoundRect(lerp(Color(0xFF4A2A2F), Mirage.redHi, on), cornerRadius = CornerRadius(1.dp.toPx()))
                    },
            )
        }
    }
}

/** Setup's progress: a ruler of ten degrees, filled red from the left. */
@Composable
fun MiRuler(fraction: Float, modifier: Modifier = Modifier, dim: Boolean = false) {
    val f by animateFloatAsState(fraction.coerceIn(0.02f, 1f), tween(300, easing = LinearEasing), label = "ruler")
    Box(
        modifier
            .fillMaxWidth()
            .height(6.dp)
            .graphicsLayer { alpha = if (dim) 0.35f else 1f }
            .drawBehind {
                val base = 5.dp.toPx()
                drawLine(Color(0xFF3A2A2D), Offset(0f, base), Offset(size.width, base), 1.dp.toPx())
                for (k in 0..10) {
                    val x = (size.width - 1f) * k / 10f
                    drawLine(Color(0xFF4A2A2F), Offset(x, 0f), Offset(x, base), 1.dp.toPx())
                }
            },
    ) {
        Spacer(
            Modifier
                .fillMaxWidth()
                .height(3.dp)
                .align(Alignment.BottomStart)
                .graphicsLayer {
                    scaleX = f
                    transformOrigin = TransformOrigin(0f, 0.5f)
                }
                .background(Mirage.redHi),
        )
    }
}

/** A setup step's mark: done, now, next, or failed. */
enum class StepState { DONE, NOW, NEXT, FAILED }

@Composable
fun SetupMark(mark: StepState, turn: State<Float>, modifier: Modifier = Modifier) {
    Box(modifier.size(19.dp), contentAlignment = Alignment.Center) {
        when (mark) {
            StepState.DONE -> {
                Spacer(Modifier.size(19.dp).background(Mirage.red, CircleShape))
                Icon(Glyph.check, null, Modifier.size(11.dp), tint = Mirage.ink)
            }
            StepState.FAILED -> {
                Spacer(Modifier.size(19.dp).drawBehind {
                    drawCircle(Color(0xFF2A1215))
                    drawCircle(Mirage.redHi, size.minDimension / 2f - 0.75.dp.toPx(), style = Stroke(1.5.dp.toPx()))
                })
                Icon(Glyph.x, null, Modifier.size(11.dp), tint = Mirage.redHi)
            }
            StepState.NOW -> {
                Spacer(
                    Modifier
                        .size(19.dp)
                        .drawBehind {
                            drawCircle(Mirage.red, size.minDimension / 2f - 0.75.dp.toPx(), style = Stroke(1.5.dp.toPx()))
                        },
                )
                // A small bead going round it.
                Spacer(
                    Modifier
                        .size(19.dp)
                        .graphicsLayer { rotationZ = turn.value }
                        .drawBehind { drawCircle(Mirage.redHi, 2.dp.toPx(), Offset(size.width / 2f, 0.75.dp.toPx())) },
                )
            }
            StepState.NEXT -> Spacer(
                Modifier
                    .size(19.dp)
                    .drawBehind {
                        drawCircle(Color(0xFF3A2A2D), size.minDimension / 2f - 0.75.dp.toPx(), style = Stroke(1.5.dp.toPx()))
                    },
            )
        }
    }
}

// -- the carving ---------------------------------------------------------------------------------

/** The two last words held together, so a line never ends on one word alone. */
private fun words(s: String): List<String> {
    val w = s.split(' ').filter { it.isNotEmpty() }
    return if (w.size < 3) w else w.dropLast(2) + (w[w.size - 2] + " " + w[w.size - 1])
}

/** One word in its groove: [down] has sunk it, [up] has raised it, both 0..1 of its own height. */
@Composable
private fun Word(text: String, style: TextStyle, i: Int, n: Int, sink: Animatable<Float, AnimationVector1D>,
                 rise: Animatable<Float, AnimationVector1D>) {
    Box(Modifier.clipToBounds()) {
        Text(text, style = style, maxLines = 1, softWrap = false, modifier = Modifier.graphicsLayer {
            val k = 4f
            val down = ((sink.value * (n + k) - (n - 1 - i)) / k).coerceIn(0f, 1f)
            val up = ((rise.value * (n + k) - i) / k).coerceIn(0f, 1f)
            translationY = (down + (1f - up)) * size.height
        })
    }
}

/**
 * The intro's words, changed by carving (preview/DECISIONS.md, «کتیبه»): a
 * thin red line runs left to right like a chisel while the words now shown
 * sink, last first, into the groove under them; the line draws back from the
 * right as the next words rise, one by one. About a second. The title stays
 * on one line, set smaller when it must but never under 14sp; the sentence
 * is two or three full lines. The block's height is fixed so the button
 * under it never moves.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CarvedStage(title: String, say: String, modifier: Modifier = Modifier, height: Dp = 140.dp) {
    val reduced = rememberReducedMotion()
    var shown by remember { mutableStateOf(title to say) }
    val sink = remember { Animatable(0f) }
    val rise = remember { Animatable(1f) }
    val line = remember { Animatable(0f) }
    var fromRight by remember { mutableStateOf(false) }
    LaunchedEffect(title, say) {
        if (shown.first == title && shown.second == say) return@LaunchedEffect
        if (reduced) {
            shown = title to say
            return@LaunchedEffect
        }
        if (sink.value == 0f) rise.snapTo(1f)
        fromRight = false
        coroutineScope {
            launch { line.animateTo(1f, tween(380, easing = FastOutSlowInEasing)) }
            sink.animateTo(1f, tween(440, easing = LinearEasing))
        }
        shown = title to say
        sink.snapTo(0f)
        rise.snapTo(0f)
        fromRight = true
        coroutineScope {
            launch { line.animateTo(0f, tween(360, easing = FastOutSlowInEasing)) }
            rise.animateTo(1f, tween(560, easing = LinearEasing))
        }
        fromRight = false
    }

    val (t, s) = shown
    val tw = t.split(' ').filter { it.isNotEmpty() }
    val sw = words(s)
    val n = tw.size + sw.size
    Column(modifier.fillMaxWidth().height(height), horizontalAlignment = Alignment.CenterHorizontally) {
        BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            val measurer = rememberTextMeasurer()
            val full = 22f
            val room = constraints.maxWidth
            val base = TextStyle(fontFamily = Sans, fontWeight = FontWeight.W500, letterSpacing = (-0.01).em,
                color = Color(0xFFF4F1EF))
            val fs = remember(t, room) {
                val w = measurer.measure(t, base.copy(fontSize = full.sp), maxLines = 1, softWrap = false).size.width
                if (w <= room || w == 0) full else max(14f, full * room / w * 0.98f)
            }
            val ts = base.copy(fontSize = fs.sp, lineHeight = (fs * 1.3f).sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                tw.forEachIndexed { i, w ->
                    if (i > 0) Spacer(Modifier.width((fs * 0.28f).dp))
                    Word(w, ts, i, n, sink, rise)
                }
            }
        }
        // The chisel: a hairline under the title, drawn from the left, taken back from the right.
        Spacer(
            Modifier
                .padding(horizontal = 6.dp, vertical = 9.dp)
                .fillMaxWidth()
                .height(1.dp)
                .graphicsLayer {
                    scaleX = line.value
                    transformOrigin = TransformOrigin(if (fromRight) 1f else 0f, 0.5f)
                    alpha = if (line.value > 0.001f) 1f else 0f
                }
                .background(Brush.horizontalGradient(listOf(Mirage.redLo, Mirage.redHi, Mirage.redLo))),
        )
        val ss = TextStyle(fontFamily = Sans, fontSize = 14.5.sp, lineHeight = 23.sp, color = Mirage.say)
        FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
        ) {
            sw.forEachIndexed { j, w -> Word(w, ss, tw.size + j, n, sink, rise) }
        }
    }
}

/** A heading on one line: [from]sp, set smaller when it must fit, never under 14sp. */
@Composable
fun MiTitle(text: String, modifier: Modifier = Modifier, from: Float = 20f) {
    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val measurer = rememberTextMeasurer()
        val room = constraints.maxWidth
        val base = TextStyle(fontFamily = Sans, fontWeight = FontWeight.W500, letterSpacing = (-0.01).em,
            color = Color(0xFFF4F1EF), textAlign = TextAlign.Center)
        val fs = remember(text, room, from) {
            val w = measurer.measure(text, base.copy(fontSize = from.sp), maxLines = 1, softWrap = false).size.width
            if (w <= room || w == 0) from else max(14f, from * room / w * 0.98f)
        }
        Text(text, style = base.copy(fontSize = fs.sp, lineHeight = (fs * 1.3f).sp), maxLines = 1, softWrap = false)
    }
}

/** Body copy on the black: centred, in full lines, the last two words kept together. */
@Composable
fun MiSay(text: String, modifier: Modifier = Modifier, size: Float = 14f) {
    val w = words(text)
    Text(w.joinToString(" "), modifier = modifier.fillMaxWidth(),
        style = TextStyle(fontFamily = Sans, fontSize = size.sp, lineHeight = (size * 1.6f).sp, color = Mirage.say,
            textAlign = TextAlign.Center))
}

/** A small label over a group: spaced capitals in a warm grey. */
@Composable
fun MiLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), modifier = modifier.padding(start = 2.dp),
        style = TextStyle(fontFamily = Sans, fontSize = 11.sp, letterSpacing = 0.16.em, color = Color(0xFF8C8481)))
}

// -- a whole-screen Mirage sheet ----------------------------------------------------------------

/**
 * A Mirage page over everything, for adding an account: fades in, answers
 * Back, and closes from the small x at the top. The chart sits high and runs
 * out into the black ([MiPage]'s fade), lit from above.
 */
@Composable
fun MiSheet(open: Boolean, onClose: () -> Unit, anchor: ChartAnchor, paused: Boolean = false,
            content: @Composable BoxScope.() -> Unit) {
    AnimatedVisibility(open, enter = fadeIn(tween(250)), exit = fadeOut(tween(170))) {
        BackHandler(onBack = onClose)
        val reduced = rememberReducedMotion()
        val turn = rememberTurn(1.5f, running = !reduced && !paused)
        MiPage(anchor, turn, chart = 520.dp, glowTop = true, fade = true) {
            content()
        }
    }
}

/** The page's close: a small round x at the top right. */
@Composable
fun MiClose(onClose: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(48.dp)
            .press(shape = CircleShape, sink = true, onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(32.dp).background(Color(0x14FFFFFF), CircleShape), contentAlignment = Alignment.Center) {
            Icon(Glyph.x, "Close", Modifier.size(16.dp), tint = Color(0xFFB5ACA9))
        }
    }
}
