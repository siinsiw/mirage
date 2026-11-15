package app.mirage

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

/**
 * Passwords, kept where the rest of the app cannot casually read them.
 *
 * The desktop seals these with the Windows account's own key and, where that
 * is unavailable, stores nothing at all and keeps the account without a
 * password - because a roster entry you have to retype a password into is
 * worth having and one that leaked a password is not. Same rule here.
 *
 * The store is separate from the ordinary preferences on purpose. `mirage.xml`
 * is written by setup-phone.sh through `run-as` on a debug build and is a
 * reasonable place for a domain and a mode; it is not a reasonable place for
 * a password, and having two files means nothing has to remember which keys
 * were which.
 *
 * Nothing in here ever crosses the bridge. The page is told whether a
 * password is set, never what it is - a screenshot of a settings sheet should
 * not be a leak.
 */
object Secrets {

    private const val FILE = "mirage.secrets"

    // The alias MasterKeys.getOrCreate hands back for AES256_GCM_SPEC. Named
    // here only so a store that cannot be opened can have its key deleted
    // along with it; nothing else in the app uses this key.
    private const val MASTER_KEY = "_androidx_security_master_key_"

    @Volatile private var store: SharedPreferences? = null

    // Set once opening has failed even after starting over, so every later
    // call does not pay for another trip through the keystore to fail again.
    @Volatile private var broken = false

    /**
     * Opening this builds a keyset through the Android keystore, which is
     * slow enough to matter on the first call and has been known to throw on
     * devices whose keystore is in a bad state. A failure here is not fatal:
     * it means secrets are not stored, which is a thing the roster already
     * knows how to be.
     *
     * Synchronized: the window's first refresh and the service's connect can
     * both arrive here at once on a cold start, and two threads building a
     * keyset into one file is how a keyset ends up written twice, half of
     * each.
     *
     * A failure is first retried as it is, once: the keystore daemon busy
     * at boot, a disk that was not ready - those pass, and the passwords in
     * the store are still good.
     *
     * Only when the retry fails too, and fails on the keys themselves, is the
     * store started over. That is a store whose key is gone - a phone
     * restored or moved to another, where the file came along and the
     * keystore key it was sealed with could not, or a key Android has
     * invalidated - and it will never open again: every launch would fail
     * the same way and the app would go on silently storing nothing. So the
     * file and the key are deleted and a new store made, and the passwords
     * that were in the old one are lost, which the log says. They were
     * unreadable already; this only stops pretending otherwise. The roster
     * notices on its own - an account whose password is not here reads as
     * signed out.
     *
     * Anything else - an IOException, a runtime error that is not about a
     * key - leaves the file alone: it is not known to be lost, and deleting
     * it would make it so. Nothing is stored meanwhile, and opening is tried
     * again after a pause.
     */
    @Synchronized
    private fun store(ctx: Context): SharedPreferences? {
        store?.let { return it }
        if (broken) return null
        if (SystemClock.elapsedRealtime() < retryAt) return null
        val app = ctx.applicationContext
        val first = try {
            return openStore(app)
        } catch (e: Exception) {
            e
        }
        Log.w(MirageVpnService.TAG, "secret store would not open (${first.javaClass.simpleName}: " +
            "${first.message}); trying again")
        val second = try {
            return openStore(app)
        } catch (e: Exception) {
            e
        }
        if (!isKeyFailure(second)) {
            // Not the keys: nothing is known to be lost. Left as it is.
            Log.w(MirageVpnService.TAG, "secret store still would not open (${second.javaClass.simpleName}: " +
                "${second.message}); leaving it as it is and trying again later")
            retryAt = SystemClock.elapsedRealtime() + RETRY_AFTER_MS
            return null
        }
        Log.w(MirageVpnService.TAG, "secret store cannot be decrypted (${second.javaClass.simpleName}: " +
            "${second.message}); starting it over - the passwords stored in it are lost")
        return try {
            app.deleteSharedPreferences(FILE)
            forgetMasterKey()
            openStore(app)
        } catch (third: Exception) {
            Log.e(MirageVpnService.TAG, "no secret store: ${third.message}")
            broken = true
            null
        }
    }

    // After a failure that is not about the keys, how long before opening is
    // tried again - so a store that is down is not retried on every read.
    private const val RETRY_AFTER_MS = 30_000L
    @Volatile private var retryAt = 0L

    /**
     * Whether [e] says the store's keys cannot be used: anything from the
     * JCA's security exceptions (KeyStoreException, UnrecoverableKeyException,
     * InvalidKeyException and KeyPermanentlyInvalidatedException under it,
     * AEADBadTagException, Tink's GeneralSecurityException for a keyset that
     * will not decrypt), the keystore provider's ProviderException, or
     * Android's own android.security.KeyStoreException - found anywhere in the
     * cause chain, because Tink and the keystore both wrap.
     */
    private fun isKeyFailure(e: Throwable): Boolean {
        var t: Throwable? = e
        var depth = 0
        while (t != null && depth < 8) {
            if (t is java.security.GeneralSecurityException) return true
            if (t is java.security.ProviderException) return true
            if (t.javaClass.name == "android.security.KeyStoreException") return true
            t = t.cause
            depth++
        }
        return false
    }

    @Suppress("DEPRECATION")
    private fun openStore(ctx: Context): SharedPreferences {
        // The older four-argument create, and MasterKeys rather than
        // MasterKey. Both are deprecated in later releases of this
        // library and both are what 1.0.0 has - which is the version
        // pinned, because it is the one that certainly exists and this
        // line cannot be relied on to tell us about the others.
        val alias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        return EncryptedSharedPreferences.create(
            FILE, alias, ctx,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        ).also { store = it }
    }

    /**
     * The keystore key the store was sealed with. Deleted with the store, so
     * the next one is made under a fresh key: a key that exists and cannot be
     * used (invalidated, or half-restored) would fail the retry exactly as it
     * failed the first try.
     */
    private fun forgetMasterKey() {
        try {
            val ks = java.security.KeyStore.getInstance("AndroidKeyStore")
            ks.load(null)
            if (ks.containsAlias(MASTER_KEY)) ks.deleteEntry(MASTER_KEY)
        } catch (e: Exception) {
            Log.w(MirageVpnService.TAG, "keystore key not deleted: ${e.message}")
        }
    }

    /** Store one, or store nothing and say so. */
    fun put(ctx: Context, key: String, value: String?): Boolean {
        val s = store(ctx) ?: return false
        return try {
            if (value.isNullOrEmpty()) s.edit().remove(key).commit()
            else s.edit().putString(key, value).commit()
        } catch (e: Exception) {
            Log.w(MirageVpnService.TAG, "secret not stored: ${e.message}")
            false
        }
    }

    fun get(ctx: Context, key: String): String? = try {
        store(ctx)?.getString(key, null)
    } catch (e: Exception) {
        null
    }

    fun drop(ctx: Context, key: String) {
        put(ctx, key, null)
    }

    /** Everything belonging to one account, when it is removed. */
    fun dropAll(ctx: Context, id: String) {
        for (kind in listOf("pw", "session", "proxyUser", "proxyPass")) {
            drop(ctx, "$kind:$id")
        }
    }
}
