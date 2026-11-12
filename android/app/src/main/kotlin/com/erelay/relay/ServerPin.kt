package com.erelay.relay

import android.content.Context
import android.util.Log
import org.json.JSONObject
import relay.Relay

/**
 * The certificate of a server reached by its IP address, known before its API
 * is asked anything.
 *
 * A server by address has no name a CA could vouch for, so the app keeps the
 * certificate it saw the first time (TOFU) and requires it from then on. The
 * tunnel learns it as a side effect of TestServer. The server's API - the
 * calls that point server + exit at a location, and the ones that keep the
 * slots for other devices - used to be asked before any test had run, with
 * no pin at all: the API password went to whatever answered on that address.
 * The core now refuses those calls outright while the pin is empty, with
 * [Relay.noPinError]'s message, and this is what runs first so that refusal
 * never has to happen: a test over the WebSocket to /gw (Shadowsocks has no
 * certificate to learn), the pin kept, and then the call.
 *
 * Blocking calls into Go throughout: never on the main thread.
 */
object ServerPin {

    private val ADDRESS = Regex("""\d{1,3}(\.\d{1,3}){3}""")

    /** Reached by an IPv4 address rather than by a name - what needs a kept pin. */
    fun isAddress(host: String): Boolean = ADDRESS.matches(host.trim())

    /** Whether [e] is the core saying "no pin yet - test first". */
    fun isNoPin(e: Throwable): Boolean {
        val want = try { Relay.noPinError() } catch (x: Throwable) { return false }
        val got = e.message ?: return false
        return want.isNotEmpty() && (got == want || got.contains(want))
    }

    /**
     * Ask the server once, the plain way, to see its certificate, and keep it
     * on [cfg] and in the settings. Throws what the test threw - a server that
     * will not answer a test is not one to send an API password to either.
     *
     * Only into the settings when [cfg] is still the saved server: a key
     * pasted meanwhile is another server, with its own pin to learn.
     */
    fun learn(ctx: Context, cfg: relay.ServerConfig, timeoutMs: Long = 20000L): String {
        val probe = Relay.newServerConfig()
        probe.domain = cfg.domain
        probe.password = cfg.password
        probe.port = cfg.port
        probe.edges = cfg.edges
        probe.pin = cfg.pin
        // "single" exits at the server itself: no exit-chain is needed for
        // the answer, which is the point - none may be set yet. Left on the
        // WebSocket even when cfg says Shadowsocks, because only TLS shows a
        // certificate.
        probe.mode = "single"
        val seen = JSONObject(Relay.testServer(probe, timeoutMs))
        val pin = seen.optString("pin")
        if (pin.isNotEmpty() && cfg.pin.isEmpty()) {
            cfg.pin = pin
            val p = Keys.prefs(ctx)
            if (p.getString(Keys.TUNNEL_DOMAIN, "") == cfg.domain) {
                p.edit().putString(Keys.TUNNEL_PIN, pin).apply()
            }
            Log.i(RelayVpnService.TAG, "kept ${cfg.domain}'s certificate before asking its API")
        }
        return cfg.pin
    }

    /** [learn], when [cfg] is a server by address whose pin is not known yet. */
    fun ensure(ctx: Context, cfg: relay.ServerConfig) {
        if (cfg.pin.isEmpty() && isAddress(cfg.domain)) learn(ctx, cfg)
    }

    /**
     * One server-API call, with the pin made sure of first - and once more if
     * the core still says it has none (a pin dropped by a key pasted between
     * the check and the call): tested, kept, and the call made again, once.
     */
    fun <T> call(ctx: Context, cfg: relay.ServerConfig, api: () -> T): T {
        ensure(ctx, cfg)
        return try {
            api()
        } catch (e: Exception) {
            if (!isNoPin(e)) throw e
            learn(ctx, cfg)
            if (cfg.pin.isEmpty()) throw e
            api()
        }
    }
}
