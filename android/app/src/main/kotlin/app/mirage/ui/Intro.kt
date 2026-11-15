package app.mirage.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** One step: a statement, and two or three full lines under it. */
private class Slide(val title: String, val say: String)

/* The Windows intro's four steps (preview/DECISIONS.md), said for a phone. */
private val SLIDES = listOf(
    Slide("Choose where your traffic comes out.",
        "Mirage connects this phone to a VPN server and checks, independently, which country websites see you in."),
    Slide("The first exit that answers.",
        "Mirage asks dozens of your provider's servers at once and connects through whichever replies first."),
    Slide("Real addresses only.",
        "This network's DNS can return forged answers. Mirage looks each server up over DNS-over-HTTPS."),
    Slide("Or a server you rent.",
        "Mirage can carry traffic through your own server, and lets named apps and sites skip the tunnel."),
)

/**
 * What Mirage is, in four steps, the first time it opens. The eye, the name
 * and the button stay where they are; only the words change, carved out and
 * cut in ([CarvedStage]). Next, or a swipe, goes on; Skip goes straight to
 * setting up.
 *
 * The eye wakes once: the upper lid opens from its middle out as the lower
 * one shows, the ring of degrees turns into place while the bead runs round
 * to its own, and the pupil lands with a little jump. After that the ring
 * turns slowly and the name's reflection shivers; the star chart behind
 * makes one turn in four minutes.
 */
@Composable
fun Intro(paused: Boolean, onDone: () -> Unit) {
    val reduced = rememberReducedMotion()
    val moving = !reduced && !paused
    var page by rememberSaveable { mutableIntStateOf(0) }
    val last = page == SLIDES.lastIndex
    val short = shortScreen()

    val anchor = remember { ChartAnchor() }
    val chart = rememberTurn(1.5f, moving)
    val slow = rememberTurn(4f, moving)

    val open = remember { Animatable(if (reduced) 1f else 0f) }
    val pupil = remember { Animatable(if (reduced) 1f else 0f) }
    val beadIn = remember { Animatable(if (reduced) 0f else -360f) }
    val ringIn = remember { Animatable(if (reduced) 0f else -60f) }
    LaunchedEffect(Unit) {
        if (reduced) return@LaunchedEffect
        launch { open.animateTo(1f, tween(700, delayMillis = 150, easing = FastOutSlowInEasing)) }
        launch { ringIn.animateTo(0f, tween(1100, delayMillis = 150, easing = FastOutSlowInEasing)) }
        launch { beadIn.animateTo(0f, tween(1300, delayMillis = 200, easing = FastOutSlowInEasing)) }
        launch {
            delay(650)
            pupil.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 380f))
        }
    }

    MiPage(anchor, chart) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
                .pointerInput(Unit) {
                    var travel = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { travel = 0f },
                        onHorizontalDrag = { _, dx -> travel += dx },
                        onDragEnd = {
                            val far = 56.dp.toPx()
                            if (travel < -far && page < SLIDES.lastIndex) page += 1
                            else if (travel > far && page > 0) page -= 1
                        },
                    )
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth().height(48.dp), horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically) {
                if (!last) MiTextButton("Skip", onDone)
            }
            Spacer(Modifier.weight(0.6f))
            MirageEye(
                Modifier.size(if (short) 118.dp else 150.dp).then(anchor.mark()),
                open = { open.value },
                ring = { ringIn.value + slow.value },
                bead = { beadIn.value },
                pupil = { pupil.value },
            )
            MirageWord(if (short) 24.sp else 30.sp, shimmer = moving)
            Spacer(Modifier.weight(1f))
            CarvedStage(SLIDES[page].title, SLIDES[page].say, Modifier.widthIn(max = 360.dp))
            Spacer(Modifier.height(14.dp))
            StepTicks(SLIDES.size, page)
            Spacer(Modifier.height(18.dp))
            OrbitButton(
                if (last) "Get started" else "Next",
                { if (last) { onDone() } else { page += 1 } },
                Modifier.fillMaxWidth().widthIn(max = 360.dp),
                paused = paused,
            )
            Spacer(Modifier.height(if (short) 16.dp else 28.dp))
        }
    }
}
