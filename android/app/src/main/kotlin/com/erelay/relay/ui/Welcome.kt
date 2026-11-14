package com.erelay.relay.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.erelay.relay.Accounts
import com.erelay.relay.data.PinRun
import com.erelay.relay.data.Setup
import com.erelay.relay.ui.theme.Mirage
import com.erelay.relay.ui.theme.Sans
import kotlinx.coroutines.delay

/**
 * The way in for a phone with nothing to connect with (preview/DECISIONS.md,
 * the welcome): the small eye with its name, the star chart turning round it,
 * one question, and three answers on black stone. There is no "not now": an
 * app with no account and no server of its own has nothing to do. Each
 * answer goes straight to the page that sets it up.
 */
@Composable
fun Welcome(
    paused: Boolean,
    onProvider: (String) -> Unit,
    onServer: () -> Unit,
    /** The Configs section: links or a subscription bought from anywhere. */
    onConfigs: () -> Unit = {},
) {
    val reduced = rememberReducedMotion()
    val moving = !reduced && !paused
    val short = shortScreen()
    val anchor = remember { ChartAnchor() }
    val chart = rememberTurn(1.5f, moving)
    val ring = rememberTurn(6f, moving)
    // The eye arrives from a little bigger and lower, settles, and blinks once.
    val settle = remember { Animatable(if (reduced) 1f else 0f) }
    val blink = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        if (reduced) return@LaunchedEffect
        settle.animateTo(1f, tween(700, easing = FastOutSlowInEasing))
        delay(140)
        blink.animateTo(0.1f, tween(110))
        blink.animateTo(1f, tween(220))
    }

    MiPage(anchor, chart, chart = 520.dp) {
        Column(
            Modifier
                .fillMaxSize()
                // A tablet gets the phone's column, in the middle.
                .wrapContentWidth()
                .widthIn(max = CONTENT_MAX)
                .fillMaxWidth()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(if (short) 24.dp else 48.dp))
            MirageEye(
                Modifier
                    .size(64.dp)
                    .then(anchor.mark())
                    .graphicsLayer {
                        val p = settle.value
                        val s = 1.18f - 0.18f * p
                        scaleX = s
                        scaleY = s * blink.value
                        translationY = (1f - p) * 14.dp.toPx()
                        alpha = p
                    },
                ring = { ring.value },
            )
            Spacer(Modifier.height(4.dp))
            MirageWord(19.sp, shimmer = moving)
            Spacer(Modifier.height(if (short) 10.dp else 18.dp))
            Rise(220) { MiTitle("Connect with an account or a server.") }
            Spacer(Modifier.height(8.dp))
            Rise(300) {
                MiSay("Mirage needs one of these before it can carry traffic. You can add more later in Settings.",
                    Modifier.widthIn(max = 320.dp))
            }
            Spacer(Modifier.height(22.dp))
            Column(Modifier.widthIn(max = 360.dp).fillMaxWidth()) {
                Rise(400) { MiLabel("VPN account") }
                Spacer(Modifier.height(8.dp))
                Rise(460) {
                    StoneRow("Surfshark", "Service login from the manual-setup page", Glyph.surfshark,
                        { onProvider(Accounts.SURFSHARK) })
                }
                Spacer(Modifier.height(8.dp))
                Rise(530) {
                    StoneRow("Windscribe", "Your Windscribe username and password", Glyph.windscribe,
                        { onProvider(Accounts.WINDSCRIBE) })
                }
                Spacer(Modifier.height(18.dp))
                Rise(610) { MiLabel("Your own server") }
                Spacer(Modifier.height(8.dp))
                Rise(670) {
                    StoneRow("My own server", "Its domain and password, behind Cloudflare", Glyph.server, onServer)
                }
                Spacer(Modifier.height(18.dp))
                Rise(750) { MiLabel("Configs from anywhere") }
                Spacer(Modifier.height(8.dp))
                Rise(810) {
                    StoneRow("Links or a subscription", "vless, vmess, trojan, ss, hysteria2 - pasted or fetched",
                        Glyph.layers, onConfigs)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * Between signing in and being able to connect: the provider's servers are
 * fetched and pinned (preview/DECISIONS.md, the setup). The eye searches -
 * its ring turning once in twelve seconds, the bead round it once in 2.4 -
 * over three steps on a dotted line and a ruler of ten degrees filling red.
 * Done, the bead goes home and a red ring closes round the eye. Nothing to
 * press unless it fails.
 */
@Composable
fun SetupScreen(
    setup: Setup,
    run: PinRun,
    paused: Boolean,
    onRetry: () -> Unit,
    onOtherAccount: () -> Unit,
) {
    val provider = providerName(setup.provider.ifEmpty { Accounts.SURFSHARK })
    val failed = setup.step == "failed"
    val done = setup.step == "done"
    val searching = !failed && !done
    // Which step a failure belongs to: the last one that was running.
    var lastStep by remember { mutableStateOf("servers") }
    LaunchedEffect(setup.step) { if (setup.step != "failed" && setup.step.isNotEmpty()) lastStep = setup.step }

    val reduced = rememberReducedMotion()
    val moving = !reduced && !paused
    val short = shortScreen()
    val anchor = remember { ChartAnchor() }
    val chart = rememberTurn(1.5f, moving)
    val ring = rememberTurn(30f, moving && searching)
    val bead = rememberTurn(150f, moving && searching)
    val mark = rememberTurn(240f, moving && searching)
    val close by animateFloatAsState(if (done) 1f else 0f, tween(if (reduced) 0 else 900), label = "close")

    MiPage(anchor, chart, chart = 520.dp) {
        Column(
            Modifier
                .fillMaxSize()
                .wrapContentWidth()
                .widthIn(max = CONTENT_MAX)
                .fillMaxWidth()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(if (short) 20.dp else 44.dp))
            Box(Modifier.size(110.dp).then(anchor.mark()), contentAlignment = Alignment.Center) {
                MirageEye(Modifier.fillMaxSize(), ring = { ring.value }, bead = { if (done) 0f else bead.value })
                Spacer(
                    Modifier
                        .fillMaxSize()
                        .drawBehind {
                            if (close > 0.001f) {
                                val inset = 2.dp.toPx()
                                drawArc(Mirage.redHi, -90f, 360f * close, false, Offset(inset, inset),
                                    Size(size.width - 2 * inset, size.height - 2 * inset),
                                    style = Stroke(1.5.dp.toPx()))
                            }
                        },
                )
            }
            Spacer(Modifier.height(16.dp))
            MiTitle(when {
                failed -> "Setup did not finish"
                done -> "Ready"
                else -> "Getting your servers ready"
            })
            Spacer(Modifier.height(8.dp))
            MiSay(when {
                failed -> "Nothing is lost: the account is saved, and this can run again."
                done -> "Mirage connects through whichever server answers first."
                else -> "Once for each account. After this, Mirage connects in a second."
            }, Modifier.widthIn(max = 300.dp), size = 13.5f)
            Spacer(Modifier.height(26.dp))

            val fetched = setup.step == "pinning" || done
            val servers = when {
                fetched -> StepState.DONE
                failed && lastStep != "pinning" -> StepState.FAILED
                else -> StepState.NOW
            }
            val pinning = when {
                done -> StepState.DONE
                failed && lastStep == "pinning" -> StepState.FAILED
                setup.step == "pinning" -> StepState.NOW
                else -> StepState.NEXT
            }
            val fraction = if (run.total > 0) run.done.toFloat() / run.total else 0f
            Column(
                Modifier
                    .widthIn(max = 330.dp)
                    .fillMaxWidth()
                    .drawBehind {
                        // The dotted line the steps hang on.
                        val x = 9.5.dp.toPx()
                        var y = 12.dp.toPx()
                        val end = size.height - 30.dp.toPx()
                        while (y < end) {
                            drawCircle(Color(0xFF3A2A2D), 0.6.dp.toPx(), Offset(x, y))
                            y += 4.dp.toPx()
                        }
                    },
            ) {
                SetupStep(StepState.DONE, "Signed in to $provider", mark)
                SetupStep(servers,
                    if (fetched) "Got $provider's server list" else "Getting $provider's server list", mark)
                SetupStep(pinning, "Pinning each server to its real address", mark,
                    detail = when {
                        done && run.done > 0 -> "${run.done} servers pinned"
                        run.total > 0 && (pinning == StepState.NOW || pinning == StepState.FAILED) -> "${run.done} of ${run.total}"
                        else -> null
                    },
                    ruler = if (pinning == StepState.NEXT) null else if (done) 1f else fraction)
            }

            if (failed) {
                if (setup.error.isNotEmpty()) {
                    Text(setup.error, modifier = Modifier.widthIn(max = 330.dp).fillMaxWidth().padding(start = 33.dp, top = 4.dp),
                        style = TextStyle(fontFamily = Sans, fontSize = 12.5.sp, lineHeight = 19.sp, color = Mirage.bad))
                }
                Spacer(Modifier.height(24.dp))
                OrbitButton("Try again", onRetry, Modifier.widthIn(max = 360.dp).fillMaxWidth(),
                    icon = Glyph.refresh, paused = paused)
                MiTextButton("Use another account", onOtherAccount)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** One step of the setup: its mark on the dotted line, its name, and what it has to say. */
@Composable
private fun SetupStep(mark: StepState, name: String, turn: androidx.compose.runtime.State<Float>,
                      detail: String? = null, ruler: Float? = null) {
    Row(Modifier.fillMaxWidth().padding(bottom = 18.dp), verticalAlignment = Alignment.Top) {
        SetupMark(mark, turn, Modifier.padding(top = 1.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = TextStyle(fontFamily = Sans, fontSize = 14.sp, lineHeight = 20.sp,
                color = when (mark) {
                    StepState.DONE, StepState.NOW -> Color(0xFFF4F1EF)
                    StepState.FAILED -> Mirage.bad
                    StepState.NEXT -> Color(0xFF6E6765)
                }))
            if (detail != null) {
                Text(detail, modifier = Modifier.padding(top = 6.dp),
                    style = TextStyle(fontFamily = Sans, fontSize = 12.sp, color = Mirage.say))
            }
            if (ruler != null) {
                MiRuler(ruler, Modifier.padding(top = 8.dp), dim = mark == StepState.NEXT)
            }
        }
    }
}
