package app.mirage

import android.content.Context
import mirage.Mirage

/**
 * The certificate of a server reached by its IP address, known before the
 * server is sent anything.
 *
 * A server by address has no name a CA could vouch for, so the app holds it
 * to one certificate: the pin. The pin comes from the server's key
 * (mirage://...), which is the server's own word for it - or, for a server
 * typed in by address, from the person: the first Test shows the SHA-256 the
 * address presents ([Mirage.serverFingerprint], a bare handshake that sends
 * no password) and only a second Test, pressed after comparing it with the
 * "certificate sha256" line the installer printed, keeps it
 * (MirageCore.testTunnel).
 *
 * It used to be taken silently - whatever answered the first Test or connect
 * became the pin, and the tunnel and API passwords followed it. The core now
 * refuses to dial an address that has no pin, with [Mirage.noPinError]'s
 * message, and nothing here learns one on its own any more: these say where
 * to go and confirm it instead.
 *
 * Calls into Go: never on the main thread.
 */
object ServerPin {

    private val ADDRESS = Regex("""\d{1,3}(\.\d{1,3}){3}""")

    // An IPv6 literal, bracketed or not, with an optional zone. At least two
    // colons: every IPv6 address has them, and no host name does - which
    // keeps a name made only of hex letters ("cafe.be") from passing.
    private val ADDRESS6 = Regex("""\[?[0-9A-Fa-f]*:[0-9A-Fa-f]*:[0-9A-Fa-f:.]*(%[\w.]+)?\]?""")

    /** What a server by address with no confirmed certificate is answered with. */
    const val UNCONFIRMED = "its certificate has not been confirmed yet. Under Settings → Connection " +
        "press Test and confirm the certificate it shows, or paste the server's mirage:// key"

    /**
     * Reached by an address (IPv4 or IPv6) rather than by a name - what needs
     * a kept pin. The core's own test is net.ParseIP, which takes both; an
     * IPv6 server this said no to was never shown its certificate, and the
     * core went on refusing it for having no pin.
     */
    fun isAddress(host: String): Boolean {
        val h = host.trim()
        return ADDRESS.matches(h) || ADDRESS6.matches(h)
    }

    /** Whether [e] is the core saying "no pin yet - test first". */
    fun isNoPin(e: Throwable): Boolean {
        val want = try { Mirage.noPinError() } catch (x: Throwable) { return false }
        val got = e.message ?: return false
        return want.isNotEmpty() && (got == want || got.contains(want))
    }

    /**
     * Throws when [cfg] is a server by address whose pin is not known: the
     * call that was about to be made would be refused by the core anyway,
     * and this says what to do about it. [ctx] is unused now that nothing is
     * learnt here; kept so the callers read as they did.
     */
    @Suppress("UNUSED_PARAMETER")
    fun ensure(ctx: Context, cfg: mirage.ServerConfig) {
        if (cfg.pin.isEmpty() && isAddress(cfg.domain)) throw IllegalStateException(UNCONFIRMED)
    }

    /**
     * One server-API call, with the pin made sure of first. Should the core
     * still say it has none (a pin dropped by a key pasted between the check
     * and the call), that comes back as the same instruction rather than as
     * the core's shorter one.
     */
    fun <T> call(ctx: Context, cfg: mirage.ServerConfig, api: () -> T): T {
        ensure(ctx, cfg)
        return try {
            api()
        } catch (e: Exception) {
            if (!isNoPin(e)) throw e
            throw IllegalStateException(UNCONFIRMED, e)
        }
    }
}
