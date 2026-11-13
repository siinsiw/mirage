package com.erelay.relay.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.erelay.relay.Accounts
import com.erelay.relay.data.Account
import com.erelay.relay.data.Mode
import com.erelay.relay.data.PinPlan
import com.erelay.relay.data.PinRun
import com.erelay.relay.data.ProviderCard
import com.erelay.relay.data.RelayCore
import com.erelay.relay.data.Said
import com.erelay.relay.data.TunnelPlan
import com.erelay.relay.ui.theme.Palette
import com.erelay.relay.ui.theme.Shape
import com.erelay.relay.ui.theme.Space
import com.erelay.relay.ui.theme.Tone
import com.erelay.relay.ui.theme.Data
import com.erelay.relay.ui.theme.Type
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * [head] is what the sheet's own title says once the screen is open: one line,
 * cut short when it does not fit, so a long menu name gets a short one there.
 */
private enum class Screen(val title: String, val note: String, val head: String = title) {
    MENU("Settings", ""),
    ACCOUNTS("Accounts", "Providers, sign-ins and servers"),
    CONNECTION("Connection", "Your own tunnel, and the local network"),
    SPLIT("Split tunnel", "Which apps and sites go around Relay"),
    LAN("Share this connection", "Let devices on the same Wi-Fi use it", head = "Share this connection"),
    SHARE("Configs for other devices", "Links and QR codes", head = "Other devices"),
    SERVERS("Servers", "Finding, pinning and timing them"),
    LOOK("Look and feel", "Haptics, the orb, motion"),
    ABOUT("About", "Version and where things are kept"),
}

/**
 * Settings: a menu of screens. The panes that only mean something on Windows
 * are left out - there is no system proxy to route and no port to listen on,
 * because the tun carries everything.
 */
@Composable
fun Prefs(
    open: Boolean,
    core: RelayCore,
    accounts: List<Account>,
    cards: Map<String, ProviderCard>,
    tunnel: TunnelPlan,
    allowLan: Boolean,
    serverCount: Int,
    countryCount: Int,
    pinPlan: PinPlan,
    pinRun: PinRun,
    deadCount: Int,
    asideCount: Int,
    onClose: () -> Unit,
    onAddAccount: (String) -> Unit,
    onImport: () -> Unit,
    onDrop: () -> Unit,
    /** The split tunnel's app picker, a sheet over this one. */
    onSplitApps: () -> Unit,
    /** The chooser of locations to share with other devices, a sheet over this one. */
    onSharePlaces: () -> Unit,
    notice: String,
    feel: com.erelay.relay.data.Feel,
    onFeel: (com.erelay.relay.data.Feel) -> Unit,
    /** Where to open: "menu", "accounts" or "connection". */
    start: String = "menu",
) {
    var screen by rememberSaveable { mutableStateOf(Screen.MENU) }
    LaunchedEffect(open, start) {
        screen = if (!open) Screen.MENU else when (start) {
            "accounts" -> Screen.ACCOUNTS
            "connection" -> Screen.CONNECTION
            else -> Screen.MENU
        }
    }
    LaunchedEffect(open, screen) {
        if (open) {
            core.refreshAccounts()
            if (screen == Screen.SERVERS) core.refreshPinPlan()
        }
    }

    Sheet(
        open, screen.head, onClose,
        onBack = if (screen == Screen.MENU) null else ({ screen = Screen.MENU }),
    ) {
        when (screen) {
            Screen.MENU -> Menu(accounts.count { it.signedIn }, serverCount, countryCount) { screen = it }
            Screen.ACCOUNTS -> AccountsScreen(core, accounts, cards, pinRun, onAddAccount, notice)
            Screen.CONNECTION -> Connection(core, tunnel, allowLan)
            // Its own screen rather than a third pane under Connection: the
            // list it keeps can run long, and Connection is long already.
            Screen.SPLIT -> SplitScreen(core, onSplitApps)
            Screen.LAN -> LanShare(core)
            // Beside the own server's screens, because everything on it goes
            // through that server: links for other devices, and the
            // locations the server leaves by for them.
            Screen.SHARE -> ShareScreen(core, tunnel, onSharePlaces)
            Screen.SERVERS -> Servers(core, serverCount, countryCount, pinPlan, pinRun,
                deadCount, asideCount, onImport, onDrop)
            Screen.LOOK -> Look(feel, onFeel)
            Screen.ABOUT -> About(core)
        }
    }
}

/**
 * The top of Settings: who this is, at a glance - the mark, the name, the
 * tunnel's state as a dot and a word, and what the app has to work with.
 */
@Composable
private fun Identity(signedIn: Int, servers: Int, countries: Int) {
    val shape = RoundedCornerShape(Shape.card)
    val mode = Tone.mode
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = Space.x2)
            .clip(shape)
            .background(surfaceBrush())
            .border(1.dp, Tone.stroke, shape)
            .padding(Space.x4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(52.dp).clip(CircleShape).background(Tone.surface2), contentAlignment = Alignment.Center) {
            RelayMark(Modifier.size(28.dp))
        }
        Spacer(Modifier.width(Space.x4))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Relay", style = Type.title.copy(fontSize = 20.sp, lineHeight = 26.sp))
                Spacer(Modifier.width(Space.x2))
                if (mode == Mode.ON) LiveDot(size = 7.dp) else
                    Box(Modifier.size(7.dp).clip(CircleShape).background(Tone.stateOf(mode)))
                Spacer(Modifier.width(6.dp))
                Text(when (mode) {
                    Mode.ON -> "Connected"
                    Mode.BUSY -> "Connecting"
                    Mode.FAIL -> "Not connected"
                    Mode.OFF -> "Off"
                }, style = Type.caption.copy(color = if (mode == Mode.OFF) Tone.text3 else Tone.stateOf(mode)), maxLines = 1)
            }
            val line = listOf(
                when (signedIn) { 0 -> "No account yet"; 1 -> "1 account"; else -> "$signedIn accounts" },
                if (servers > 0) "$servers servers in $countries countries" else "no servers yet",
            ).joinToString("  ·  ")
            Text(line, style = Type.caption, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun Menu(signedIn: Int, servers: Int, countries: Int, go: (Screen) -> Unit) {
    Identity(signedIn, servers, countries)
    Section("Connect") {
        Group {
            NavRow(Screen.ACCOUNTS.title, Screen.ACCOUNTS.note, { go(Screen.ACCOUNTS) }, Glyph.key)
            Sep()
            NavRow(Screen.CONNECTION.title, Screen.CONNECTION.note, { go(Screen.CONNECTION) }, Glyph.tower)
            Sep()
            NavRow(Screen.SPLIT.title, Screen.SPLIT.note, { go(Screen.SPLIT) }, Glyph.route)
        }
    }
    Section("Share") {
        Group {
            NavRow(Screen.LAN.title, Screen.LAN.note, { go(Screen.LAN) }, Glyph.share)
            Sep()
            NavRow(Screen.SHARE.title, Screen.SHARE.note, { go(Screen.SHARE) }, Glyph.qr)
        }
    }
    Section("App") {
        Group {
            NavRow(Screen.SERVERS.title, Screen.SERVERS.note, { go(Screen.SERVERS) }, Glyph.folder)
            Sep()
            NavRow(Screen.LOOK.title, Screen.LOOK.note, { go(Screen.LOOK) }, Glyph.palette)
            Sep()
            NavRow(Screen.ABOUT.title, Screen.ABOUT.note, { go(Screen.ABOUT) }, Glyph.info)
        }
    }
}

// -- accounts -----------------------------------------------------------------

/**
 * Accounts: one section per provider rather than a list of accounts in one
 * place and the providers' steps in another. Everything about Surfshark - the
 * accounts it has, how far along it is, and the one thing to do next - is
 * under Surfshark's heading, so there is nothing to match up between two lists.
 */
@Composable
private fun AccountsScreen(
    core: RelayCore, accounts: List<Account>, cards: Map<String, ProviderCard>, run: PinRun,
    onAddAccount: (String) -> Unit, notice: String,
) {
    val scope = rememberCoroutineScope()
    var said by remember { mutableStateOf<Said?>(null) }
    var busy by remember { mutableStateOf("") }
    val providers = listOf(Accounts.SURFSHARK, Accounts.WINDSCRIBE)
    val ready = providers.count { cards[it]?.usable == true }

    // The summary is a sentence, not a card: the count is the one big thing.
    Column(Modifier.padding(top = Space.x4, start = Space.x1, end = Space.x1)) {
        Text("$ready of ${providers.size} ready", style = Type.display)
        Text(when {
            ready == providers.size -> "Every provider can connect."
            accounts.isEmpty() -> "Add an account to connect."
            ready > 0 -> "Enough to connect. The other provider is optional."
            else -> "Almost there: get the servers and pin them."
        }, style = Type.body, modifier = Modifier.padding(top = Space.x1))
    }
    if (notice.isNotEmpty()) SaidLine(notice)

    for (p in providers) {
        ProviderAccounts(p, cards[p] ?: ProviderCard(), accounts.filter { it.provider == p },
            busy = busy == p,
            onAdd = { onAddAccount(p) },
            onUse = { a -> scope.launch { said = core.useAccount(a.id) } },
            onRemove = { a -> scope.launch { said = core.removeAccount(a.id) } },
        ) { action ->
            busy = p
            said = null
            scope.launch {
                said = when (action) {
                    "servers" -> if (p == Accounts.SURFSHARK) core.surfsharkServers()
                                 else core.windscribeServers(freeOnly = false)
                    // Pinned right here: point the run at this provider's pile and
                    // start it, and the progress shows under the lists.
                    "pin" -> core.pinFor(p).let { if (it.ok) core.startPin().takeIf { s -> !s.ok } else it }
                    "refresh" -> core.windscribeRefresh()
                    else -> null
                }
                busy = ""
            }
        }
    }

    if (run.running || run.finished.isNotEmpty()) {
        Section("Pinning") { Block { PinRunView(run) { core.cancelPin() } } }
    }
    said?.let { SaidLine(it.text, bad = !it.ok) }
}

/**
 * One provider: how it stands, its accounts, and the one button that is next.
 * A group of rows, and under it the action.
 */
@Composable
private fun ProviderAccounts(
    provider: String, card: ProviderCard, accounts: List<Account>, busy: Boolean,
    onAdd: () -> Unit, onUse: (Account) -> Unit, onRemove: (Account) -> Unit, act: (String) -> Unit,
) {
    val name = providerName(provider)
    Section(name, note = when {
        card.usable && card.waiting > 0 -> "${card.servers} ready · ${card.waiting} to pin"
        card.usable -> "${card.servers} servers ready to connect"
        card.servers > 0 -> "${card.servers} servers · add an account to use them"
        card.account && card.waiting > 0 -> "${card.waiting} servers waiting to be pinned"
        card.account -> "Signed in · no servers yet"
        else -> "Not set up"
    }) {
        Group {
            // Windscribe only: its proxy credential goes stale now and then, and
            // this fetches another with the session already held - no puzzle.
            SettingRow("Account", desc = if (card.account) "Signed in" else "None yet",
                leading = {
                    IconWell(if (provider == Accounts.WINDSCRIBE) Glyph.windscribe else Glyph.surfshark,
                        size = 36.dp, tint = Data.provider(provider))
                }) {
                if (provider == Accounts.WINDSCRIBE && card.account) {
                    IconButton(Glyph.refresh, "Refresh the Windscribe credential", { act("refresh") },
                        size = 40.dp, enabled = !busy)
                } else if (card.account) {
                    Icon(Glyph.check, null, Modifier.size(18.dp), tint = Tone.good)
                }
            }
            Sep()
            SettingRow("Servers", desc = if (card.servers > 0 || card.waiting > 0) "Fetched" else "Not fetched yet") {
                if (card.servers > 0 || card.waiting > 0) Icon(Glyph.check, null, Modifier.size(18.dp), tint = Tone.good)
            }
            Sep()
            SettingRow("Pinned", desc = if (card.servers > 0) "${card.servers} to real addresses" else "Nothing pinned yet") {
                if (card.servers > 0) Icon(Glyph.check, null, Modifier.size(18.dp), tint = Tone.good)
            }
            for (a in accounts) {
                Sep()
                AccountLine(a, onUse = { onUse(a) }, onRemove = { onRemove(a) })
            }
        }
        val (step, label) = when {
            !card.account -> "add" to "Add a $name account"
            card.waiting > 0 -> "pin" to "Pin ${card.waiting} waiting"
            card.servers == 0 -> "servers" to "Get servers"
            else -> "servers" to "Update servers"
        }
        ButtonRow {
            Btn(if (busy) "Working…" else label, { if (step == "add") onAdd() else act(step) },
                modifier = Modifier.weight(1f), enabled = !busy, strong = step != "servers" || card.servers == 0,
                icon = when (step) {
                    "add" -> Glyph.plus
                    "pin" -> Glyph.pin
                    else -> Glyph.refresh
                })
        }
    }
}

/**
 * One account: its name and state, and Use. Remove asks once more in place,
 * because it cannot be undone and the button is a thumb's width from Use.
 */
@Composable
private fun AccountLine(a: Account, onUse: () -> Unit, onRemove: () -> Unit) {
    var sure by remember(a.id) { mutableStateOf(false) }
    val title = a.label.ifEmpty { a.username }
    SettingRow(title, leading = if (a.active) ({ LiveDot(ring = false, color = Tone.good) }) else null,
        desc = listOfNotNull(if (a.active) "In use" else null,
        if (a.signedIn) "signed in" else "not signed in").joinToString(" · ")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            when {
                sure -> {
                    Btn("Remove", onRemove, danger = true)
                    IconButton(Glyph.x, "Keep it", { sure = false }, size = 40.dp)
                }
                else -> {
                    if (!a.active) Btn("Use", onUse)
                    IconButton(Glyph.x, "Remove ${title}", { sure = true }, size = 40.dp, tint = Tone.text3)
                }
            }
        }
    }
}

/** A pin run as it goes: a bar, the counts, the last address, and Stop. */
@Composable
private fun PinRunView(run: PinRun, onStop: () -> Unit) {
    if (run.running) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Pinning", style = Type.strong, modifier = Modifier.weight(1f))
            Btn("Stop", onStop, danger = true)
        }
        Spacer(Modifier.height(Space.x2))
        Progress(run.done, run.total)
        Text("${run.done} of ${run.total} · ${run.reachable} answering · ${run.unreachable} not" +
            if (run.forged > 0) " · ${run.forged} forged answers seen" else "",
            style = Type.mono, modifier = Modifier.padding(top = Space.x2))
        if (run.last.isNotEmpty()) Text(run.last, style = Type.mono, maxLines = 1, overflow = TextOverflow.Ellipsis)
    } else {
        Text(run.finished, style = Type.body)
    }
}

// -- connection -----------------------------------------------------------------

@Composable
private fun Connection(core: RelayCore, tunnel: TunnelPlan, allowLan: Boolean) {
    val scope = rememberCoroutineScope()
    var lanSaid by remember { mutableStateOf("") }

    Section("Local network") {
        Group {
            SwitchRow("Reach the local network",
                "The printer, the router's page, a NAS and adb over Wi-Fi stay reachable while the " +
                    "tunnel is up. A local address goes nowhere beyond the house.",
                allowLan, { on ->
                    val up = core.setAllowLan(on)
                    lanSaid = if (up) "Takes effect on the next connect." else ""
                })
        }
        SaidLine(lanSaid)
    }

    var domain by remember(tunnel.domain) { mutableStateOf(tunnel.domain) }
    var password by remember { mutableStateOf("") }
    var api by remember { mutableStateOf("") }
    var said by remember { mutableStateOf<Said?>(null) }
    var modeSaid by remember { mutableStateOf<Said?>(null) }
    var busy by remember { mutableStateOf(false) }
    var key by remember { mutableStateOf("") }

    // The short way first: a server that is already set up printed a
    // relay:// key, and the key is the whole of what this asks for.
    Section("Your own tunnel",
        note = when {
            busy -> "Asking…"
            tunnel.ready -> "Ready."
            else -> "Not set up. Paste a key, or fill in the server below."
        },
        help = "A server of your own, by its IP address - or a domain, behind Cloudflare or " +
            "not. Type the address and copy the install command: it carries passwords this " +
            "app made, so once it has run on the server there is only Test left to press. " +
            "The tunnel password opens the tunnel; the API password is how server + exit " +
            "tells the server which location to leave by.") {
        Block {
            FieldLabel("Key")
            Field(key, { key = it }, "relay://…", mono = true, secret = true)
            ButtonRow {
                Btn("Use key", {
                    busy = true
                    said = null
                    scope.launch {
                        val r = core.importKey(key)
                        // Out of memory the moment it is on disk: it holds three passwords.
                        if (r.ok) key = ""
                        said = r
                        busy = false
                    }
                }, strong = !tunnel.ready, enabled = !busy && key.isNotBlank(), icon = Glyph.key)
            }
            if (tunnel.hasSs) {
                Spacer(Modifier.height(Space.x3))
                Segment(listOf("ss", "ws"), tunnel.transport,
                    { if (it == "ss") "Shadowsocks" else "WebSocket" },
                    { t -> scope.launch { modeSaid = core.setTunnelTransport(t) } }, enabled = !busy)
                if (tunnel.transport == "ss") {
                    Text("Straight to ${tunnel.domain}:${tunnel.ssPort}, and :${tunnel.ssPort + 1} " +
                        "for server + exit. Best for a server in Iran; from Iran to a server " +
                        "abroad it connects and then carries nothing - use the WebSocket there.",
                        style = Type.mono, modifier = Modifier.padding(top = Space.x2))
                }
                // Right under the switch, not at the foot of the next section:
                // a refusal said out of sight looked like a switch that did
                // nothing.
                modeSaid?.let { SaidLine(it.text, bad = !it.ok) }
            }
        }
    }

    Section("Server") {
        Block {
            FieldLabel("Address")
            Field(domain, { domain = it }, "Server IP or domain", mono = true)
            Spacer(Modifier.height(Space.x3))
            FieldLabel("Tunnel password")
            Field(password, { password = it },
                if (tunnel.hasPassword) "Saved" else "Made for you", secret = true)
            Spacer(Modifier.height(Space.x3))
            FieldLabel("API password")
            Field(api, { api = it },
                if (tunnel.hasApiPassword) "Saved" else "Made for you", secret = true)
            ButtonRow {
                Btn("Save", {
                    val typed = Triple(domain, password, api)
                    // Checked and stored on the core's thread. The fields
                    // are emptied only once it is saved: a refusal leaves
                    // what was typed there to be corrected, with the reason
                    // under it.
                    scope.launch {
                        val r = core.saveTunnel(typed.first, typed.second, typed.third)
                        if (r.ok) {
                            password = ""
                            api = ""
                        }
                        said = r
                    }
                }, strong = true)
                Btn(if (busy) "Testing…" else "Test", {
                    busy = true
                    said = null
                    scope.launch { said = core.testTunnel(); busy = false }
                }, enabled = !busy && tunnel.ready)
            }
            // An address has no other way in: it is not behind Cloudflare.
            if (!tunnel.isAddress) ButtonRow {
                Btn("Find another way in", {
                    busy = true
                    said = null
                    scope.launch { said = core.rescanEdges(); busy = false }
                }, enabled = !busy && tunnel.domain.isNotEmpty(), icon = Glyph.refresh)
            }
            if (tunnel.pin.isNotEmpty()) {
                Text("Certificate: sha256 ${tunnel.pin.take(16)}…", style = Type.mono,
                    modifier = Modifier.padding(top = Space.x3))
            }
            if (tunnel.edges.isNotEmpty() && !tunnel.isAddress) {
                Text("Way in: ${tunnel.edges[0]}" + if (tunnel.edges.size > 1) "  (+${tunnel.edges.size - 1})" else "",
                    style = Type.mono, modifier = Modifier.padding(top = Space.x3))
            }
            said?.let { SaidLine(it.text, bad = !it.ok) }
        }
    }

    ExitCheck(core, tunnel)

    Section("Setting up the server",
        help = "Paste these into an SSH session on the server, as root. Install sets up " +
            "everything in one go, with this app's passwords - run it again any time to " +
            "repair or update. Status shows what is running; Uninstall takes the tunnel " +
            "off and leaves everything else on the server alone.") {
        Group {
            SettingRow("Copy the install command", desc = "Run on the server as root",
                icon = Glyph.copy, onClick = {
                    val host = domain
                    scope.launch {
                        // The command, or why there is none (a host or a
                        // saved password the server-settings rule refuses).
                        val cmd = core.installCommand(host)
                        said = if (!cmd.ok) cmd else {
                            // It carries both passwords: no clipboard preview.
                            core.copy(cmd.text, sensitive = true)
                            Said(true, "Copied. Paste it on the server, then press Test.")
                        }
                    }
                })
            Sep()
            SettingRow("Copy status", desc = "Shows what is running", icon = Glyph.copy, onClick = {
                core.copy(core.statusCommand()); said = Said(true, "Copied the status command.")
            })
            Sep()
            SettingRow("Copy uninstall", desc = "Leaves the rest of the server alone", icon = Glyph.copy,
                onClick = { core.copy(core.uninstallCommand()); said = Said(true, "Copied the uninstall command.") })
        }
    }
}

/**
 * Server + exit's check of the exit it is pointed at: on, and kept on unless
 * somebody decides otherwise with the reason in front of them.
 *
 * The check makes sure the provider exit the server leaves by is the real
 * one before traffic goes through it, and the server names the exit when it
 * connects. From a server inside Iran that name is what keeps upload fast:
 * measured 2026-11-12, an exit reached without it (the check off) was held
 * to about 10 KB/s of upload, and with it six of seven Windscribe exits ran
 * at 1.2-1.9 MB/s. So the Iran notice says to keep it on; turning it off is
 * still the switch, behind a confirmation that says what it costs - the one
 * thing standing between the traffic and an exit that is not who it says it
 * is, and from Iran, the upload speed. A second notice says that Surfshark's
 * exits cannot be reached from a server in Iran at all (every cluster
 * failed TLS there the same day) when a Surfshark account is active.
 *
 * Per server: the switch is this server's, and a server pasted in next starts
 * with the check on again. Whether the server is in Iran is the core's answer
 * (ServerInIran, by its addresses), asked once per server; when it cannot
 * tell, there is no notice, and the switch is still here.
 */
@Composable
private fun ExitCheck(core: RelayCore, tunnel: TunnelPlan) {
    if (tunnel.domain.isEmpty()) return
    val verify by core.tunnelVerify.collectAsStateWithLifecycle()
    val inIranAnswer by core.serverInIran.collectAsStateWithLifecycle()
    val accounts by core.accounts.collectAsStateWithLifecycle()
    var askOff by remember { mutableStateOf(false) }
    val inIran = inIranAnswer == true
    // Which providers server + exit can pick an exit from: the active
    // account of each (Accounts.credentials is what the service reads).
    val surfshark = accounts.any { it.provider == Accounts.SURFSHARK && it.active }
    val windscribe = accounts.any { it.provider == Accounts.WINDSCRIBE && it.active }
    LaunchedEffect(tunnel.domain) { core.checkServerInIran() }

    Section("Server + exit",
        help = "Before server + exit sends anything through a provider exit, the server checks " +
            "that the exit is the provider's real server, and names it when it connects. Leave " +
            "it on - it is what stops a forged exit from seeing your traffic, and from a server " +
            "in Iran it is also what keeps upload fast. Turn it off only if connecting through " +
            "the exit fails entirely. The setting is kept for each server on its own.") {
        Group {
            SwitchRow("Check the exit first",
                if (verify) "On for ${tunnel.domain}. Every exit is checked before it is used."
                else "Off for ${tunnel.domain}. Exits are used without being checked.",
                verify, { on ->
                    // Off only after the question below has been answered.
                    if (on) {
                        core.setTunnelVerify(true)
                    } else {
                        askOff = true
                    }
                })
        }
        // From Iran the advice is to keep the check on. Measured 2026-11-12
        // from a server in Iran: an exit reached without its real name is
        // held to about 10 KB/s of upload, and checked - the name sent - it
        // runs at full speed. Shown whichever way the switch is: it is the
        // reason to turn it back on as much as to leave it. The switch above
        // still turns it off, after asking.
        if (inIran) {
            Block(Modifier.padding(top = Space.x3)) {
                Text("Your server is in Iran", style = Type.strong)
                Text("Keep the check on. From Iran, an exit reached without its real name is " +
                    "held to about 10 KB/s of upload; with the check on, uploads run at full " +
                    "speed. Turn it off only if connecting through the exit fails entirely.",
                    style = Type.body, modifier = Modifier.padding(top = Space.x1))
            }
        }
        // Surfshark as the exit of a server in Iran: every cluster failed TLS
        // from there (Accounts.SURFSHARK_FROM_IRAN). Said, not enforced.
        if (inIran && surfshark) {
            Block(Modifier.padding(top = Space.x3)) {
                Text("Surfshark is blocked from Iran", style = Type.strong)
                Text(Accounts.SURFSHARK_FROM_IRAN + if (windscribe) {
                    " For server + exit, pick a Windscribe location."
                } else {
                    " Add a Windscribe account and use it for server + exit."
                }, style = Type.body, modifier = Modifier.padding(top = Space.x1))
            }
        }
    }

    if (askOff) {
        Prompt(
            title = "Turn off the exit check for ${tunnel.domain}?",
            text = "Server + exit will send your traffic through whichever exit it is pointed at, " +
                "without first making sure it is the provider's real server. On a filtered line " +
                "that exit could be a forgery that sees everything you send. " +
                (if (inIran) {
                    "Your server is in Iran, and from there an exit reached without its real " +
                        "name is held to about 10 KB/s of upload - so uploads will likely drop " +
                        "to that. "
                } else {
                    ""
                }) +
                "Turn it off only if connecting through the exit fails entirely, and turn it " +
                "back on when you can.",
            confirm = "Turn off",
            danger = true,
            onConfirm = {
                core.setTunnelVerify(false)
                askOff = false
            },
            onDismiss = { askOff = false },
        )
    }
}

// -- share this connection ---------------------------------------------------------

/**
 * Settings → Share this connection: the tunnel offered, as a proxy, to the
 * devices on the Wi-Fi (or the hotspot) this phone is on. The service starts
 * it once the tunnel is up; the switch here starts or stops it at once.
 */
@Composable
private fun LanShare(core: RelayCore) {
    val scope = rememberCoroutineScope()
    val conn by core.conn.collectAsStateWithLifecycle()
    val on by core.lanShare.collectAsStateWithLifecycle()
    val port by core.lanSharePort.collectAsStateWithLifecycle()
    val saved by core.lanSharePassword.collectAsStateWithLifecycle()
    val lan by core.lanState.collectAsStateWithLifecycle()
    val up = conn.mode == Mode.ON
    var said by remember { mutableStateOf<Said?>(null) }
    // The password prompt the switch opens when there is no password yet.
    var asking by remember { mutableStateOf(false) }
    val minimum = com.erelay.relay.Keys.LAN_SHARE_MIN_PASSWORD
    val maximum = com.erelay.relay.Keys.LAN_SHARE_MAX_PASSWORD
    // The core's rule, said before it is asked: null when the password will do.
    fun problem(p: String): String? = com.erelay.relay.Keys.lanPasswordProblem(p.trim())

    // Asked every couple of seconds while this screen is the one showing.
    LaunchedEffect(Unit) {
        while (isActive) {
            core.refreshLanShare()
            delay(2500)
        }
    }

    Section("Share this connection",
        note = "Phones and laptops on the same Wi-Fi or hotspot can use Relay's connection through " +
            "this phone. Only devices on the same network can reach it, only while Relay is connected, " +
            "and only with the password.") {
        Group {
            SwitchRow("Share on Wi-Fi",
                when {
                    !on -> "Off"
                    !up -> "Starts when Relay connects."
                    lan.on -> "Sharing now"
                    // The core's own reason, e.g. no Wi-Fi or hotspot address.
                    lan.error.isNotEmpty() -> lan.error.replaceFirstChar { it.uppercase() }
                    else -> "Starting…"
                },
                on, { v ->
                    said = null
                    // Never on without a password: anything on a café's Wi-Fi
                    // could otherwise borrow the tunnel. Asked for here, the
                    // first time, rather than refused with a sentence.
                    if (v && problem(saved) != null) {
                        asking = true
                    } else {
                        scope.launch { said = core.setLanShare(v).takeIf { !it.ok } }
                    }
                })
        }
        said?.let { SaidLine(it.text, bad = !it.ok) }
    }

    if (asking) {
        var typed by remember { mutableStateOf("") }
        var wrong by remember { mutableStateOf("") }
        var saving by remember { mutableStateOf(false) }
        Prompt(
            title = "A password for the share",
            text = "Other devices sign in to the proxy with the user name relay and this password. " +
                "Without one, anything on the same Wi-Fi could use your connection.",
            confirm = if (saving) "Turning on…" else "Turn on",
            confirmEnabled = !saving && problem(typed) == null,
            onConfirm = {
                saving = true
                scope.launch {
                    // The password first: one the core would refuse keeps
                    // the prompt open with the reason under the field.
                    val set = core.setLanSharePassword(typed)
                    if (!set.ok) {
                        saving = false
                        wrong = set.text
                        return@launch
                    }
                    typed = ""
                    saving = false
                    asking = false
                    // Then the share. A start that fails - no Wi-Fi or
                    // hotspot address, a port in use - is said in the core's
                    // words under the switch; the setting stays on.
                    said = core.setLanShare(true).takeIf { !it.ok }
                }
            },
            onDismiss = { asking = false },
        ) {
            FieldLabel("Password", hint = "$minimum to $maximum characters")
            Field(typed, { typed = it; wrong = "" }, "Password", secret = true)
            // Said as it is typed, once there is something to say it about.
            SaidLine(wrong.ifEmpty { if (typed.isEmpty()) "" else problem(typed).orEmpty() }, bad = true)
        }
    }

    if (on) {
        Section("Type this on the other device") {
            val shownPort = if (lan.on && lan.port > 0) lan.port else port
            Group {
                if (lan.on && lan.addrs.isNotEmpty()) {
                    lan.addrs.forEachIndexed { i, a ->
                        if (i > 0) Sep()
                        AddressRow(a, shownPort) { core.copy("$a:$shownPort") }
                    }
                } else {
                    Column(Modifier.fillMaxWidth().padding(Space.x4)) {
                        Text(if (!up) "Connect first. The address shows here once Relay is up."
                             else "No Wi-Fi or hotspot address found. Join a Wi-Fi network or turn on the hotspot.",
                            style = Type.body)
                        Text("Port $shownPort", style = Type.mono, modifier = Modifier.padding(top = Space.x2))
                    }
                }
            }
            Text("Tap to copy.", style = Type.caption, modifier = Modifier.padding(start = Space.x1, top = Space.x1))
            Group(Modifier.padding(top = Space.x3)) {
                SettingRow("Devices using it", desc = "Connected right now") {
                    Text("${if (lan.on) lan.clients else 0}", style = Type.monoBody)
                }
            }
        }

        Section("Port and password") {
            var portText by remember(port) { mutableStateOf(port.toString()) }
            var pass by remember(saved) { mutableStateOf(saved) }
            Block {
                FieldLabel("Port")
                Field(portText, { portText = it.filter(Char::isDigit).take(5) }, "8877",
                    mono = true, keyboard = KeyboardType.Number)
                Spacer(Modifier.height(Space.x3))
                FieldLabel("Password", hint = "$minimum to $maximum characters")
                Field(pass, { pass = it }, "Password", secret = true)
                if (pass.isNotEmpty()) SaidLine(problem(pass).orEmpty(), bad = true)
                Text("The other device has to sign in to the proxy: user name relay, and this password. On an iPhone, turn Authentication on under Configure Proxy.",
                    style = Type.caption, modifier = Modifier.padding(top = Space.x2))
                ButtonRow {
                    Btn("Save", {
                        scope.launch {
                            val p = portText.toIntOrNull() ?: 0
                            val a = core.setLanSharePort(p)
                            said = if (!a.ok) a else core.setLanSharePassword(pass).takeIf { !it.ok } ?: Said(true, "Saved.")
                        }
                    }, strong = true, enabled = (portText != port.toString() || pass.trim() != saved) &&
                        problem(pass) == null)
                }
            }
        }
    }

    Section("How to use it") {
        Group {
            Column(Modifier.fillMaxWidth().padding(Space.x4)) {
                Text("iPhone or iPad", style = Type.strong)
                Text("Settings → Wi-Fi → the (i) next to this network → Configure Proxy → Manual. " +
                    "Enter the server and port above.", style = Type.body,
                    modifier = Modifier.padding(top = Space.x1))
            }
            Sep()
            Column(Modifier.fillMaxWidth().padding(Space.x4)) {
                Text("Another phone or a laptop", style = Type.strong)
                Text("Set the Wi-Fi network's proxy to manual HTTP, with the same server and port.",
                    style = Type.body, modifier = Modifier.padding(top = Space.x1))
            }
            Sep()
            Column(Modifier.fillMaxWidth().padding(Space.x4)) {
                Text("With a hotspot", style = Type.strong)
                Text("Turn on this phone's hotspot, join it from the other device, and use the address " +
                    "shown above. It changes if the hotspot is turned off and on.", style = Type.body,
                    modifier = Modifier.padding(top = Space.x1))
            }
        }
    }
}

/** The three things a proxy form asks for, in the order it asks; the whole block copies. */
@Composable
private fun AddressRow(addr: String, port: Int, onCopy: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onCopy).padding(Space.x4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            ProxyLine("Proxy", "Manual")
            ProxyLine("Server", addr)
            ProxyLine("Port", port.toString())
        }
        Icon(Glyph.copy, "Copy", Modifier.size(20.dp), tint = Tone.text3)
    }
}

@Composable
private fun ProxyLine(label: String, value: String) {
    Row(Modifier.padding(vertical = Space.x1)) {
        Text(label, style = Type.caption, modifier = Modifier.width(56.dp))
        Text(value, style = Type.monoBody, modifier = Modifier.weight(1f))
    }
}

// -- servers ---------------------------------------------------------------------

@Composable
private fun Servers(
    core: RelayCore, serverCount: Int, countryCount: Int, plan: PinPlan, run: PinRun,
    deadCount: Int, asideCount: Int, onImport: () -> Unit, onDrop: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var said by remember { mutableStateOf<Said?>(null) }

    Section("Server folder", note = "$serverCount servers · $countryCount countries",
        help = "Configs pinned to real addresses live here, in the app's own folder. Import a " +
            "folder of .ovpn files from the phone - they are copied in - or get them from a " +
            "provider under Accounts.") {
        Block {
            Text(core.dataFolder, style = Type.mono, maxLines = 3, overflow = TextOverflow.Ellipsis)
            ButtonRow { Btn("Import a folder…", onImport, icon = Glyph.folder) }
        }
    }

    Section("Pin to real addresses",
        note = when {
            run.running -> "Pinning…"
            plan.total > 0 -> "${plan.total} waiting"
            else -> null
        },
        help = "A downloaded config names a host, and on this line the resolver answers for " +
            "that host with a forged address. Pinning resolves each one over DNS-over-HTTPS " +
            "and writes the real address in. Direct asks from here; Through the tunnel asks " +
            "from wherever the tunnel comes out.") {
        Group {
            Column(Modifier.fillMaxWidth().padding(Space.x4)) {
                Segment(listOf("direct", "proxy"), plan.route.ifEmpty { "direct" },
                    { if (it == "direct") "Direct" else "Through the tunnel" },
                    { r -> scope.launch { core.setPinRoute(route = r) } }, enabled = !run.running)
            }
            Sep()
            SettingRow("Addresses per host",
                desc = "More means more exits to race and a longer pin.") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(Glyph.minus, "Fewer", { scope.launch { core.setPinRoute(maxIps = plan.maxIps - 1) } },
                        enabled = !run.running && plan.maxIps > 1)
                    Text(plan.maxIps.toString(), style = Type.monoBody)
                    IconButton(Glyph.plus, "More", { scope.launch { core.setPinRoute(maxIps = plan.maxIps + 1) } },
                        enabled = !run.running && plan.maxIps < 32)
                }
            }
            Sep()
            SwitchRow("Check each address answers",
                "Slower, and the folder only holds exits that were alive when pinned.",
                plan.test, { on -> scope.launch { core.setPinRoute(test = on) } })
        }
        for (b in plan.blockers) SaidLine(b.say, bad = true)
        if (plan.total > 0 && !run.running) {
            Text("${plan.total} to pin from ${plan.folder.substringAfterLast('/')}" +
                if (plan.minutes > 0) ", about ${plan.minutes} min" else "", style = Type.caption,
                modifier = Modifier.padding(start = Space.x1, top = Space.x2))
        }
        if (run.running) {
            Spacer(Modifier.height(Space.x3))
            Progress(run.done, run.total)
            Text("${run.done} of ${run.total} · ${run.reachable} answering · ${run.unreachable} not" +
                if (run.forged > 0) " · ${run.forged} forged answers seen" else "",
                style = Type.mono, modifier = Modifier.padding(top = Space.x2))
            if (run.last.isNotEmpty()) Text(run.last, style = Type.mono, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (!run.running && run.finished.isNotEmpty()) SaidLine(run.finished)
        ButtonRow {
            if (run.running) Btn("Stop", { core.cancelPin() }, danger = true)
            else Btn("Pin them", {
                said = core.startPin().takeIf { !it.ok }
            }, strong = true, enabled = plan.total > 0 && plan.blockers.isEmpty())
        }
        said?.let { SaidLine(it.text, bad = !it.ok) }
    }

    Section("Exits that stopped answering",
        help = "Exits that refused or timed out the last time the bolt on a country tested " +
            "them. Setting them aside keeps the race off them; they can be put back.") {
        Group {
            SettingRow(when {
                deadCount > 0 -> "$deadCount to set aside"
                asideCount > 0 -> "$asideCount set aside earlier"
                else -> "None"
            }, onClick = if (deadCount > 0 || asideCount > 0) onDrop else null, icon = Glyph.archive) {
                if (deadCount > 0 || asideCount > 0) Chevron()
            }
        }
    }

    Timing(core)
}

/**
 * Timing servers: one exit per country, so the Ping order in the exit list
 * means something. When it last ran, whether it runs on its own, and the
 * button that runs it now.
 */
@Composable
private fun Timing(core: RelayCore) {
    val quickAt by core.quickAt.collectAsStateWithLifecycle()
    val auto by core.autoQuick.collectAsStateWithLifecycle()
    val reach by core.reach.collectAsStateWithLifecycle()
    var said by remember { mutableStateOf<Said?>(null) }
    val running = reach.running && reach.code == com.erelay.relay.data.QUICK
    val ago = if (quickAt <= 0L) null else minutes(quickAt / 1000)

    Section("Timing servers",
        note = when {
            running -> "Timing…"
            ago == null -> "Never timed"
            ago < 60 -> "Timed ${ago}m ago"
            ago < 60 * 24 -> "Timed ${ago / 60}h ago"
            else -> "Timed ${ago / (60 * 24)}d ago"
        },
        help = "One exit from each country is asked how quickly its proxy answers - six at " +
            "a time, a few seconds each, so a minute or so for the whole list and gentle on " +
            "the line. Only with the tunnel down: through it, every time would be the " +
            "tunnel's. The bolt on a country in the exit list times all of its exits.") {
        Group {
            SwitchRow("Time the list on its own",
                "When the exit list opens and the last timing is over six hours old. Never while the tunnel is up.",
                auto, { core.setAutoQuick(it) })
            Sep()
            Column(Modifier.fillMaxWidth().padding(Space.x4)) {
                Text(when {
                    running -> "Timing ${reach.done} of ${reach.total} countries…"
                    reach.code == com.erelay.relay.data.QUICK && reach.said.isNotEmpty() -> reach.said
                    ago == null -> "No country has been timed yet, so the Ping order is a guess."
                    else -> "The exit list is ordered by these times."
                }, style = Type.body)
                if (running) {
                    Spacer(Modifier.height(Space.x2))
                    Progress(reach.done, reach.total)
                }
                ButtonRow {
                    if (running) Btn("Stop", { core.cancelReach() }, danger = true, modifier = Modifier.weight(1f))
                    else Btn("Time every country", { said = core.quickTest().takeIf { !it.ok } },
                        strong = true, enabled = !reach.running, icon = Glyph.bolt, modifier = Modifier.weight(1f))
                }
            }
        }
        said?.let { SaidLine(it.text, bad = !it.ok) }
    }
}

@Composable
private fun Progress(done: Int, total: Int) {
    Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(Tone.surface2)) {
        Box(Modifier.fillMaxWidth(if (total > 0) (done.toFloat() / total).coerceIn(0.02f, 1f) else 0.02f)
            .height(4.dp).clip(CircleShape).background(Tone.text2))
    }
}

// -- look and feel ----------------------------------------------------------------

@Composable
private fun Look(feel: com.erelay.relay.data.Feel, onFeel: (com.erelay.relay.data.Feel) -> Unit) {
    // No theme to pick any more: Mirage has the one, black (2026-11-13).
    Section("Touch") {
        Group {
            SwitchRow("Haptic feedback",
                "A light tick under each press, and a buzz when the tunnel comes up or fails.",
                feel.haptics, { onFeel(feel.copy(haptics = it)) })
            Sep()
            SwitchRow("Tap the orb",
                "Tap to connect, tap again to disconnect. Hold while connected to reconnect " +
                    "through a fresh exit. Only the sphere itself.",
                feel.orbTap, { onFeel(feel.copy(orbTap = it)) })
        }
    }

    Section("Motion") {
        Group {
            SwitchRow("Animate the map",
                "Off draws it once and leaves it still: no journey between countries, no orbiting rings, less for the GPU to do.",
                feel.orbMotion, { onFeel(feel.copy(orbMotion = it)) })
            Sep()
            SwitchRow("Show the traffic meter",
                "The live wave of what is going up and down while connected.",
                feel.meter, { onFeel(feel.copy(meter = it)) })
            Sep()
            IranFlagRow()
        }
    }

    if (android.os.Build.VERSION.SDK_INT >= 33) {
        val context = androidx.compose.ui.platform.LocalContext.current
        var tileSaid by remember { mutableStateOf("") }
        Section("Quick settings") {
            Group {
                SettingRow("Add the Relay tile",
                    desc = "In the panel you pull down from the top: one press connects, another disconnects.",
                    icon = Glyph.bolt, onClick = {
                        val bar = context.getSystemService(android.app.StatusBarManager::class.java)
                        bar?.requestAddTileService(
                            android.content.ComponentName(context, com.erelay.relay.RelayTile::class.java),
                            "Relay",
                            android.graphics.drawable.Icon.createWithResource(context, com.erelay.relay.R.drawable.ic_relay),
                            context.mainExecutor,
                        ) { result ->
                            tileSaid = when (result) {
                                android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> "Added."
                                android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> "It is already there."
                                android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> ""
                                else -> "This phone did not offer to add it. Edit the quick-settings panel and drag Relay in."
                            }
                        }
                    })
            }
            SaidLine(tileSaid)
        }
    }
}

/**
 * One theme as a little picture of itself: its page, a card on it. The chosen
 * one is selected: a Pulse edge fading across it and the edge bar, no glow.
 */
@Composable
private fun Swatch(p: Palette, on: Boolean, modifier: Modifier, onPick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    val reduced = rememberReducedMotion()
    // The check lands on a spring, a hair past full size and back.
    val mark by androidx.compose.animation.core.animateFloatAsState(if (on) 1f else 0f,
        if (reduced) androidx.compose.animation.core.tween<Float>(0)
        else androidx.compose.animation.core.spring<Float>(dampingRatio = 0.5f, stiffness = 500f), label = "swatch")
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(80.dp)
                .press(shape = shape, sink = true, feel = HapticFeedbackType.SegmentTick, onClick = onPick)
                .clip(shape)
                .background(p.bg)
                .border(if (on) 2.dp else 1.dp, if (on) com.erelay.relay.ui.theme.Accent.brush()
                    else androidx.compose.ui.graphics.SolidColor(Tone.strokeStrong), shape)
                .padding(Space.x2),
        ) {
            // A little picture of the theme: a card on its page, the accent pill and the green dot on it.
            Row(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(30.dp)
                    .clip(RoundedCornerShape(12.dp)).background(p.surface)
                    .border(1.dp, p.stroke, RoundedCornerShape(12.dp)).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(6.dp).clip(androidx.compose.foundation.shape.CircleShape).background(p.ok))
                Spacer(Modifier.weight(1f))
                Box(Modifier.size(22.dp, 8.dp).clip(RoundedCornerShape(4.dp))
                    .background(com.erelay.relay.ui.theme.Accent.brush()))
            }
            if (mark > 0.01f) {
                Box(
                    Modifier.align(Alignment.TopEnd).size(20.dp)
                        .graphicsLayer { scaleX = mark; scaleY = mark; alpha = mark.coerceIn(0f, 1f) }
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(com.erelay.relay.ui.theme.Accent.brush()),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Glyph.check, null, Modifier.size(12.dp), tint = Color.White)
                }
            }
        }
        Spacer(Modifier.height(Space.x2))
        Text(p.label, style = Type.caption.copy(color = if (on) Tone.text else Tone.text2,
            fontWeight = if (on) FontWeight.W600 else FontWeight.W400), maxLines = 1,
            overflow = TextOverflow.Ellipsis)
    }
}

// -- about -----------------------------------------------------------------------

@Composable
private fun About(core: RelayCore) {
    Section("Relay for Android",
        help = "Accounts are kept with a key the Android keystore holds; configs, pins and " +
            "test results are plain files in the data folder.") {
        Group {
            Line("Version", core.appVersion)
            Sep()
            Line("Core", core.version)
            Sep()
            Line("Data", core.dataFolder)
        }
    }
}

/** A label and its value in two columns; the value wraps rather than runs off. */
@Composable
private fun Line(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = Space.x5, vertical = Space.x4)) {
        Text(label, style = Type.caption, modifier = Modifier.width(64.dp))
        Text(value, style = Type.mono.copy(color = Tone.text), modifier = Modifier.weight(1f))
    }
}
