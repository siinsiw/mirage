package com.erelay.relay.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.erelay.relay.Keys
import com.erelay.relay.data.Captcha
import com.erelay.relay.data.Said
import com.erelay.relay.ui.theme.Tone
import com.erelay.relay.ui.theme.Type
import kotlinx.coroutines.CompletableDeferred

/**
 * The note before the first Windscribe sign-in, and the gate that shows it.
 *
 * Relay signs in to Windscribe the way its browser extension does - there is
 * no other door for an app that is not theirs - and Windscribe's terms may
 * not allow that. Somebody about to type their account in is owed that in
 * one plain sentence, before anything is sent, with a way to stop.
 *
 * [begin] is the sign-in's first step; what comes back is the same step with
 * the note in front of it. Continue is remembered (Keys.WINDSCRIBE_NOTE_OK)
 * and the note is not shown again; Cancel sends nothing and answers the way
 * a stopped sign-in does, so the sheet shows its usual line.
 */
@Composable
fun rememberWindscribeGate(
    begin: suspend (String, String, String) -> Pair<Said, Pair<String, Captcha>?>,
): suspend (String, String, String) -> Pair<Said, Pair<String, Captcha>?> {
    val context = LocalContext.current.applicationContext
    val latest by rememberUpdatedState(begin)
    var asking by remember { mutableStateOf<CompletableDeferred<Boolean>?>(null) }

    val answer = asking
    if (answer != null) {
        Dialog(onDismissRequest = { answer.complete(false) }) {
            val shape = RoundedCornerShape(20.dp)
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(Tone.surface)
                    .border(1.dp, Tone.stroke, shape)
                    .padding(20.dp),
            ) {
                Text("Before you sign in", style = Type.strong.copy(color = Tone.text))
                Text("Relay signs in the way Windscribe's browser extension does. Windscribe " +
                    "may not allow this, and your account could be blocked.",
                    style = Type.caption.copy(color = Tone.text2),
                    modifier = Modifier.padding(top = 8.dp))
                Row(Modifier.fillMaxWidth().padding(top = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Btn("Cancel", { answer.complete(false) },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp))
                    Btn("Continue", { answer.complete(true) },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp), strong = true)
                }
            }
        }
    }

    val gate: suspend (String, String, String) -> Pair<Said, Pair<String, Captcha>?> =
        gate@{ label, user, password ->
            val prefs = Keys.prefs(context)
            if (!prefs.getBoolean(Keys.WINDSCRIBE_NOTE_OK, false)) {
                val wait = CompletableDeferred<Boolean>()
                asking = wait
                val yes = try {
                    wait.await()
                } finally {
                    asking = null
                }
                if (!yes) {
                    return@gate Pair<Said, Pair<String, Captcha>?>(
                        Said(false, "Sign-in stopped. Nothing was sent."), null)
                }
                prefs.edit().putBoolean(Keys.WINDSCRIBE_NOTE_OK, true).apply()
            }
            latest(label, user, password)
        }
    return gate
}
