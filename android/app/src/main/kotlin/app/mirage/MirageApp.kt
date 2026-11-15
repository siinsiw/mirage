package app.mirage

import android.app.Application
import app.mirage.data.MirageCore

/**
 * Tells the core where its folders are before anything can ask for a list,
 * and holds the one [MirageCore] every screen reads. The service announces
 * the folders too, because either may be the first thing alive.
 */
class MirageApp : Application() {

    lateinit var core: MirageCore
        private set

    override fun onCreate() {
        super.onCreate()
        AppFiles.announce(this)
        core = MirageCore(this)
    }
}
