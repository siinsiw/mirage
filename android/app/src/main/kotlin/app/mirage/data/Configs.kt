package app.mirage.data

import android.app.Application
import android.content.Context
import app.mirage.Keys
import app.mirage.MirageVpnService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import mirage.ConfigsProgress
import mirage.Mirage

/*
 * The Configs section: servers bought or given from anywhere - vless:// vmess://
 * trojan:// ss:// hysteria2:// tuic:// links and subscriptions - kept, tested and
 * connected through the Go core's sing-box Way (mobile/core/configs_box.go).
 *
 * The same shape the Windows app keeps (app/configs/store.py): groups ("manual"
 * and one per subscription), configs as the core read them, the last test of
 * each, and three switches. All of it in the secret store, as one JSON value:
 * every config carries a password or an id that is as good as one, and a
 * subscription address is the key to a seller's whole list.
 *
 * Links are never read here. The core reads them, field by field, and what it
 * hands back is what is kept; when a connection is made from a kept config, the
 * core checks it again rather than believing the file.
 */

data class CfgGroup(
    val id: String, val kind: String, val name: String, val host: String,
    val plainHttp: Boolean, val upload: Long, val download: Long, val total: Long,
    val expire: Long, val updated: Long, val error: String,
)

data class CfgItem(
    val id: String, val group: String, val name: String, val label: String,
    val proto: String, val host: String, val port: Int, val warnings: List<String>,
    val engine: String, val tls: Boolean, val allowInsecure: Boolean,
    /** The last test: ms > 0 is a time; otherwise [error] says why, [said] in sing-box's words. */
    val ms: Int, val country: String, val error: String, val said: String,
)

data class CfgState(
    val groups: List<CfgGroup> = emptyList(),
    val items: List<CfgItem> = emptyList(),
    val selected: String = "",
    val auto: Boolean = false,
    val fragment: Boolean = false,
    /** A test under way: how far it has got. */
    val testing: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    /** Subscriptions being fetched now. */
    val fetching: Set<String> = emptySet(),
)

/** What the service connects with: the group, checked again by the core. */
data class CfgPick(
    val groupJson: String, val selected: String, val auto: Boolean,
    val fragment: Boolean, val name: String, val host: String,
)

/** Pure: the stored JSON, changed and read. No Android, no Go - the tests' half. */
object ConfigsStore {
    const val MANUAL = "manual"
    const val MAX_TOTAL = 1000
    const val MAX_GROUPS = 32

    fun empty(): JSONObject = JSONObject()
        .put("v", 1)
        .put("groups", JSONArray().put(JSONObject().put("id", MANUAL).put("kind", "manual").put("name", "")))
        .put("configs", JSONArray())
        .put("selected", "").put("auto", false).put("fragment", false)

    fun parse(text: String): JSONObject = try {
        val o = JSONObject(text)
        if (o.optJSONArray("configs") == null || o.optJSONArray("groups") == null) empty() else o
    } catch (e: Exception) {
        empty()
    }

    private fun configs(o: JSONObject): List<JSONObject> {
        val a = o.optJSONArray("configs") ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }
    }

    private fun groups(o: JSONObject): List<JSONObject> {
        val a = o.optJSONArray("groups") ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }
    }

    private fun setConfigs(o: JSONObject, list: List<JSONObject>) {
        o.put("configs", JSONArray().also { a -> list.forEach { a.put(it) } })
    }

    /** New configs (the core's views) into a group. Returns added, already there, over the limit. */
    fun add(o: JSONObject, views: JSONArray, group: String = MANUAL): Triple<Int, Int, Int> {
        val list = configs(o).toMutableList()
        val have = list.map { it.optString("id") }.toMutableSet()
        var added = 0; var dup = 0; var over = 0
        for (i in 0 until views.length()) {
            val v = views.optJSONObject(i) ?: continue
            val id = v.optString("id")
            if (id.isEmpty()) continue
            if (id in have) { dup++; continue }
            if (list.size >= MAX_TOTAL) { over++; continue }
            list.add(JSONObject(v.toString()).put("group", group).put("allow_insecure", false))
            have.add(id)
            added++
        }
        setConfigs(o, list)
        return Triple(added, dup, over)
    }

    /** A subscription's list, swapped for what it serves now; a config still there keeps its test and the person's choices. */
    fun replaceGroup(o: JSONObject, group: String, views: JSONArray): Int {
        val old = configs(o).filter { it.optString("group") == group }.associateBy { it.optString("id") }
        val others = configs(o).filter { it.optString("group") != group }
        val seen = others.map { it.optString("id") }.toMutableSet()
        val room = MAX_TOTAL - others.size
        val fresh = mutableListOf<JSONObject>()
        for (i in 0 until views.length()) {
            val v = views.optJSONObject(i) ?: continue
            val id = v.optString("id")
            if (id.isEmpty() || id in seen || fresh.size >= room) continue
            seen.add(id)
            val keep = old[id]
            val c = JSONObject(v.toString()).put("group", group)
                .put("allow_insecure", keep?.optBoolean("allow_insecure") ?: false)
            keep?.optJSONObject("last")?.let { c.put("last", it) }
            fresh.add(c)
        }
        setConfigs(o, others + fresh)
        return fresh.size
    }

    fun remove(o: JSONObject, ids: Set<String>): Int {
        val before = configs(o)
        val after = before.filter { it.optString("id") !in ids }
        setConfigs(o, after)
        if (o.optString("selected") in ids) o.put("selected", "")
        return before.size - after.size
    }

    fun rename(o: JSONObject, id: String, name: String): Boolean {
        val clean = cleanName(name)
        val c = configs(o).firstOrNull { it.optString("id") == id } ?: return false
        if (clean.isNotEmpty()) c.put("name", clean)
        return true
    }

    fun trust(o: JSONObject, id: String, on: Boolean): Boolean {
        val c = configs(o).firstOrNull { it.optString("id") == id } ?: return false
        c.put("allow_insecure", on)
        return true
    }

    fun setResult(o: JSONObject, result: JSONObject, at: Long) {
        val id = result.optString("id")
        val c = configs(o).firstOrNull { it.optString("id") == id } ?: return
        if (result.optString("error") == "cancelled") return
        c.put("last", JSONObject(result.toString()).put("at", at))
    }

    /** A subscription, by its address. Returns its id (the existing one if added before). */
    fun addGroup(o: JSONObject, url: String, name: String, plainHttp: Boolean, newId: String): String {
        groups(o).firstOrNull { it.optString("url") == url }?.let { return it.optString("id") }
        if (groups(o).size >= MAX_GROUPS) throw IllegalStateException("too many subscriptions")
        val host = try { java.net.URI(url).host.orEmpty() } catch (e: Exception) { "" }
        o.getJSONArray("groups").put(JSONObject().put("id", newId).put("kind", "sub")
            .put("url", url).put("name", cleanName(name)).put("host", host)
            .put("plainHttp", plainHttp))
        return newId
    }

    fun updateGroup(o: JSONObject, id: String, name: String?, info: JSONObject?, updated: Long?, error: String?) {
        val g = groups(o).firstOrNull { it.optString("id") == id } ?: return
        if (name != null && g.optString("name").isEmpty()) g.put("name", cleanName(name))
        if (info != null) g.put("info", info)
        if (updated != null) g.put("updated", updated)
        if (error != null) g.put("error", error)
    }

    fun removeGroup(o: JSONObject, id: String): Int {
        if (id == MANUAL) return 0
        val gone = configs(o).filter { it.optString("group") == id }.map { it.optString("id") }.toSet()
        o.put("groups", JSONArray().also { a -> groups(o).filter { it.optString("id") != id }.forEach { a.put(it) } })
        return remove(o, gone)
    }

    fun groupUrl(o: JSONObject, id: String): String =
        groups(o).firstOrNull { it.optString("id") == id }?.optString("url").orEmpty()

    /** Fastest first, untested next, failed last - each kept in its order. */
    fun ordered(list: List<CfgItem>): List<CfgItem> = list.sortedWith(compareBy<CfgItem>({
        when {
            it.ms > 0 -> 0
            it.error.isNotEmpty() -> 2
            else -> 1
        }
    }, { if (it.ms > 0) it.ms else 0 }))

    /** The group of [id], as the core reads it back, fastest first. */
    fun pick(o: JSONObject, id: String = o.optString("selected")): CfgPick? {
        val all = configs(o)
        val chosen = all.firstOrNull { it.optString("id") == id } ?: return null
        val group = chosen.optString("group")
        val items = state(o).items.filter { it.group == group }
        val order = ordered(items).map { it.id }
        val byId = all.associateBy { it.optString("id") }
        val arr = JSONArray()
        for (cid in order) byId[cid]?.let { arr.put(it) }
        return CfgPick(arr.toString(), id, o.optBoolean("auto"), o.optBoolean("fragment"),
            chosen.optString("name"), chosen.optString("host"))
    }

    fun state(o: JSONObject): CfgState {
        val groups = groups(o).map { g ->
            val info = g.optJSONObject("info") ?: JSONObject()
            CfgGroup(g.optString("id"), g.optString("kind"), g.optString("name"), g.optString("host"),
                g.optBoolean("plainHttp"), info.optLong("upload"), info.optLong("download"),
                info.optLong("total"), info.optLong("expire"), g.optLong("updated"), g.optString("error"))
        }
        val items = configs(o).map { c ->
            val w = c.optJSONArray("warnings") ?: JSONArray()
            val last = c.optJSONObject("last") ?: JSONObject()
            CfgItem(c.optString("id"), c.optString("group", MANUAL), c.optString("name"),
                c.optString("label"), c.optString("proto"), c.optString("host"), c.optInt("port"),
                (0 until w.length()).map { w.optString(it) }, c.optString("engine", "sing-box"),
                c.optBoolean("tls"), c.optBoolean("allow_insecure"), last.optInt("ms"),
                last.optString("country"), last.optString("error"), last.optString("said"))
        }
        return CfgState(groups, items, o.optString("selected"), o.optBoolean("auto"), o.optBoolean("fragment"))
    }

    /**
     * A name fit to show: no control or direction characters (a name carrying
     * them can show as something it is not), at most 64 long. Zero-width
     * non-joiner and joiner stay - Persian spelling needs the first.
     */
    fun cleanName(text: String): String {
        val bidi = setOf(0x200E, 0x200F, 0x202A, 0x202B, 0x202C, 0x202D, 0x202E,
            0x2066, 0x2067, 0x2068, 0x2069, 0x061C)
        val sb = StringBuilder()
        var i = 0
        var n = 0
        while (i < text.length && n < 256) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            n++
            if (cp == 0x200C || cp == 0x200D) { sb.appendCodePoint(cp); continue }
            val type = Character.getType(cp)
            if (cp in bidi || type == Character.CONTROL.toInt() || type == Character.FORMAT.toInt() ||
                type == Character.PRIVATE_USE.toInt() || type == Character.SURROGATE.toInt() ||
                type == Character.UNASSIGNED.toInt()) continue
            if (Character.isSpaceChar(cp) || Character.isWhitespace(cp)) sb.append(' ') else sb.appendCodePoint(cp)
        }
        val joined = sb.toString().trim().split(Regex(" +")).joinToString(" ")
        val cps = joined.codePoints().toArray()
        return (if (cps.size > 64) String(cps, 0, 64) else joined).trim()
    }
}

/** The stored value, read and written. One lock for the window and the service. */
object Configs {
    const val PROVIDER = "configs"
    private const val KEY = "configsStore"
    private val lock = Any()

    fun read(ctx: Context): JSONObject = synchronized(lock) {
        ConfigsStore.parse(Keys.secret(ctx, KEY).ifEmpty { "{}" })
    }

    fun <T> edit(ctx: Context, fn: (JSONObject) -> T): T = synchronized(lock) {
        val o = read(ctx)
        val out = fn(o)
        Keys.putSecret(ctx, KEY, o.toString())
        out
    }

    /** What the service connects with, or null when nothing is picked. */
    fun pick(ctx: Context): CfgPick? = ConfigsStore.pick(read(ctx))

    /** Erasing everything takes these too. */
    fun forget(ctx: Context) = synchronized(lock) { Keys.putSecret(ctx, KEY, null) }
}

/**
 * The screen's side: state as a flow, every call into Go on [io]. The core's
 * answers are JSON, read here; nothing the screen draws carries a secret.
 */
class ConfigsModel(private val app: Application) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val io = Dispatchers.IO
    private val _state = MutableStateFlow(CfgState())
    val state: StateFlow<CfgState> = _state.asStateFlow()

    init { scope.launch { refresh() } }

    private fun refresh() {
        val s = ConfigsStore.state(Configs.read(app))
        _state.update { s.copy(testing = it.testing, done = it.done, total = it.total, fetching = it.fetching) }
    }

    private fun <T> change(fn: (JSONObject) -> T): T {
        val out = Configs.edit(app, fn)
        refresh()
        return out
    }

    /** A paste: links, a base64 list, or sing-box JSON. */
    suspend fun import(text: String): Said = withContext(io) {
        if (text.isBlank()) return@withContext Said.no("The clipboard has no text in it.")
        val r = JSONObject(Mirage.parseConfigsJSON(text))
        val err = r.optString("error")
        if (err.isNotEmpty()) return@withContext Said.no(err.replaceFirstChar { it.uppercase() } + ".")
        val views = r.optJSONArray("configs") ?: JSONArray()
        val (added, dup, over) = change { ConfigsStore.add(it, views) }
        val problems = r.optJSONArray("problems") ?: JSONArray()
        var plain = 0; var insecure = 0
        for (i in 0 until views.length()) {
            val w = views.optJSONObject(i)?.optJSONArray("warnings") ?: continue
            for (j in 0 until w.length()) when (w.optString(j)) {
                "plain" -> plain++
                "insecure" -> insecure++
            }
        }
        val parts = mutableListOf(if (added > 0) "Added $added." else "Nothing new added.")
        if (dup > 0) parts += "$dup already here."
        if (over > 0) parts += "$over over the limit of ${ConfigsStore.MAX_TOTAL}."
        if (problems.length() > 0) {
            val p = problems.getJSONObject(0)
            parts += "${problems.length()} not taken - line ${p.optInt("n")}: ${p.optString("why")}."
        }
        if (plain > 0) parts += "$plain send traffic without encryption."
        if (insecure > 0) parts += "$insecure ask for the certificate check to be off; it stays on."
        Said(added > 0 || dup > 0, parts.joinToString(" "))
    }

    /** A subscription address: kept, then fetched. */
    suspend fun addSubscription(url: String): Said = withContext(io) {
        val why = Mirage.checkSubscriptionURL(url.trim())
        if (why.isNotEmpty()) return@withContext Said.no(why.replaceFirstChar { it.uppercase() } + ".")
        val id = "s-" + java.util.UUID.randomUUID().toString().replace("-", "").take(10)
        val gid = try {
            change { ConfigsStore.addGroup(it, url.trim(), "", url.trim().startsWith("http://"), id) }
        } catch (e: IllegalStateException) {
            return@withContext Said.no("There are already ${ConfigsStore.MAX_GROUPS} subscriptions.")
        }
        updateSubscription(gid)
    }

    /** Fetch one subscription again - through the connection when one is up (the core sees to that). */
    suspend fun updateSubscription(gid: String): Said = withContext(io) {
        val url = ConfigsStore.groupUrl(Configs.read(app), gid)
        if (url.isEmpty()) return@withContext Said.no("That subscription is not there any more.")
        _state.update { it.copy(fetching = it.fetching + gid) }
        try {
            val r = JSONObject(Mirage.fetchSubscriptionJSON(url, 20_000L))
            val err = r.optString("error")
            if (err.isNotEmpty()) {
                change { ConfigsStore.updateGroup(it, gid, null, null, null, err) }
                return@withContext Said.no(err.replaceFirstChar { it.uppercase() } + ".")
            }
            val views = r.optJSONArray("configs") ?: JSONArray()
            val n = change {
                val count = ConfigsStore.replaceGroup(it, gid, views)
                ConfigsStore.updateGroup(it, gid, r.optString("name"), r.optJSONObject("info"),
                    System.currentTimeMillis() / 1000, "")
                count
            }
            val bad = r.optJSONArray("problems")?.length() ?: 0
            Said(true, "Updated: $n configs" + (if (bad > 0) ", $bad not taken" else "") + ".")
        } finally {
            _state.update { it.copy(fetching = it.fetching - gid) }
        }
    }

    fun removeSubscription(gid: String) = scope.launch { change { ConfigsStore.removeGroup(it, gid) } }
    fun remove(id: String) = scope.launch { change { ConfigsStore.remove(it, setOf(id)) } }
    fun rename(id: String, name: String) = scope.launch { change { ConfigsStore.rename(it, id, name) } }
    fun trust(id: String, on: Boolean) = scope.launch { change { ConfigsStore.trust(it, id, on) } }
    fun setAuto(on: Boolean) = scope.launch { change { it.put("auto", on) } }
    fun setFragment(on: Boolean) = scope.launch { change { it.put("fragment", on) } }
    fun select(id: String) = change { it.put("selected", id) }

    /** The link a config came from, for copying; "" for one from a sing-box JSON list. */
    fun linkOf(id: String): String {
        val a = Configs.read(app).optJSONArray("configs") ?: return ""
        for (i in 0 until a.length()) {
            val c = a.optJSONObject(i) ?: continue
            if (c.optString("id") == id) return c.optString("link")
        }
        return ""
    }

    /** Whether a connection can be made: something picked that the phone can carry. */
    fun ready(): Said {
        val s = _state.value
        val c = s.items.firstOrNull { it.id == s.selected } ?: return Said.no("Pick a config first.")
        if (c.engine == "xray" && !s.auto) {
            return Said.no("This config needs Xray (xhttp), which the phone app does not carry yet. " +
                "The Windows app connects with it.")
        }
        return Said.OK
    }

    /** Another config of the running group, live; false when it has to be a reconnect. */
    fun switchLive(id: String): Boolean {
        val c = _state.value.items.firstOrNull { it.id == id } ?: return false
        if (_state.value.auto) return false
        val ok = MirageVpnService.selectConfig(id, c.name)
        if (ok) select(id)
        return ok
    }

    /** Test a group, one config, or all of them - at most four at once (weak phones). */
    fun test(group: String? = null, only: String? = null) {
        if (_state.value.testing) return
        val o = Configs.read(app)
        val all = o.optJSONArray("configs") ?: return
        val arr = JSONArray()
        for (i in 0 until all.length()) {
            val c = all.optJSONObject(i) ?: continue
            if ((group == null || c.optString("group") == group) &&
                (only == null || c.optString("id") == only)) arr.put(c)
        }
        if (arr.length() == 0) return
        _state.update { it.copy(testing = true, done = 0, total = arr.length()) }
        scope.launch {
            try {
                Mirage.testConfigsJSON(arr.toString(), o.optBoolean("fragment"), 4L, 8_000L,
                    object : ConfigsProgress {
                        override fun tested(done: Long, total: Long, resultJSON: String) {
                            val r = try { JSONObject(resultJSON) } catch (e: Exception) { return }
                            Configs.edit(app) { ConfigsStore.setResult(it, r, System.currentTimeMillis() / 1000) }
                            refresh()
                            _state.update { it.copy(done = done.toInt(), total = total.toInt()) }
                        }
                    })
            } finally {
                _state.update { it.copy(testing = false) }
                refresh()
            }
        }
    }

    fun cancelTest() {
        try { Mirage.cancelConfigsTest() } catch (e: Exception) { }
    }
}
