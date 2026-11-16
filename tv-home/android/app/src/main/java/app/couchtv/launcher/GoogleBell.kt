package app.couchtv.launcher

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The Google Home doorbell (optional; GoogleHome.kt, docs/CONFIG.md). Only when config.json's
 * "google_home" has an "events_subscription". Then, while the screen is on, one thread waits on
 * Google for a doorbell press (GoogleHome.rings). When someone rings:
 *   the home is in front: it opens that doorbell's camera over the whole screen;
 *   (a camera that saw a person, where config.json asks for that, is only marked on the home when
 *   the home is in front, and is not announced at all over an app)
 *   an app is in front:   a small card slides in at the top right. OK on the remote opens the
 *                         home on that camera; any other button only hides the card, and it goes
 *                         by itself after 45 seconds.
 * The card needs "display over other apps", which apply.sh grants over ADB only when the doorbell
 * is set up (undo.sh takes it back). Without it there is no card over apps; the home still opens
 * the camera when it is in front. With the screen off nothing is asked at all.
 */
object GoogleBell {
    private const val RING_MS = 45_000L
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    @Volatile private var app: Context? = null
    private var card: View? = null

    /** Called when the launcher starts and whenever apply.sh sends new settings. Safe to repeat. */
    fun start(ctx: Context) {
        app = ctx.applicationContext
        if (GoogleHome.settings(ctx)?.events == null || running) return
        running = true
        Thread({ loop() }, "google-bell").apply { isDaemon = true }.start()
    }

    private fun awake(c: Context) = (c.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive

    private fun loop() {
        try {
            while (true) {
                val c = app ?: break
                val s = GoogleHome.settings(c)
                if (s?.events == null) break   // switched off: start() begins again when it is back
                if (!awake(c)) { Thread.sleep(5000); continue }
                val rang = try { GoogleHome.rings(s) } catch (e: Exception) { null }
                if (rang == null) { Thread.sleep(30000); continue }   // Google did not answer: try again in a while
                if (rang.isNotEmpty() && awake(c)) main.post { ring(c, rang[0]) }
                Thread.sleep(2000)
            }
        } catch (e: InterruptedException) {
        } finally {
            running = false
        }
    }

    private fun ring(c: Context, r: GoogleHome.Ring) {
        // The home is in front: the doorbell opens its camera; a person seen is only marked on the home.
        if (MainActivity.inFront) { open(c, r.key, if (r.kind == "person") "gh_alert" else "gh_ring"); return }
        if (r.kind == "person") return   // an app is in front: only the doorbell interrupts what is being watched
        main.removeCallbacks(timeUp)
        main.postDelayed(timeUp, RING_MS)
        if (card == null) show(c, r)
    }

    /** Brings the home forward on that camera (MainActivity.onNewIntent, then tv.ring on the page). */
    private fun open(c: Context, key: String, how: String = "gh_ring") {
        try {
            c.startActivity(Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(how, key))
        } catch (e: Exception) {}
    }

    private val timeUp = Runnable { hide() }

    private fun show(c: Context, r: GoogleHome.Ring) {
        if (!Settings.canDrawOverlays(c)) return
        val wm = c.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val m = c.resources.displayMetrics
        val dp = m.density
        val w = (maxOf(m.widthPixels, m.heightPixels) * 0.25f).toInt()
        val frame = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(Color.parseColor("#F00E1317")); cornerRadius = 18 * dp }
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, 18 * dp) }
            }
            clipToOutline = true
            elevation = 12 * dp
            setPadding((18 * dp).toInt(), (14 * dp).toInt(), (18 * dp).toInt(), (16 * dp).toInt())
        }
        frame.addView(TextView(c).apply {
            text = "Someone is at the door"
            setTextColor(Color.WHITE); textSize = 17f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        frame.addView(TextView(c).apply {
            text = r.name + "  \u00B7  OK to see the camera"
            setTextColor(Color.parseColor("#C9D3DC")); textSize = 13f
        })
        // It takes the remote while it is up: OK opens the camera, anything else only hides the card.
        frame.isFocusable = true
        frame.isFocusableInTouchMode = true
        frame.setOnKeyListener { _, code, e ->
            if (e.action == KeyEvent.ACTION_UP) {
                val ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER
                hide()
                if (ok) open(c, r.key)
            }
            true
        }
        @Suppress("DEPRECATION")
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE
        val lp = WindowManager.LayoutParams(w, WindowManager.LayoutParams.WRAP_CONTENT, type,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.END
            x = (m.widthPixels * 0.042f).toInt()
            y = (m.heightPixels * 0.056f).toInt()
        }
        try { wm.addView(frame, lp) } catch (e: Exception) { return }
        frame.requestFocus()
        card = frame
        frame.alpha = 0f; frame.translationY = -20 * dp
        frame.animate().alpha(1f).translationY(0f).setDuration(380).start()
    }

    private fun hide() {
        main.removeCallbacks(timeUp)
        val v = card ?: return
        card = null
        val c = app ?: return
        v.animate().alpha(0f).setDuration(220).withEndAction {
            try { (c.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v) } catch (e: Exception) {}
        }.start()
    }
}
