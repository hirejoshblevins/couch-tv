package app.couchtv.launcher

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.Toast

/**
 * The way out, by hand: press Home five times quickly and couch-tv stands aside for the TV's own
 * home screen (Google TV) until it is asked back. While it stands aside the Home button watch
 * leaves the TV's home alone and a Home press that reaches couch-tv is passed on to that home, a
 * restart included. Five quick presses again, or opening Couch TV from the TV's list of apps,
 * brings couch-tv back.
 *
 * All of it is here and in FireWatch and MainActivity, not in the page, so it still works when the
 * page is broken. The presses are counted from the Home key where the TV lets the Home button
 * watch see it, and otherwise from each time a Home press shows (the TV's home coming forward, or
 * couch-tv being asked to the front as the home).
 */
object Failsafe {
    private const val PREFS = "failsafe"
    private const val PRESSES = 5
    private const val WITHIN_MS = 3000L
    private val times = ArrayList<Long>()
    @Volatile private var cached: Boolean? = null
    /** True once the Home key itself was seen: then only key presses are counted. */
    @Volatile var keysSeen = false

    fun on(c: Context): Boolean = cached ?: runCatching {
        c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("aside", false)
    }.getOrDefault(false).also { cached = it }

    fun set(c: Context, aside: Boolean) {
        if (on(c) == aside) return
        cached = aside
        runCatching { c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("aside", aside).commit() }
        Log.i("couch-tv", "failsafe: " + if (aside) "standing aside for the TV's own home" else "couch-tv is the home again")
    }

    /** One Home press. True when it was the fifth quick one and the home was changed over. */
    @Synchronized fun homePress(c: Context): Boolean {
        val now = SystemClock.elapsedRealtime()
        times.add(now)
        times.removeAll { now - it > WITHIN_MS }
        if (times.size < PRESSES) return false
        times.clear()
        val app = c.applicationContext
        val aside = !on(app)
        set(app, aside)
        val main = Handler(Looper.getMainLooper())
        main.post {
            runCatching {
                Toast.makeText(app, if (aside) "Google TV home. Press Home 5 times quickly to go back to Couch TV."
                    else "Back to Couch TV.", Toast.LENGTH_LONG).show()
            }
        }
        // after the press itself has had its effect
        main.postDelayed({
            runCatching {
                if (aside) CrashGuard.openStockHome(app, "asked for by hand")
                else app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }, 500)
        return true
    }
}
