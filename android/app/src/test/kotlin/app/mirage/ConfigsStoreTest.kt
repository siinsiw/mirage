package app.mirage

import app.mirage.data.CfgItem
import app.mirage.data.ConfigsStore
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Configs section's stored list, changed and read: the half that needs
 * neither Android nor Go. The configs here are the shape the Go core hands
 * back (bind/configs.go configView): every field of core.Config plus
 * engine, warnings, label and tls.
 */
class ConfigsStoreTest {

    private fun view(id: String, name: String = "c-$id", warnings: List<String> = emptyList(),
                     engine: String = "sing-box", tls: Boolean = true): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("proto", "vless").put("host", "203.0.113.$id".take(30))
        .put("port", 443).put("uuid", "b831381d-6324-4d53-ad4f-8cda48b30811")
        .put("security", if (tls) "tls" else "none").put("label", "VLESS·TLS")
        .put("engine", engine).put("tls", tls).put("link", "vless://secret@203.0.113.1:443#x")
        .put("warnings", JSONArray(warnings))

    private fun views(vararg v: JSONObject) = JSONArray().also { a -> v.forEach { a.put(it) } }

    @Test
    fun addKeepsOrderDropsDuplicatesAndHoldsTheLimit() {
        val o = ConfigsStore.empty()
        assertEquals(Triple(2, 0, 0), ConfigsStore.add(o, views(view("1"), view("2"))))
        assertEquals(Triple(1, 2, 0), ConfigsStore.add(o, views(view("1"), view("2"), view("3"))))
        val s = ConfigsStore.state(o)
        assertEquals(listOf("1", "2", "3"), s.items.map { it.id })
        assertTrue(s.items.all { it.group == ConfigsStore.MANUAL && !it.allowInsecure })
    }

    @Test
    fun stateCarriesNoSecret() {
        val o = ConfigsStore.empty()
        ConfigsStore.add(o, views(view("1")))
        val s = ConfigsStore.state(o).toString()
        assertFalse(s.contains("b831381d"))
        assertFalse(s.contains("secret@"))
    }

    @Test
    fun aSubscriptionRefreshKeepsWhatThePersonChose() {
        val o = ConfigsStore.empty()
        val gid = ConfigsStore.addGroup(o, "https://sub.example.com/abc", "Shop", false, "s-1")
        assertEquals(gid, ConfigsStore.addGroup(o, "https://sub.example.com/abc", "", false, "s-2"))
        assertEquals(3, ConfigsStore.replaceGroup(o, gid, views(view("1"), view("2"), view("3"))))
        ConfigsStore.trust(o, "2", true)
        ConfigsStore.setResult(o, JSONObject().put("id", "2").put("ms", 120).put("country", "de"), 100)
        ConfigsStore.replaceGroup(o, gid, views(view("2"), view("4")))
        val s = ConfigsStore.state(o)
        assertEquals(listOf("2", "4"), s.items.map { it.id })
        val kept = s.items.first { it.id == "2" }
        assertTrue(kept.allowInsecure)
        assertEquals(120, kept.ms)
        assertEquals("de", kept.country)
        assertEquals("sub.example.com", s.groups.first { it.id == gid }.host)
        assertEquals(2, ConfigsStore.removeGroup(o, gid))
        assertTrue(ConfigsStore.state(o).items.isEmpty())
        assertEquals(0, ConfigsStore.removeGroup(o, ConfigsStore.MANUAL))
    }

    @Test
    fun aCancelledTestLeavesTheLastOneAlone() {
        val o = ConfigsStore.empty()
        ConfigsStore.add(o, views(view("1")))
        ConfigsStore.setResult(o, JSONObject().put("id", "1").put("ms", 90), 1)
        ConfigsStore.setResult(o, JSONObject().put("id", "1").put("error", "cancelled"), 2)
        assertEquals(90, ConfigsStore.state(o).items[0].ms)
    }

    @Test
    fun fastestFirstUntestedNextFailedLast() {
        fun item(id: String, ms: Int, error: String = "") = CfgItem(id, "manual", id, "", "vless", "h", 1,
            emptyList(), "sing-box", true, false, ms, "", error, "")
        val got = ConfigsStore.ordered(listOf(item("a", 0, "timeout"), item("b", 300), item("c", 0),
            item("d", 90))).map { it.id }
        assertEquals(listOf("d", "b", "c", "a"), got)
    }

    @Test
    fun pickIsTheGroupFastestFirstAndCarriesTheSwitches() {
        val o = ConfigsStore.empty()
        ConfigsStore.add(o, views(view("1"), view("2"), view("3")))
        ConfigsStore.setResult(o, JSONObject().put("id", "3").put("ms", 50), 1)
        o.put("selected", "2").put("auto", true).put("fragment", true)
        val p = ConfigsStore.pick(o)
        assertNotNull(p)
        assertEquals("2", p!!.selected)
        assertTrue(p.auto && p.fragment)
        val ids = JSONArray(p.groupJson).let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("id") } }
        assertEquals(listOf("3", "1", "2"), ids)
        o.put("selected", "gone")
        assertNull(ConfigsStore.pick(o))
    }

    @Test
    fun removingTheSelectedOneUnpicksIt() {
        val o = ConfigsStore.empty()
        ConfigsStore.add(o, views(view("1"), view("2")))
        o.put("selected", "1")
        assertEquals(1, ConfigsStore.remove(o, setOf("1")))
        assertEquals("", o.getString("selected"))
    }

    @Test
    fun namesAreCleaned() {
        assertEquals("ab‌c d", ConfigsStore.cleanName("a‮b‌c\u0007   d"))
        assertEquals(64, ConfigsStore.cleanName("ن".repeat(100)).codePointCount(0, 64))
        val o = ConfigsStore.empty()
        ConfigsStore.add(o, views(view("1")))
        assertTrue(ConfigsStore.rename(o, "1", "  my‮ server "))
        assertEquals("my server", ConfigsStore.state(o).items[0].name)
        assertTrue(ConfigsStore.rename(o, "1", "‮"))
        assertEquals("my server", ConfigsStore.state(o).items[0].name)
    }

    @Test
    fun aDamagedFileIsAnEmptyList() {
        assertTrue(ConfigsStore.state(ConfigsStore.parse("not json")).items.isEmpty())
        assertTrue(ConfigsStore.state(ConfigsStore.parse("{\"configs\":3}")).items.isEmpty())
    }
}
