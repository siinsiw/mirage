package com.erelay.relay.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import com.erelay.relay.data.City
import com.erelay.relay.data.Country
import com.erelay.relay.data.Exit
import com.erelay.relay.data.ReachRun
import com.erelay.relay.data.Sort
import com.erelay.relay.data.Source
import com.erelay.relay.ui.theme.Data
import com.erelay.relay.ui.theme.Shape
import com.erelay.relay.ui.theme.Space
import com.erelay.relay.ui.theme.Tone
import com.erelay.relay.ui.theme.Type
import com.erelay.relay.ui.theme.script
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/** What the picker needs from the core, as plain values and callbacks. */
class PickerModel(
    val countries: List<Country>,
    val favourites: Set<String>,
    val sort: Sort,
    val picked: String,
    val reach: ReachRun,
    val sources: List<Source>,
    val source: String,
    val exitsIn: suspend (String) -> List<Exit>,
    val onSort: (Sort) -> Unit,
    val onStar: (String) -> Unit,
    val onTest: (String) -> Unit,
    val onCancelTest: () -> Unit,
    val onChoose: (String) -> Unit,
    val onSources: () -> Unit,
    /** Kept for the caller; the list no longer shows a Test all button. */
    val onTestAll: () -> Unit = {},
    /** The picker opened: the moment a stale list gets a fresh quick test. */
    val onOpen: () -> Unit = {},
    /**
     * Whether choosing a row connects now, or only remembers the pick for
     * later - while a connect is already under way. The touch says which.
     */
    val connects: Boolean = true,
    /** Where the tunnel is now, for the card at the top of the list; null draws no card. */
    val now: PickerNow? = null,
)

/**
 * The server the screen opens on - the reference's top card: where the
 * traffic leaves now (or what is picked, before), the address, the traffic
 * either way, the ping, and the one action - slide to disconnect, or connect.
 */
class PickerNow(
    val mode: com.erelay.relay.data.Mode,
    /** Two letters, or empty for "any" or your own server. */
    val code: String,
    val name: String,
    val ip: String,
    /** Bytes a second, down and up. */
    val down: Long,
    val up: Long,
    val ping: Int?,
    val canAct: Boolean,
    val onAct: () -> Unit,
)

/** A row chosen: the firmer confirm when it connects, a tick when it only remembers. */
private fun Haptics.chose(connects: Boolean) = if (connects) confirm() else select()

// -- sections -----------------------------------------------------------------------------

/**
 * One section of the list: a title, a line under it that says why these are
 * together, and the countries in it as codes. Every country is in one section only.
 */
private class Shelf(val key: String, val title: String, val note: String, val codes: List<String>)

/** How many countries "Fastest now" holds: a short answer, not a second list. */
private const val FASTEST = 4

// -- search and order -------------------------------------------------------------------

/**
 * A match score for the search box, standing in for fuse.js: the desktop
 * searches name, code and alias with a forgiving threshold. A prefix beats
 * a substring beats letters in order; nothing else matches.
 */
private fun score(c: Country, q: String): Int? {
    if (q.isEmpty()) return 0
    val hay = listOf(c.name.lowercase(), c.code.lowercase()) + c.alias.map { it.lowercase() }
    var best: Int? = null
    for (h in hay) {
        val s = when {
            h == q -> 0
            h.startsWith(q) -> 1
            h.split(' ', '-').any { it.startsWith(q) } -> 2
            h.contains(q) -> 3
            q.length >= 3 && inOrder(h, q) -> 5
            else -> null
        }
        if (s != null && (best == null || s < best)) best = s
    }
    return best
}

private fun inOrder(h: String, q: String): Boolean {
    var i = 0
    for (ch in h) if (i < q.length && ch == q[i]) i++
    return i == q.length
}

/** Starred itself, or one of its cities is: either way it goes to the top. */
private fun Country.starredIn(favourites: Set<String>) =
    code in favourites || cities.any { it.code in favourites }

/** Within a group: places known dead sink, then by the chosen measure; untimed after timed. */
private fun inGroup(sort: Sort): Comparator<Country> = compareBy<Country> { it.blocked }
    .then(when (sort) {
        Sort.PING -> compareBy<Country>({ it.tested == 0 }, { it.ping == null }, { it.ping ?: 0 }, { it.name })
        Sort.LOAD -> compareBy<Country>({ it.lowestLoad == null }, { it.lowestLoad ?: 0 }, { it.name })
        Sort.NAME -> compareBy { it.name }
    })

/**
 * The list, by what is useful rather than by geography - the question on
 * this screen is "where do I connect", and the answer is rarely "which
 * continent":
 *
 *   Starred        the user's own short list, first
 *   Fastest now    the few with the quickest measured round trip - the
 *                  answer to that question for whoever has none in mind
 *   All locations  everything else, in the order the chips at the top chose
 *   Not answering  the places known dead, at the foot, out of the way
 *
 * A search is one list of results, best match first. Empty sections are left out.
 */
private fun laidOut(countries: List<Country>, q: String, sort: Sort, favourites: Set<String>): List<Shelf> {
    if (q.isNotEmpty()) {
        val hits = countries.mapNotNull { c -> score(c, q)?.let { c to it } }
        if (hits.isEmpty()) return emptyList()
        val n = hits.size
        return listOf(Shelf("results", "Results", if (n == 1) "1 match" else "$n matches",
            hits.sortedWith(compareBy<Pair<Country, Int>>({ it.second }, { it.first.blocked }, { it.first.name }))
                .map { it.first.code }))
    }
    val starred = countries.filter { it.starredIn(favourites) }.sortedWith(inGroup(sort))
    val dead = countries.filter { it.blocked && !it.starredIn(favourites) }.sortedBy { it.name }
    val open = countries.filter { !it.blocked && !it.starredIn(favourites) }
    val timed = open.filter { it.tested > 0 && it.ping != null }.sortedBy { it.ping }
    // Only worth a section when there is something to compare.
    val fastest = if (timed.size >= 2) timed.take(FASTEST) else emptyList()
    val fastCodes = fastest.map { it.code }.toSet()
    val rest = open.filter { it.code !in fastCodes }.sortedWith(inGroup(sort))
    return buildList {
        if (starred.isNotEmpty()) add(Shelf("starred", "Starred", "Your short list", starred.map { it.code }))
        if (fastest.isNotEmpty()) add(Shelf("fastest", "Fastest now", "The quickest round trips from here, just measured",
            fastest.map { it.code }))
        if (rest.isNotEmpty()) add(Shelf("all", if (fastest.isEmpty() && starred.isEmpty()) "Locations" else "All locations",
            when (sort) {
                Sort.PING -> "Quickest first"
                Sort.LOAD -> "Least busy first"
                Sort.NAME -> "A to Z"
            }, rest.map { it.code }))
        if (dead.isNotEmpty()) add(Shelf("dead", "Not answering", "Their servers did not reply to the last test",
            dead.map { it.code }))
    }
}

/**
 * A narrow row: a small screen, or a large font on an ordinary one - what
 * counts is how much text fits across. The ping then goes under the name
 * instead of beside the star.
 */
private val LocalNarrow = staticCompositionLocalOf { false }

/** The closest two scroll ticks may come: a fling past ten rows is a run of ticks, not a hum. */
private const val TICK_GAP_MS = 45L

@Composable
private fun narrowScreen(): Boolean {
    val density = LocalDensity.current
    val wide = with(density) { LocalWindowInfo.current.containerSize.width.toDp() }
    return wide.value / density.fontScale < 380f
}

// -- the sheet --------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun Picker(open: Boolean, m: PickerModel, onClose: () -> Unit) {
    var query by remember { mutableStateOf("") }
    // Bumped by tapping the sort that is already chosen: the way to put
    // times that came in since the list was laid out into their places.
    var resort by remember { mutableStateOf(0) }
    LaunchedEffect(open) {
        if (!open) query = "" else m.onOpen()
    }

    Sheet(open, "Server", onClose, scroll = false) {
        val q = query.trim().lowercase()
        // The order is laid out once - on opening, on a new sort or search -
        // and not again when results arrive, so a row never moves out from
        // under a finger. The rows themselves still show the newest numbers.
        val groups = remember(open, m.sort, m.favourites, q, resort, m.countries.size) {
            laidOut(m.countries, q, m.sort, m.favourites)
        }
        val byCode = remember(m.countries) { m.countries.associateBy { it.code } }
        val expanded = remember { mutableStateMapOf<String, Boolean>() }
        // Rows that have made their entrance this time the sheet is open, so
        // one scrolled back into view does not make it again.
        val entered = remember(open) { mutableSetOf<String>() }
        val reduced = rememberReducedMotion()
        val list = rememberLazyListState()
        // A light detent as each country passes the top of the list, the
        // way a picker wheel clicks under the thumb. Only while a finger has
        // the list - dragging it, or the fling it let go into - and never for
        // a scroll the app made itself; only for a country row, not a
        // heading; and never closer together than a fast fling's rows would
        // make a buzz of it. Look and feel's switch, and the phone's own
        // touch-feedback setting, are both honoured by [Haptics].
        val haptics = rememberHaptics()
        LaunchedEffect(list, haptics) {
            // From a drag's start until the scroll it began - the fling
            // included - comes to rest. A programmatic scroll never drags.
            var byFinger = false
            launch {
                list.interactionSource.interactions.collect {
                    if (it is DragInteraction.Start) byFinger = true
                }
            }
            launch {
                snapshotFlow { list.isScrollInProgress }.collect { if (!it) byFinger = false }
            }
            var last = 0L
            snapshotFlow { list.firstVisibleItemIndex }
                .drop(1)
                .collect {
                    if (!byFinger || !list.isScrollInProgress) return@collect
                    val top = list.layoutInfo.visibleItemsInfo.firstOrNull()
                    if (top?.contentType != "country") return@collect
                    val now = android.os.SystemClock.uptimeMillis()
                    if (now - last >= TICK_GAP_MS) {
                        last = now
                        haptics.detent()
                    }
                }
        }
        val quick = m.reach.code == "quick"
        val timing = quick && m.reach.running
        val stale = m.sort == Sort.PING && q.isEmpty() && m.countries.any { it.tested == 0 } &&
            m.countries.any { it.ping != null }

        Column(Modifier.padding(horizontal = Space.x4)) {
            Search(query) { query = it }
            Spacer(Modifier.height(Space.x3))
            // One scrollable line: the sorts, then where the list comes from.
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(Space.x2),
                verticalAlignment = Alignment.CenterVertically) {
                SortChip(Glyph.gauge, "Ping", m.sort == Sort.PING) {
                    if (m.sort == Sort.PING) resort++ else m.onSort(Sort.PING)
                }
                SortChip(Glyph.layers, "Load", m.sort == Sort.LOAD) { m.onSort(Sort.LOAD) }
                SortChip(Glyph.az, "A–Z", m.sort == Sort.NAME) { m.onSort(Sort.NAME) }
                SourceChip(m.sources.firstOrNull { it.key == m.source }?.name ?: "Everything", m.onSources)
            }
            val note = when {
                timing -> "Timing ${m.reach.done} of ${m.reach.total} countries…"
                stale -> "New times are in. Tap Ping to put them in order."
                else -> sortNote(m)
            }
            if (note.isNotEmpty() || timing) {
                Row(Modifier.padding(top = Space.x3), verticalAlignment = Alignment.CenterVertically) {
                    // Three lines before it is cut, and an ellipsis when it is:
                    // a narrow phone with a large font needs the third.
                    Text(note, style = Type.caption, maxLines = 3, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f))
                    if (timing) {
                        Text("Stop", style = Type.caption.copy(color = Tone.text),
                            modifier = Modifier.heightIn(min = 32.dp).clickable { m.onCancelTest() }
                                .padding(start = Space.x3), maxLines = 1)
                    }
                }
                if (timing) {
                    Spacer(Modifier.height(Space.x2))
                    Box(Modifier.fillMaxWidth().height(2.dp).background(Tone.surface2)) {
                        Box(Modifier.fillMaxWidth(if (m.reach.total > 0)
                            (m.reach.done.toFloat() / m.reach.total).coerceIn(0.03f, 1f) else 0.03f)
                            .height(2.dp).background(Tone.text2))
                    }
                }
            }
            Spacer(Modifier.height(Space.x1))
        }

        val narrow = narrowScreen()
        CompositionLocalProvider(LocalNarrow provides narrow) {
            // Drawer motion (DESIGN-NIGHT 6): rows arrive over 0.3s expo.out
            // and leave over 0.18s power2.in; nothing moves with animations off.
            val rowIn = if (reduced) null else tween<Float>(Shape.DRAWER_OPEN_MS, easing = Shape.expoOut)
            val rowOut = if (reduced) null else tween<Float>(Shape.DRAWER_CLOSE_MS, easing = Shape.power2In)
            val rowMove = if (reduced) null
                else tween<androidx.compose.ui.unit.IntOffset>(Shape.DRAWER_OPEN_MS, easing = Shape.expoOut)
            LazyColumn(Modifier.fillMaxWidth().weight(1f), state = list,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = Space.x4)) {
                // The server in use, on top, scrolling away with the list; not while a search is typed.
                m.now?.let { now ->
                    if (q.isEmpty()) item(key = "now", contentType = "now") { NowCard(now) }
                }
                if (groups.isEmpty()) {
                    item(key = "none") {
                        Text("No country matches “$query”. Try a city or a two-letter code, or clear the search.",
                            style = Type.body, modifier = Modifier.fillMaxWidth().padding(vertical = Space.x6))
                    }
                }
                // Every section open, every row on the page: one list to scroll,
                // its headings stuck to the top while their rows pass under.
                var order = 0
                for (shelf in groups) {
                    item(key = "gap-${shelf.key}", contentType = "gap") { Spacer(Modifier.height(Space.x3)) }
                    stickyHeader(key = "group-${shelf.key}", contentType = "header") {
                        ShelfHead(shelf.title, shelf.note, shelf.codes.size)
                    }
                    val base = order
                    order += shelf.codes.size
                    itemsIndexed(shelf.codes, key = { _, c -> c }, contentType = { _, _ -> "country" }) { i, code ->
                        // Bare rows on the page with hairlines between, as the reference's list is.
                        Box(Modifier.animateItem(fadeInSpec = rowIn, placementSpec = rowMove, fadeOutSpec = rowOut)) {
                            byCode[code]?.let { c ->
                                // The first rows on screen come in one after another, down
                                // the whole list rather than per section; decided once, so a
                                // later recomposition does not cut it short.
                                val stagger = remember { if (!reduced && base + i < 12 && code !in entered) base + i else -1 }
                                SideEffect { entered += code }
                                Entrance(stagger) { CountryItem(c, m, expanded, first = i == 0) }
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(Space.x6)) }
            }
        }
    }
}

/**
 * A section's heading, the reference's "Free server" / "Premium server": the
 * title in 18sp on the bare page, its count in mono beside it, and one quiet
 * line under it saying why these are together. Sticky, with the page drawn
 * behind it so rows passing under do not show through. Not a control.
 */
@Composable
private fun ShelfHead(title: String, note: String, n: Int) {
    Column(
        Modifier.fillMaxWidth()
            .drawBehind { drawRect(Tone.bg) }
            .semantics { heading() }
            .padding(start = Space.x1, end = Space.x1, top = Space.x2, bottom = Space.x2),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = Type.subtitle.copy(fontSize = 18.sp, lineHeight = 24.sp), maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(Space.x2))
            Text("$n", style = Type.mono.copy(color = Tone.text3), maxLines = 1)
        }
        if (note.isNotEmpty()) Text(note, style = Type.caption, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * A row's entrance (DESIGN-NIGHT 6): up 8dp from transparent, 30ms after the
 * row above it. [order] below zero: no entrance, it is simply there.
 */
@Composable
private fun Entrance(order: Int, content: @Composable () -> Unit) {
    if (order < 0) {
        content()
        return
    }
    val p = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(Unit) {
        p.animateTo(1f, tween(Shape.DRAWER_OPEN_MS, delayMillis = order * 30, easing = Shape.expoOut))
    }
    Box(Modifier.graphicsLayer {
        alpha = p.value
        translationY = (1f - p.value) * 8.dp.toPx()
    }) { content() }
}

// -- the server in use --------------------------------------------------------------------

/** Bytes a second as the reference writes speed: megabits, one decimal under ten. */
private fun speedOf(caption: String, bps: Long, on: Boolean): Stat {
    if (!on) return Stat(caption, "—", "")
    val mbps = bps * 8.0 / 1_000_000.0
    val v = when {
        mbps >= 10 -> "%.0f".format(mbps)
        mbps >= 0.05 -> "%.1f".format(mbps)
        else -> "0"
    }
    return Stat(caption, v, "mbps")
}

/**
 * The reference's top card: the flag as a bead, the country big, the
 * address under it; download, ping and upload in a strip; then the action -
 * a track to slide for Disconnect (or Cancel), so a brush of the thumb never
 * drops the tunnel, or the accent button for Connect.
 */
@Composable
private fun NowCard(n: PickerNow) {
    val shape = RoundedCornerShape(Shape.card)
    val on = n.mode == com.erelay.relay.data.Mode.ON
    val busy = n.mode == com.erelay.relay.data.Mode.BUSY
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = Space.x1, bottom = Space.x4)
            .clip(shape)
            .background(surfaceBrush())
            .border(1.dp, Tone.stroke, shape)
            .padding(start = Space.x5, end = Space.x5, top = Space.x5, bottom = Space.x4),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (n.code.isNotEmpty()) FlagDisc(n.code, 56.dp)
            else IconWell(Glyph.globe, size = 56.dp, tint = Tone.text)
            Spacer(Modifier.width(Space.x4))
            Column(Modifier.weight(1f)) {
                Text(n.name, style = Type.title.copy(fontSize = 24.sp, lineHeight = 30.sp).script(n.name),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (n.ip.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Glyph.globe, null, Modifier.size(14.dp), tint = Tone.text2)
                        Spacer(Modifier.width(6.dp))
                        Text(n.ip, style = Type.mono.copy(fontSize = 13.sp, color = Tone.text2), maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        Spacer(Modifier.height(Space.x4))
        StatStrip(
            down = speedOf("Download", n.down, on),
            ping = if (n.ping != null && n.ping > 0) Stat("Ping", n.ping.toString(), "ms") else Stat("Ping", "—", ""),
            up = speedOf("Upload", n.up, on),
            pingLevel = barsFor(n.ping),
        )
        Spacer(Modifier.height(Space.x4))
        // One press either way: the accent pill to connect; a ghost pill with
        // its edge to disconnect or cancel, so stopping never looks like the primary.
        Btn(
            when {
                on -> "Disconnect"
                busy -> "Cancel"
                else -> "Connect"
            },
            n.onAct, Modifier.fillMaxWidth().heightIn(min = 56.dp), enabled = n.canAct,
            strong = !on && !busy, icon = Glyph.power,
        )
    }
}

// -- rows -------------------------------------------------------------------------------

/** A hairline between two rows inside a drawer, from [inset] in to the card's inner edge. */
@Composable
private fun Hair(inset: Dp = Space.x4) {
    Box(Modifier.fillMaxWidth().padding(start = inset, end = Space.x4).height(1.dp).background(Tone.hairline))
}

@Composable
private fun rowColors() = ListItemDefaults.colors(containerColor = Color.Transparent)

@Composable
private fun CountryItem(c: Country, m: PickerModel, expanded: MutableMap<String, Boolean>, first: Boolean = false) {
    val open = expanded[c.code] == true
    val haptics = rememberHaptics()
    val starred = c.code in m.favourites
    val narrow = LocalNarrow.current
    val reach = m.reach.takeIf { it.code == c.code }
    val testing = reach?.running == true
    // Lit when the pick is anywhere inside it - a city or one server in
    // it as much as the country itself - so the list says where you are.
    val picked = m.picked != "auto" && countryOfPick(m.picked).lowercase() == c.code.lowercase()
    val expandable = c.count > 1 || c.cities.size > 1
    val tenG = c.cities.any { (it.gbps ?: 0) >= 10 }
    val p2p = c.cities.any { it.p2p == true }

    Column(Modifier.fillMaxWidth().alpha(if (c.blocked) 0.6f else 1f)) {
        if (!first) Hair(Space.x1)
        // The reference's row: a round glossy flag, the name over a "N servers"
        // line, and a small Connect pill at the end. The whole row still
        // chooses; the pill is the same choice said out loud.
        ListItem(
            modifier = Modifier
                // No haptic of its own: the caller's says whether this connects.
                .press(feel = null) { haptics.chose(m.connects); m.onChoose(c.code) },
            colors = rowColors(),
            leadingContent = { FlagDisc(c.code, 44.dp) },
            headlineContent = {
                Text(c.name, style = Type.strong.copy(fontSize = 17.sp, lineHeight = 22.sp).script(c.name),
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            },
            supportingContent = {
                val places = if (c.cities.size > 1) "${c.cities.size} cities"
                    else c.cities.firstOrNull()?.name?.takeIf { it.isNotEmpty() }
                val line = listOfNotNull("${c.count} server${if (c.count == 1) "" else "s"}", places)
                    .joinToString("  ·  ")
                Column {
                    // The line under the name opens the country's cities and servers.
                    Row(
                        Modifier.then(if (expandable) Modifier.press(shape = RoundedCornerShape(Shape.control)) {
                            haptics.tick(); expanded[c.code] = !open
                        } else Modifier).padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(line, style = Type.caption, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false))
                        if (expandable) {
                            val turn by animateFloatAsState(if (open) 180f else 0f,
                                tween(Shape.DRAWER_OPEN_MS, easing = Shape.expoOut), label = "rowChev")
                            Icon(Glyph.chevDown, if (open) "Hide servers" else "Show servers",
                                Modifier.padding(start = 2.dp).size(14.dp).graphicsLayer { rotationZ = turn },
                                tint = Tone.text3)
                        }
                    }
                    ProviderLine(c.by, c.byTested, c.byOk, tenG, p2p,
                        extra = { PingText(c.ping, c.blocked, reach.takeIf { testing }) })
                }
            },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Star(starred) { m.onStar(c.code) }
                    ConnectPill(
                        text = when {
                            picked && m.now?.mode == com.erelay.relay.data.Mode.ON -> "Connected"
                            picked -> "Selected"
                            else -> "Connect"
                        },
                        current = picked,
                        onClick = { haptics.chose(m.connects); m.onChoose(c.code) },
                    )
                }
            },
        )
        // The country's cities and servers hang under it, indented past the
        // flag, each set off by a hairline.
        Expand(open) {
            Column(Modifier.padding(start = Space.x10)) {
                Hair(Space.x4)
                TestRow(testing, reach) { if (testing) m.onCancelTest() else m.onTest(c.code) }
                if (c.cities.size > 1) {
                    // Starred cities first; the rest keep the order the core gave.
                    // Keyed by city, so a city's own open/closed state goes with it
                    // when starring one moves it up, rather than staying at the slot.
                    for (city in c.cities.sortedBy { it.code !in m.favourites }) key(city.code) {
                        Hair(Space.x4)
                        CityBlock(city, m)
                    }
                } else {
                    ExitList(c.code, m)
                }
            }
        }
    }
}

/** Opens in place (DESIGN-NIGHT 6): height and opacity, 0.3s expo.out; shuts in 0.18s power2.in. */
@Composable
private fun Expand(visible: Boolean, content: @Composable () -> Unit) {
    val reduced = rememberReducedMotion()
    val inMs = if (reduced) 0 else Shape.DRAWER_OPEN_MS
    val outMs = if (reduced) 0 else Shape.DRAWER_CLOSE_MS
    AnimatedVisibility(visible,
        enter = expandVertically(tween(inMs, easing = Shape.expoOut)) + fadeIn(tween(inMs, easing = Shape.expoOut)),
        exit = shrinkVertically(tween(outMs, easing = Shape.power2In)) + fadeOut(tween(outMs, easing = Shape.power2In))) {
        content()
    }
}

/** Providers' bare marks, 10G and P2P as words; [extra] is a place to put the ping on a narrow row. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProviderLine(
    by: Map<String, Int>, byTested: Map<String, Int>, byOk: Map<String, Int>,
    tenG: Boolean, p2p: Boolean, extra: (@Composable () -> Unit)? = null,
) {
    val marks = by.toSortedMap().filter { it.value > 0 }
    if (marks.isEmpty() && !tenG && !p2p && extra == null) return
    FlowRow(Modifier.padding(top = Space.x1), itemVerticalAlignment = Alignment.CenterVertically) {
        for ((provider, n) in marks) {
            ProviderTag(provider, count = n, tested = byTested[provider] ?: 0, ok = byOk[provider] ?: 0)
        }
        if (tenG) Text("10G", style = Type.caption, modifier = Modifier.padding(end = Space.x2))
        if (p2p) Text("P2P", style = Type.caption, modifier = Modifier.padding(end = Space.x2))
        extra?.invoke()
    }
}

/** The round trip, in mono and tabular; "blocked" in the fail colour; the test's progress while it runs. */
@Composable
private fun PingText(ping: Int?, blocked: Boolean, testing: ReachRun?) {
    val (say, c) = when {
        testing != null -> "${testing.done}/${testing.total}" to Tone.work
        blocked -> "blocked" to Tone.fail
        ping != null -> "$ping ms" to Tone.text2
        else -> return
    }
    Text(say, style = Type.mono.copy(color = c), maxLines = 1, softWrap = false,
        modifier = Modifier.padding(end = Space.x1))
}

/**
 * A star, felt as a switch: on with one tick, off with a softer one. A line
 * star either way (no filled icons beside line ones); on, it is in Pulse.
 */
@Composable
private fun Star(starred: Boolean, onClick: () -> Unit) {
    val haptics = rememberHaptics()
    IconToggleButton(checked = starred, onCheckedChange = { haptics.toggle(it); onClick() }) {
        Icon(Glyph.star, if (starred) "Unstar" else "Star",
            Modifier.size(20.dp), tint = if (starred) Tone.work else Tone.text3)
    }
}

/** Open or close what is inside a row; the light tick under the thumb, since this lays a list out. */
@Composable
private fun Chevron(open: Boolean, onToggle: () -> Unit) {
    val haptics = rememberHaptics()
    val ms = if (rememberReducedMotion()) 0 else Shape.ENTER_MS
    val turn by animateFloatAsState(if (open) 180f else 0f, tween(ms, easing = Shape.ease), label = "chev")
    IconToggleButton(checked = open, onCheckedChange = { haptics.tick(); onToggle() }) {
        Icon(Glyph.chevDown, if (open) "Hide" else "Show", Modifier.size(20.dp).graphicsLayer { rotationZ = turn },
            tint = Tone.text2)
    }
}

/** Time just these exits: the per-row test, now inside the row that opens. */
@Composable
private fun TestRow(testing: Boolean, reach: ReachRun?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).press(onClick = onClick)
            .padding(horizontal = Space.x4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Glyph.bolt, null, Modifier.size(16.dp), tint = if (testing) Tone.work else Tone.text2)
        Spacer(Modifier.width(Space.x3))
        Text(if (testing) "Stop timing  ·  ${reach?.done ?: 0} of ${reach?.total ?: 0}" else "Time these exits",
            style = Type.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun CityBlock(city: City, m: PickerModel) {
    var open by remember { mutableStateOf(false) }
    val haptics = rememberHaptics()
    val narrow = LocalNarrow.current
    val testing = m.reach.running && m.reach.code == city.code
    val reach = m.reach.takeIf { testing }
    val starred = city.code in m.favourites
    val blocked = city.tested > 0 && city.ok == 0
    Column(Modifier.fillMaxWidth()) {
        ListItem(
            modifier = Modifier
                .pickedBar(m.picked == city.code)
                .press(feel = null) { haptics.chose(m.connects); m.onChoose(city.code) },
            colors = rowColors(),
            headlineContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(city.name.ifEmpty { city.code }, style = Type.strong, maxLines = 2,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (city.nick.isNotEmpty()) {
                        Spacer(Modifier.width(Space.x2))
                        Text(city.nick, style = Type.caption.copy(color = Tone.text3).script(city.nick), maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    }
                }
            },
            supportingContent = {
                Column {
                    Text(buildString {
                        append("${city.count} relay${if (city.count == 1) "" else "s"}")
                        if (city.load != null) append("  ·  ${city.load}% load")
                    }, style = Type.caption, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    ProviderLine(city.by, emptyMap(), emptyMap(), (city.gbps ?: 0) >= 10, city.p2p == true,
                        extra = if (narrow) { { PingText(city.ping, blocked, reach) } } else null)
                }
            },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!narrow) PingText(city.ping, blocked, reach)
                    Star(starred) { m.onStar(city.code) }
                    Chevron(open) { open = !open }
                }
            },
        )
        Expand(open) {
            Column(Modifier.padding(start = Space.x6)) {
                Hair(Space.x4)
                TestRow(testing, reach) { if (testing) m.onCancelTest() else m.onTest(city.code) }
                ExitList(city.code, m)
            }
        }
    }
}

/** The individual exits behind a row, fetched when it opens. */
@Composable
private fun ExitList(code: String, m: PickerModel) {
    var exits by remember(code) { mutableStateOf<List<Exit>?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(code, m.countries) { scope.launch { exits = m.exitsIn(code) } }
    val got = exits
    val haptics = rememberHaptics()
    when {
        got == null -> Column(Modifier.padding(Space.x4)) {
            // A skeleton of the rows that are coming, not a spinner.
            Skeleton(Modifier.fillMaxWidth(0.6f).height(12.dp))
            Spacer(Modifier.height(Space.x2))
            Skeleton(Modifier.fillMaxWidth(0.4f).height(10.dp))
        }
        got.isEmpty() -> Text("No exits here. Time them again, or pick the country itself.", style = Type.caption,
            modifier = Modifier.padding(Space.x4))
        else -> Column {
            for (e in got) key(e.file) {
                Hair(Space.x4)
                ExitRow(e, m.picked == "file:${e.file}") {
                    haptics.chose(m.connects)
                    m.onChoose("file:${e.file}")
                }
            }
        }
    }
}

/** The core's word for why an exit failed, said for a person. */
private fun failReason(why: String): String = when {
    why.isEmpty() -> "It did not answer."
    why.contains("timeout", true) || why.contains("deadline", true) ->
        "It did not answer in time. It may be down, or blocked on this line."
    why.contains("refused", true) -> "It refused the connection."
    why.contains("407") || why.contains("auth", true) -> "It turned the account away."
    else -> why.replaceFirstChar { it.uppercase() }
}

@Composable
private fun ExitRow(e: Exit, picked: Boolean, onClick: () -> Unit) {
    // The city is the name; the server's number and address are what
    // tell two exits in it apart.
    val number = serverNumber(e.host.ifEmpty { e.file })
    ListItem(
        modifier = Modifier.pickedBar(picked).press(feel = null, onClick = onClick),
        colors = rowColors(),
        leadingContent = {
            ProviderTag(e.provider, count = 1, tested = if (e.ok != null) 1 else 0,
                ok = if (e.ok == true) 1 else 0, slot = 16.dp)
        },
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(e.cityName.ifEmpty { e.host.ifEmpty { e.file } }, style = Type.strong,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (e.nick.isNotEmpty()) {
                    Spacer(Modifier.width(Space.x2))
                    Text(e.nick, style = Type.caption.copy(color = Tone.text3).script(e.nick), maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                }
            }
        },
        supportingContent = {
            Text(listOfNotNull(number ?: e.host.ifEmpty { null }, e.ip.ifEmpty { null }).joinToString(" · "),
                style = Type.mono, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        trailingContent = {
            when {
                e.ok == true && e.ms != null -> Text("${e.ms} ms", style = Type.mono, maxLines = 1, softWrap = false)
                // A red mark where the time would be; the reason is behind it.
                e.ok == false -> InfoTip(failReason(e.why), icon = Glyph.alert, tint = Tone.fail,
                    label = "Why it failed")
            }
        },
    )
}

// -- the controls above the list ----------------------------------------------------------

private fun sortNote(m: PickerModel): String {
    val tested = m.countries.any { it.tested > 0 }
    return when {
        m.reach.running -> "Testing ${m.reach.done} of ${m.reach.total}…"
        m.reach.said.isNotEmpty() -> m.reach.said
        m.sort == Sort.PING && !tested -> "Nothing tested yet."
        m.sort == Sort.LOAD && m.countries.all { it.lowestLoad == null } ->
            "No load figures in these servers. Get Surfshark's list to have them."
        m.sort == Sort.PING -> "Times are from the last test."
        else -> ""
    }
}

/** The search box over a long list: this sheet's countries, the split tunnel's apps. A pill; Pulse ring while typing. */
@Composable
fun Search(value: String, hint: String = "Search a country or city", onChange: (String) -> Unit) {
    val focus = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val focused by focus.collectIsFocusedAsState()
    val shape = RoundedCornerShape(Shape.pill)
    Row(
        Modifier
            .fillMaxWidth()
            // At least the height it was drawn at; taller when a large font
            // needs it, rather than the text being cut through the middle.
            .heightIn(min = 48.dp)
            .clip(shape)
            .background(Tone.surface2)
            .border(1.dp, when {
                focused -> Tone.focus
                value.isEmpty() -> Tone.stroke
                else -> Tone.strokeStrong
            }, shape)
            .padding(start = Space.x4, end = Space.x1, top = Space.x1, bottom = Space.x1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Glyph.search, null, Modifier.size(20.dp), tint = if (value.isEmpty()) Tone.text3 else Tone.text)
        Spacer(Modifier.width(Space.x3))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(hint, style = Type.body.copy(fontSize = 16.sp, color = Tone.text3),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicTextField(value, onChange, singleLine = true,
                textStyle = Type.body.copy(fontSize = 16.sp, color = Tone.text).script(value),
                cursorBrush = SolidColor(Tone.pulse), interactionSource = focus, modifier = Modifier.fillMaxWidth())
        }
        if (value.isNotEmpty()) IconButton(Glyph.x, "Clear", { onChange("") }, size = 40.dp)
    }
}

/**
 * A chip (DESIGN-NIGHT 4): a pill, Raised Slate with a stronger Edge when on,
 * a bare Edge when off. 36dp to look at, 44dp to touch.
 */
@Composable
fun Chip(
    label: String, on: Boolean, onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    trailing: androidx.compose.ui.graphics.vector.ImageVector? = null,
    maxWidth: Dp = Dp.Unspecified,
) {
    val shape = RoundedCornerShape(Shape.pill)
    Box(Modifier.heightIn(min = 44.dp).press(shape = shape, feel = null, onClick = onClick)
        .semantics { selected = on }, contentAlignment = Alignment.Center) {
        Row(
            Modifier.heightIn(min = 36.dp).clip(shape)
                .background(if (on) Tone.surface2 else Color.Transparent)
                .border(1.dp, if (on) Tone.strokeStrong else Tone.stroke, shape)
                .padding(horizontal = Space.x3),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, null, Modifier.size(16.dp), tint = if (on) Tone.text else Tone.text3)
                Spacer(Modifier.width(6.dp))
            }
            Text(label, style = Type.body.copy(color = if (on) Tone.text else Tone.text2), maxLines = 1,
                softWrap = false, overflow = TextOverflow.Ellipsis,
                modifier = if (maxWidth != Dp.Unspecified) Modifier.widthIn(max = maxWidth) else Modifier)
            if (trailing != null) {
                Spacer(Modifier.width(4.dp))
                Icon(trailing, null, Modifier.size(16.dp), tint = Tone.text3)
            }
        }
    }
}

/** A sort, as a chip that always says what it is. */
@Composable
private fun SortChip(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, on: Boolean,
                     onClick: () -> Unit) {
    val haptics = rememberHaptics()
    Chip(label, on, { haptics.tick(); onClick() }, icon = icon)
}

@Composable
private fun SourceChip(name: String, onClick: () -> Unit) {
    Chip(name, false, onClick, trailing = Glyph.chevDown, maxWidth = 180.dp)
}

/**
 * Loading, as the shape of what is coming (DESIGN-NIGHT 4): a Raised Slate
 * bar with a soft sheen passing over it. Still with animations off.
 */
@Composable
fun Skeleton(modifier: Modifier = Modifier) {
    val reduced = rememberReducedMotion()
    val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "skeleton")
    val x by t.animateFloat(-1f, 2f, androidx.compose.animation.core.infiniteRepeatable(
        tween(1400, easing = androidx.compose.animation.core.LinearEasing)), label = "sheen")
    Box(modifier.clip(RoundedCornerShape(Shape.pill)).background(Tone.surface2).drawBehind {
        if (!reduced) {
            val w = size.width
            drawRect(androidx.compose.ui.graphics.Brush.horizontalGradient(
                0f to Color.Transparent, 0.5f to Tone.wash.copy(alpha = 0.06f), 1f to Color.Transparent,
                startX = w * (x - 0.5f), endX = w * (x + 0.5f)))
        }
    })
}

/**
 * Who runs these exits: the provider's own mark and nothing round it - no
 * tile, no capsule, no border - drawn as the desktop's row__tag draws it. A
 * 17dp slot (16 on a single exit) holding a 14dp mark, so a row one provider
 * reaches keeps the same column as a row both do.
 *
 * The colour is the verdict, state for state: dim while untested, green
 * when every exit asked answered, amber when some did or not all were
 * asked, red when every one was asked and none did.
 */
@Composable
fun ProviderTag(provider: String, count: Int = 1, tested: Int = 0, ok: Int = 0, slot: Dp = 17.dp) {
    // Untested, the provider's own data colour; once asked, the status colours.
    val c = when {
        tested <= 0 -> Data.provider(provider)
        ok > 0 -> if (ok < tested) Tone.work else Tone.good
        tested >= count -> Tone.fail
        else -> Tone.work
    }
    Box(
        Modifier.padding(end = Space.x2).size(slot),
        contentAlignment = Alignment.Center,
    ) {
        when (provider) {
            "windscribe" -> Icon(Glyph.windscribe, "Windscribe", Modifier.size(14.dp), tint = c)
            "surfshark" -> Icon(Glyph.surfshark, "Surfshark", Modifier.size(14.dp), tint = c)
            // Anything not taught yet keeps its initial, which still holds the column.
            else -> Text(provider.take(1).uppercase(), style = Type.mono.copy(color = c))
        }
    }
}

/** Which pool to connect out of: Everything, or only the starred places. */
@Composable
fun SourceSheet(
    open: Boolean, sources: List<Source>, source: String,
    onPick: (String) -> Unit, onImport: () -> Unit, onClose: () -> Unit,
) {
    Sheet(open, "Servers", onClose) {
        if (sources.isNotEmpty()) Group {
            sources.forEachIndexed { i, s ->
                if (i > 0) Sep()
                ListItem(
                    modifier = Modifier.pickedBar(s.key == source).press { onPick(s.key) }.padding(horizontal = Space.x1),
                    colors = rowColors(),
                    headlineContent = { Text(s.name, style = Type.strong.script(s.name)) },
                    supportingContent = { Text(s.note, style = Type.caption) },
                    trailingContent = { CountBadge(s.count.toString(), active = s.key == source) },
                )
            }
        }
        if (sources.isEmpty()) Text("Nothing has been read yet. Import a folder of configs to begin.", style = Type.body,
            modifier = Modifier.padding(vertical = Space.x4))
        ButtonRow {
            Btn("Import a folder…", onImport, icon = Glyph.folder)
            InfoTip("A picked folder is copied into the app's own; the copy is what is read.")
        }
    }
}
