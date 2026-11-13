package com.erelay.relay.data

import android.content.Context
import com.erelay.relay.RelayVpnService
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import relay.Relay

/**
 * "Is there a newer version?" - asked only when somebody presses for it.
 *
 * The app never asks on its own: nothing here runs at start, on a timer or
 * in the background, and nothing is downloaded or installed. One request for
 * GitHub's releases/latest, and its tag compared with this build's version.
 *
 * While the tunnel is up the question goes through it (the Go core's
 * LatestReleaseTag): this app leaves itself out of its own interface, so a
 * plain request from here would go by the bare line. With no tunnel, or when
 * the exit would not carry it, it is asked directly.
 *
 * Not wired to a button yet - see [check].
 */
object Updates {

    const val LATEST = "https://api.github.com/repos/siinsiw/ovpn-pin/releases/latest"

    /** Where a person goes to get it. */
    const val PAGE = "https://github.com/siinsiw/ovpn-pin/releases/latest"

    /** What one check found. */
    data class Found(val current: String, val latest: String, val newer: Boolean) {
        /** One line for the window. */
        val text: String
            get() = if (newer) "Version $latest is out - this is $current. Get it from the releases page."
            else "This is the newest version ($current)."
    }

    /**
     * The numbers of a version: "v0.3.1" and "0.3.1-debug" are both 0, 3, 1.
     * Null when there is no number in it at all.
     */
    internal fun numbers(version: String): List<Int>? {
        val run = Regex("""\d+(?:\.\d+)*""").find(version)?.value ?: return null
        return run.split('.').map { it.toIntOrNull() ?: return null }
    }

    /**
     * Whether [latest] is a later version than [current], number by number,
     * a missing number counting as 0 ("1.2" is "1.2.0"). False when either
     * cannot be read: a tag that is not a version is not news.
     */
    internal fun isNewer(latest: String, current: String): Boolean {
        val a = numbers(latest) ?: return false
        val b = numbers(current) ?: return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** tag_name out of GitHub's answer for one release; "" when it has none. */
    internal fun tagOf(json: String): String = try {
        JSONObject(json).optString("tag_name").trim()
    } catch (e: Exception) {
        ""
    }

    /** This build's version, as the manifest has it. */
    fun current(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName.orEmpty()
    } catch (e: Exception) {
        ""
    }

    /**
     * Ask once. Throws when GitHub could not be asked or did not answer with
     * a release - the message is fit to show.
     *
     * Nothing calls this yet: the button belongs in Settings (ui/Prefs.kt),
     * which is being redesigned on another branch. It needs only
     * `scope.launch { said = try { Updates.check(context).text } catch (e: Exception) { e.message } }`.
     */
    suspend fun check(ctx: Context): Found = withContext(Dispatchers.IO) {
        val tag = throughTunnel() ?: direct()
        if (tag.isEmpty()) throw IOException("GitHub's answer named no release.")
        val now = current(ctx)
        Found(now, tag, isNewer(tag, now))
    }

    /** The tag asked through the running tunnel; null when there is none or it did not carry the question. */
    private fun throughTunnel(): String? {
        if (RelayVpnService.exitAddress.isEmpty()) return null
        return try {
            Relay.latestReleaseTag(15000L).trim().takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        }
    }

    private fun direct(): String {
        val c = URL(LATEST).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10000
            c.readTimeout = 10000
            c.instanceFollowRedirects = false
            // GitHub refuses a request with no User-Agent.
            c.setRequestProperty("User-Agent", "relay-update-check")
            c.setRequestProperty("Accept", "application/vnd.github+json")
            val code = c.responseCode
            if (code != 200) throw IOException("GitHub answered $code.")
            // One release's description: tens of kilobytes. Not read past a megabyte.
            val body = c.inputStream.use { it.readNBytesCapped(1 shl 20) }
            return tagOf(String(body, Charsets.UTF_8))
        } finally {
            c.disconnect()
        }
    }

    private fun java.io.InputStream.readNBytesCapped(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (out.size() < limit) {
            val n = read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }
}
