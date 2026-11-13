package com.erelay.relay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import android.os.PowerManager
import android.os.SystemClock
import com.erelay.relay.data.SplitMode
import java.net.Inet4Address
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import relay.Client
import relay.Progress
import relay.Protector
import relay.Relay

/**
 * The tunnel's whole lifetime on Android.
 *
 * Three things here are not boilerplate, and each of them fails silently if
 * it is wrong:
 *
 *  - [Protector]. Once [VpnService.Builder.addRoute] sends 0.0.0.0/0 into the
 *    tun, the socket the core opens to the exit goes there too. The exit's
 *    address enters the tunnel, comes back out of the stack, is dialled
 *    again, and the phone spends a core talking to itself. Nothing throws.
 *    `protect()` is what breaks the loop, and it has to be handed to the core
 *    before anything dials.
 *
 *  - The descriptor is detached, and ownership goes with it. sing-tun does
 *    not duplicate the fd; it closes it when the tunnel ends. Holding the
 *    [ParcelFileDescriptor] as well means two owners for one descriptor, and
 *    Android's fdsan says so out loud and kills the process:
 *
 *        fdsan: attempted to close file descriptor 136, expected to be
 *        unowned, actually owned by ParcelFileDescriptor
 *
 *    So `detachFd()` hands it over for good, and nothing here closes it
 *    afterwards. The one case that has to be caught by hand is a tunnel that
 *    fails to start after the detach - there the fd is adopted back only to
 *    be closed.
 *
 *  - Foreground, with a notification, before anything else. A VPN service
 *    that is not in the foreground is killed as soon as the window goes away,
 *    and the tunnel goes with it.
 *
 * And one that is about time rather than about the platform: every Connect,
 * Disconnect and teardown is a numbered request ([generation]), and only the
 * newest one may change anything. A connect spends seconds to a minute in
 * calls that cannot be interrupted - a race, an edge scan, a test request -
 * and a Disconnect pressed meanwhile used to tear down what was there, after
 * which the connect finished anyway and opened a tunnel nobody wanted any
 * more, or cleared the protector out from under the next one. Now a request
 * takes its number on the main thread the moment it arrives; the work that
 * changes what is up happens under [lifecycle], and every long call is
 * followed by a check that the number is still the newest. A connect that
 * has been overtaken simply stops: whatever overtook it tears down what it
 * had opened, because every request starts with [stopTunnel].
 */
class RelayVpnService : VpnService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // The raw descriptor, once ownership has left ParcelFileDescriptor. Kept
    // only so that a status line can say whether one is open; nothing here
    // closes it, because the core does.
    @Volatile private var tunFd: Int = -1
    @Volatile private var client: Client? = null

    // A Client a connect is using before it carries anything - server + exit's
    // search for an exit - so that Disconnect can stop it as it stops a race.
    @Volatile private var pending: Client? = null

    // The watchdog over a tunnel that is up. See watch().
    @Volatile private var watcher: Job? = null

    // The sentence the tunnel came up with, said again when a stall clears
    // without the exit having changed.
    @Volatile private var connectedText = ""

    // Whether the newest request to this instance was to be connected. Only
    // for a stray ACTION_SEEN, which must not keep alive a service that was
    // told to stop.
    @Volatile private var wanted = false

    /**
     * One request: its number, and the start id Android gave it - which is
     * what [stopSelf] is told, so that a request that fails cannot stop the
     * service out from under a newer one that has already arrived.
     */
    private class Attempt(val gen: Long, val startId: Int) {
        val current: Boolean get() = RelayVpnService.generation.get() == gen
    }

    /**
     * What a Connect asked for, and nothing secret: the passwords are read
     * from [Secrets] and [Accounts] when they are needed, so a restart by the
     * system needs only this to reach the same place the window did.
     *
     * [provider] is the way out ("surfshark" for the provider race, else the
     * server mode), [country] the pick, [only] the files behind it.
     */
    private class Request(
        val provider: String, val configDir: String, val country: String, val only: String,
    ) {
        fun save(ctx: android.content.Context) {
            Keys.prefs(ctx).edit().putString(Keys.LAST_CONNECT, JSONObject()
                .put("provider", provider).put("configDir", configDir)
                .put("country", country).put("only", only).toString()).apply()
        }

        companion object {
            fun from(i: Intent) = Request(
                provider = i.getStringExtra(RelayVpnService.EXTRA_PROVIDER) ?: "surfshark",
                configDir = i.getStringExtra(RelayVpnService.EXTRA_CONFIG_DIR).orEmpty(),
                country = i.getStringExtra(RelayVpnService.EXTRA_COUNTRY) ?: "auto",
                only = i.getStringExtra(RelayVpnService.EXTRA_ONLY).orEmpty(),
            )

            /** The last Connect, or null when there has not been one since this was kept. */
            fun saved(ctx: android.content.Context): Request? {
                val raw = Keys.prefs(ctx).getString(Keys.LAST_CONNECT, null) ?: return null
                return try {
                    val o = JSONObject(raw)
                    Request(o.optString("provider", "surfshark").ifEmpty { "surfshark" },
                        o.optString("configDir"), o.optString("country", "auto").ifEmpty { "auto" },
                        o.optString("only"))
                } catch (e: Exception) {
                    null
                }
            }
        }
    }

    companion object {
        const val TAG = "Relay"
        const val ACTION_START = "com.erelay.relay.START"
        const val ACTION_STOP = "com.erelay.relay.STOP"

        /**
         * Where the traffic was measured to come out, from the window's
         * seen-as check. The notification names this rather than the
         * country the server was sold as, which is not always where it is.
         */
        const val ACTION_SEEN = "com.erelay.relay.SEEN"
        const val EXTRA_SEEN_COUNTRY = "seenCountry"

        // No credential travels in the intent. Both providers' accounts, the
        // tunnel password and the API password are read by the service from
        // [Accounts] and [Keys.secret] as it connects - which is also what
        // lets a restart by the system, with no intent at all, connect.
        const val EXTRA_CONFIG_DIR = "configDir"
        const val EXTRA_COUNTRY = "country"

        // Which files, when the window is connecting out of Starred rather
        // than out of everything. A set of names rather than a folder,
        // because that is what Starred is - a handful of exits spread across
        // every country, and no folder holds exactly them.
        const val EXTRA_ONLY = "only"

        // Which way out. "surfshark" races the pinned provider exits; the
        // rest go through the user's own server and differ only in the path
        // they ask it for. The server itself - its address, edges, pin and
        // port - is read from the settings, so it is the same on a restart.
        const val EXTRA_PROVIDER = "provider"

        private const val CHANNEL = "relay.tunnel"
        private const val NOTIFICATION = 1

        /**
         * The newest request's number. In the companion rather than on the
         * instance because the teardown of a destroyed instance runs after
         * it is gone, and may overlap the next instance's first connect; one
         * count across both is what lets the old one see it is no longer the
         * newest and leave the shared state - the protector above all - to
         * the new one.
         *
         * Internal rather than private only so that [Attempt], a nested
         * class, reads it without leaning on the compiler's accessors.
         */
        internal val generation = AtomicLong()

        /** Held while anything is being set up or torn down. See the class comment. */
        private val lifecycle = Any()

        /**
         * Held while server + exit searches for an exit - the one long call
         * that writes the server's exit-chain and that nothing can interrupt.
         * Separate from [lifecycle] so a Disconnect is never kept waiting for
         * it; see connectServer.
         */
        private val serverChain = Any()

        /**
         * Why the Wi-Fi share is not running when it should be - the core's
         * own words ("there is no Wi-Fi, hotspot or Ethernet address to share
         * on", a port in use) - or empty. Read by the share screen, which
         * otherwise could only say "Starting…" forever.
         */
        @Volatile var lanShareError: String = ""
            private set

        /**
         * For teardowns that must finish after the service is gone. The
         * service's own scope is cancelled in onDestroy, and a coroutine that
         * had not started on it yet would never run - leaving the stack, and
         * the tun descriptor it owns, open.
         */
        private val process = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** The instance alive now, for [noteSeen]; null between instances. */
        @Volatile private var running: RelayVpnService? = null

        /**
         * Where the window measured the traffic coming out, for the
         * notification. A call rather than an intent: an intent is a start
         * request, and one arriving just after Disconnect would leave
         * Android holding a start id newer than the Disconnect's - and the
         * service, told to stop with the older one, would not.
         */
        fun noteSeen(country: String) {
            if (exitAddress.isEmpty()) return
            seenCountry = country
            running?.let { s -> s.notificationManager().notify(NOTIFICATION, s.notification(status)) }
        }

        /**
         * Where the window is told to look, rather than the window being sent
         * a message. A broadcast wakes it; it reads these. That way a window
         * that was not listening yet - just opened, just rotated - still finds
         * the current state rather than waiting for the next change.
         */
        const val ACTION_STATUS = "com.erelay.relay.STATUS"

        @Volatile var status: String = "not connected"
            private set
        @Volatile var exitAddress: String = ""
            private set
        @Volatile var exitName: String = ""
            private set

        /**
         * Which config is carrying, and whose.
         *
         * Read by the window when it asks where the traffic is coming out:
         * that check goes through the exit, so it has to be opened with the
         * same provider's credential the race used.
         */
        @Volatile var exitFile: String = ""
            private set
        @Volatile var exitProvider: String = ""
            private set
        @Volatile var asked: Int = 0
            private set
        @Volatile var total: Int = 0
            private set
        @Volatile var tookMs: Int = 0
            private set

        /** When the current tunnel came up, in epoch seconds, or 0. */
        @Volatile var since: Long = 0

        /** The measured exit country, two letters, or empty until checked. */
        @Volatile var seenCountry: String = ""
            private set

        /**
         * The tunnel is up but its exit has stopped carrying, and the service
         * is finding another. The interface stays up meanwhile - see watch() -
         * so [exitAddress] is still set, and this is what says that "up" is
         * not "connected" just now.
         */
        @Volatile var stalled: Boolean = false
            private set

        // How the watchdog paces itself is in VpnLogic, with the rest of its
        // arithmetic, where a test can reach it.
        // A working exit answers the check in one to two seconds from Iran;
        // eight is slow enough to forgive a bad moment on the line.
        private const val CHECK_TIMEOUT_MS = 8_000L
        // Checks in a row that fail before the exit is given up on. Two, so
        // one dropped packet on a bad line does not cost a working exit.
        private const val MISSES_BEFORE_SWITCH = 2

        /**
         * What went where, asked of whichever tunnel is up.
         *
         * On the companion rather than the instance because the window has
         * no handle on the service - it was never bound to one, deliberately,
         * so that closing the window cannot take the tunnel with it.
         */
        @Volatile private var live: Client? = null

        fun hostsJSON(): String =
            live?.hostsJSON() ?: """{"live":false,"rows":[]}"""

        /** Who is using the Wi-Fi share, asked of whichever tunnel is up. */
        fun lanShareJSON(): String =
            live?.lanShareJSON() ?: """{"on":false,"port":0,"addrs":[],"clients":0}"""

        /** Starts the share on the live tunnel. Throws when there is none, or the port is taken. */
        fun startLanShare(port: Int, password: String): String {
            val c = live ?: throw IllegalStateException("The tunnel is not up.")
            return try {
                c.startLANShare(port.toLong(), password).also { lanShareError = "" }
            } catch (e: Exception) {
                lanShareError = e.message ?: e.toString()
                throw e
            }
        }

        fun stopLanShare() {
            lanShareError = ""
            try {
                live?.stopLANShare()
            } catch (e: Exception) {
                Log.w(TAG, "stopping the Wi-Fi share", e)
            }
        }

        /**
         * Say something on the service's behalf from somewhere that is not
         * the service - a refused permission, for instance, which the
         * Activity learns about and the service never does.
         */
        fun report(ctx: android.content.Context, text: String) {
            status = text
            ctx.sendBroadcast(Intent(ACTION_STATUS).setPackage(ctx.packageName))
        }
    }

    override fun onCreate() {
        super.onCreate()
        running = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Before the core is asked for anything. Cheap, idempotent, and the
        // service may well be running with no window to have done it.
        AppFiles.announce(applicationContext)

        if (intent?.action == ACTION_SEEN) {
            // Only a better word for the notification: the tunnel is not
            // touched, which is why this is checked before anything else.
            // The window calls noteSeen() now; this is for an intent from an
            // older build of the window still in flight.
            if (exitAddress.isNotEmpty()) {
                seenCountry = intent.getStringExtra(EXTRA_SEEN_COUNTRY).orEmpty()
                notificationManager().notify(NOTIFICATION, notification(status))
                return START_STICKY
            }
            if (!wanted) {
                // Nothing up and nothing asked for: this intent is all that
                // started the service, and it has nothing to keep it for.
                stopSelf(startId)
                return START_NOT_STICKY
            }
            return START_STICKY
        }
        if (intent?.action == ACTION_STOP) {
            val a = Attempt(generation.incrementAndGet(), startId)
            wanted = false
            // A race or a search in flight ends now, rather than running on
            // to the end of its timeouts while the window says "Disconnecting".
            interrupt()
            // Off the main thread: closing the stack closes every connection
            // it carries, and that froze the window for as long as it took -
            // which was the stutter on every Disconnect.
            scope.launch {
                synchronized(lifecycle) {
                    // A Connect pressed after this one is the newer request:
                    // its own first step is this same teardown.
                    if (!a.current) return@launch
                    stopTunnel(a.gen)
                    leaveForeground(a)
                }
            }
            return START_NOT_STICKY
        }

        // A start that is not the window's: Android restarting the service
        // after the process was killed (a null intent - START_STICKY), or
        // the always-on VPN bringing it up at boot or when it is switched on
        // (the android.net.VpnService action, no extras). Either way the
        // only thing to connect to is what the window last asked for.
        val fromWindow = intent?.action == ACTION_START
        if (!fromWindow && (client != null || exitAddress.isNotEmpty())) {
            // Always-on said "be up" to a service that already is.
            return START_STICKY
        }
        val req = if (fromWindow) Request.from(intent!!).also { it.save(this) }
            else Request.saved(this)

        val a = Attempt(generation.incrementAndGet(), startId)
        wanted = req != null
        if (!enterForeground(if (req != null) "finding an exit" else "starting")) {
            // Android refused the foreground (a start from the background it
            // does not exempt). Nothing can run without it: say so and stop,
            // rather than be killed for not having called startForeground.
            if (req != null) {
                status = "failed: Android did not let the tunnel start from the background - " +
                    "open Relay and connect"
                sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName))
            }
            wanted = false
            stopSelf(startId)
            return START_NOT_STICKY
        }

        if (req == null) {
            // Nothing has ever been connected to (or not since this version
            // started keeping it): there is nothing to restart. The service
            // stops rather than sit in the foreground with an empty
            // notification. With lockdown on, Android goes on blocking
            // traffic until somebody connects - which is what lockdown is
            // for; without it, traffic was never in a tunnel to begin with.
            Log.i(TAG, "started with nothing to reconnect to")
            wanted = false
            scope.launch {
                synchronized(lifecycle) {
                    if (!a.current) return@launch
                    leaveForeground(a)
                }
            }
            return START_NOT_STICKY
        }

        // Whatever was carrying traffic a moment ago stops now.
        //
        // The core refuses to start a second tunnel on one Client, but every
        // connect builds a fresh Client - so that guard never sees the old
        // one, and pressing Connect twice left two interfaces up:
        //
        //     86: tun0  172.19.0.1/30
        //     87: tun1  172.19.0.1/30
        //
        // Android hands the newest one the routes, so it looked like it
        // worked. The first was still open, still holding a websocket, still
        // keeping the radio awake with a keepalive nobody was reading.
        //
        // In the coroutine below rather than here, for the same reason as Stop:
        // this runs on the main thread and the window is drawing on it. A
        // race the old connect is still running is told to stop first, so it
        // does not hold up this one.
        interrupt()
        scope.launch {
            synchronized(lifecycle) {
                if (!a.current) return@launch
                stopTunnel(a.gen)
                // Again, under the lock: a Disconnect that was already past
                // its check when this request arrived may have taken the
                // service out of the foreground just now.
                enterForeground("finding an exit")
            }
            if (req.provider == "surfshark") connect(a, req) else connectServer(a, req)
        }
        return START_STICKY
    }

    private fun enterForeground(text: String): Boolean = try {
        startForeground(NOTIFICATION, notification(text))
        true
    } catch (e: Exception) {
        Log.w(TAG, "foreground refused", e)
        false
    }

    /**
     * Out of the foreground and stopped, for a request that has ended. Under
     * [lifecycle] and only for the newest request, so it cannot take down a
     * connect that arrived after it; and with the request's own start id, so
     * Android does not stop a service it has already handed a newer start.
     */
    private fun leaveForeground(a: Attempt) {
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            Log.w(TAG, "leaving the foreground", e)
        }
        stopSelf(a.startId)
    }

    /**
     * Stop the Go work a connect may be blocked in, without waiting for it
     * and without the lock: Client.stop() makes a race return early, and
     * the connect that was waiting on it then finds it has been overtaken.
     * On the process scope, because onDestroy calls this as the service's
     * own scope is cancelled.
     */
    private fun interrupt() {
        val c = client
        val p = pending
        if (c == null && p == null) return
        process.launch {
            try { c?.stop() } catch (e: Exception) { Log.w(TAG, "stopping the race", e) }
            try { p?.stop() } catch (e: Exception) { Log.w(TAG, "stopping the search", e) }
        }
    }

    /** The platform's answers the core needs before anything dials. See the class comment. */
    private fun hooks() {
        Relay.setProtector(object : Protector {
            override fun protect(fd: Long): Boolean =
                this@RelayVpnService.protect(fd.toInt())
        })
        // Who opened each connection, for the log sheet. Handed over
        // beside the protector because both are the platform answering a
        // question the core cannot ask for itself. The same lookup as a
        // uid is what splitting by app asks, when it has to ask.
        val namer = AppNamer(applicationContext)
        Relay.setNamer(namer)
        Relay.setOwner(namer)
    }

    /**
     * The interface both ways out are carried on.
     *
     * Shared rather than written twice, because everything it decides -
     * which addresses go in, which app is left out, whether the LAN is
     * spared - is a property of the tunnel and not of what is at the far end
     * of it. Two copies would agree today and disagree the first time one is
     * changed.
     *
     * Handed back with it, the core's half of the split tunnel, which is
     * worked out here because it depends on what the interface was told:
     * the caller passes it on with [applySplit] before the tunnel starts.
     */
    private fun buildInterface(): Pair<Builder, CoreSplit> {
        val b = Builder()
            .setSession("Relay")
            .addAddress(Relay.tunAddress(), Relay.tunPrefix().toInt())
            .addDnsServer(Relay.tunDNS())
            .setMtu(Relay.tunMTU().toInt())
            // Non-blocking: sing-tun reads the descriptor itself and a
            // blocking read would park its goroutine on a thread Android is
            // entitled to reclaim.
            .setBlocking(false)

        // Whether the printer, the NAS, the router's page and adb-over-wifi
        // stay reachable. On by default: local addresses are not routable
        // beyond the house, so nothing leaves by being excluded, and a VPN
        // that silently breaks the LAN is a VPN people blame for the wrong
        // thing.
        val allowLan = Keys.prefs(this).getBoolean(Keys.ALLOW_LAN, true)

        if (!allowLan) {
            b.addRoute("0.0.0.0", 0)
        } else if (Build.VERSION.SDK_INT >= 33) {
            // Android 13 can subtract a route. Below that there is only
            // adding, which is what Routes.exceptPrivate is for.
            b.addRoute("0.0.0.0", 0)
            for (p in Routes.PRIVATE) {
                b.excludeRoute(android.net.IpPrefix(
                    java.net.InetAddress.getByName(p.addr), p.bits))
            }
        } else {
            for (p in Routes.exceptPrivate()) b.addRoute(p.addr, p.bits)
        }

        // Which apps the interface admits, and what the core does with what
        // it is handed - Settings → Split tunnel. This app leaving itself
        // out happens in there too, because one of the ways to split cannot
        // have it said by name.
        val split = splitInto(b)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) b.setMetered(false)
        return b to split
    }

    /**
     * What the core is told about the split tunnel. [mode] is what the core
     * does with what reaches it, and is not always the mode the setting
     * names: see [splitInto].
     */
    private class CoreSplit(
        val mode: SplitMode,
        val iran: Boolean,
        /** The user's domains, addresses and ranges, one a line, as saved. */
        val entries: String,
        /** The listed apps' uids, comma-joined, when the core is to ask whose a connection is. */
        val uids: String,
    )

    /**
     * Split tunnelling, decided half here and half in the core, because each
     * of them can do only half of it.
     *
     * The interface admits or leaves out an app by its name, and nothing
     * finer. The core sees every connection and where it is going, so it can
     * send a destination straight out - but only what the interface admitted
     * ever reaches it. So apps are settled here, and sites and ranges are the
     * core's to send round the tunnel:
     *
     *   Except these   the listed apps are left out of the interface, and
     *                  the core sends the listed destinations straight out.
     *
     *   Only these     apps alone: only they are let in, and the core carries
     *                  all of what arrives. Destinations alone: every app is
     *                  let in, and the core carries only what goes to them.
     *                  Both: every app is let in, and the core asks whose each
     *                  connection is (AppNamer, as relay.Owner) and carries
     *                  the listed apps' along with the listed destinations.
     *
     * Both at once needs that question answered, and Android only answers it
     * from 10 on. Below 10 the listed apps are let in and the destinations
     * are not acted on: an interface that admits only some apps cannot also
     * catch a site opened by any app. The window says so where it is chosen.
     *
     * Android throws when one interface is given apps to let in and apps to
     * leave out, so Only these with apps let in never leaves this app out by
     * name. It need not: an app that is not let in is out already.
     */
    private fun splitInto(b: Builder): CoreSplit {
        val prefs = Keys.prefs(this)
        val mode = SplitMode.of(prefs.getString(Keys.SPLIT_MODE, "off"))
        val iran = prefs.getBoolean(Keys.SPLIT_IRAN, true)
        val entries = prefs.getString(Keys.SPLIT_DOMAINS, "").orEmpty().trim()
        val hasDest = iran || entries.isNotEmpty()

        // This app must not route its own traffic. Without it, the socket to
        // the exit is protected but every other thing the process does - a
        // crash report, an update check - re-enters its own tunnel.
        fun leaveSelfOut() = b.addDisallowedApplication(packageName)
        fun told(m: SplitMode, uids: String = "") = CoreSplit(m, iran, entries, uids)

        if (mode == SplitMode.OFF) {
            leaveSelfOut()
            return told(SplitMode.OFF)
        }
        val apps = splitApps(prefs.getString(Keys.SPLIT_APPS, "").orEmpty(), iran)

        // Which of the cases above this is: decided in VpnLogic.splitPlan,
        // where it is tested, and carried out here. Under Only these with
        // nothing listed it is SELF_OUT and Off: a tunnel that carries
        // nothing is a tunnel that does nothing; carrying everything is the
        // safer way to be wrong, and the window warns before it comes to this.
        val plan = VpnLogic.splitPlan(mode, apps.isNotEmpty(), hasDest,
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        when (plan.apps) {
            VpnLogic.Apps.SELF_OUT -> {
                leaveSelfOut()
            }
            VpnLogic.Apps.LISTED_OUT -> {
                for (pkg in apps.keys) {
                    try {
                        b.addDisallowedApplication(pkg)
                    } catch (e: PackageManager.NameNotFoundException) {
                        // Uninstalled since it was looked up: nothing to leave out.
                    }
                }
                leaveSelfOut()
            }
            // Apps alone, or apps and destinations on a phone that cannot say
            // whose a connection is: the apps are let in, and whatever they
            // send is carried.
            VpnLogic.Apps.LISTED_IN -> {
                var admitted = 0
                for (pkg in apps.keys) {
                    try {
                        b.addAllowedApplication(pkg)
                        admitted++
                    } catch (e: PackageManager.NameNotFoundException) {
                        // As above.
                    }
                }
                // An interface with no app let in carries every app, this one
                // included. If every listed app went away meanwhile, that is
                // what this is, and this app leaves itself out as usual.
                if (admitted == 0) leaveSelfOut()
            }
        }
        return told(plan.core, if (plan.withUids) apps.values.distinct().joinToString(",") else "")
    }

    /**
     * The listed apps that are on this phone, with their uids: the user's
     * picks, and the core's list of Iranian apps while that preset is on.
     * Never this app - [splitInto] leaves it out, or not, itself.
     */
    private fun splitApps(picked: String, iran: Boolean): Map<String, Int> {
        val listed = LinkedHashSet<String>()
        picked.lines().mapTo(listed) { it.trim() }
        if (iran) Relay.splitIranApps().lines().mapTo(listed) { it.trim() }
        val found = LinkedHashMap<String, Int>()
        for (pkg in listed) {
            if (pkg.isEmpty() || pkg == packageName) continue
            try {
                found[pkg] = packageManager.getApplicationInfo(pkg, 0).uid
            } catch (e: PackageManager.NameNotFoundException) {
                // Not on this phone. Most of the Iranian list is not, on any
                // one phone, and an interface told to leave out an app that is
                // not there throws rather than shrugging.
            }
        }
        return found
    }

    /**
     * The DNS server of the network under the tunnel, for the names the split
     * tunnel sends straight out. An Iranian site has to be looked up the way
     * the line would look it up, or its CDN answers with a foreign address
     * and "straight out" goes the long way round. IPv4, because that is what
     * the core dials; empty when there is none, and the core falls back on
     * its own.
     *
     * The active network first when it is not a VPN - it is the one the apps
     * left out of the tunnel are using - and otherwise any network that
     * reaches the internet without being one.
     */
    private fun directDns(): String {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return ""
        fun underneath(n: Network): Boolean {
            val caps = cm.getNetworkCapabilities(n) ?: return false
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
        return try {
            val nets = ArrayList<Network>()
            val active = cm.activeNetwork
            if (active != null && underneath(active)) nets.add(active)
            for (n in cm.allNetworks) if (n != active && underneath(n)) nets.add(n)
            nets.firstNotNullOfOrNull { n ->
                cm.getLinkProperties(n)?.dnsServers?.firstOrNull { it is Inet4Address }?.hostAddress
            }.orEmpty()
        } catch (e: Exception) {
            Log.w(TAG, "finding the line's own DNS: ${e.message}")
            ""
        }
    }

    /**
     * The core's half of the split, handed over after the interface is built
     * and before the tunnel starts - the stack reads it as it comes up. False,
     * with the failure said, when the core refuses it: a tunnel that came up
     * routing other than it was told to would be worse than none.
     */
    private fun applySplit(a: Attempt, c: Client, s: CoreSplit): Boolean {
        val dns = if (s.mode == SplitMode.OFF) "" else directDns()
        Log.i(TAG, "split: ${s.mode.key}, iran ${s.iran}, " +
            "${s.entries.lines().count { it.isNotBlank() }} entries, " +
            "${s.uids.split(',').count { it.isNotBlank() }} apps by uid, " +
            "line DNS ${dns.ifEmpty { "unknown" }}")
        return try {
            c.setSplit(s.mode.key, s.iran, s.entries, s.uids, dns)
            true
        } catch (e: Exception) {
            fail(a, "the split tunnel settings were not taken: " + (e.message ?: e.toString()).take(140))
            false
        }
    }

    /**
     * Puts the server's Shadowsocks settings on [cfg] when that is the way in,
     * and says whether it is. The same settings RelayCore.importKey writes;
     * the password out of [Secrets], like every other one.
     */
    private fun applySs(cfg: relay.ServerConfig): Boolean {
        val prefs = Keys.prefs(this)
        if (prefs.getString(Keys.TUNNEL_TRANSPORT, "") != "ss") return false
        val secret = Keys.secret(this, Keys.TUNNEL_SS_PASSWORD)
        if (secret.isEmpty()) return false
        cfg.transport = "ss"
        cfg.ssMethod = prefs.getString(Keys.TUNNEL_SS_METHOD, "").orEmpty()
        cfg.ssPassword = secret
        cfg.ssPort = prefs.getInt(Keys.TUNNEL_SS_PORT, 0).toLong()
        cfg.ssExitPort = prefs.getInt(Keys.TUNNEL_SS_EXIT_PORT, 0).toLong()
        return true
    }

    /**
     * The way out through the user's own server.
     *
     * No race and no catalogue: there is one server, and what varies is which
     * path it is asked for - /gw exits at the server itself, /ex at a
     * provider node it picks, /gwb is /gw without multiplexing. The tun, the
     * stack and the DNS above are the same ones the provider path uses,
     * because the core is written not to know which is underneath.
     *
     * Everything about the server is read here, from the settings and from
     * [Secrets], rather than carried in the intent: a restart by the system
     * reaches the server the same way the window did, and no password
     * travels in an Intent.
     */
    private fun connectServer(a: Attempt, req: Request) {
        try {
            // A start at boot can get here before the window's first refresh
            // has moved anything into the secret store. Once done, a no-op.
            Keys.migrate(applicationContext)
            val mode = req.provider
            val prefs = Keys.prefs(this)
            val domain = prefs.getString(Keys.TUNNEL_DOMAIN, "").orEmpty()
            val password = Keys.secret(this, Keys.TUNNEL_PASSWORD)
            if (domain.isEmpty() || password.isEmpty()) {
                fail(a, "no server configured - set the domain and password in Settings")
                return
            }

            // Hooks and the Client together, and only if nothing newer has
            // been asked for: a Disconnect pressed while this waited for the
            // lock has already torn down, and must not find a protector set
            // again behind it.
            val c: Client = synchronized(lifecycle) {
                if (!a.current) return
                hooks()
                Relay.newClient("", "").also { client = it; live = it }
            }

            val cfg = Relay.newServerConfig()
            cfg.domain = domain
            cfg.password = password
            cfg.mode = mode
            cfg.edges = prefs.getString(Keys.TUNNEL_EDGES, "").orEmpty()
            cfg.pin = prefs.getString(Keys.TUNNEL_PIN, "").orEmpty()
            cfg.port = prefs.getInt(Keys.TUNNEL_PORT, 443).toLong()

            // Shadowsocks, when the server's key offered it and it is the
            // chosen way in: straight to the address, so there are no edges
            // to measure.
            val viaSs = applySs(cfg)

            // Measured here rather than carried over from the desktop: which
            // Cloudflare addresses are filtered is a fact about this line, and
            // the phone is not always on the same line the desktop was.
            if (!viaSs && cfg.edges.isBlank()) {
                say("finding a way in", a)
                cfg.edges = try {
                    Relay.scanEdges(domain, cfg.port, 30000L)
                } catch (e: Exception) {
                    // Not fatal. The name may still carry the tunnel on a line
                    // that is only lying about the addresses.
                    Log.w(TAG, "edge scan: ${e.message}")
                    ""
                }
                if (!a.current) return
                // Kept, so the next connect does not pay for it again. Five
                // seconds of TLS handshakes is not much once and is a great
                // deal every time - and the answer does not change between
                // one connect and the next on the same line.
                //
                // Written from the service rather than handed back to the
                // Activity because the Activity may not exist: the tunnel
                // outlives the window, which is the whole point of it being
                // a service.
                if (cfg.edges.isNotBlank()) {
                    prefs.edit().putString(Keys.TUNNEL_EDGES, cfg.edges).apply()
                }
            }

            if (!viaSs && cfg.edges.isBlank()) {
                fail(a, "no way in to $domain answered - check the domain, or try " +
                    "Find another way in under Settings")
                return
            }

            // Server + exit: the server is told which exit to leave by - the
            // picked location's first, with that exit's own provider's
            // account - before anything goes through it.
            var exitPlace = ""
            // The provider of the exit the server was left on, for the one
            // failure below that has a known cause: Surfshark from Iran.
            var exitVia = ""
            if (mode == "multi") {
                val surfshark = Accounts.credentials(applicationContext, Accounts.SURFSHARK)
                val windscribe = Accounts.credentials(applicationContext, Accounts.WINDSCRIBE)
                val apiPassword = Keys.secret(this, Keys.TUNNEL_API_PASSWORD)
                // Per server, on unless turned off for this one (Settings).
                val verify = Keys.verifyExit(this, domain)
                say("checking the server answers", a)

                // The pin before the API. A server by address met for the
                // first time used to be handed the API password - and every
                // provider credential the exit search sends - before anything
                // had checked its certificate; the core now refuses that
                // outright. So it is tested first, plainly, and its
                // certificate kept; ServerPin.call below handles the case
                // where the core still says there is none.
                try {
                    ServerPin.ensure(applicationContext, cfg)
                } catch (e: Exception) {
                    fail(a, "$domain did not answer: " + (e.message ?: e.toString()).take(140))
                    return
                }
                if (!a.current) return

                val pick = Relay.newClient(surfshark?.first.orEmpty(), surfshark?.second.orEmpty())
                try {
                    pending = pick
                    pick.load()
                    // From a server in Iran, upload to an exit can crawl -
                    // mostly when the exit is not checked (2026-11-12: about
                    // 10 KB/s without the exit's name, full speed with it),
                    // but one exit in seven stayed slow even checked. Each is
                    // set and asked to carry an upload, and the first that
                    // does is kept - the location stays the one picked.
                    //
                    // Every exit tried is written to the server's exit-chain,
                    // and Client.stop() does not interrupt that - so a connect
                    // that was overtaken would go on writing it, and could
                    // land its last write after the newer connect's. One
                    // search at a time ([serverChain]), each starting only if
                    // it is still the newest request: the newer one waits for
                    // the old search to end, and its writes come last.
                    val at = synchronized(serverChain) {
                        if (!a.current) return
                        say("finding an exit that uploads", a)
                        ServerPin.call(applicationContext, cfg) {
                            pick.bestServerExit(cfg, apiPassword, req.country.ifEmpty { "auto" },
                                surfshark?.first.orEmpty(), surfshark?.second.orEmpty(),
                                windscribe?.first.orEmpty(), windscribe?.second.orEmpty(),
                                verify, 6L, 4000L)
                        }
                    }
                    exitPlace = at.name + if (at.tookMs > 0) ", ${at.tookMs} KB/s up" else ", upload throttled"
                    exitVia = at.provider
                    // Nothing went up through any exit tried. Through Surfshark
                    // from a server in Iran the reason is known, and the
                    // connection's name says it rather than only "throttled".
                    if (at.tookMs <= 0 && surfsharkFromIran(a, domain, at.provider) != null) {
                        exitPlace = at.name + ", no upload - Surfshark is blocked from Iran"
                    }
                } catch (e: Exception) {
                    // Not the place for the Surfshark-from-Iran reason: this
                    // throws only when the server's API refuses, or there is
                    // no account or no exit to try - a dead exit measures 0
                    // and does not throw. The real error is the one to show.
                    fail(a, "$domain did not take the exit: " + (e.message ?: e.toString()).take(140))
                    return
                } finally {
                    if (pending === pick) pending = null
                }
                if (!a.current) return
            }

            // One real request through the server before the tunnel goes up.
            // Without it the interface came up for any domain at all - a
            // server that did not exist showed as connected, with every app's
            // traffic routed into nothing. A few seconds now buys a
            // "connected" that is true.
            say("checking the server answers", a)
            try {
                val seen = JSONObject(Relay.testServer(cfg, 20000L))
                if (!a.current) return
                // A server by address, met for the first time: its
                // certificate is kept, and required from then on.
                val seenPin = seen.optString("pin")
                if (cfg.pin.isEmpty() && seenPin.isNotEmpty()) {
                    cfg.pin = seenPin
                    prefs.edit().putString(Keys.TUNNEL_PIN, seenPin).apply()
                }
            } catch (e: Exception) {
                val why = "$domain did not carry a request: " + (e.message ?: e.toString()).take(140)
                // Through a Surfshark exit from a server in Iran, the likely
                // reason first, and the error itself after it.
                val known = if (mode == "multi") surfsharkFromIran(a, domain, exitVia) else null
                fail(a, if (known != null) "$known ($why)" else why)
                return
            }

            say("opening the tunnel to $domain", a)

            // From here to the end is one step under the lock: nothing newer
            // can arrive between the interface going up and the stack taking
            // it, and nothing newer is overtaken by it.
            synchronized(lifecycle) {
                if (!a.current) return
                val (builder, split) = buildInterface()
                if (!applySplit(a, c, split)) return

                val pfd = builder.establish()
                if (pfd == null) {
                    fail(a, "Android refused to open the interface")
                    return
                }
                val fd = pfd.detachFd()
                try {
                    c.startServerTunnel(fd.toLong(), Relay.tunMTU(), cfg)
                } catch (e: Exception) {
                    ParcelFileDescriptor.adoptFd(fd).close()
                    throw e
                }
                tunFd = fd

                exitAddress = domain
                exitFile = ""
                exitProvider = ""
                exitName = when (mode) {
                    "multi" -> "$domain (server + exit${if (exitPlace.isNotEmpty()) ", $exitPlace" else ""})"
                    "bulk" -> "$domain (bulk)"
                    else -> "$domain (your server)"
                }
                since = System.currentTimeMillis() / 1000
                connectedText = "connected through $exitName"
                say(connectedText)
                shareOnLan(c)
                watch(c) { redialServer(c) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "server connect failed", e)
            fail(a, e.message ?: e.toString())
        }
    }

    private fun connect(a: Attempt, req: Request) {
        try {
            // As in connectServer: the accounts may still be in their old files.
            Keys.migrate(applicationContext)
            // Both, when both are signed in. A pool holding one provider the
            // phone has no credential for still races the other half rather
            // than refusing outright - core.Logins.Keep decides that, and it
            // needs to have been told. Read here, out of Secrets, rather than
            // handed over in the intent.
            val surfshark = Accounts.credentials(applicationContext, Accounts.SURFSHARK)
            val windscribe = Accounts.credentials(applicationContext, Accounts.WINDSCRIBE)
            if (surfshark == null && windscribe == null) {
                fail(a, "no account - add one under Settings → Accounts")
                return
            }

            // Before anything dials, and only if this is still the request
            // being served. See the class comment.
            val c: Client = synchronized(lifecycle) {
                if (!a.current) return
                hooks()
                Relay.newClient(surfshark?.first.orEmpty(), surfshark?.second.orEmpty()).also {
                    it.setCredentials("windscribe", windscribe?.first.orEmpty(), windscribe?.second.orEmpty())
                    client = it
                    live = it
                }
            }

            // One scan rather than a file at a time: the folder is the truth,
            // and the same scan is what the window's country list came from -
            // so a country the page offers is a country the race can run in.
            c.scanFolder(req.configDir)
            // The configs behind the pick, as the window resolved them from
            // the row that was tapped - a country, a city, one provider's
            // share, one server, or Starred. Narrowed to exactly those, the
            // race takes the whole of what is left: asking the core to parse
            // the code again here is how a city or a single server came back
            // with nothing to race while a country connected.
            val narrowed = req.only.isNotBlank()
            if (narrowed) c.setOnly(req.only)
            if (c.count() == 0L) {
                fail(a, if (narrowed) "pick-gone" else "no pinned configs in ${req.configDir}")
                return
            }
            // A start with no list - an old intent, or Android restarting the
            // service on its own - still parses the code, as it always did.
            val pool = if (narrowed) "auto" else req.country

            if (!a.current) return
            total = c.count().toInt()
            say("racing ${c.count()} exits", a)
            val winner = race(c, pool) { a.current }
            // The long wait is over. Whatever was asked for meanwhile wins:
            // a Disconnect has stopped this race and torn down its Client,
            // and a newer Connect will have done the same.
            if (!a.current) return

            say("opening the tunnel via ${winner.name}", a)

            synchronized(lifecycle) {
                if (!a.current) return
                val (builder, split) = buildInterface()
                if (!applySplit(a, c, split)) return

                val pfd = builder.establish()
                if (pfd == null) {
                    fail(a, "Android refused to open the interface")
                    return
                }

                // Handed over for good. See the class comment: two owners for one
                // descriptor is what fdsan kills the process for.
                val fd = pfd.detachFd()
                try {
                    c.startTunnel(fd.toLong(), Relay.tunMTU(), winner.addr, winner.name,
                        winner.provider)
                } catch (e: Exception) {
                    // Nobody owns it now, so adopt it back only to close it.
                    ParcelFileDescriptor.adoptFd(fd).close()
                    throw e
                }
                tunFd = fd

                exitAddress = winner.addr
                exitName = winner.name
                exitFile = winner.file
                exitProvider = winner.provider
                tookMs = winner.tookMs.toInt()
                since = System.currentTimeMillis() / 1000
                connectedText = "connected via ${winner.name} (${winner.addr}), ${winner.tookMs} ms"
                say(connectedText)
                shareOnLan(c)
                // A replacement comes from the same pick: the narrowed list again.
                watch(c) { switchExit(c, pool) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "connect failed", e)
            fail(a, e.message ?: e.toString())
        }
    }

    /**
     * One race over the pick, with the window told how it is going.
     *
     * Long literals, not Int. gomobile maps Go's int to Java long, and Kotlin
     * does not widen an Int to fill a Long parameter.
     */
    private fun race(c: Client, country: String, stillWanted: () -> Boolean): relay.Result {
        // The race keeps reporting after it has a winner - the exits still in
        // flight answer late - and each report used to overwrite the status,
        // so a connected tunnel read "asked 8 of 353" in the notification and
        // the window. Only the race's own lifetime counts.
        //
        // And only while the race is still wanted: a race that a Disconnect
        // has stopped reports a few more answers on its way out, and each one
        // would put "asked 3 of 300" back over "not connected" - a window
        // left saying Connecting about a tunnel nobody is building. Checked
        // without the lifecycle lock, deliberately: this runs on the core's
        // own threads, and one of them blocked here while the lock's holder
        // waits for the race to stop would be a deadlock.
        val racing = java.util.concurrent.atomic.AtomicBoolean(true)
        return try {
            c.raceIn(country, 6000L, 8L, object : Progress {
                override fun onProgress(n: Long, of: Long) {
                    if (!racing.get() || !stillWanted()) return
                    asked = n.toInt()
                    total = of.toInt()
                    say("asked $n of $of")
                }
            })
        } finally {
            racing.set(false)
        }
    }

    // -- noticing a tunnel that has stopped carrying ---------------------------

    /**
     * Watch a tunnel that is up, and do something when its exit stops carrying.
     *
     * Without this, "connected" was only ever true of the moment the tunnel
     * came up. Nothing looked again. When the exit stopped answering later -
     * filtered after a while on the line, overloaded, taken down - every new
     * connection waited out its fifteen seconds in the core and failed, every
     * DNS query waited out its ten, the phone went slow and then dead, and the
     * notification went on saying "Connected" for as long as anyone cared to
     * read it.
     *
     * Two signals, one cheap and one certain. The core counts connections the
     * exit itself failed in a row ([Client.health]); that costs nothing and is
     * read every few seconds. A count that climbs, or a minute with the screen
     * on, sends one real request through the exit ([Client.check]) - which is
     * the only way to catch an exit that takes CONNECT and then forwards
     * nothing, or a phone that is idle and so failing nothing.
     *
     * Two checks failing in a row is a stall, and [recover] runs. It does not
     * take the interface down: the exit is replaced under the running stack,
     * so apps see slow connections for a moment rather than a network that
     * vanishes, and nothing leaves by the bare line meanwhile.
     */
    private fun watch(c: Client, recover: suspend () -> Boolean) {
        watcher?.cancel()
        val power = getSystemService(PowerManager::class.java)
        watcher = scope.launch {
            var lastCheck = SystemClock.elapsedRealtime()
            var failsAtCheck = 0L
            var misses = 0
            // After a recovery that found nothing, wait longer each time: a
            // phone with no signal at all fails every check, and racing every
            // half a minute until it gets one back is a battery for nothing.
            var backoff = 0L
            while (isActive && client === c) {
                // A check that just failed is asked again almost at once: it
                // already waited out its own timeout, and every second spent
                // here is a second every app on the phone is waiting too.
                delay(VpnLogic.watchDelay(misses, backoff))
                if (client !== c) break
                // Dozing, nothing on the phone has a network - this check
                // would fail and give up on a working exit for it.
                if (power?.isDeviceIdleMode == true) continue
                debugStall(c)
                val h = c.health() ?: break
                val now = SystemClock.elapsedRealtime()
                // Failures since the last check, not in all: a check that
                // passed does not reset the core's count, and an idle phone
                // would otherwise be checked every ten seconds after one bad
                // burst. A count that went down was reset by a new exit.
                failsAtCheck = VpnLogic.failsBaseline(h.fails, failsAtCheck)
                if (!VpnLogic.shouldCheck(h.fails, failsAtCheck, now - lastCheck,
                        power?.isInteractive != false, misses, stalled)) continue

                lastCheck = now
                failsAtCheck = h.fails
                val ok = try {
                    c.check(CHECK_TIMEOUT_MS)
                    true
                } catch (e: Exception) {
                    Log.w(TAG, "check through ${exitName.ifEmpty { exitAddress }} failed " +
                        "(${h.fails} in a row, last answer ${h.sinceOKMs / 1000}s ago): ${e.message}")
                    false
                }
                if (client !== c || !isActive) break

                if (ok) {
                    misses = 0
                    backoff = 0
                    if (stalled) {
                        // Back on its own - the line came back, or the
                        // exit did - without anything having been changed.
                        // Under the lock, so it is never said over a
                        // Disconnect that landed during the check.
                        synchronized(lifecycle) {
                            if (client === c) {
                                stalled = false
                                say(connectedText)
                            }
                        }
                    }
                    continue
                }
                misses++
                if (misses < MISSES_BEFORE_SWITCH) continue

                synchronized(lifecycle) {
                    if (client === c) stalled = true
                }
                if (client !== c) break
                val fixed = try {
                    recover()
                } catch (e: Exception) {
                    Log.w(TAG, "recovering the tunnel", e)
                    false
                }
                if (client !== c) break
                misses = 0
                lastCheck = SystemClock.elapsedRealtime()
                backoff = VpnLogic.nextBackoff(fixed, backoff)
            }
        }
    }

    /**
     * Debug builds only: an exit dying under a connected tunnel, on demand.
     *
     *     adb shell run-as com.erelay.relay.debug touch files/debug-stall
     *
     * points the running tunnel at an address that answers nothing, and tells
     * the window nothing - which is exactly what the real failure looks like
     * from the phone. The watchdog is then expected to notice and move on.
     * A flag file rather than an intent because the service is not exported,
     * and should not be for this.
     */
    private fun debugStall(c: Client) {
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        if (!java.io.File(filesDir, "debug-stall").delete()) return
        Log.w(TAG, "debug: pointing the tunnel at 192.0.2.1, which answers nothing")
        c.switchExit("192.0.2.1", exitName, exitProvider)
    }

    /**
     * A provider exit that stopped carrying, replaced by another from the same
     * pick. True when traffic is moving again.
     */
    private fun switchExit(c: Client, country: String): Boolean {
        val dead = exitAddress
        say("reconnecting: ${exitName.ifEmpty { dead }} stopped answering")
        // Out of this race and every one after it on this Client: it still
        // answers the race's quick question more often than not, which is how
        // it would win again and carry nothing again.
        c.avoid(dead)
        val winner = try {
            race(c, country) { client === c }
        } catch (e: Exception) {
            if (client === c) say("reconnecting: no other exit answered - " +
                (e.message ?: e.toString()).take(100))
            return false
        }
        // Under the lock, so a Disconnect cannot land between the check and
        // the switch and leave "connected via" said about a tunnel it has
        // already taken down.
        synchronized(lifecycle) {
            if (client !== c) return false
            c.switchExit(winner.addr, winner.name, winner.provider)
            Log.i(TAG, "switched from $dead to ${winner.addr} (${winner.name})")
            exitAddress = winner.addr
            exitName = winner.name
            exitFile = winner.file
            exitProvider = winner.provider
            tookMs = winner.tookMs.toInt()
            // Where it comes out is a fact about the old exit until asked again.
            seenCountry = ""
            since = System.currentTimeMillis() / 1000
            stalled = false
            connectedText = "connected via ${winner.name} (${winner.addr}), ${winner.tookMs} ms"
            say(connectedText)
        }
        return true
    }

    /**
     * The user's own server, stopped carrying. There is no other server to go
     * to, so the session is dropped and dialled again - a websocket that went
     * quiet is not closed until smux's keepalive gives up on it, and until
     * then every connection is opened on a corpse.
     */
    private fun redialServer(c: Client): Boolean {
        say("reconnecting: $exitAddress stopped answering")
        c.resetServer()
        try {
            c.check(CHECK_TIMEOUT_MS)
        } catch (e: Exception) {
            if (client === c) say("reconnecting: $exitAddress is not answering - " +
                (e.message ?: e.toString()).take(100))
            return false
        }
        synchronized(lifecycle) {
            if (client !== c) return false
            stalled = false
            say(connectedText)
        }
        return true
    }

    /**
     * Take down whatever this instance has up, and - when [gen] is still the
     * newest request - the shared state with it. Under [lifecycle] always.
     *
     * This instance's own Client is stopped either way: nothing else holds
     * it. The rest - the companion's exit and status, and the core's global
     * hooks - belongs to whichever request is newest. A teardown that has
     * been overtaken (onDestroy's, finishing after the next instance's
     * Connect has begun) leaves it alone; clearing the protector there is
     * what used to make the new tunnel's first dial fail with "could not be
     * kept out of the tunnel".
     *
     * [announce] false leaves the status line as it is, for a failure that
     * is about to say why itself.
     */
    private fun stopTunnel(gen: Long, announce: Boolean = true) {
        watcher?.cancel()
        watcher = null
        val c = client
        client = null
        // A race in flight - the first one, or one replacing a stalled exit -
        // ends here rather than finishing on its own and opening something
        // nobody asked for any more.
        try {
            c?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "stopping the race", e)
        }
        try {
            pending?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "stopping the search", e)
        }
        try {
            c?.stopTunnel()
        } catch (e: Exception) {
            Log.w(TAG, "stopping the tunnel", e)
        }
        if (c != null && live === c) live = null
        // Nothing closes the descriptor here. stopTunnel() above ends the
        // stack, and the stack owns it - which is the whole point of handing
        // it over with detachFd().
        tunFd = -1

        if (generation.get() != gen) return
        live = null
        stalled = false
        seenCountry = ""
        lanShareError = ""

        // Handed back, because it belongs to a service that is about to stop
        // being able to answer. VpnService.protect() on a dead service
        // returns false, and a false there is not a warning - core turns it
        // into a dial error, so every socket the window opens afterwards
        // fails with "the socket to the exit could not be kept out of the
        // tunnel" about a tunnel that is not there.
        //
        // The namer goes with it, in both its roles: it holds a Context and
        // has nothing to answer about once nothing is being carried.
        try {
            Relay.setProtector(null)
            Relay.setNamer(null)
            Relay.setOwner(null)
        } catch (e: Exception) {
            Log.w(TAG, "letting go of the platform hooks", e)
        }
        exitAddress = ""
        exitName = ""
        exitFile = ""
        exitProvider = ""
        // The counts too, which the tile and the window read as "a connect
        // is under way" while they are above zero. Left over from a race
        // that failed, they made the next tap on the tile a Disconnect.
        asked = 0
        total = 0
        tookMs = 0
        since = 0
        if (announce) {
            status = "not connected"
            sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName))
        }
    }

    /**
     * Gone - stopped, revoked, or killed for memory with the process staying.
     *
     * The teardown runs off the main thread, as Stop's does: closing the
     * stack closes every connection it carries, and doing that here froze
     * whatever the main thread was drawing for as long as it took. On the
     * process scope rather than this service's, which is cancelled two lines
     * down; and it always runs, so the stack - and with it the tun
     * descriptor it owns - is closed even though nothing waits for it.
     *
     * A failure keeps its words: the window is still reading them.
     */
    override fun onDestroy() {
        val gen = generation.incrementAndGet()
        wanted = false
        if (running === this) running = null
        interrupt()
        val announce = !status.startsWith("failed")
        process.launch {
            synchronized(lifecycle) { stopTunnel(gen, announce) }
        }
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Android tearing the VPN down from Settings, rather than the app - or
     * another VPN app taking over. VpnService's own onRevoke stops the
     * service, and the teardown is onDestroy's; this only takes the service
     * out of the foreground at once, so its notification does not linger
     * saying Connected about a tunnel Android has already taken away.
     */
    override fun onRevoke() {
        wanted = false
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            Log.w(TAG, "leaving the foreground", e)
        }
        super.onRevoke()
    }

    // -- saying what is happening --------------------------------------------

    @Volatile private var lastProgressAt = 0L

    /**
     * Said on behalf of [a], when given: only while it is still the newest
     * request. A connect that has been overtaken goes on talking until its
     * next check - "finding a way in", "checking the server answers" - and
     * each line would put a connect back on the screen after Disconnect.
     * Lock-free, like the race's progress, and for the same reason.
     */
    private fun say(text: String, a: Attempt? = null) {
        if (a != null && !a.current) return
        status = text
        // A race reports once per exit it asks - hundreds, in a second or two.
        // Each was a notification and a broadcast, and each broadcast redrew
        // the window: the stutter while connecting. The count is still kept
        // exact in `asked`; it is only told about a few times a second.
        if (text.startsWith("asked")) {
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastProgressAt < 200) return
            lastProgressAt = now
        }
        Log.i(TAG, text)
        notificationManager().notify(NOTIFICATION, notification(text))
        sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName))
    }

    /**
     * Share the tunnel on the Wi-Fi or hotspot, when Settings says to. It
     * stops with the tunnel (the core closes it), so there is no stop path
     * here; a failure is logged and the connection carries on without it.
     */
    private fun shareOnLan(c: Client) {
        val p = Keys.prefs(this)
        if (!p.getBoolean(Keys.LAN_SHARE, false)) return
        // A proxy on the Wi-Fi that anyone there can find is never opened
        // without a password. Settings will not turn the share on without
        // one; this is for a setting that got here some other way. The rule
        // is the core's (8 to 128 printable ASCII), checked before asking it.
        val password = Keys.secret(this, Keys.LAN_SHARE_PASSWORD)
        Keys.lanPasswordProblem(password)?.let { why ->
            lanShareError = why
            Log.w(TAG, "sharing on the local network: not started - $why")
            return
        }
        try {
            val said = c.startLANShare(p.getInt(Keys.LAN_SHARE_PORT, 8877).toLong(), password)
            lanShareError = ""
            Log.i(TAG, "sharing on the local network: $said")
        } catch (e: Exception) {
            // Kept for the share screen: "no Wi-Fi, hotspot or Ethernet
            // address" is something the person can fix, if told.
            lanShareError = e.message ?: e.toString()
            Log.w(TAG, "sharing on the local network: ${e.message}")
        }
    }

    /**
     * The failure text for server + exit through a Surfshark exit when the
     * server is in Iran, or null when that is not what happened - [provider]
     * is not Surfshark, the server is not at an Iranian address, or the core
     * could not tell. From a server in Iran every Surfshark cluster failed
     * TLS on 2026-11-12 ([Accounts.SURFSHARK_FROM_IRAN]), so that is the
     * reason to give before the error itself. Only asked on the way to a
     * failure, or when no exit tried carried an upload - both already off the
     * main thread; the attempt itself is never refused for it, because
     * blocks change.
     */
    private fun surfsharkFromIran(a: Attempt, domain: String, provider: String): String? {
        if (provider != Accounts.SURFSHARK || domain.isEmpty() || !a.current) return null
        // Settings has usually asked already (RelayCore.checkServerInIran);
        // its answer is taken as it is. Only without one is the core asked,
        // briefly, and an attempt overtaken meanwhile says nothing.
        val inIran = (application as? RelayApp)?.core?.knownServerInIran(domain) ?: try {
            Relay.serverInIran(domain, 3000L)
        } catch (e: Exception) {
            false
        }
        if (!a.current) return null
        return if (inIran) {
            "could not connect through the exit. ${Accounts.SURFSHARK_FROM_IRAN} " +
                "Use a Windscribe account for server + exit."
        } else {
            null
        }
    }

    /**
     * A connect that ended without a tunnel, said and finished.
     *
     * Only for the newest request: one that was overtaken failed because it
     * was stopped, and the request that stopped it is saying what is
     * happening now. What it opened is taken down, its counts with it -
     * the tile reads a count above zero as a connect still under way - and
     * the service leaves the foreground and stops. Before, it stayed: a
     * foreground service with an "ongoing" notification and nothing in it,
     * which Android then restarted with START_STICKY to fail again.
     *
     * Leaving does not open anything. There was no interface yet - every
     * failure here is before establish(), or a startTunnel that closed the
     * one it was given - so traffic is where it was: blocked, if the
     * always-on VPN's lockdown is on (Android keeps it so until a VPN comes
     * up), or on the bare line if it is not, as it was a moment ago.
     */
    private fun fail(a: Attempt, text: String) {
        synchronized(lifecycle) {
            if (!a.current) return
            wanted = false
            stopTunnel(a.gen, announce = false)
            val line = "failed: $text"
            status = line
            Log.i(TAG, line)
            sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName))
            leaveForeground(a)
            // Said once more where it can be read with the window closed - a
            // connect the always-on VPN started has no other way to say why
            // it is not up. Not ongoing: there is nothing running to stop.
            try {
                notificationManager().notify(NOTIFICATION, notification(line, ongoing = false))
            } catch (e: Exception) {
                Log.w(TAG, "failure notification", e)
            }
        }
    }

    /**
     * The status line, said for a person rather than a log: a title that is
     * the state, and the detail under it. The log keeps the raw line.
     */
    private fun spoken(text: String): Pair<String, String> {
        val place = when {
            seenCountry.isNotEmpty() -> com.erelay.relay.ui.countryName(seenCountry)
            exitProvider.isNotEmpty() -> com.erelay.relay.ui.countryName(exitName.take(2))
            exitName.contains("server + exit") -> "your server's exit"
            exitAddress.isNotEmpty() -> "your server"
            else -> ""
        }
        return when {
            text.startsWith("connected") -> "Connected to ${place.ifEmpty { "Relay" }}" to
                (if (exitProvider.isNotEmpty()) exitName else exitAddress)
            text.startsWith("reconnecting") -> "Reconnecting…" to
                text.removePrefix("reconnecting: ").replaceFirstChar { it.uppercase() }
            text.startsWith("failed") -> "Could not connect" to
                text.removePrefix("failed: ").replaceFirstChar { it.uppercase() }
            text.startsWith("asked") -> "Connecting…" to "Asked ${asked} of $total exits"
            text.startsWith("racing") -> "Connecting…" to text.replaceFirstChar { it.uppercase() }
            text.startsWith("opening") || text.startsWith("finding") ||
                text.startsWith("checking") -> "Connecting…" to text.replaceFirstChar { it.uppercase() }
            else -> "Relay" to text.replaceFirstChar { it.uppercase() }
        }
    }

    private fun notificationManager() =
        getSystemService(NotificationManager::class.java)

    private fun notification(text: String, ongoing: Boolean = true): android.app.Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager().createNotificationChannel(
                NotificationChannel(CHANNEL, "Tunnel", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        // Disconnect from the shade, without opening anything.
        val stop = PendingIntent.getService(
            this, 1, Intent(this, RelayVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        // NotificationCompat rather than Notification.Builder: the
        // channel-taking constructor is API 26 and minSdk here is 24, and the
        // compat one does the right thing on both without a version branch.
        val (title, body) = spoken(text)
        // Connected, the header counts up how long for: the platform's own
        // chronometer, ticking without a notification update every second.
        val up = text.startsWith("connected") && since > 0
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle(title)
            .setContentText(body)
            .setShowWhen(up)
            .setUsesChronometer(up)
            .setWhen(if (up) since * 1000 else System.currentTimeMillis())
            .setSmallIcon(R.drawable.ic_relay)
            .setContentIntent(open)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        // Disconnect only while there is something to disconnect.
        if (ongoing) b.addAction(0, "Disconnect", stop)
        return b.build()
    }
}
