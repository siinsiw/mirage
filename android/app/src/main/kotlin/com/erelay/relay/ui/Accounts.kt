package com.erelay.relay.ui

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.erelay.relay.data.Captcha
import com.erelay.relay.data.Said
import com.erelay.relay.ui.theme.Space
import com.erelay.relay.ui.theme.Data
import com.erelay.relay.ui.theme.Shape
import com.erelay.relay.ui.theme.Tone
import com.erelay.relay.ui.theme.Type
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.erelay.relay.ui.theme.Mirage
import com.erelay.relay.ui.theme.Sans
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private enum class Who(val key: String, val label: String) {
    SURFSHARK("surfshark", "Surfshark"), WINDSCRIBE("windscribe", "Windscribe")
}

/**
 * Adding an account (preview/DECISIONS.md, adding an account): a Mirage page
 * of its own, the provider's mark inside a ring of degrees at the top, the
 * fields on black stone, and the bead button. Surfshark is a service
 * username and password, checked by the exits themselves, so it is two
 * writes. Windscribe is a login behind a puzzle: this fetches the puzzle and
 * [Captcha] finishes it. The name, and Windscribe's two-step code, wait
 * behind two small links until they are wanted.
 *
 * An empty field is said under that field; a refused sign-in is said under
 * the fields in the core's own words, and shakes the form once. Signed in,
 * the button turns red with a check and a red ring closes round the mark,
 * and then the setup takes over.
 *
 * One account per provider: a provider that already has one is shown as
 * added and cannot be picked, so the way to change it is to remove it first.
 */
@Composable
fun AddAccount(
    open: Boolean,
    provider: String,
    /** Providers that already have an account. */
    taken: Set<String>,
    onClose: () -> Unit,
    addSurfshark: suspend (String, String, String) -> Said,
    beginWindscribe: suspend (String, String, String) -> Pair<Said, Pair<String, Captcha>?>,
    finishWindscribe: suspend (String, String, List<Int>, List<Int>, String) -> Said,
    /** The line to show, and which provider the new account is for. */
    onDone: (String, String) -> Unit,
) {
    var who by remember { mutableStateOf(Who.SURFSHARK) }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var twoFa by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    var said by remember { mutableStateOf<Said?>(null) }
    var busy by remember { mutableStateOf(false) }
    var signedIn by remember { mutableStateOf(false) }
    var puzzle by remember { mutableStateOf<Pair<String, Captcha>?>(null) }
    var userBad by remember { mutableStateOf<String?>(null) }
    var passBad by remember { mutableStateOf<String?>(null) }
    var twoOpen by remember { mutableStateOf(false) }
    var nameOpen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val anchor = remember { ChartAnchor() }

    LaunchedEffect(open, provider) {
        if (open) who = if (provider == Who.WINDSCRIBE.key) Who.WINDSCRIBE else Who.SURFSHARK
    }
    LaunchedEffect(open) {
        if (!open) {
            user = ""; password = ""; twoFa = ""; label = ""; said = null; busy = false; puzzle = null
            signedIn = false; userBad = null; passBad = null; twoOpen = false; nameOpen = false
        }
    }

    /* Signed in: the button and the ring say so for a moment, then the setup. */
    suspend fun succeed(line: String) {
        busy = false
        signedIn = true
        delay(950)
        onDone(line, who.key)
    }

    fun submit() {
        val emptyUser = user.isBlank()
        val emptyPass = password.isEmpty()
        userBad = if (emptyUser) (if (who == Who.SURFSHARK) "Enter the service username." else "Enter the username.") else null
        passBad = if (emptyPass) "Enter the password." else null
        if (emptyUser || emptyPass) return
        busy = true
        said = null
        scope.launch {
            if (who == Who.SURFSHARK) {
                val r = addSurfshark(label, user, password)
                if (r.ok) succeed("Surfshark account saved. Now get its servers.") else { busy = false; said = r }
            } else {
                val (r, got) = beginWindscribe(label, user, password)
                if (!r.ok || got == null) {
                    busy = false
                    said = r
                } else {
                    puzzle = got
                }
            }
        }
    }

    MiSheet(open, onClose, anchor, paused = puzzle != null) {
        val reduced = rememberReducedMotion()
        val seal = rememberTurn(6f, !reduced && puzzle == null && !signedIn)
        val closed by animateFloatAsState(if (signedIn) 1f else 0f, tween(if (reduced) 0 else 700), label = "seal")
        // A refused sign-in shakes the form once, side to side.
        val shake = remember { Animatable(0f) }
        LaunchedEffect(said) {
            if (said?.ok == false && !reduced) {
                for (x in listOf(10f, -8f, 6f, -4f, 2f, 0f)) shake.animateTo(x, tween(55))
            }
        }
        Column(
            Modifier
                .fillMaxSize()
                .wrapContentWidth()
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.End) {
                MiClose(onClose, Modifier.offset(x = 12.dp))
            }
            Seal(who, seal, closed, Modifier.size(84.dp).then(anchor.mark()))
            Spacer(Modifier.height(12.dp))
            Text("Connect ${who.label}", style = TextStyle(fontFamily = Sans, fontWeight = FontWeight.W500,
                fontSize = 20.sp, lineHeight = 26.sp, color = Color(0xFFF4F1EF), textAlign = TextAlign.Center))
            Spacer(Modifier.height(6.dp))
            MiSay(if (who == Who.SURFSHARK) "Use the service login from Surfshark's manual-setup page, not your email."
                else "Your Windscribe username and password. A short puzzle comes next, asked once.",
                Modifier.widthIn(max = 290.dp), size = 13f)

            if (who.key in taken) {
                Spacer(Modifier.height(24.dp))
                MiSay("${who.label} already has its account. Each provider takes one; remove it in Accounts to add a different one.",
                    size = 13f)
                Spacer(Modifier.height(20.dp))
                MiTextButton("Close", onClose)
                return@Column
            }

            Spacer(Modifier.height(26.dp))
            Column(Modifier.fillMaxWidth().graphicsLayer { translationX = shake.value * density }) {
                StoneField(if (who == Who.SURFSHARK) "Service username" else "Username or email", user,
                    { user = it; userBad = null; said = null },
                    if (who == Who.SURFSHARK) "from the manual-setup page" else "your Windscribe login",
                    bad = userBad)
                Spacer(Modifier.height(14.dp))
                StoneField("Password", password, { password = it; passBad = null; said = null }, "password",
                    secret = true, bad = passBad)
                if (who == Who.WINDSCRIBE && twoOpen) {
                    Spacer(Modifier.height(14.dp))
                    StoneField("Two-step code", twoFa, { twoFa = it }, "only if it is on",
                        keyboard = androidx.compose.ui.text.input.KeyboardType.Number)
                }
                if (nameOpen) {
                    Spacer(Modifier.height(14.dp))
                    StoneField("Name (optional)", label, { label = it }, "the username, if you leave this")
                }
                said?.let {
                    if (!it.ok && it.text.isNotEmpty()) Row(Modifier.padding(top = 12.dp, start = 2.dp),
                        verticalAlignment = Alignment.Top) {
                        Icon(Glyph.alert, null, Modifier.padding(top = 2.dp).size(14.dp), tint = Mirage.bad)
                        Spacer(Modifier.width(6.dp))
                        Text(it.text, style = TextStyle(fontFamily = Sans, fontSize = 12.5.sp, lineHeight = 18.sp,
                            color = Mirage.bad))
                    }
                }
                if ((who == Who.WINDSCRIBE && !twoOpen) || !nameOpen) {
                    Row(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                        if (who == Who.WINDSCRIBE && !twoOpen) MiLink("I use two-step sign-in") { twoOpen = true }
                        if (!nameOpen) MiLink("Add a name (optional)") { nameOpen = true }
                    }
                }
            }

            Spacer(Modifier.height(28.dp))
            // Where it goes, said before it is typed rather than after.
            Row(Modifier.padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Glyph.key, null, Modifier.size(14.dp), tint = Mirage.red)
                Spacer(Modifier.width(8.dp))
                Text("Kept on this phone. Sent only to ${who.label}.",
                    style = TextStyle(fontFamily = Sans, fontSize = 12.sp, color = Color(0xFF8C8481)))
            }
            OrbitButton(
                when {
                    signedIn -> "Signed in"
                    busy -> "Signing in…"
                    who == Who.SURFSHARK -> "Save account"
                    else -> "Continue to the puzzle"
                },
                { submit() },
                Modifier.fillMaxWidth(),
                busy = busy,
                done = signedIn,
                paused = puzzle != null,
            )
            MiTextButton("Cancel", onClose)
            Spacer(Modifier.height(16.dp))
        }
    }

    CaptchaSheet(
        puzzle = puzzle,
        onClose = {
            puzzle = null
            busy = false
            said = Said(false, "Sign-in stopped. Nothing was sent.")
        },
        submit = { solution, xs, ys ->
            val token = puzzle?.first.orEmpty()
            val r = finishWindscribe(token, solution, xs, ys, twoFa.trim())
            puzzle = null
            if (r.ok) succeed("Signed in to Windscribe. Now get its servers.") else { busy = false; said = r }
        },
    )
}

/** A small red link that opens a hidden field. */
@Composable
private fun MiLink(text: String, onClick: () -> Unit) {
    Box(Modifier.heightIn(min = 40.dp).press(onClick = onClick).padding(end = 18.dp, top = 8.dp, bottom = 8.dp)) {
        Text(text, style = TextStyle(fontFamily = Sans, fontSize = 12.5.sp, color = Color(0xFFC9787F)))
    }
}

/**
 * The provider's own mark in a ring of degrees, turning slowly; signed in, a
 * red ring closes round it ([closed], 0..1).
 */
@Composable
private fun Seal(w: Who, turn: androidx.compose.runtime.State<Float>, closed: Float, modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        // The degrees: 48 short strokes, drawn once and only turned.
        Spacer(
            Modifier
                .fillMaxSize()
                .graphicsLayer { rotationZ = turn.value }
                .drawBehind {
                    val r = size.minDimension / 2f - 6.dp.toPx()
                    val step = (2f * Math.PI.toFloat() * r) / 48f
                    drawCircle(Mirage.red, r, style = Stroke(5.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(1.dp.toPx(), step - 1.dp.toPx()))))
                },
        )
        Spacer(
            Modifier
                .size(56.dp)
                .drawBehind {
                    drawCircle(Brush.radialGradient(listOf(Color(0xFF3A1C21), Color(0xFF140C0E)),
                        center = Offset(size.width * 0.38f, size.height * 0.32f), radius = size.minDimension * 0.8f))
                    drawCircle(Color(0x73C31C2E), size.minDimension / 2f - 0.5.dp.toPx(), style = Stroke(1.dp.toPx()))
                },
        )
        Icon(if (w == Who.WINDSCRIBE) Glyph.windscribe else Glyph.surfshark, w.label,
            Modifier.size(28.dp), tint = Color(0xFFE8DCD9))
        Spacer(
            Modifier
                .fillMaxSize()
                .drawBehind {
                    if (closed > 0.001f) {
                        val i = 1.dp.toPx()
                        drawArc(Mirage.redHi, -90f, 360f * closed, false, Offset(i, i),
                            Size(size.width - 2 * i, size.height - 2 * i), style = Stroke(2.dp.toPx()))
                    }
                },
        )
    }
}

private const val TRAIL_MAX = 50

private fun decode(b64: String): ImageBitmap? = try {
    val raw = Base64.decode(b64, Base64.DEFAULT)
    BitmapFactory.decodeByteArray(raw, 0, raw.size)?.asImageBitmap()
} catch (e: Exception) {
    null
}

/**
 * Windscribe's slider puzzle: drag the piece into the gap and let go.
 *
 * What is sent is the desktop's: x is the *piece's* position, clamped, in
 * drawn units; y is measured from the top of the picture; the answer is the
 * piece's position converted back into the picture's own pixels, which is
 * the space the puzzle was cut in. Releasing sends it, once.
 */
@Composable
private fun CaptchaSheet(
    puzzle: Pair<String, Captcha>?,
    onClose: () -> Unit,
    submit: suspend (String, List<Int>, List<Int>) -> Unit,
) {
    val captcha = puzzle?.second
    var sending by remember(puzzle) { mutableStateOf(false) }
    var answer by remember(puzzle) { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    Sheet(captcha != null, "Prove you are there", onClose) {
        if (captcha == null) return@Sheet
        Spacer(Modifier.height(Space.x2))
        if (captcha.kind == "ascii") {
            Text("Type what the drawing says.", style = Type.caption)
            Spacer(Modifier.height(Space.x3))
            Text(captcha.art, style = Type.mono.copy(fontFamily = FontFamily.Monospace, fontSize = 7.sp,
                lineHeight = 8.sp, color = Tone.text),
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .clip(RoundedCornerShape(Shape.tip)).background(Tone.bg).border(1.dp, Tone.stroke, RoundedCornerShape(Shape.tip)).padding(Space.x3))
            Spacer(Modifier.height(Space.x3))
            Field(answer, { answer = it }, "Answer")
            ButtonRow {
                Btn(if (sending) "Checking…" else "Send", {
                    sending = true
                    scope.launch { submit(answer.trim(), emptyList(), emptyList()) }
                }, strong = true, enabled = !sending && answer.isNotBlank())
            }
            return@Sheet
        }

        Text(if (sending) "Checking…" else "Drag the piece into the gap, then let go.", style = Type.caption)
        Spacer(Modifier.height(Space.x3))
        val bg = remember(captcha) { decode(captcha.background) }
        val piece = remember(captcha) { decode(captcha.slider) }
        if (bg == null || piece == null) {
            SaidLine("The puzzle did not come through. Close this and try again.", bad = true)
            return@Sheet
        }

        val density = LocalDensity.current
        var left by remember(puzzle) { mutableFloatStateOf(0f) }
        val xs = remember(puzzle) { mutableStateListOf<Int>() }
        val ys = remember(puzzle) { mutableStateListOf<Int>() }

        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val drawn = with(density) { maxWidth.toPx() }
            val scale = drawn / bg.width
            val pieceW = piece.width * scale
            val span = (drawn - pieceW).coerceAtLeast(0f)
            val stageH = with(density) { (bg.height * scale).toDp() }

            fun drag(change: androidx.compose.ui.geometry.Offset, dx: Float) {
                left = (left + dx).coerceIn(0f, span)
                xs += with(density) { left.toDp().value.roundToInt() }
                ys += with(density) { change.y.toDp().value.roundToInt() }
                while (xs.size > TRAIL_MAX) { xs.removeAt(0); ys.removeAt(0) }
            }
            fun release() {
                if (sending) return
                sending = true
                val solution = (left / scale).roundToInt().toString()
                scope.launch { submit(solution, xs.toList(), ys.toList()) }
            }

            Column {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(stageH)
                        .clip(RoundedCornerShape(Shape.tip))
                        .pointerInput(puzzle) {
                            detectDragGestures(
                                onDragStart = { xs.clear(); ys.clear() },
                                onDragEnd = { release() },
                                onDragCancel = { release() },
                            ) { change, amount -> if (!sending) drag(change.position, amount.x) }
                        },
                ) {
                    Image(bg, null, Modifier.fillMaxWidth().height(stageH), contentScale = ContentScale.FillWidth)
                    Image(piece, null,
                        Modifier
                            .offset { IntOffset(left.roundToInt(), (captcha.top * scale).roundToInt()) }
                            .width(with(density) { pieceW.toDp() })
                            .height(with(density) { (piece.height * scale).toDp() }),
                        contentScale = ContentScale.FillBounds)
                }
                Spacer(Modifier.height(Space.x4))
                // The rail: a knob that reads as draggable, moving the same piece.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                        .clip(CircleShape)
                        .background(Tone.surface2)
                        .border(1.dp, Tone.stroke, CircleShape)
                        .pointerInput(puzzle) {
                            detectDragGestures(
                                onDragStart = { xs.clear(); ys.clear() },
                                onDragEnd = { release() },
                                onDragCancel = { release() },
                            ) { change, amount ->
                                if (!sending) drag(change.position.copy(y = (captcha.top * scale)), amount.x)
                            }
                        },
                ) {
                    Box(
                        Modifier
                            .offset { IntOffset(left.roundToInt(), 0) }
                            .width(with(density) { pieceW.toDp() })
                            .height(40.dp)
                            .clip(CircleShape)
                            .background(Tone.text),
                    )
                }
            }
        }
        Text("One try. If it is refused, nothing is retried - close this and sign in again.",
            style = Type.caption, modifier = Modifier.padding(top = Space.x3))
    }
}
