package app.mirage

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * More than one account, and one place to keep them.
 *
 * app/accounts.py, on a phone. The roster is a thing on top: whichever
 * account is active for a provider is the credential that provider's exits
 * are asked with, and [credentials] is the one place that is read.
 *
 * Two things differ from the desktop, and both are about where a phone keeps
 * things. Passwords go to [Secrets] rather than to DPAPI - all of them,
 * including the credential in use. That used to be written out again as
 * two lines of plain text, `auth` and `auth-windscribe`, on shared storage
 * where anybody with a cable could read it; nothing is written there now.
 * A phone set up that way, or handed `.ovpn-auth` by setup-phone.sh, still
 * works: [adopt] reads such a file into an account, exactly as the desktop
 * adopts an existing .ovpn-auth, and deletes it.
 */
object Accounts {

    const val SURFSHARK = "surfshark"
    const val WINDSCRIBE = "windscribe"

    /**
     * Surfshark as the exit of a server in Iran, in the words Settings and a
     * failed server + exit both use. Measured 2026-11-12 from a server in
     * Iran: the TLS handshake failed on all 142 Surfshark clusters - with the
     * exit's name, without it, with a decoy - while the same exits answered
     * normally from a server outside Iran. Their addresses are blocked there,
     * not the account. Said, never enforced: blocks change.
     */
    const val SURFSHARK_FROM_IRAN = "Surfshark's exits cannot currently be reached from " +
        "servers in Iran - they are blocked at the network level, whatever your server sends."

    private val LABELS = mapOf(SURFSHARK to "Surfshark", WINDSCRIBE to "Windscribe")

    private fun store(ctx: Context) = File(AppFiles.state(ctx), "accounts.json")

    // -- the file --------------------------------------------------------------

    fun load(ctx: Context): JSONObject {
        val got = try {
            JSONObject(store(ctx).readText())
        } catch (e: Exception) {
            JSONObject()
        }
        if (!got.has("accounts")) got.put("accounts", JSONArray())
        if (!got.has("active")) got.put("active", JSONObject())
        return got
    }

    fun save(ctx: Context, state: JSONObject) {
        try {
            AppFiles.state(ctx).mkdirs()
            val tmp = File(AppFiles.state(ctx), "accounts.json.tmp")
            tmp.writeText(state.toString())
            if (!tmp.renameTo(store(ctx))) {
                store(ctx).writeText(state.toString())
                tmp.delete()
            }
        } catch (e: Exception) {
            Log.w(MirageVpnService.TAG, "roster not saved: ${e.message}")
        }
    }

    private fun rows(state: JSONObject): JSONArray = state.optJSONArray("accounts") ?: JSONArray()

    private fun row(state: JSONObject, id: String): JSONObject? {
        val all = rows(state)
        for (i in 0 until all.length()) {
            val a = all.getJSONObject(i)
            if (a.optString("id") == id) return a
        }
        return null
    }

    /** The active account for a provider, or nothing. */
    fun active(ctx: Context, provider: String): JSONObject? {
        val state = load(ctx)
        val id = state.optJSONObject("active")?.optString(provider).orEmpty()
        return if (id.isEmpty()) null else row(state, id)
    }

    // -- adopting what was already here ----------------------------------------

    /**
     * Turn a credential handed over as a file into an account, and the file
     * into nothing.
     *
     * Two places, both two lines of username and password. The old one on
     * shared storage, `Android/data/<id>/files/auth`, which every install
     * before this one wrote and which setup-phone.sh used to push to; and
     * [AppFiles.authInbox] in the app's private folder, which is where the
     * scripts push it now. A phone with a working credential and no roster
     * should not be shown an empty account list and a "not set up" screen,
     * so the file is read and believed - and stored in [Secrets], and
     * deleted, so the only copy left in plain text is none.
     *
     * Every time rather than once: a file pushed after the first run is a
     * credential somebody means to be used, and the check is two stat calls
     * when there is nothing there. Synchronized because the window and the
     * service both call it on the way up, and two of these at once would
     * each add the same account.
     */
    @Synchronized
    fun adopt(ctx: Context) {
        for (provider in listOf(SURFSHARK, WINDSCRIBE)) {
            AppFiles.legacyAuth(ctx, provider)?.let { if (it.isFile) take(ctx, provider, it) }
            val inbox = AppFiles.authInbox(ctx, provider)
            if (inbox.isFile) take(ctx, provider, inbox)
        }
    }

    /** One file's credential into the roster and [Secrets]; see [adopt]. */
    private fun take(ctx: Context, provider: String, file: File) {
        val inbox = AppFiles.authInbox(ctx, provider)
        val pair = try {
            readCredentials(file)
        } catch (e: Exception) {
            Log.w(MirageVpnService.TAG, "credential file not read: ${e.message}")
            return
        }
        if (pair == null) {
            // Not two lines: not a credential, and nothing to keep. Left on
            // shared storage it would only be read again next time.
            file.delete()
            return
        }
        val (user, pass) = pair

        val state = load(ctx)
        val row = matching(ctx, state, provider, user) ?: JSONObject().apply {
            put("id", fresh())
            put("provider", provider)
            put("label", user)
            put("username", user)
            put("added", System.currentTimeMillis() / 1000)
            put("hasPassword", false)
        }.also { rows(state).put(it) }
        val id = row.optString("id")

        // Surfshark's file is the account itself: a service username and its
        // password. Windscribe's is the proxy credential its sign-in produced,
        // which is what its exits are asked with.
        val stored = if (provider == WINDSCRIBE) {
            Secrets.put(ctx, "proxyUser:$id", user) && Secrets.put(ctx, "proxyPass:$id", pass)
        } else {
            Secrets.put(ctx, "pw:$id", pass).also { if (it) row.put("hasPassword", true) }
        }
        state.optJSONObject("active")?.put(provider, id)
        state.put("adopted", true)
        save(ctx, state)

        if (stored) {
            if (file.delete()) {
                Log.i(MirageVpnService.TAG, "${LABELS[provider]} credential moved into the secret store")
            } else {
                Log.w(MirageVpnService.TAG, "${LABELS[provider]} credential stored, but its file was not deleted")
            }
        } else if (file.absolutePath != inbox.absolutePath) {
            // No secret store on this phone. Off shared storage all the same:
            // into the app's own folder, where it goes on being the credential
            // ([credentials] falls back on it) without anything else reading it.
            try {
                inbox.parentFile?.mkdirs()
                file.copyTo(inbox, overwrite = true)
                file.delete()
            } catch (e: Exception) {
                Log.w(MirageVpnService.TAG, "credential not moved off shared storage: ${e.message}")
            }
        }
    }

    /**
     * The account a credential file belongs to. By username; and for
     * Windscribe also by the proxy username it holds, because the file an
     * older install wrote for a signed-in account carries the proxy
     * credential, not the login name the row is listed under.
     */
    private fun matching(ctx: Context, state: JSONObject, provider: String, user: String): JSONObject? {
        val all = rows(state)
        for (i in 0 until all.length()) {
            val a = all.getJSONObject(i)
            if (a.optString("provider") != provider) continue
            if (a.optString("username").equals(user, ignoreCase = true)) return a
            if (provider == WINDSCRIBE && Secrets.get(ctx, "proxyUser:${a.optString("id")}") == user) return a
        }
        return null
    }

    private fun fresh() = UUID.randomUUID().toString().replace("-", "").take(8)

    // -- what the page draws ---------------------------------------------------

    /**
     * The roster, without any of the secrets in it.
     *
     * `signedIn` is not `hasPassword`. On a phone with no working secret
     * store an adopted account has no stored password and works: the file it
     * was adopted from stays, privately, as the credential in use. What it
     * cannot do is be switched away from and back again, which is what
     * `hasPassword` says.
     */
    fun listing(ctx: Context): JSONArray {
        adopt(ctx)
        val state = load(ctx)
        val activeIds = state.optJSONObject("active") ?: JSONObject()
        val all = rows(state)
        val out = ArrayList<JSONObject>()

        for (i in 0 until all.length()) {
            val a = all.getJSONObject(i)
            val id = a.optString("id")
            val provider = a.optString("provider")
            val isActive = activeIds.optString(provider) == id
            val hasPassword = a.optBoolean("hasPassword") &&
                Secrets.get(ctx, "pw:$id") != null
            val signedIn = when (provider) {
                WINDSCRIBE -> Secrets.get(ctx, "proxyUser:$id") != null ||
                    (isActive && AppFiles.authInbox(ctx, WINDSCRIBE).isFile)
                else -> hasPassword || (isActive && AppFiles.authInbox(ctx, SURFSHARK).isFile)
            }
            out.add(JSONObject().apply {
                put("id", id)
                put("provider", provider)
                put("providerName", LABELS[provider] ?: provider)
                put("label", a.optString("label"))
                put("username", a.optString("username"))
                put("active", isActive)
                put("hasPassword", hasPassword)
                put("signedIn", signedIn)
                put("added", a.optLong("added"))
            })
        }
        out.sortWith(compareBy({ it.optString("provider") },
            { it.optString("label").lowercase() }))

        val array = JSONArray()
        for (a in out) array.put(a)
        return array
    }

    // -- changing it -----------------------------------------------------------

    /**
     * Add or update one, and make it the active one for its provider.
     *
     * Matched on the username within a provider rather than on an id, because
     * somebody typing the same account in twice means to correct it, not to
     * have two of it.
     */
    fun put(
        ctx: Context, provider: String, label: String, username: String,
        password: String? = null, session: String? = null,
        proxyUser: String? = null, proxyPass: String? = null,
    ): JSONObject {
        val state = load(ctx)
        val all = rows(state)
        var found: JSONObject? = null
        for (i in 0 until all.length()) {
            val a = all.getJSONObject(i)
            if (a.optString("provider") == provider &&
                a.optString("username").equals(username, ignoreCase = true)
            ) {
                found = a
                break
            }
        }
        if (found == null) {
            found = JSONObject().apply {
                put("id", fresh())
                put("provider", provider)
                put("added", System.currentTimeMillis() / 1000)
            }
            all.put(found)
        }
        val id = found.optString("id")
        found.put("label", label.ifEmpty { username })
        found.put("username", username)

        if (!password.isNullOrEmpty()) {
            found.put("hasPassword", Secrets.put(ctx, "pw:$id", password))
        }
        if (!session.isNullOrEmpty()) Secrets.put(ctx, "session:$id", session)
        if (!proxyUser.isNullOrEmpty()) Secrets.put(ctx, "proxyUser:$id", proxyUser)
        if (!proxyPass.isNullOrEmpty()) Secrets.put(ctx, "proxyPass:$id", proxyPass)

        state.optJSONObject("active")?.put(provider, id)
        state.put("adopted", true)
        save(ctx, state)
        return found
    }

    /** Make an existing account the one in use. */
    fun activate(ctx: Context, id: String): JSONObject? {
        val state = load(ctx)
        val a = row(state, id) ?: return null
        state.optJSONObject("active")?.put(a.optString("provider"), id)
        save(ctx, state)
        return a
    }

    /**
     * Remove one, and hand its place to whatever is left.
     *
     * A credential kept in a file (a phone with no secret store) goes only
     * when nothing of that provider remains - removing one of two accounts
     * should leave the other one connecting.
     */
    fun remove(ctx: Context, id: String): JSONObject? {
        val state = load(ctx)
        val gone = row(state, id) ?: return null
        val provider = gone.optString("provider")

        val kept = JSONArray()
        val all = rows(state)
        var heir: String? = null
        for (i in 0 until all.length()) {
            val a = all.getJSONObject(i)
            if (a.optString("id") == id) continue
            kept.put(a)
            if (heir == null && a.optString("provider") == provider) heir = a.optString("id")
        }
        state.put("accounts", kept)
        val activeIds = state.optJSONObject("active") ?: JSONObject()
        if (heir != null) activeIds.put(provider, heir) else activeIds.remove(provider)
        save(ctx, state)
        Secrets.dropAll(ctx, id)

        if (heir == null) {
            AppFiles.authInbox(ctx, provider).delete()
            // And one from an older install that was never read in, so a
            // removed account cannot come back from it on the next start.
            AppFiles.legacyAuth(ctx, provider)?.delete()
        }
        return gone
    }

    // -- where the rest of the app reads it ------------------------------------

    /**
     * The credential in use for a provider: the active account's, out of
     * [Secrets], handed to the core in memory and never written down again.
     *
     * Surfshark's is the account's own username and password; Windscribe's
     * is the proxy credential its sign-in produced. A phone with no working
     * secret store falls back on the file the credential was handed over in,
     * kept privately for exactly that ([AppFiles.authInbox]).
     *
     * Reads [Secrets], so not on the main thread.
     */
    fun credentials(ctx: Context, provider: String): Pair<String, String>? {
        active(ctx, provider)?.let { a ->
            val id = a.optString("id")
            val pair = if (provider == WINDSCRIBE) {
                val user = Secrets.get(ctx, "proxyUser:$id")
                val pass = Secrets.get(ctx, "proxyPass:$id")
                if (user.isNullOrEmpty() || pass.isNullOrEmpty()) null else user to pass
            } else {
                val user = a.optString("username")
                val pass = Secrets.get(ctx, "pw:$id")
                if (user.isEmpty() || pass.isNullOrEmpty()) null else user to pass
            }
            if (pair != null) return pair
        }
        return try {
            readCredentials(AppFiles.authInbox(ctx, provider))
        } catch (e: Exception) {
            null
        }
    }

    /** Two lines: a username and a password, as OpenVPN would have been given. */
    private fun readCredentials(file: java.io.File): Pair<String, String>? {
        if (!file.isFile) return null
        val lines = file.readLines().map { it.trim() }.filter { it.isNotEmpty() }
        return if (lines.size >= 2) lines[0] to lines[1] else null
    }
}
