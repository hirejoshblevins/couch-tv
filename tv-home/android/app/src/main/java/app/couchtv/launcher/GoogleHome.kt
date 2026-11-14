package app.couchtv.launcher

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

/**
 * Google Home cameras on the home, live, through Google's Device Access (Smart Device Management)
 * API. Optional and off unless config.json has a "google_home" section and .env has the four
 * Google values (docs/CONFIG.md, Google Home cameras): apply.sh hands them to the app as the
 * "gh_b64" extra, and the app keeps them in its own private store ("gh"), apart from the page's
 * settings. With no section there is nothing here: no calls, no camera row.
 *
 * The page never sees the client secret, the refresh token, an access token or a device id. It
 * asks through window.TVGH (Page below) and only these are allowed, each for the cameras this
 * account shared with the Device Access project and nothing else:
 *   list the cameras (a short key and a name each), start a live stream for one (the page makes
 *   the WebRTC offer, Google answers), keep that stream going, and stop it.
 * Every call goes to Google's two fixed addresses only, never follows a redirect, and gives up
 * after a few seconds. Nothing here is ever logged.
 */
object GoogleHome {
    private const val STORE = "gh"
    private const val KEY = "section"
    private const val OFFLINE_HOST = "home.tvos.invalid"   // the launcher's own copy of the page
    private const val TOKEN_URL = "https://oauth2.googleapis.com/token"
    private const val API = "https://smartdevicemanagement.googleapis.com/v1/"
    private const val LIVE = "sdm.devices.commands.CameraLiveStream."
    private const val MOST = 4   // cameras on the home at once

    class Settings(val clientId: String, val clientSecret: String, val refreshToken: String, val project: String, val cameras: List<String>)
    private class Cam(val device: String, val name: String)

    private val CLIENT_ID = Regex("[0-9A-Za-z._-]{20,200}")
    private val CLIENT_SECRET = Regex("[0-9A-Za-z_-]{10,200}")
    private val REFRESH = Regex("[0-9A-Za-z/_.-]{20,2048}")
    private val PROJECT = Regex("[0-9a-f-]{20,64}")
    private val DEVICE = Regex("enterprises/[0-9a-f-]{20,64}/devices/[0-9A-Za-z_-]{10,200}")

    @Volatile private var cached: Settings? = null
    @Volatile private var cachedFrom: String? = null

    private fun store(ctx: Context) = ctx.applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE)

    /** What apply.sh sent. Replaced whole, never merged. "none" or anything that does not check out switches it off. */
    fun readExtras(ctx: Context, i: Intent) {
        val v = i.getStringExtra("gh_b64")?.trim() ?: return
        val e = store(ctx).edit()
        val json = if (v.isEmpty() || v == "none") null else try {
            String(android.util.Base64.decode(v, android.util.Base64.DEFAULT), Charsets.UTF_8)
                .takeIf { parse(JSONObject(it)) != null }
        } catch (x: Exception) { null }
        if (json == null) e.remove(KEY) else e.putString(KEY, json)
        e.commit()
        cached = null; cachedFrom = null
        synchronized(lock) { access = null; cams = null }
    }

    /** The section, checked, or null when Google Home is off. */
    fun settings(ctx: Context): Settings? {
        val raw = store(ctx).getString(KEY, null) ?: return null
        if (raw == cachedFrom) return cached
        val s = try { parse(JSONObject(raw)) } catch (e: Exception) { null }
        cached = s; cachedFrom = raw
        return s
    }

    private fun parse(o: JSONObject): Settings? {
        val id = o.optString("client_id").trim().takeIf { CLIENT_ID.matches(it) } ?: return null
        val secret = o.optString("client_secret").trim().takeIf { CLIENT_SECRET.matches(it) } ?: return null
        val refresh = o.optString("refresh_token").trim().takeIf { REFRESH.matches(it) } ?: return null
        val project = o.optString("project_id").trim().takeIf { PROJECT.matches(it) } ?: return null
        val names = mutableListOf<String>()
        o.optJSONArray("cameras")?.let { a ->
            for (k in 0 until a.length()) a.optString(k).trim().take(60).takeIf { it.isNotEmpty() }?.let { names.add(it) }
        }
        return Settings(id, secret, refresh, project, names.take(MOST))
    }

    // ---------------------------------------------------------------- talking to Google

    private val lock = Any()
    private var access: String? = null
    private var accessUntil = 0L
    private var cams: List<Cam>? = null

    /** One call to one of Google's two addresses. The status code and the body, or -1 when it failed. */
    private fun call(url: String, bearer: String?, form: String?, json: JSONObject?): Pair<Int, JSONObject?> {
        if (!url.startsWith(TOKEN_URL) && !url.startsWith(API)) return -1 to null
        var c: HttpURLConnection? = null
        return try {
            c = URL(url).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 5000
            c.readTimeout = 12000
            if (bearer != null) c.setRequestProperty("Authorization", "Bearer $bearer")
            val body = form?.toByteArray() ?: json?.toString()?.toByteArray()
            if (body != null) {
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Content-Type", if (form != null) "application/x-www-form-urlencoded" else "application/json")
                c.outputStream.use { it.write(body) }
            }
            val code = c.responseCode
            val stream = (if (code in 200..299) c.inputStream else c.errorStream) ?: return code to null
            val out = ByteArrayOutputStream()
            stream.use { inp ->
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = inp.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > 1024 * 1024) return -1 to null
                }
            }
            code to try { JSONObject(String(out.toByteArray(), Charsets.UTF_8)) } catch (e: Exception) { null }
        } catch (e: Exception) {
            -1 to null
        } finally {
            c?.disconnect()
        }
    }

    private fun enc(v: String) = URLEncoder.encode(v, "UTF-8")

    /** A fresh access token, from the refresh token. Null when Google refused or did not answer. */
    private fun token(s: Settings, force: Boolean = false): String? {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            val have = access
            if (!force && have != null && now < accessUntil) return have
            val form = "client_id=${enc(s.clientId)}&client_secret=${enc(s.clientSecret)}" +
                "&refresh_token=${enc(s.refreshToken)}&grant_type=refresh_token"
            val (code, o) = call(TOKEN_URL, null, form, null)
            val t = o?.optString("access_token")?.takeIf { code == 200 && it.isNotEmpty() }
            access = t
            accessUntil = now + ((o?.optLong("expires_in", 0L) ?: 0L) - 120).coerceAtLeast(60) * 1000
            return t
        }
    }

    /** A call with the access token; one more try with a new token when Google says it has run out. */
    private fun api(s: Settings, path: String, body: JSONObject?): Pair<Int, JSONObject?> {
        val t = token(s) ?: return -1 to null
        val first = call(API + path, t, null, body)
        if (first.first != 401) return first
        val again = token(s, force = true) ?: return first
        return call(API + path, again, null, body)
    }

    /** The cameras to show: every shared camera or doorbell that can stream, or the ones named in config.json, in that order. */
    private fun cameras(s: Settings, refresh: Boolean): List<Cam>? {
        synchronized(lock) { cams?.takeIf { !refresh }?.let { return it } }
        val (code, o) = api(s, "enterprises/${s.project}/devices", null)
        if (code != 200 || o == null) return null
        val all = mutableListOf<Cam>()
        val list = o.optJSONArray("devices") ?: JSONArray()
        for (k in 0 until list.length()) {
            val d = list.optJSONObject(k) ?: continue
            val device = d.optString("name")
            val traits = d.optJSONObject("traits") ?: continue
            val live = traits.optJSONObject("sdm.devices.traits.CameraLiveStream") ?: continue
            val protocols = live.optJSONArray("supportedProtocols")
            val webrtc = protocols != null && (0 until protocols.length()).any { protocols.optString(it) == "WEB_RTC" }
            if (!webrtc || !DEVICE.matches(device) || !device.startsWith("enterprises/${s.project}/")) continue
            var name = traits.optJSONObject("sdm.devices.traits.Info")?.optString("customName")?.trim().orEmpty()
            if (name.isEmpty()) name = d.optJSONArray("parentRelations")?.optJSONObject(0)?.optString("displayName")?.trim().orEmpty()
            all.add(Cam(device, name.ifEmpty { "Camera" }.take(40)))
        }
        val picked = if (s.cameras.isEmpty()) all else s.cameras.mapNotNull { want -> all.firstOrNull { it.name.equals(want, ignoreCase = true) } }
        val out = picked.distinctBy { it.device }.take(MOST)
        synchronized(lock) { cams = out }
        return out
    }

    private fun cam(s: Settings, key: String): Cam? {
        val n = key.removePrefix("c").toIntOrNull() ?: return null
        return (cameras(s, false) ?: return null).getOrNull(n)
    }

    // ---------------------------------------------------------------- what the page may ask

    private val SESSION = Regex("[\\x21-\\x7E]{8,4096}")

    private fun answer(s: Settings?, action: String, key: String, value: String): JSONObject {
        if (s == null) return JSONObject().put("ok", false).put("error", "off")
        val refused = JSONObject().put("ok", false).put("error", "refused")
        fun failed(code: Int, o: JSONObject?) = JSONObject().put("ok", false).put("error", "failed").put("code", code)
            .put("status", o?.optJSONObject("error")?.optString("status").orEmpty())
        return when (action) {
            "cameras" -> {
                val list = cameras(s, true) ?: return JSONObject().put("ok", false).put("error", "failed")
                JSONObject().put("ok", true).put("cameras",
                    JSONArray(list.mapIndexed { n, c -> JSONObject().put("key", "c$n").put("name", c.name) }))
            }
            "offer" -> {
                val c = cam(s, key) ?: return refused
                if (value.length !in 100..60000 || !value.startsWith("v=0")) return refused
                val (code, o) = api(s, c.device + ":executeCommand",
                    JSONObject().put("command", LIVE + "GenerateWebRtcStream").put("params", JSONObject().put("offerSdp", value)))
                val r = o?.optJSONObject("results")
                if (code != 200 || r == null || r.optString("answerSdp").isEmpty()) return failed(code, o)
                JSONObject().put("ok", true).put("answer", r.optString("answerSdp"))
                    .put("session", r.optString("mediaSessionId")).put("expires", r.optString("expiresAt"))
            }
            "extend", "stop" -> {
                val c = cam(s, key) ?: return refused
                if (!SESSION.matches(value)) return refused
                val (code, o) = api(s, c.device + ":executeCommand",
                    JSONObject().put("command", LIVE + (if (action == "extend") "ExtendWebRtcStream" else "StopWebRtcStream"))
                        .put("params", JSONObject().put("mediaSessionId", value)))
                if (code != 200) return failed(code, o)
                val r = o?.optJSONObject("results")
                JSONObject().put("ok", true).put("session", r?.optString("mediaSessionId").orEmpty().ifEmpty { value })
                    .put("expires", r?.optString("expiresAt").orEmpty())
            }
            else -> refused
        }
    }

    /**
     * window.TVGH on the page. on() says whether Google Home is set up; request() does one of the
     * allowed things and answers later through googleCams.reply(id, ...), so the page never waits
     * on the network. Only the launcher's own pages may ask: the bridge it found, or its saved copy.
     */
    class Page(ctx: Context, private val web: WebView) {
        private val app = ctx.applicationContext
        private val main = Handler(Looper.getMainLooper())
        private val io = Executors.newFixedThreadPool(3)
        private val ids = Regex("[a-z0-9]{1,16}")

        @JavascriptInterface
        fun on(): Boolean = settings(app) != null

        @JavascriptInterface
        fun request(id: String, action: String, key: String, value: String) {
            if (!ids.matches(id)) return
            main.post {
                if (!trusted(web.url)) { reply(id, JSONObject().put("ok", false).put("error", "refused")); return@post }
                io.execute {
                    val out = try { answer(settings(app), action, key, value) } catch (e: Exception) { JSONObject().put("ok", false) }
                    main.post { reply(id, out) }
                }
            }
        }

        private fun trusted(url: String?): Boolean {
            if (url == null) return false
            if (url.startsWith("http://$OFFLINE_HOST/")) return true
            val bridge = app.getSharedPreferences("home", Context.MODE_PRIVATE).getString("bridge", null) ?: return false
            return url.startsWith("$bridge/")
        }

        private fun reply(id: String, out: JSONObject) {
            try { web.evaluateJavascript("window.googleCams && googleCams.reply(\"$id\", $out)", null) } catch (e: Exception) {}
        }
    }
}
