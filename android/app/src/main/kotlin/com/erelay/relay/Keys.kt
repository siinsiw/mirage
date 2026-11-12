package com.erelay.relay

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * The names things are kept under, in one place, and the passwords among them.
 *
 * `relay.xml` has always been read by name from three files - the window's
 * state, the service and Pin - and a name typed in three places is a name
 * that is one typo from a setting that silently never takes. So the ones the
 * service and the window both read are written down here.
 *
 * Four of them are passwords, and those do not live in `relay.xml` any more:
 * they go to [Secrets], where `run-as` on a debug build and a backup of the
 * preferences cannot read them out. An install that predates that still has
 * them in `relay.xml`; [secret] moves each one across the first time it is
 * read, and [migrate] does all of them at once on the way up. setup-phone.sh
 * still writes them into `relay.xml` on a debug build - that is the way in
 * for a script, and they leave the file again the next time the app starts.
 */
object Keys {

    /** The ordinary preferences file. Not a secret in it. */
    const val FILE = "relay"

    // -- your own server --------------------------------------------------------
    const val TUNNEL_DOMAIN = "tunnelDomain"
    const val TUNNEL_PASSWORD = "tunnelPassword"
    const val TUNNEL_API_PASSWORD = "tunnelApiPassword"
    const val TUNNEL_PIN = "tunnelPin"
    const val TUNNEL_EDGES = "tunnelEdges"
    const val TUNNEL_PORT = "tunnelPort"
    const val TUNNEL_TRANSPORT = "tunnelTransport"
    const val TUNNEL_SS_METHOD = "tunnelSsMethod"
    const val TUNNEL_SS_PASSWORD = "tunnelSsPassword"
    const val TUNNEL_SS_PORT = "tunnelSsPort"
    const val TUNNEL_SS_EXIT_PORT = "tunnelSsExitPort"
    const val TUNNEL_EXPORT_ID = "tunnelExportId"
    const val TUNNEL_EXPORT_SLOTS = "tunnelExportSlots"
    const val EXPORT_LAST = "exportLast"

    /**
     * Server + exit: the servers whose exit is *not* checked, by host. The
     * check - the server verifying the provider exit's certificate against
     * its pinned name - is on for every server unless somebody turned it off
     * for that one, after a confirmation (Settings → Connection). Kept per
     * server, as the desktop keeps it, so turning it off for a server in Iran
     * does not carry over to the next server pasted in. See [verifyExit].
     */
    const val TUNNEL_VERIFY_OFF = "tunnelVerifyOff"

    // -- the tunnel itself ----------------------------------------------------------
    const val MODE = "mode"
    const val ALLOW_LAN = "allowLan"
    const val SPLIT_MODE = "splitMode"
    const val SPLIT_IRAN = "splitIran"
    const val SPLIT_APPS = "splitApps"
    const val SPLIT_DOMAINS = "splitDomains"

    /**
     * What the last Connect asked for, without a password in it: which way
     * out, which folder, which pick. The service reads it back when Android
     * starts it with nothing to say - a restart after the process was killed,
     * or the always-on VPN coming up at boot.
     */
    const val LAST_CONNECT = "lastConnect"

    // -- sharing on the Wi-Fi -------------------------------------------------------
    const val LAN_SHARE = "lanShare"
    const val LAN_SHARE_PORT = "lanSharePort"
    const val LAN_SHARE_PASSWORD = "lanSharePassword"

    /** The share is a proxy anyone on the Wi-Fi can find; a password shorter than this is not one. */
    const val LAN_SHARE_MIN_PASSWORD = 8

    /** The core's upper bound, so a password it would refuse is refused here first. */
    const val LAN_SHARE_MAX_PASSWORD = 128

    /**
     * Why [password] will not do for the Wi-Fi share, or null when it will.
     * The core's own rule (core.CheckLANPassword), said before it is asked:
     * 8 to 128 characters, printable ASCII (space to tilde) - what every
     * device's proxy settings can type and send in a Basic header.
     */
    fun lanPasswordProblem(password: String): String? = when {
        password.length < LAN_SHARE_MIN_PASSWORD ->
            "Use a password of $LAN_SHARE_MIN_PASSWORD or more characters."
        password.length > LAN_SHARE_MAX_PASSWORD ->
            "Use a password of at most $LAN_SHARE_MAX_PASSWORD characters."
        password.any { it < ' ' || it > '~' } ->
            "Use only letters, digits, spaces and the symbols on an English keyboard - " +
                "other devices' proxy settings cannot all send anything else."
        else -> null
    }

    // -- what a server setting may be -------------------------------------------
    //
    // The same rule the desktop and the installer hold to. These values do not
    // stay in a text field: the host and both passwords go into the install
    // command that is pasted into a root shell on the server, and the host into
    // file paths there. So they are checked when they are typed (or pasted in a
    // key), the reason is shown, and nothing that fails is saved - and the
    // commands quote them anyway ([shellQuote]).

    private val IPV4 = Regex("""(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})""")
    private val LABEL = Regex("""[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?""")

    /**
     * Why [host] is not a server address, or null when it is: an IPv4 literal
     * (four numbers 0-255), or a hostname of letters, digits and hyphens
     * (LDH) - labels of 1 to 63, not starting or ending with a hyphen, 253
     * in all.
     */
    fun hostProblem(host: String): String? {
        val h = host.trim()
        if (h.isEmpty()) return "Type the server's IP address or domain."
        IPV4.matchEntire(h)?.let { m ->
            return if (m.groupValues.drop(1).all { it.toInt() <= 255 }) null
            else "That is not an IPv4 address - each of the four numbers is 0 to 255."
        }
        if (h.length > 253) return "That domain is too long."
        if (h.split('.').all { LABEL.matches(it) }) return null
        return "Use the server's IPv4 address, or a domain of letters, digits, hyphens and dots " +
            "(no spaces, no https://, no path)."
    }

    // Printable ASCII without the space, and without what a shell or a config
    // file would read as something other than a character of the password.
    private const val PASSWORD_FORBIDDEN = "'\"`\$\\{}#"

    /**
     * Why [password] cannot be one of the server's - the tunnel, API or
     * Shadowsocks password - or null when it can: 1 to 128 characters,
     * printable ASCII without the space (0x21-0x7E), and none of
     * ' " ` $ \ { } #. [what] names it in the reason.
     */
    fun serverPasswordProblem(password: String, what: String = "The password"): String? = when {
        password.isEmpty() -> "$what is empty."
        password.length > 128 -> "$what is longer than 128 characters."
        password.any { it < '!' || it > '~' } ->
            "$what can only have letters, digits and the symbols on an English keyboard - " +
                "no spaces or other characters."
        password.any { it in PASSWORD_FORBIDDEN } ->
            "$what cannot contain any of  ' \" ` \$ \\ { } #  - they mean something to the shell " +
                "the installer runs in."
        else -> null
    }

    /**
     * [s] as one POSIX shell word: in single quotes, with each single quote
     * closed, escaped and reopened ('\''). Nothing inside single quotes is
     * special to sh, so this holds for any string - the defence behind the
     * checks above, not instead of them.
     */
    fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    private fun hostKey(host: String) = host.trim().lowercase()

    /** Whether server + exit checks the exit for the server at [host]. True unless turned off for it. */
    fun verifyExit(ctx: Context, host: String): Boolean =
        hostKey(host) !in (prefs(ctx).getStringSet(TUNNEL_VERIFY_OFF, null) ?: emptySet())

    fun setVerifyExit(ctx: Context, host: String, on: Boolean) {
        val key = hostKey(host)
        if (key.isEmpty()) return
        // A copy: the set SharedPreferences hands back must not be changed.
        val off = (prefs(ctx).getStringSet(TUNNEL_VERIFY_OFF, null) ?: emptySet()).toMutableSet()
        if (on) off.remove(key) else off.add(key)
        prefs(ctx).edit().putStringSet(TUNNEL_VERIFY_OFF, off).apply()
    }

    /** The ones that are passwords, and so live in [Secrets] rather than in [FILE]. */
    val SECRET_KEYS = listOf(TUNNEL_PASSWORD, TUNNEL_API_PASSWORD, TUNNEL_SS_PASSWORD, LAN_SHARE_PASSWORD)

    fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /**
     * One of [SECRET_KEYS], or "" when there is none.
     *
     * A value still in `relay.xml` wins, because it is the newer one: either
     * this install predates the move, or setup-phone.sh has just written it.
     * Either way it is moved into [Secrets] and taken out of the file here,
     * and only left where it was when there is no secret store on this phone
     * to move it to (see [putSecret] for why that is not a loss).
     */
    fun secret(ctx: Context, key: String): String {
        val p = prefs(ctx)
        val plain = p.getString(key, null)
        if (!plain.isNullOrEmpty()) {
            if (Secrets.put(ctx, key, plain)) p.edit().remove(key).commit()
            return plain
        }
        return Secrets.get(ctx, key).orEmpty()
    }

    fun hasSecret(ctx: Context, key: String): Boolean = secret(ctx, key).isNotEmpty()

    /**
     * Keep one, or with an empty [value] forget it. True when it went to
     * [Secrets].
     *
     * False means this phone has no working secret store even after [Secrets]
     * started it over. The password is then kept in `relay.xml` as it always
     * was - private to the app, and kept out of every backup and device
     * transfer by the rules in res/xml - rather than dropped: a tunnel
     * password the app cannot hold is a server it cannot reach, and the
     * difference from before is only that the keystore would have been in
     * front of it too.
     */
    fun putSecret(ctx: Context, key: String, value: String?): Boolean {
        val p = prefs(ctx)
        if (Secrets.put(ctx, key, value)) {
            if (p.contains(key)) p.edit().remove(key).commit()
            return true
        }
        if (value.isNullOrEmpty()) {
            p.edit().remove(key).commit()
        } else {
            Log.w(RelayVpnService.TAG, "no secret store: $key kept in the app's private preferences")
            p.edit().putString(key, value).commit()
        }
        return false
    }

    /**
     * Everything that has to have moved before anything reads it: the
     * passwords out of `relay.xml`, and the provider credentials out of the
     * files on shared storage (see [Accounts.adopt]).
     *
     * Off the main thread: opening the secret store is a keystore operation.
     * Called by the window on the way up and by the service before it
     * connects, because either may be the first thing alive; doing it twice
     * finds nothing to do the second time.
     */
    @Synchronized
    fun migrate(ctx: Context) {
        for (key in SECRET_KEYS) secret(ctx, key)

        // A share that was on with no password, or one the core would refuse,
        // from before a password was required. Turned off rather than left to
        // start open: it comes back on when somebody sets one.
        val p = prefs(ctx)
        if (p.getBoolean(LAN_SHARE, false) &&
            lanPasswordProblem(secret(ctx, LAN_SHARE_PASSWORD)) != null
        ) {
            Log.i(RelayVpnService.TAG, "Wi-Fi share turned off: its password is missing or not one the core takes")
            p.edit().putBoolean(LAN_SHARE, false).commit()
        }

        Accounts.adopt(ctx)
    }
}
