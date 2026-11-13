package com.erelay.relay.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.erelay.relay.Keys
import com.erelay.relay.ui.theme.Tone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * The desktop's flags, drawn the way the desktop draws them: the whole flag
 * filling a circle, cropped to its middle. They are the same files - lipis'
 * flag-icons, MIT, the licence beside them in assets/flags - rasterised from
 * app/ui/flags' SVGs to 144px WebP, because Compose has no SVG of its own.
 *
 * Iran's is the exception: the Lion and Sun flag, from Wikimedia Commons
 * ("State flag of Iran (1964-1980)", public domain), cut to the same 4:3.
 * That is what is shown unless the person asks for none: Settings has a
 * switch ([IranFlagRow]) that draws Iran as a plain disc with "IR" on it,
 * and no flag of any era, instead.
 *
 * The emoji they replace sat small in the middle of a grey disc, with the
 * disc showing round it, and every phone drew them in its own hand.
 *
 * Decoding: a flag is read from the assets and decoded on Dispatchers.IO, not
 * during composition - a list of countries scrolling for the first time used
 * to decode one on the main thread for every new row. The disc is drawn empty
 * until its picture arrives. The last [KEPT] are kept (about 60 KB each), not
 * all 237.
 */

private const val KEPT = 40

private val flags = LruCache<String, ImageBitmap>(KEPT)

/** Codes there is no picture for, so the assets are not asked twice. A few two-letter strings at most. */
private val noFlag = HashSet<String>()

private fun flagKey(code: String): String? = code.lowercase().take(2).takeIf { it.length == 2 }

/**
 * Iran drawn without a flag (Keys.IRAN_NEUTRAL_FLAG). Compose state, so a
 * flag on screen changes with the switch; read from the preferences once.
 */
private val iranNeutral = mutableStateOf<Boolean?>(null)

private fun iranIsNeutral(context: Context): Boolean =
    iranNeutral.value ?: Keys.prefs(context).getBoolean(Keys.IRAN_NEUTRAL_FLAG, false)
        .also { iranNeutral.value = it }

private fun setIranNeutral(context: Context, on: Boolean) {
    Keys.prefs(context).edit().putBoolean(Keys.IRAN_NEUTRAL_FLAG, on).apply()
    iranNeutral.value = on
}

/** Whether [code] is to be drawn as its two letters, with no picture: Iran, when the person chose so. */
internal fun flagHidden(context: Context, code: String): Boolean =
    flagKey(code) == "ir" && iranIsNeutral(context)

/**
 * The flag for a country code, decoded once and kept for a while; null if
 * there is none, or it is hidden ([flagHidden]). Reads the assets when it is
 * not in the cache: not for the main thread - the composables below go
 * through [rememberFlag], and the map calls this from its GL thread.
 */
internal fun flagBitmap(context: Context, code: String): ImageBitmap? {
    val c = flagKey(code) ?: return null
    if (flagHidden(context, c)) return null
    flags.get(c)?.let { return it }
    synchronized(noFlag) { if (c in noFlag) return null }
    // The core folds gb into uk, the map and the trace say gb; the files have whichever they have.
    val got = listOf(c, if (c == "uk") "gb" else if (c == "gb") "uk" else c).distinct().firstNotNullOfOrNull { name ->
        try {
            context.assets.open("flags/$name.webp").use { BitmapFactory.decodeStream(it) }?.asImageBitmap()
        } catch (e: Exception) {
            null
        }
    }
    if (got != null) flags.put(c, got) else synchronized(noFlag) { noFlag += c }
    return got
}

/** A flag once it is known: its picture, or null for a country that has none. */
private class Known(val bitmap: ImageBitmap?)

private val NONE = Known(null)

/** What is known without reading anything: null means it has still to be decoded. */
private fun known(context: Context, key: String?): Known? {
    if (key == null || flagHidden(context, key)) return NONE
    flags.get(key)?.let { return Known(it) }
    return synchronized(noFlag) { if (key in noFlag) NONE else null }
}

/**
 * The flag for [code], decoded off the main thread. Null while that is under
 * way - the caller draws its placeholder - and a [Known] after.
 */
@Composable
private fun rememberFlag(code: String): Known? {
    val context = LocalContext.current.applicationContext
    val key = flagKey(code)
    val hidden = flagHidden(context, code)
    val state = remember(key, hidden) { mutableStateOf(known(context, key)) }
    LaunchedEffect(key, hidden) {
        if (state.value == null && key != null) {
            state.value = Known(withContext(Dispatchers.IO) { flagBitmap(context, key) })
        }
    }
    return state.value
}

/** The flag's picture alone, for a background that wants more than a circle of it. Null until it is decoded. */
@Composable
fun rememberFlagBitmap(code: String): ImageBitmap? = rememberFlag(code)?.bitmap

/**
 * A country's flag as a filled circle with a hairline edge. A country there
 * is no picture for falls back to its emoji, and no country to a globe. The
 * bare disc while the picture is being decoded; Iran with its flag hidden is
 * the disc with "IR" on it.
 */
@Composable
fun Flag(code: String, size: Dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val flag = rememberFlag(code)
    val bitmap = flag?.bitmap
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(Tone.surface2)
            .border(1.dp, Tone.stroke, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        when {
            flag == null -> Unit
            bitmap != null -> Image(bitmap, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            flagHidden(context, code) -> Text("IR", fontSize = (size.value * 0.36f).sp,
                fontWeight = FontWeight.Medium, color = Tone.text2, maxLines = 1)
            flagOf(code).isNotEmpty() -> Text(flagOf(code), fontSize = (size.value * 0.55f).sp)
            else -> Icon(Glyph.globe, null, Modifier.size(size * 0.45f), tint = Tone.text2)
        }
    }
}

/**
 * The flag as the address panel draws it: the whole 4:3 flag, small, its
 * corners rounded and a hairline round it. Nothing at all when there is no
 * picture of it - a grey disc with a globe in it says less than the name beside.
 * Its space is held while the picture is decoded, so the row does not shift
 * when it arrives.
 */
@Composable
fun FlagTile(code: String, modifier: Modifier = Modifier) {
    val flag = rememberFlag(code)
    if (flag == null) {
        Box(modifier.size(22.dp, 16.5.dp))
        return
    }
    val bitmap = flag.bitmap ?: return
    val shape = RoundedCornerShape(3.dp)
    Image(bitmap, null, modifier.size(22.dp, 16.5.dp).clip(shape).border(1.dp, Tone.stroke, shape),
        contentScale = ContentScale.Crop)
}

/** The switch for Iran's flag, for the settings page: one row, kept in the preferences. */
@Composable
fun IranFlagRow() {
    val context = LocalContext.current.applicationContext
    SwitchRow("Neutral flag for Iran",
        "Off shows the Lion and Sun flag. On shows a plain disc with IR, and no flag at all.",
        iranIsNeutral(context), { setIranNeutral(context, it) })
}
