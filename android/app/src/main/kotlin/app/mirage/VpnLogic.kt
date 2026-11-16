package app.mirage

import app.mirage.data.SplitMode

/**
 * The decisions MirageVpnService makes that need no Android to make them, kept
 * apart so they can be tested on a JVM: which apps the interface admits and
 * what the core is told for each split-tunnel setting, and the watchdog's
 * arithmetic. The service does the doing; the reasons for each rule are in
 * its own comments (splitInto, watch) and are not repeated here.
 */
internal object VpnLogic {

    // -- split tunnel -----------------------------------------------------------

    /** What the interface is told about apps. */
    enum class Apps {
        /** Every app is let in but this one. */
        SELF_OUT,
        /** The listed apps are left out, and this one with them. */
        LISTED_OUT,
        /** Only the listed apps are let in (this app only if none of them is still installed). */
        LISTED_IN,
    }

    /**
     * One split-tunnel decision: what the interface does with apps, the mode
     * the core is told - not always the one the setting names - whether the
     * core is handed the listed apps' uids to ask whose a connection is, and
     * whether the core is told the Iran preset.
     *
     * The core reads the Iran preset by its mode: under Off and Except these
     * it is "Iran direct", Iran straight out; under Only these it is Iran
     * listed, through the exit. So where Only these ends up telling the core
     * Off, it is not told the preset at all - Off with the preset would turn
     * it the other way round.
     */
    data class SplitPlan(
        val apps: Apps,
        val core: SplitMode,
        val withUids: Boolean = false,
        val iran: Boolean = false,
    )

    /**
     * @param mode the setting
     * @param iran whether the Iran preset is on ("Iran direct" outside Only these)
     * @param hasApps whether any listed app is on this phone - under Off, only the Iranian ones count
     * @param hasDest whether there are destinations: the Iran preset, or the user's own entries
     * @param canAskOwner whether Android will say whose a connection is (10 and later)
     */
    fun splitPlan(
        mode: SplitMode, iran: Boolean, hasApps: Boolean, hasDest: Boolean, canAskOwner: Boolean,
    ): SplitPlan = when {
        // Off with Iran direct is Except these with Iran alone: the Iranian
        // apps on the phone are left out, and the core sends Iran straight.
        mode == SplitMode.OFF ->
            if (iran) SplitPlan(Apps.LISTED_OUT, SplitMode.OFF, iran = true)
            else SplitPlan(Apps.SELF_OUT, SplitMode.OFF)
        mode == SplitMode.BYPASS ->
            if (hasDest) SplitPlan(Apps.LISTED_OUT, SplitMode.BYPASS, iran = iran)
            else SplitPlan(Apps.LISTED_OUT, SplitMode.OFF)
        // Only these.
        !hasApps && !hasDest -> SplitPlan(Apps.SELF_OUT, SplitMode.OFF)
        !hasApps -> SplitPlan(Apps.SELF_OUT, SplitMode.ONLY, iran = iran)
        hasDest && canAskOwner -> SplitPlan(Apps.SELF_OUT, SplitMode.ONLY, withUids = true, iran = iran)
        else -> SplitPlan(Apps.LISTED_IN, SplitMode.OFF)
    }

    // -- the watchdog -------------------------------------------------------------

    // How the watchdog paces itself. Counts are read every few seconds and
    // cost nothing; a real request through the exit is sent once a minute
    // while somebody is looking at the phone, and at once when the counts
    // say apps are failing.
    const val WATCH_EVERY_MS = 10_000L
    const val CHECK_EVERY_MS = 60_000L
    const val RECHECK_AFTER_MS = 2_000L
    // Connections in a row the exit failed before it is asked directly.
    const val FAILS_BEFORE_CHECK = 2
    // After a recovery that found nothing: at least this long, and never longer than that.
    const val BACKOFF_MIN_MS = 20_000L
    const val BACKOFF_MAX_MS = 120_000L

    /** How long to sleep before the next look: soon after a failed check, otherwise the usual pace plus [backoff]. */
    fun watchDelay(misses: Int, backoff: Long): Long =
        if (misses > 0) RECHECK_AFTER_MS else WATCH_EVERY_MS + backoff

    /** The count failures are measured from: a count that went down was reset by a new exit, and starts at nothing. */
    fun failsBaseline(fails: Long, failsAtCheck: Long): Long = if (fails < failsAtCheck) 0 else failsAtCheck

    /**
     * Whether a real request is sent through the exit this time round.
     *
     * @param fails connections in a row the core says failed
     * @param failsAtCheck that count at the last check, after [failsBaseline]
     * @param sinceCheckMs how long ago the last check was
     * @param interactive whether the screen is on (true when that cannot be told)
     * @param misses checks failed in a row so far
     * @param stalled whether the tunnel is already marked as stalled
     */
    fun shouldCheck(
        fails: Long, failsAtCheck: Long, sinceCheckMs: Long,
        interactive: Boolean, misses: Int, stalled: Boolean,
    ): Boolean {
        val failing = fails >= FAILS_BEFORE_CHECK && fails > failsAtCheck
        val due = sinceCheckMs >= CHECK_EVERY_MS && interactive
        return failing || due || misses > 0 || stalled
    }

    /** The extra wait after a recovery: none when it worked, doubling between the two bounds when it did not. */
    fun nextBackoff(fixed: Boolean, backoff: Long): Long =
        if (fixed) 0 else (backoff * 2).coerceIn(BACKOFF_MIN_MS, BACKOFF_MAX_MS)
}
