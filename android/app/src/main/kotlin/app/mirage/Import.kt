package app.mirage

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.io.InputStream

/**
 * The name a picked document is kept under, or null when it is not one to
 * keep. The name is whatever the documents provider says it is - any app can
 * be one - and it used to go into File(folder, name) as it came, so
 * "../../x.ovpn" wrote outside the folder. Only a bare file name ending in
 * .ovpn passes: no separator of either kind, no "..", no control character,
 * something before the extension, and short enough to be a file name.
 */
internal fun safeOvpnName(raw: String?): String? {
    val name = raw?.trim().orEmpty()
    if (name.length <= ".ovpn".length || name.length > 200) return null
    if (!name.endsWith(".ovpn", ignoreCase = true)) return null
    if (name.startsWith(".") || name.contains("..")) return null
    if (name.any { it == '/' || it == '\\' || it == ':' || it.code < 0x20 || it.code == 0x7f }) return null
    // What the file system itself takes the last segment to be, as a second opinion.
    if (File(name).name != name) return null
    return name
}

/** The most one config may weigh. A real one is a few kilobytes, inline keys and all. */
internal const val MAX_OVPN_BYTES = 1L shl 20

/**
 * Copy [input] to [into], giving up past [limit] bytes: false then, with
 * [into] left for the caller to delete. A provider can say a file is a
 * config and hand over a stream that never ends.
 */
internal fun copyCapped(input: InputStream, into: File, limit: Long = MAX_OVPN_BYTES): Boolean {
    val buffer = ByteArray(8192)
    var total = 0L
    into.outputStream().use { out ->
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > limit) return false
            out.write(buffer, 0, n)
        }
    }
    return true
}

/**
 * Configs, in from a folder somebody picked.
 *
 * The desktop's chooseFolder points the app at a folder and reads it where it
 * stands. A phone cannot: a picked folder arrives as a content URI rather
 * than a path, nothing outside the picker can open it by name, and the grant
 * does not survive much. So the files are copied in rather than read where
 * they are, and the app keeps reading its own folder - which is the only one
 * the Go core can be handed a path to.
 *
 * This is what makes the phone usable without a cable. Before it, configs
 * arrived only by `adb push`, which means a computer, a cable and a developer
 * setting, to move files a file manager already has.
 */
object Import {

    /** What came of one import, in the shape chooseFolder answers with. */
    data class Result(val copied: Int, val skipped: Int, val seen: Int)

    /**
     * Copy every .ovpn in a picked tree into the app's own folder.
     *
     * One cursor over the tree rather than DocumentFile.listFiles(), which is
     * one binder round trip per file and takes seconds over a folder of four
     * hundred.
     *
     * Existing names are skipped rather than overwritten. A config is named
     * for the exit and the address it was pinned to, so the same name is the
     * same file - and re-importing a folder should be free rather than four
     * hundred writes.
     *
     * Windscribe's and Surfshark's unpinned configs are told apart by the
     * mark in the name and sent to their own inboxes, so that a folder holding
     * both arrives as two piles ready to pin rather than one that neither
     * provider card can count.
     *
     * A name that is not a plain file name ([safeOvpnName]) and a file over
     * [MAX_OVPN_BYTES] are counted as seen and not copied.
     */
    fun fromTree(ctx: Context, tree: Uri): Result {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            tree, DocumentsContract.getTreeDocumentId(tree))

        var copied = 0
        var skipped = 0
        var seen = 0

        ctx.contentResolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null,
        )?.use { rows ->
            while (rows.moveToNext()) {
                val id = rows.getString(0) ?: continue
                val shown = rows.getString(1) ?: continue
                if (!shown.trim().endsWith(".ovpn", ignoreCase = true)) continue
                seen++
                val name = safeOvpnName(shown) ?: continue

                val into = File(destination(ctx, name), name)
                if (into.exists()) {
                    skipped++
                    continue
                }
                val from = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                try {
                    into.parentFile?.mkdirs()
                    val whole = ctx.contentResolver.openInputStream(from)?.use { input ->
                        copyCapped(input, into)
                    } ?: continue
                    if (whole) copied++ else into.delete()
                } catch (e: Exception) {
                    // One unreadable file is not a failed import. The count
                    // says how many arrived, and the page prints the count.
                    into.delete()
                }
            }
        }
        return Result(copied, skipped, seen)
    }

    /**
     * Which folder a config belongs in, decided by its own name.
     *
     * A pinned config carries an address on the end and goes straight into
     * the folder the app races from. An unpinned one goes to its provider's
     * inbox, where the pin run will find it - putting it in pinned/ would
     * make the catalogue try to dial a hostname, which is the one thing this
     * whole repo exists to stop doing.
     */
    private fun destination(ctx: Context, name: String): File = when {
        PINNED.containsMatchIn(name) -> AppFiles.pinned(ctx)
        name.contains(".ws.") -> AppFiles.windscribe(ctx)
        else -> AppFiles.configs(ctx)
    }

    /** The address a pin run puts on the end: `..._146.70.253.194.ovpn`. */
    private val PINNED = Regex("""_\d{1,3}(?:\.\d{1,3}){3}\.ovpn$""")
}
