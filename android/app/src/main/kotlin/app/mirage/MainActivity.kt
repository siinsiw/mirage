package app.mirage

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.graphics.toArgb
import app.mirage.ui.Root
import app.mirage.ui.theme.MirageTheme
import app.mirage.ui.theme.Palette
import app.mirage.ui.theme.Tone

/**
 * The window. Everything it shows is Compose; everything it knows comes from
 * [MirageApp.core]. The tunnel is not this Activity's - closing the window is
 * not disconnecting.
 */
class MainActivity : ComponentActivity() {

    private var barsFor: Palette? = null

    private fun paintBars() {
        if (barsFor == Tone.palette) return
        barsFor = Tone.palette
        val bg = Tone.bg.toArgb()
        if (Tone.light) {
            enableEdgeToEdge(
                statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT,
                    android.graphics.Color.TRANSPARENT),
                navigationBarStyle = SystemBarStyle.light(bg, bg),
            )
        } else {
            enableEdgeToEdge(
                statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
                navigationBarStyle = SystemBarStyle.dark(bg),
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val core = (application as MirageApp).core
        // Before the first frame, so a Black or Graphite theme does not open
        // on one frame of Midnight - and before the bars take their colour.
        Tone.palette = Palette.of(core.feel.value.theme)
        paintBars()
        setContent {
            // The bars and the window behind Compose follow the theme as it
            // changes: dark icons on a light page, and no dark flash under it.
            // Read here so a change of theme recomposes this and repaints them.
            val palette = Tone.palette
            androidx.compose.runtime.SideEffect {
                paintBars()
                window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Tone.bg.toArgb()))
            }
            MirageTheme {
                Root(core)
            }
        }
    }
}
