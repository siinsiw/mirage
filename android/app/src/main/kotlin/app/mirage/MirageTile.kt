package app.mirage

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import app.mirage.ui.countryName

/**
 * Mirage in the quick-settings panel: one press to connect, one to stop.
 *
 * It connects to whatever the window last picked, through whichever way out
 * the window has chosen - the same call Connect makes, so the tile and the
 * button can never disagree about what connecting means. What the tile
 * cannot do on its own it hands to the window: Android's VPN consent and a
 * missing account are both questions only an Activity can ask.
 */
class MirageTile : TileService() {

    private val status = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = paint()
    }
    private var listening = false

    override fun onStartListening() {
        super.onStartListening()
        ContextCompat.registerReceiver(this, status, IntentFilter(MirageVpnService.ACTION_STATUS),
            ContextCompat.RECEIVER_NOT_EXPORTED)
        listening = true
        paint()
    }

    override fun onStopListening() {
        if (listening) unregisterReceiver(status)
        listening = false
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val core = (application as MirageApp).core
        val up = MirageVpnService.exitAddress.isNotEmpty()
        // A failure is not a connect under way, whatever the counts say.
        val busy = !up && MirageVpnService.total > 0 &&
            !MirageVpnService.status.startsWith("failed")
        if (up || busy) {
            core.disconnect()
            return
        }
        if (VpnService.prepare(this) != null) {
            openWindow()
            return
        }
        // Whether there is anything to connect with, and which configs are
        // behind the pick, are files and the secret store - off the main
        // thread, which this is. The answer comes back on it.
        core.connectThen(core.picked.value) { said ->
            if (!said.ok) openWindow()
            paint()
        }
    }

    private fun openWindow() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        // Asked from connectThen's answer as well as from the tap, by which
        // time the panel may have closed and the tile been let go of. Android
        // may then refuse, and the window simply does not open: the tile
        // still shows the failure.
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        } catch (e: Exception) {
            android.util.Log.w(MirageVpnService.TAG, "tile: window not opened", e)
        }
    }

    /** The tile from the service's companion: lit while up, a word under the name. */
    private fun paint() {
        val tile = qsTile ?: return
        val up = MirageVpnService.exitAddress.isNotEmpty()
        val note = MirageVpnService.status
        val working = !up && !note.startsWith("failed") && MirageVpnService.total > 0
        // A tap that is still being looked into (connectThen) paints as off
        // until the service says otherwise; the broadcast repaints it.
        tile.state = when {
            up -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        tile.label = "Mirage"
        tile.icon = Icon.createWithResource(this, R.drawable.ic_mirage)
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = when {
                up && MirageVpnService.stalled -> "Reconnecting…"
                up -> MirageVpnService.exitName.take(2).let { cc ->
                    countryName(cc).ifEmpty { "Connected" }.takeIf { MirageVpnService.exitProvider.isNotEmpty() }
                        ?: "Your server"
                }
                working -> "Connecting…"
                note.startsWith("failed") -> "Could not connect"
                else -> "Off"
            }
        }
        tile.updateTile()
    }
}
