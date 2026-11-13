package com.erelay.relay

import java.io.ByteArrayInputStream
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A picked document's name comes from whichever app answers as the documents
 * provider, and its length from the same place: neither is taken on trust.
 */
class ImportTest {

    @Test
    fun anOrdinaryConfigNameIsKept() {
        assertEquals("de-fra.prod.surfshark.com_udp_146.70.253.194.ovpn",
            safeOvpnName("de-fra.prod.surfshark.com_udp_146.70.253.194.ovpn"))
        assertEquals("Windscribe-Paris.ws.ovpn", safeOvpnName("Windscribe-Paris.ws.ovpn"))
        assertEquals("my server (2).OVPN", safeOvpnName("  my server (2).OVPN "))
    }

    @Test
    fun aNameThatIsAPathIsRefused() {
        assertNull(safeOvpnName("../../x.ovpn"))
        assertNull(safeOvpnName("..\\..\\x.ovpn"))
        assertNull(safeOvpnName("/data/data/com.erelay.relay/files/x.ovpn"))
        assertNull(safeOvpnName("pinned/x.ovpn"))
        assertNull(safeOvpnName("a\\b.ovpn"))
        assertNull(safeOvpnName("c:x.ovpn"))
        assertNull(safeOvpnName("a..ovpn"))
        assertNull(safeOvpnName(".."))
    }

    @Test
    fun aNameThatIsNotAConfigIsRefused() {
        assertNull(safeOvpnName(null))
        assertNull(safeOvpnName(""))
        assertNull(safeOvpnName("   "))
        assertNull(safeOvpnName(".ovpn"))
        assertNull(safeOvpnName(".hidden.ovpn"))
        assertNull(safeOvpnName("notes.txt"))
        assertNull(safeOvpnName("x.ovpn.exe"))
        assertNull(safeOvpnName("line\nbreak.ovpn"))
        assertNull(safeOvpnName("nul\u0000.ovpn"))
        assertNull(safeOvpnName("a".repeat(200) + ".ovpn"))
    }

    @Test
    fun aCopyStopsAtTheLimit() {
        val dir = File(System.getProperty("java.io.tmpdir"), "relay-import-test-" + System.nanoTime())
        assertTrue(dir.mkdirs())
        try {
            val small = ByteArray(1000) { (it % 251).toByte() }
            val kept = File(dir, "small.ovpn")
            assertTrue(copyCapped(ByteArrayInputStream(small), kept, limit = 1000))
            assertArrayEquals(small, kept.readBytes())

            val big = File(dir, "big.ovpn")
            assertFalse(copyCapped(ByteArrayInputStream(ByteArray(1001)), big, limit = 1000))
            // Whatever was written before the limit is the caller's to delete, and is never over it.
            assertTrue(big.length() <= 1000)

            assertEquals(1L shl 20, MAX_OVPN_BYTES)
        } finally {
            dir.deleteRecursively()
        }
    }
}
