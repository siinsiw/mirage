package com.erelay.relay.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.erelay.relay.data.Conn
import com.erelay.relay.data.Mode
import com.erelay.relay.data.Place
import com.erelay.relay.data.Traffic
import com.erelay.relay.data.Way
import com.erelay.relay.ui.theme.Shape
import com.erelay.relay.ui.theme.Space
import com.erelay.relay.ui.theme.Tone
import com.erelay.relay.ui.theme.Type
import com.erelay.relay.ui.theme.script
import com.erelay.relay.world.WorldInput
import com.erelay.relay.world.WorldLayer
import kotlinx.coroutines.delay

/** Everything the main screen draws, gathered by [Root] from the core's flows. */
data class HomeState(
    val conn: Conn,
    val realIp: Place?,
    val realIpAsked: Boolean,
    val seen: Place?,
    val seenFailed: Boolean,
    val traffic: Traffic,
    val way: Way,
    val tunnelReady: Boolean,
    val picked: String,
    val pickLabel: String,
    val countries: Int,
    val serverCount: Int,
    val loaded: Boolean,
    val hasCredentials: Boolean,
    val deadCount: Int,
    val hint: String,
    val hintBad: Boolean,
    val feel: com.erelay.relay.data.Feel = com.erelay.relay.data.Feel(),
) {
    /** Whether the chosen way out has what it needs to start. */
    val canStart get() = if (way == Way.PROVIDER) hasCredentials && serverCount > 0 else tunnelReady
}

/*
 * The main screen, Midnight (the reference's left screen):
 *
 *   the bar      settings on the leading edge; what is using the connection,
 *                the exits that stopped answering, and the way out as the
 *                accent pill on the trailing edge
 *   the readout  how long the tunnel has been up, big, its digits rolling;
 *                "Secured connection" under it with its shield
 *   the button   the power disc in its rings - the one action
 *   the state    "Connected" in green and the address the world now sees
 *   the band     the dotted planet shows through here: the country the
 *                traffic leaves from is in the middle of the band, its dots
 *                in its flag's colours, and a glass card beside it says
 *                where, which address, and how much is moving
 *   the foot     the server card - flag, place, address - the way into the list
 */
@Composable
fun Home(
    s: HomeState,
    orbPaused: Boolean,
    onLog: () -> Unit,
    onDrop: () -> Unit,
    onSettings: () -> Unit,
    onWay: (Way) -> Unit,
    onPick: () -> Unit,
    onAct: () -> Unit,
    onCopy: (String) -> Unit,
    onReconnect: () -> Unit = {},
    /** The Configs section: servers bought or given from anywhere. */
    onConfigs: () -> Unit = {},
) {
    // Where the scene's own box and the band are on screen: the map puts its
    // country in the middle of the band, not in the middle of the screen.
    var scene by remember { mutableStateOf(Rect.Zero) }
    var band by remember { mutableStateOf(Rect.Zero) }
    val shift = if (scene.height > 0f && band.height > 0f)
        (Math.round(((band.top + band.bottom) / 2f - (scene.top + scene.bottom) / 2f) / scene.height * 500f) / 500f)
    else 0f

    val reduced = rememberReducedMotion()
    val light = Tone.light
    val bg = Tone.bg
    val live = connectedCode(s)
    val picked = if (s.way == Way.SERVER) "" else countryOfPick(s.picked).lowercase()
    val home = s.realIp?.country.orEmpty()
    val calm = reduced || !s.feel.orbMotion
    val world = remember(s.conn.mode, live, picked, home, light, calm, bg, shift) {
        WorldInput(s.conn.mode, live, picked, home, light, calm, floatArrayOf(bg.red, bg.green, bg.blue), shift)
    }

    Backdrop {
        // The planet fills the screen, behind everything.
        WorldLayer(world, orbPaused, Modifier.fillMaxSize().onGloballyPositioned { scene = it.boundsInRoot() })
        // The page darkens over the planet's top and foot, so the words and
        // the card read over it and the planet seems to rise out of the page.
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
            0f to bg.copy(alpha = 0.94f), 0.34f to bg.copy(alpha = 0.72f), 0.52f to Color.Transparent,
            0.84f to Color.Transparent, 1f to bg.copy(alpha = 0.86f))))

        BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            val screen = maxHeight
            val scroll = rememberScrollState()
            val gutter = if (maxWidth < 360.dp) Space.x4 else Space.x5
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .widthIn(max = CONTENT_MAX)
                    .fillMaxSize()
                    .verticalScroll(scroll, enabled = scroll.maxValue > 0)
                    .padding(horizontal = gutter),
            ) {
                FillColumn(screen, flex = 2, least = 132.dp) {
                    TopBar(s, onLog, onDrop, onSettings, onWay, onConfigs)
                    Readout(s, onAct, onCopy)
                    Band(s, Modifier.onGloballyPositioned { band = it.boundsInRoot() })
                    Foot(s, onPick, onAct, onWay)
                }
            }
        }
    }
}

// -- the connected country ----------------------------------------------------------

/** The country the tunnel comes out in, once there is one; empty when there is nothing to show. */
private fun connectedCode(s: HomeState): String {
    if (s.conn.mode != Mode.ON) return ""
    val measured = s.seen?.country.orEmpty()
    return when {
        measured.isNotEmpty() -> measured.lowercase()
        s.way == Way.PROVIDER -> s.conn.exitName.take(2).lowercase()
        s.way == Way.SERVER_EXIT -> countryOfPick(s.picked).lowercase()
        else -> ""
    }.takeIf { it.length == 2 } ?: ""
}

// -- the bar ------------------------------------------------------------------------------

private val WAY_NOTE = mapOf(
    Way.PROVIDER to "The provider's proxy: every country, slow uploads",
    Way.SERVER to "Your own server through a CDN: the steadiest",
    Way.SERVER_EXIT to "Your server, out by a provider exit",
)

private val WAY_ICON = mapOf(
    Way.PROVIDER to Glyph.globe,
    Way.SERVER to Glyph.tower,
    Way.SERVER_EXIT to Glyph.route,
)

/** The ways out that can work now: a provider needs an account and servers, your server needs the tunnel set up. */
private fun waysOf(s: HomeState): List<Way> {
    val provider = s.hasCredentials && s.serverCount > 0
    return Way.entries.filter {
        when (it) {
            Way.PROVIDER -> provider
            Way.SERVER -> s.tunnelReady
            Way.SERVER_EXIT -> s.tunnelReady && provider
        }
    }
}

@Composable
private fun TopBar(s: HomeState, onLog: () -> Unit, onDrop: () -> Unit, onSettings: () -> Unit,
                   onWay: (Way) -> Unit, onConfigs: () -> Unit) {
    val ways = waysOf(s)
    LaunchedEffect(ways) {
        if (ways.isNotEmpty() && s.way !in ways) onWay(ways.first())
    }
    Row(Modifier.fillMaxWidth().padding(top = Space.x2), verticalAlignment = Alignment.CenterVertically) {
        IconButton(Glyph.menu, "Settings", onSettings, tint = com.erelay.relay.ui.theme.Mirage.bright)
        Spacer(Modifier.weight(1f))
        IconButton(Glyph.layers, "Configs", onConfigs, tint = com.erelay.relay.ui.theme.Mirage.bright)
        Box {
            IconButton(Glyph.activity, "What is using the connection", onLog, tint = com.erelay.relay.ui.theme.Mirage.bright)
            if (s.traffic.live && s.traffic.downRate + s.traffic.upRate > 512) {
                LiveDot(Modifier.align(Alignment.TopEnd).offset((-Space.x2), Space.x3), size = 6.dp, ring = false)
            }
        }
        if (s.deadCount > 0) {
            Box {
                IconButton(Glyph.archive, "Exits that stopped answering", onDrop, tint = com.erelay.relay.ui.theme.Mirage.bright)
                Text(s.deadCount.toString(), style = Type.mono.copy(color = Tone.ink, fontSize = 11.sp),
                    modifier = Modifier.align(Alignment.TopEnd).offset((-Space.x1), Space.x1)
                        .clip(RoundedCornerShape(Shape.pill)).background(Tone.fail).padding(horizontal = 5.dp))
            }
        }
        // The way out is no longer up here: it is the three cards at the foot
        // (Mirage's layout 7, 2026-11-13). Nothing at the top is a control but
        // these icons.
    }
}

// -- the readout ------------------------------------------------------------------------

private val STAGES = mapOf(
    "finding" to "Finding a way in",
    "racing" to "Looking for a server",
    "asked" to "Looking for a server",
    "checking" to "Checking the server answers",
    "opening" to "Opening the connection",
)

private fun wordOf(mode: Mode) = when (mode) {
    Mode.OFF -> "Disconnected"
    Mode.BUSY -> "Connecting"
    Mode.ON -> "Connected"
    Mode.FAIL -> "Not connected"
}

/**
 * The column down the middle: the timer, the shield line, the power button,
 * the state word and the address under it. Centred, every line a fixed
 * height so nothing moves when the words change.
 */
@Composable
private fun Readout(s: HomeState, onAct: () -> Unit, onCopy: (String) -> Unit) {
    val mode = s.conn.mode
    val short = shortScreen()
    Column(Modifier.fillMaxWidth().padding(top = if (short) Space.x4 else Space.x8),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Timer(s)
        Spacer(Modifier.height(Space.x1))
        SecureLine(mode)
        Spacer(Modifier.height(if (short) Space.x1 else Space.x3))
        StateWord(mode)
        Spacer(Modifier.height(2.dp))
        AddressLine(s, onCopy)
    }
}

/** How long the tunnel has been up, big, as hh:mm:ss; its digits roll as they change. Zeros, dimmed, when it is down. */
@Composable
private fun Timer(s: HomeState) {
    val on = s.conn.mode == Mode.ON
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(on, s.conn.since) {
        while (on) {
            now = System.currentTimeMillis()
            delay(1000L - now % 1000L)
        }
    }
    val secs = if (on && s.conn.since > 0) (now / 1000 - s.conn.since).coerceAtLeast(0) else 0
    val text = "%02d:%02d:%02d".format(secs / 3600, secs / 60 % 60, secs % 60)
    val reduced = rememberReducedMotion()
    val ink by animateColorAsState(if (on) Tone.text else Tone.text3.copy(alpha = 0.55f),
        tween(if (reduced) 0 else 500), label = "timerInk")
    val size = if (shortScreen()) 44.sp else 52.sp
    RollingText(text, Type.display.copy(color = ink, fontSize = size, lineHeight = size * 1.12f,
        letterSpacing = (-0.01).em, fontFeatureSettings = "tnum"))
}

/** The state in its colour: one word, rising into place as it changes. */
@Composable
private fun StateWord(mode: Mode) {
    val reduced = rememberReducedMotion()
    val color by animateColorAsState(if (mode == Mode.OFF) Tone.text2 else Tone.stateOf(mode),
        tween(if (reduced) 0 else Shape.LARGE_MS, easing = Shape.ease), label = "word")
    AnimatedContent(wordOf(mode), transitionSpec = {
        (fadeIn(tween(if (reduced) 0 else Shape.ENTER_MS, easing = Shape.ease)) +
            slideInVertically(tween(if (reduced) 0 else 360, easing = Shape.ease)) { it / 3 }) togetherWith
            (fadeOut(tween(if (reduced) 0 else Shape.FADE_MS)) +
                slideOutVertically(tween(if (reduced) 0 else 200)) { -it / 4 })
    }, label = "word") { w ->
        androidx.compose.foundation.text.BasicText(w,
            style = Type.status.copy(color = color, fontSize = 26.sp, lineHeight = 32.sp,
                letterSpacing = (-0.015).em, textAlign = TextAlign.Center),
            maxLines = 1, softWrap = false, autoSize = FitText(26.sp, floor = 18.dp))
    }
}

/**
 * Under the state word, in mono: once connected, the address the world sees
 * (tap to copy); while connecting, how far the search has got; at rest, your
 * own address.
 */
@Composable
private fun AddressLine(s: HomeState, onCopy: (String) -> Unit) {
    val mode = s.conn.mode
    val copy: String?
    val line = when (mode) {
        Mode.ON -> {
            copy = s.seen?.ip
            s.seen?.ip ?: if (s.seenFailed) "address unconfirmed" else "checking the address…"
        }
        Mode.BUSY -> {
            copy = null
            val stage = STAGES.entries.firstOrNull { s.conn.note.startsWith(it.key) }?.value ?: "Looking for a server"
            if (s.conn.total > 0) "$stage  ·  ${s.conn.asked} of ${s.conn.total}" else stage
        }
        else -> {
            copy = s.realIp?.ip
            when {
                s.realIp != null -> "Your address  ${s.realIp.ip}"
                s.realIpAsked -> "Your address is unknown"
                else -> "Checking your address…"
            }
        }
    }
    Row(
        Modifier
            .heightIn(min = 28.dp)
            .clip(RoundedCornerShape(Shape.pill))
            .then(if (copy != null) Modifier.press(shape = RoundedCornerShape(Shape.pill)) { onCopy(copy) } else Modifier)
            .padding(horizontal = Space.x3, vertical = Space.x1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (mode == Mode.ON) {
            Icon(Glyph.globe, null, Modifier.size(15.dp), tint = Tone.text2)
            Spacer(Modifier.width(6.dp))
        }
        Text(line, style = Type.ip.copy(fontSize = if (mode == Mode.ON) 17.sp else 13.sp,
            color = if (mode == Mode.ON) Tone.text else Tone.text2, textAlign = TextAlign.Center),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// -- the band -------------------------------------------------------------------------------

/**
 * The free height over the planet. Its middle is where the map brings the
 * country down to; once connected - after the camera has landed there - a pin
 * lights on it and a glass card opens beside it: the flag and the country, the
 * address, and the traffic either way.
 */
@Composable
private fun Band(s: HomeState, modifier: Modifier) {
    val code = connectedCode(s)
    val on = s.conn.mode == Mode.ON && code.isNotEmpty()
    val reduced = rememberReducedMotion()
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(on, code) {
        shown = false
        if (on) {
            // The journey over the planet takes about this long to land.
            if (!reduced) delay(2700)
            shown = true
        }
    }
    val p by animateFloatAsState(if (shown) 1f else 0f,
        if (reduced) tween<Float>(0) else spring<Float>(dampingRatio = 0.72f, stiffness = Spring.StiffnessLow), label = "tip")
    val ok = Tone.ok

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val w = maxWidth
        val h = maxHeight
        if (p > 0.01f) {
            LiveDot(Modifier.align(Alignment.Center), color = ok, size = 9.dp)
        }
    }
}

/** The glass card by the pin: flag and country, the address, up and down. */
@Composable
private fun ExitTip(s: HomeState, code: String, modifier: Modifier) {
    val shape = RoundedCornerShape(18.dp)
    val name = countryName(code)
    Column(
        modifier
            .widthIn(max = 220.dp)
            .floatShadow(shape)
            .glass(shape, solid = false)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FlagDisc(code, 16.dp)
            Spacer(Modifier.width(8.dp))
            Text(name, style = Type.strong.copy(fontSize = 15.sp).script(name), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Glyph.globe, null, Modifier.size(15.dp), tint = Tone.text2)
            Spacer(Modifier.width(8.dp))
            Text(s.seen?.ip ?: "checking…", style = Type.mono.copy(color = Tone.text, fontSize = 13.sp), maxLines = 1)
        }
        if (s.feel.meter) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Glyph.arrowUpRight, null, Modifier.size(14.dp), tint = Tone.text2)
                Spacer(Modifier.width(4.dp))
                RollingText(rate(s.traffic.upRate), Type.mono.copy(color = Tone.text2))
                Spacer(Modifier.width(10.dp))
                Icon(Glyph.arrowDownLeft, null, Modifier.size(14.dp), tint = Tone.text2)
                Spacer(Modifier.width(4.dp))
                RollingText(rate(s.traffic.downRate), Type.mono.copy(color = Tone.text2))
            }
        }
    }
}

// -- the foot -------------------------------------------------------------------------------

private const val WAY_NEEDS_TUNNEL = "Set the domain and passwords under Settings → Your own tunnel first."

/** Anything the screen has to say, then the server card. */
@Composable
private fun Foot(s: HomeState, onPick: () -> Unit, onAct: () -> Unit, onWay: (Way) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = Space.x2, bottom = Space.x3)) {
        AnimatedVisibility(s.hint.isNotEmpty()) {
            Text(s.hint, style = Type.caption.copy(color = if (s.hintBad) Tone.fail else Tone.text2,
                textAlign = TextAlign.Center), maxLines = 4, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(bottom = Space.x3, start = Space.x2, end = Space.x2))
        }
        val ready = s.way == Way.PROVIDER || s.tunnelReady
        AnimatedVisibility(!ready) {
            Text(WAY_NEEDS_TUNNEL, style = Type.caption.copy(color = Tone.fail, textAlign = TextAlign.Center),
                modifier = Modifier.fillMaxWidth().padding(bottom = Space.x3))
        }
        ServerCard(s, onPick)
        Spacer(Modifier.height(Space.x3))
        val mode = s.conn.mode
        Btn(
            when (mode) {
                Mode.BUSY -> "Cancel"
                Mode.ON -> "Disconnect"
                else -> "Connect"
            },
            onAct,
            Modifier.fillMaxWidth().heightIn(min = 54.dp),
            enabled = mode == Mode.BUSY || mode == Mode.ON || s.canStart,
            strong = mode != Mode.ON,
            icon = Glyph.power,
        )
        Spacer(Modifier.height(Space.x3))
        WayCards(s, onWay)
    }
}

/**
 * The ways out, as three cards side by side under Connect - never a menu.
 * Only the ways that can work now are drawn; with one there is nothing to
 * choose and the row is not drawn. Locked while connecting.
 */
@Composable
private fun WayCards(s: HomeState, onWay: (Way) -> Unit) {
    val ways = waysOf(s)
    if (ways.size < 2) return
    val busy = s.conn.mode == Mode.BUSY
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.x2)) {
        for (w in ways) {
            val on = w == s.way
            val shape = RoundedCornerShape(16.dp)
            Column(
                Modifier
                    .weight(1f)
                    .heightIn(min = 72.dp)
                    .press(enabled = !busy && !on, shape = shape, sink = true, onClick = { onWay(w) })
                    .clip(shape)
                    .glass(shape, solid = false)
                    .border(if (on) 1.5.dp else 1.dp, if (on) Tone.text2 else Tone.stroke, shape)
                    .graphicsLayer { alpha = if (busy) 0.45f else 1f }
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(w.label, style = Type.strong.copy(fontSize = 13.sp, textAlign = TextAlign.Center), maxLines = 1)
                Text(WAY_NOTE.getValue(w), style = Type.caption.copy(fontSize = 11.sp, lineHeight = 14.sp,
                    textAlign = TextAlign.Center), maxLines = 3)
            }
        }
    }
}

/**
 * The reference's foot card: the place's round flag, its name, the address
 * under it, and a chevron - the way into the list of places. While
 * connected it is where the traffic leaves; before, what is picked.
 */
@Composable
private fun ServerCard(s: HomeState, onPick: () -> Unit) {
    val mode = s.conn.mode
    val pickable = s.way != Way.SERVER && mode != Mode.BUSY
    val any = s.picked == "auto" && s.way != Way.SERVER
    val pickCode = countryOfPick(s.picked).lowercase()
    val exit = connectedCode(s)
    val place: String
    val code: String
    when {
        s.way == Way.SERVER && mode != Mode.ON -> { place = "Your server"; code = "" }
        mode == Mode.ON && s.seen == null && s.way != Way.PROVIDER -> { place = "Your server"; code = "" }
        mode == Mode.ON && exit.isNotEmpty() -> {
            place = if (!any && pickCode == exit) s.pickLabel else countryName(exit)
            code = exit
        }
        any -> { place = "Any country"; code = "" }
        else -> { place = s.pickLabel.ifEmpty { "Choose a location" }; code = pickCode }
    }
    val detail = when {
        mode == Mode.ON && s.seen != null -> "IP ${s.seen.ip}"
        mode == Mode.ON -> "checking the address…"
        s.way == Way.SERVER -> "Reached through a CDN"
        any -> "The quickest of ${s.serverCount} servers"
        s.serverCount > 0 -> "${s.serverCount} servers in ${s.countries} countries"
        else -> "No servers yet"
    }

    val shape = RoundedCornerShape(Shape.card)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 76.dp)
            .press(pickable, shape, sink = true, sinkTo = 0.985f, onClick = onPick)
            .floatShadow(shape)
            .glass(shape, solid = false)
            .padding(start = 12.dp, end = Space.x4, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (code.isNotEmpty()) FlagDisc(code, 52.dp)
        else IconWell(Glyph.globe, size = 52.dp, tint = Tone.text)
        Spacer(Modifier.width(Space.x3))
        Column(Modifier.weight(1f)) {
            Text(place, style = Type.title.copy(fontSize = 20.sp, lineHeight = 26.sp).script(place),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (mode == Mode.ON) {
                    LiveDot(size = 6.dp, ring = false)
                    Spacer(Modifier.width(6.dp))
                }
                Text(detail, style = Type.mono.copy(fontSize = 12.5.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (pickable) {
            Spacer(Modifier.width(Space.x2))
            Icon(Glyph.chevRight, "Change location", Modifier.size(20.dp), tint = Tone.text2)
        }
    }
}

// -- layout ---------------------------------------------------------------------------------

/**
 * A column [height] tall, its [flex]th child given whatever the others leave
 * it, but never less than [least]. When the others leave less, the column is
 * taller than [height] and whatever holds it scrolls.
 */
@Composable
private fun FillColumn(height: Dp, flex: Int, least: Dp, content: @Composable () -> Unit) {
    androidx.compose.ui.layout.Layout(content) { measurables, constraints ->
        val loose = constraints.copy(minHeight = 0, maxHeight = androidx.compose.ui.unit.Constraints.Infinity)
        val placed = arrayOfNulls<androidx.compose.ui.layout.Placeable>(measurables.size)
        measurables.forEachIndexed { i, m -> if (i != flex) placed[i] = m.measure(loose) }
        val rest = placed.sumOf { it?.height ?: 0 }
        val h = maxOf(least.roundToPx(), height.roundToPx() - rest)
        placed[flex] = measurables[flex].measure(loose.copy(minHeight = h, maxHeight = h))
        val width = constraints.maxWidth
        layout(width, rest + h) {
            var y = 0
            for (p in placed) {
                p!!.place((width - p.width) / 2, y)
                y += p.height
            }
        }
    }
}
