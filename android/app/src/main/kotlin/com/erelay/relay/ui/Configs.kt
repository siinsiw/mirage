package com.erelay.relay.ui

import android.content.ClipboardManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.erelay.relay.data.CfgGroup
import com.erelay.relay.data.CfgItem
import com.erelay.relay.data.CfgState
import com.erelay.relay.data.ConfigsModel
import com.erelay.relay.data.ConfigsStore
import com.erelay.relay.ui.theme.Accent
import com.erelay.relay.ui.theme.Shape
import com.erelay.relay.ui.theme.Space
import com.erelay.relay.ui.theme.Tone
import com.erelay.relay.ui.theme.Type
import com.erelay.relay.ui.theme.script
import kotlinx.coroutines.launch

/*
 * The Configs sheet: servers bought or given from anywhere, in a section of
 * their own - the phone's side of app/ui/configs.js.
 *
 * Dense on purpose. A seller's list is a hundred lines, and what is being
 * compared is the column of times on the right, so the rows are short and the
 * list is lazy: a weak phone composes the rows on screen, not all of them.
 * No motion of its own beyond the sheet's.
 */

private sealed interface CfgAsk {
    data class Plain(val c: CfgItem) : CfgAsk
    data class Trust(val c: CfgItem) : CfgAsk
    data class Delete(val c: CfgItem) : CfgAsk
    data class Rename(val c: CfgItem) : CfgAsk
    data class DropGroup(val g: CfgGroup, val n: Int) : CfgAsk
}

private val WARN = mapOf(
    "plain" to "No encryption",
    "notls" to "No TLS",
    "insecure" to "Asks to skip the certificate check",
)

private val FAIL = mapOf(
    "timeout" to "timeout", "cert" to "certificate", "refused" to "refused",
    "dns" to "no address", "closed" to "closed", "failed" to "failed",
    "xray-missing" to "needs Xray", "cancelled" to "",
)

@Composable
fun ConfigsSheet(
    open: Boolean,
    model: ConfigsModel,
    state: CfgState,
    connected: Boolean,
    onConnect: (String) -> Unit,
    onCopy: (String) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var said by remember { mutableStateOf("" to false) }
    var subOpen by remember { mutableStateOf(false) }
    var subUrl by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf<String?>(null) }
    var ask by remember { mutableStateOf<CfgAsk?>(null) }
    var newName by remember { mutableStateOf("") }

    fun go(c: CfgItem) {
        if (connected && model.switchLive(c.id)) {
            said = "Now through ${c.name}." to false
            return
        }
        onConnect(c.id)
    }

    fun tap(c: CfgItem) {
        if ("plain" in c.warnings) ask = CfgAsk.Plain(c) else go(c)
    }

    fun paste() {
        val cm = context.getSystemService(ClipboardManager::class.java)
        val clip = cm?.primaryClip
        val text = if (clip != null && clip.itemCount > 0)
            clip.getItemAt(0).coerceToText(context)?.toString().orEmpty() else ""
        if (text.isBlank()) {
            said = "Copy the links first - from Telegram, a seller's message, anywhere - then press Paste links." to true
            return
        }
        scope.launch {
            val r = model.import(text)
            said = r.text to !r.ok
        }
    }

    Sheet(open, "Configs", onClose, scroll = false) {
        val byGroup = state.items.groupBy { it.group }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = Space.x4, end = Space.x4, bottom = Space.x8),
        ) {
            item(key = "bar") {
                Column(Modifier.fillMaxWidth()) {
                    Btn("Paste links", ::paste, Modifier.fillMaxWidth(), strong = true, icon = Glyph.plus)
                    Spacer(Modifier.height(Space.x2))
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.x2)) {
                        Btn("Subscription", { subOpen = !subOpen }, Modifier.weight(1f), icon = Glyph.refresh)
                        Btn(if (state.testing) "Stop · ${state.done}/${state.total}" else "Test all",
                            { if (state.testing) model.cancelTest() else model.test() },
                            Modifier.weight(1f), icon = Glyph.gauge,
                            enabled = state.items.isNotEmpty())
                    }
                }
            }
            if (subOpen) item(key = "sub") {
                Block(Modifier.padding(top = Space.x3)) {
                    FieldLabel("Subscription address")
                    Field(subUrl, { subUrl = it }, "https://…", mono = true, keyboard = KeyboardType.Uri)
                    SaidLine("Fetched only when you add it or press update, and through the connection when one is up.")
                    ButtonRow {
                        Grow()
                        Btn("Add and fetch", {
                            val url = subUrl
                            said = "Fetching…" to false
                            scope.launch {
                                val r = model.addSubscription(url)
                                said = r.text to !r.ok
                                if (r.ok) { subUrl = ""; subOpen = false }
                            }
                        }, strong = true, enabled = subUrl.isNotBlank())
                    }
                }
            }
            item(key = "said") { SaidLine(said.first, said.second) }

            if (state.items.isEmpty() && state.groups.none { it.kind == "sub" }) {
                item(key = "empty") {
                    Column(Modifier.padding(vertical = Space.x6, horizontal = Space.x1)) {
                        Text("Nothing here yet.", style = Type.subtitle)
                        Text("Copy vless://, vmess://, trojan://, ss://, hysteria2:// or tuic:// links from " +
                            "wherever you got them and press Paste links - or add the subscription address " +
                            "your seller gave you.", style = Type.caption, modifier = Modifier.padding(top = Space.x2))
                    }
                }
            }

            for (g in state.groups) {
                val members = ConfigsStore.ordered(byGroup[g.id].orEmpty())
                if (g.kind == "manual" && members.isEmpty()) continue
                item(key = "g-" + g.id) {
                    GroupHead(g, members.size, fetching = g.id in state.fetching,
                        onTest = { model.test(g.id) },
                        onUpdate = {
                            scope.launch {
                                val r = model.updateSubscription(g.id)
                                said = r.text to !r.ok
                            }
                        },
                        onRemove = { ask = CfgAsk.DropGroup(g, members.size) })
                }
                items(members, key = { it.id }) { c ->
                    Column {
                        ConfigRow(c, current = connected && state.selected == c.id,
                            open = expanded == c.id, onTap = { tap(c) },
                            onMore = { expanded = if (expanded == c.id) null else c.id })
                        if (expanded == c.id) RowActions(c,
                            onTest = { model.test(only = c.id) },
                            onCopy = {
                                val link = model.linkOf(c.id)
                                if (link.isEmpty()) said = "This one came from a sing-box list and has no link to copy." to true
                                else onCopy(link)
                            },
                            onRename = { newName = c.name; ask = CfgAsk.Rename(c) },
                            onTrust = {
                                if (c.allowInsecure) model.trust(c.id, false) else ask = CfgAsk.Trust(c)
                            },
                            onDelete = { ask = CfgAsk.Delete(c) })
                    }
                }
            }

            item(key = "options") {
                Group(Modifier.padding(top = Space.x6)) {
                    SwitchRow("Fastest, automatically",
                        "Picks the fastest of the group and moves on when one stops answering. From the next connection.",
                        state.auto, { model.setAuto(it) })
                    Sep()
                    SwitchRow("Split the TLS hello",
                        "Sends the first message in pieces, which helps where the filter reads it. Off unless needed.",
                        state.fragment, { model.setFragment(it) })
                }
            }
        }
    }

    when (val a = ask) {
        is CfgAsk.Plain -> Prompt("No encryption",
            "\"${a.c.name}\" sends your traffic to its server without any encryption. Anyone on the line in " +
                "between can read it.", "Connect anyway",
            onConfirm = { ask = null; go(a.c) }, onDismiss = { ask = null }, danger = true)
        is CfgAsk.Trust -> Prompt("Skip the certificate check?",
            "Anything between you and \"${a.c.name}\" could then pretend to be it and read what you send. " +
                "Only for a server you set up yourself, or whose owner told you to.", "Skip the check",
            onConfirm = { ask = null; model.trust(a.c.id, true) }, onDismiss = { ask = null }, danger = true)
        is CfgAsk.Delete -> Prompt("Delete this config?", "\"${a.c.name}\" goes from the list.", "Delete",
            onConfirm = { ask = null; expanded = null; model.remove(a.c.id) }, onDismiss = { ask = null },
            danger = true)
        is CfgAsk.DropGroup -> Prompt("Remove this subscription?",
            "\"${a.g.name.ifEmpty { a.g.host }}\" and its ${a.n} configs go from the list.", "Remove",
            onConfirm = { ask = null; model.removeSubscription(a.g.id) }, onDismiss = { ask = null },
            danger = true)
        is CfgAsk.Rename -> Prompt("Rename", "What to call it here. The server is not told.", "Save",
            onConfirm = { ask = null; model.rename(a.c.id, newName) }, onDismiss = { ask = null },
            confirmEnabled = newName.isNotBlank()) {
            Field(newName, { newName = it }, a.c.name)
        }
        null -> Unit
    }
}

private fun cfgBytes(n: Long): String {
    var v = n.toDouble()
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var i = 0
    while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
    return if (v >= 10 || i == 0) "${v.toLong()} ${units[i]}" else String.format(java.util.Locale.US, "%.1f %s", v, units[i])
}

private fun cfgAgo(ts: Long): String {
    if (ts <= 0) return ""
    val s = System.currentTimeMillis() / 1000 - ts
    return when {
        s < 90 -> "just now"
        s < 5400 -> "${s / 60} min ago"
        s < 129600 -> "${s / 3600} h ago"
        else -> "${s / 86400} days ago"
    }
}

@Composable
private fun GroupHead(g: CfgGroup, count: Int, fetching: Boolean,
                      onTest: () -> Unit, onUpdate: () -> Unit, onRemove: () -> Unit) {
    val title = if (g.kind == "manual") "Added by hand" else g.name.ifEmpty { g.host.ifEmpty { "Subscription" } }
    val bits = mutableListOf("$count")
    if (g.total > 0) bits += "${cfgBytes(g.upload + g.download)} of ${cfgBytes(g.total)}"
    if (g.expire > 0) {
        val days = (g.expire - System.currentTimeMillis() / 1000) / 86400
        bits += if (days >= 0) "$days days left" else "expired"
    }
    if (fetching) bits += "fetching…" else if (g.updated > 0) bits += "updated ${cfgAgo(g.updated)}"
    Row(Modifier.fillMaxWidth().padding(top = Space.x6, bottom = Space.x1),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(start = Space.x1)) {
            Text(title.uppercase(), style = Type.caption.copy(color = Tone.text2, fontSize = 11.sp,
                letterSpacing = 0.08.sp).script(title), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(bits.joinToString(" · "), style = Type.caption.copy(color = Tone.text3, fontSize = 11.5.sp))
            if (g.plainHttp) CfgChip("http: the line can read and change this list", Tone.fail)
            if (g.error.isNotEmpty()) Text(g.error, style = Type.caption.copy(color = Tone.fail, fontSize = 11.5.sp))
        }
        if (count > 0) IconButton(Glyph.gauge, "Test these", onTest, size = 36.dp, quiet = true)
        if (g.kind == "sub") {
            IconButton(Glyph.refresh, "Update now", onUpdate, size = 36.dp, quiet = true, enabled = !fetching)
            IconButton(Glyph.x, "Remove this subscription", onRemove, size = 36.dp, quiet = true)
        }
    }
}

@Composable
private fun CfgChip(text: String, color: Color) {
    Text(text, style = Type.caption.copy(color = color, fontSize = 10.5.sp),
        modifier = Modifier.padding(top = 3.dp)
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(Shape.pill))
            .padding(horizontal = 7.dp, vertical = 1.dp))
}

@Composable
private fun ConfigRow(c: CfgItem, current: Boolean, open: Boolean, onTap: () -> Unit, onMore: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .press(true, RoundedCornerShape(10.dp), onClick = onTap)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The connected one carries the accent bar the picker's chosen row does.
        Box(Modifier.width(3.dp).height(28.dp).clip(RoundedCornerShape(2.dp))
            .background(if (current) Accent.mid else Color.Transparent))
        Spacer(Modifier.width(Space.x2))
        if (c.country.length == 2) FlagDisc(c.country, 22.dp)
        else Box(Modifier.size(22.dp).clip(CircleShape).background(Tone.surface2))
        Spacer(Modifier.width(Space.x3))
        Column(Modifier.weight(1f)) {
            Text(c.name, style = Type.strong.copy(fontSize = 14.sp,
                color = if (current) Color.White else Tone.text).script(c.name),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${c.label}  ${c.host}:${c.port}", style = Type.caption.copy(color = Tone.text3, fontSize = 11.5.sp),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            // Under the name rather than beside it: a phone is narrow, and a
            // chip beside a seller's long name leaves room for neither.
            for (w in c.warnings) {
                if (w == "insecure" && c.allowInsecure) continue
                CfgChip(WARN[w] ?: w, if (w == "plain") Tone.fail else if (w == "insecure") Tone.work else Tone.text2)
            }
            if (c.allowInsecure) CfgChip("Certificate not checked", Tone.work)
        }
        val (ms, color) = when {
            c.ms > 0 -> "${c.ms} ms" to when {
                c.ms < 300 -> Tone.text
                c.ms < 900 -> Tone.text2
                else -> Tone.work
            }
            c.error.isNotEmpty() -> (FAIL[c.error] ?: "failed") to Tone.fail
            else -> "—" to Tone.text3
        }
        Text(ms, style = Type.mono.copy(color = color, textAlign = TextAlign.End),
            modifier = Modifier.width(76.dp), maxLines = 1)
        IconButton(if (open) Glyph.chevDown else Glyph.chevRight, "More for ${c.name}", onMore,
            size = 36.dp, quiet = true)
    }
}

@Composable
private fun RowActions(c: CfgItem, onTest: () -> Unit, onCopy: () -> Unit, onRename: () -> Unit,
                       onTrust: () -> Unit, onDelete: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = Space.x8, bottom = Space.x2)) {
        if (c.error.isNotEmpty() && c.said.isNotEmpty()) {
            Text(c.said, style = Type.caption.copy(color = Tone.text3, fontSize = 11.sp),
                modifier = Modifier.padding(bottom = Space.x2))
        }
        if (c.engine == "xray") {
            Text("Needs Xray (xhttp), which the phone app does not carry yet; the Windows app connects with it.",
                style = Type.caption.copy(color = Tone.work, fontSize = 11.5.sp),
                modifier = Modifier.padding(bottom = Space.x2))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Space.x2)) {
            Btn("Test", onTest, Modifier.weight(1f))
            Btn("Copy link", onCopy, Modifier.weight(1f))
        }
        Spacer(Modifier.height(Space.x2))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.x2)) {
            Btn("Rename", onRename, Modifier.weight(1f))
            Btn("Delete", onDelete, Modifier.weight(1f), danger = true)
        }
        if (c.tls) {
            Spacer(Modifier.height(Space.x2))
            Btn(if (c.allowInsecure) "Check the certificate again" else "Skip the certificate check",
                onTrust, Modifier.fillMaxWidth(), danger = !c.allowInsecure)
        }
    }
}
