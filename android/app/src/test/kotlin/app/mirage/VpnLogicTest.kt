package app.mirage

import app.mirage.VpnLogic.Apps
import app.mirage.VpnLogic.SplitPlan
import app.mirage.data.SplitMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What MirageVpnService decides without Android: the split tunnel's cases, as
 * the comment on splitInto lists them, and the watchdog's arithmetic. A
 * changed rule fails here rather than as traffic leaving by the wrong road.
 */
class VpnLogicTest {

    private fun plan(mode: SplitMode, apps: Boolean, dest: Boolean, owner: Boolean = true, iran: Boolean = false) =
        VpnLogic.splitPlan(mode, iran = iran, hasApps = apps, hasDest = dest || iran, canAskOwner = owner)

    @Test
    fun offWithoutIranDirectCarriesEverythingButThisApp() {
        for (apps in listOf(false, true)) for (dest in listOf(false, true)) for (owner in listOf(false, true)) {
            assertEquals(SplitPlan(Apps.SELF_OUT, SplitMode.OFF), plan(SplitMode.OFF, apps, dest, owner))
        }
    }

    @Test
    fun offWithIranDirectLeavesIranianAppsOutAndTellsTheCoreIran() {
        // The default: Off with Iran direct on. Iranian apps on the phone are
        // left out, and the core is told Off with the preset - Iran straight.
        for (apps in listOf(false, true)) for (dest in listOf(false, true)) for (owner in listOf(false, true)) {
            assertEquals(SplitPlan(Apps.LISTED_OUT, SplitMode.OFF, iran = true),
                plan(SplitMode.OFF, apps, dest, owner, iran = true))
        }
    }

    @Test
    fun theCoreHearsOfIranOnlyWhereItsModeReadsItRightWayRound() {
        // Except these with the preset: Iran straight, along with the person's list.
        assertEquals(SplitPlan(Apps.LISTED_OUT, SplitMode.BYPASS, iran = true),
            plan(SplitMode.BYPASS, apps = false, dest = false, iran = true))
        // Only these with the preset, told Only: Iran is listed, through the exit.
        assertEquals(SplitPlan(Apps.SELF_OUT, SplitMode.ONLY, iran = true),
            plan(SplitMode.ONLY, apps = false, dest = false, iran = true))
        assertEquals(SplitPlan(Apps.SELF_OUT, SplitMode.ONLY, withUids = true, iran = true),
            plan(SplitMode.ONLY, apps = true, dest = false, iran = true))
        // Only these told Off (apps let in on a phone below 10): the preset is
        // withheld, because Off with it would send Iran straight - the
        // opposite of what Only these means by it.
        assertEquals(SplitPlan(Apps.LISTED_IN, SplitMode.OFF),
            plan(SplitMode.ONLY, apps = true, dest = false, owner = false, iran = true))
        // Without the preset, nobody is told of it.
        for (mode in SplitMode.entries) for (apps in listOf(false, true)) for (dest in listOf(false, true)) {
            for (owner in listOf(false, true)) {
                assertFalse("$mode apps=$apps dest=$dest owner=$owner", plan(mode, apps, dest, owner).iran)
            }
        }
    }

    @Test
    fun exceptTheseLeavesTheAppsOutAndTellsTheCoreOnlyOfDestinations() {
        // The core has something to send straight out only when there are destinations.
        assertEquals(SplitPlan(Apps.LISTED_OUT, SplitMode.BYPASS), plan(SplitMode.BYPASS, apps = true, dest = true))
        assertEquals(SplitPlan(Apps.LISTED_OUT, SplitMode.BYPASS), plan(SplitMode.BYPASS, apps = false, dest = true))
        assertEquals(SplitPlan(Apps.LISTED_OUT, SplitMode.OFF), plan(SplitMode.BYPASS, apps = true, dest = false))
        assertEquals(SplitPlan(Apps.LISTED_OUT, SplitMode.OFF), plan(SplitMode.BYPASS, apps = false, dest = false))
        // Whether Android names a connection's owner changes nothing here.
        assertEquals(plan(SplitMode.BYPASS, apps = true, dest = true, owner = true),
            plan(SplitMode.BYPASS, apps = true, dest = true, owner = false))
    }

    @Test
    fun onlyTheseWithNothingListedCarriesEverything() {
        assertEquals(SplitPlan(Apps.SELF_OUT, SplitMode.OFF), plan(SplitMode.ONLY, apps = false, dest = false))
        assertEquals(SplitPlan(Apps.SELF_OUT, SplitMode.OFF),
            plan(SplitMode.ONLY, apps = false, dest = false, owner = false))
    }

    @Test
    fun onlyTheseWithDestinationsAloneLetsEveryAppInAndTheCoreChooses() {
        assertEquals(SplitPlan(Apps.SELF_OUT, SplitMode.ONLY), plan(SplitMode.ONLY, apps = false, dest = true))
        assertEquals(SplitPlan(Apps.SELF_OUT, SplitMode.ONLY),
            plan(SplitMode.ONLY, apps = false, dest = true, owner = false))
    }

    @Test
    fun onlyTheseWithAppsAloneLetsOnlyThemIn() {
        // Only they reach the core, so the core carries all of what arrives.
        assertEquals(SplitPlan(Apps.LISTED_IN, SplitMode.OFF), plan(SplitMode.ONLY, apps = true, dest = false))
        assertEquals(SplitPlan(Apps.LISTED_IN, SplitMode.OFF),
            plan(SplitMode.ONLY, apps = true, dest = false, owner = false))
    }

    @Test
    fun onlyTheseWithBothAsksWhoseEachConnectionIs() {
        val both = plan(SplitMode.ONLY, apps = true, dest = true, owner = true)
        assertEquals(SplitPlan(Apps.SELF_OUT, SplitMode.ONLY, withUids = true), both)
        assertTrue(both.withUids)
        // Below Android 10 nobody answers that: the apps are let in and the destinations are not acted on.
        assertEquals(SplitPlan(Apps.LISTED_IN, SplitMode.OFF),
            plan(SplitMode.ONLY, apps = true, dest = true, owner = false))
    }

    @Test
    fun uidsGoToTheCoreInThatOneCaseOnly() {
        for (mode in SplitMode.entries) for (apps in listOf(false, true)) for (dest in listOf(false, true)) {
            for (owner in listOf(false, true)) {
                val want = mode == SplitMode.ONLY && apps && dest && owner
                assertEquals("$mode apps=$apps dest=$dest owner=$owner", want, plan(mode, apps, dest, owner).withUids)
            }
        }
    }

    @Test
    fun appsAreNeverBothLetInAndLeftOut() {
        // Android throws when one interface is given both, so a plan is one or the other.
        for (mode in SplitMode.entries) for (apps in listOf(false, true)) for (dest in listOf(false, true)) {
            val p = plan(mode, apps, dest)
            if (p.apps == Apps.LISTED_IN) {
                assertEquals(SplitMode.ONLY, mode)
                assertTrue(apps)
            }
        }
    }

    // -- the watchdog -------------------------------------------------------------

    @Test
    fun theWatchdogLooksEveryTenSecondsAndSoonAfterAMiss() {
        assertEquals(10_000L, VpnLogic.watchDelay(misses = 0, backoff = 0))
        assertEquals(30_000L, VpnLogic.watchDelay(misses = 0, backoff = 20_000))
        assertEquals(2_000L, VpnLogic.watchDelay(misses = 1, backoff = 0))
        // A check that just failed is asked again at once, whatever the backoff.
        assertEquals(2_000L, VpnLogic.watchDelay(misses = 1, backoff = 120_000))
    }

    @Test
    fun aCountThatWentDownStartsAgainFromNothing() {
        assertEquals(0L, VpnLogic.failsBaseline(fails = 1, failsAtCheck = 5))
        assertEquals(5L, VpnLogic.failsBaseline(fails = 5, failsAtCheck = 5))
        assertEquals(5L, VpnLogic.failsBaseline(fails = 9, failsAtCheck = 5))
        assertEquals(0L, VpnLogic.failsBaseline(fails = 0, failsAtCheck = 0))
    }

    private fun check(
        fails: Long = 0, at: Long = 0, since: Long = 0,
        interactive: Boolean = true, misses: Int = 0, stalled: Boolean = false,
    ) = VpnLogic.shouldCheck(fails, at, since, interactive, misses, stalled)

    @Test
    fun aQuietTunnelIsNotChecked() {
        assertFalse(check())
        assertFalse(check(fails = 1))
        assertFalse(check(since = 59_999))
    }

    @Test
    fun failuresSinceTheLastCheckAskForOne() {
        assertTrue(check(fails = 2))
        assertTrue(check(fails = 3, at = 2))
        // The same failures as last time are not a reason to ask again.
        assertFalse(check(fails = 2, at = 2))
        assertFalse(check(fails = 5, at = 5))
        // One failure is not yet a pattern.
        assertFalse(check(fails = 1, at = 0))
    }

    @Test
    fun aMinuteWithTheScreenOnAsksForOne() {
        assertTrue(check(since = 60_000))
        assertTrue(check(since = 600_000))
        // Screen off: an idle phone is not checked on the clock alone.
        assertFalse(check(since = 600_000, interactive = false))
        // But failures still are, screen off or not.
        assertTrue(check(fails = 2, interactive = false))
    }

    @Test
    fun aMissOrAStallKeepsChecking() {
        assertTrue(check(misses = 1))
        assertTrue(check(stalled = true))
        assertTrue(check(misses = 1, interactive = false))
        assertTrue(check(stalled = true, interactive = false))
    }

    @Test
    fun backoffDoublesBetweenTwentySecondsAndTwoMinutes() {
        assertEquals(0L, VpnLogic.nextBackoff(fixed = true, backoff = 80_000))
        assertEquals(20_000L, VpnLogic.nextBackoff(fixed = false, backoff = 0))
        assertEquals(40_000L, VpnLogic.nextBackoff(fixed = false, backoff = 20_000))
        assertEquals(80_000L, VpnLogic.nextBackoff(fixed = false, backoff = 40_000))
        assertEquals(120_000L, VpnLogic.nextBackoff(fixed = false, backoff = 80_000))
        assertEquals(120_000L, VpnLogic.nextBackoff(fixed = false, backoff = 120_000))
    }
}
