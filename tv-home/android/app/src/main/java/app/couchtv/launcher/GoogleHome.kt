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
 * Google Home on the home: cameras live, the thermostat, and a shared Google Photos album, the
 * first two through Google's Device Access (Smart Device Management) API. Optional and off unless config.json has a "google_home" section and .env has the four
 * Google values (docs/CONFIG.md, Google Home cameras): apply.sh hands them to the app as the
 * "gh_b64" extra, and the app keeps them in its own private store ("gh"), apart from the page's
 * settings. With no section there is nothing here: no calls, no camera row.
 *
 * The page never sees the client secret, the refresh token, an access token or a device id. It
 * asks through window.TVGH (Page below) and only these are allowed, each for the cameras this
 * account shared with the Device Access project and nothing else:
 *   list the cameras (a short key and a name each), start a live stream for one (the page makes
 *   the WebRTC offer, Google answers), keep that stream going, and stop it;
 *   read the thermostat, set its temperature (kept inside fixed limits) and its mode;
 *   list the pictures of the one shared album whose link came with the settings.
 * Every Device Access call goes to Google's two fixed addresses only and never follows a redirect.
 * The album is read from Google Photos' own two addresses only. Everything gives up after a few
 * seconds. The log gets counts and status codes only (how many cameras, how many pictures, what
 * Google answered), never a secret, an address or a device id.
 */
object GoogleHome {
    private const val STORE = "gh"
    private const val KEY = "section"
    private const val OFFLINE_HOST = "home.tvos.invalid"   // the launcher's own copy of the page
    private const val TOKEN_URL = "https://oauth2.googleapis.com/token"
    private const val API = "https://smartdevicemanagement.googleapis.com/v1/"
    private const val LIVE = "sdm.devices.commands.CameraLiveStream."
    private const val MOST = 4   // cameras on the home at once

    class Settings(
        val clientId: String, val clientSecret: String, val refreshToken: String, val project: String,
        val cameras: List<String>, val thermostat: Boolean, val album: String?, val photoSeconds: Int,
        val stayAwake: Boolean, val greeting: Boolean, val restMinutes: Int, val events: String?,
    )
    class Ring(val key: String, val name: String)
    private class Cam(val device: String, val name: String)

    private val CLIENT_ID = Regex("[0-9A-Za-z._-]{20,200}")
    private val CLIENT_SECRET = Regex("[0-9A-Za-z_-]{10,200}")
    private val REFRESH = Regex("[0-9A-Za-z/_.-]{20,2048}")
    private val PROJECT = Regex("[0-9a-f-]{20,64}")
    private val SUBSCRIPTION = Regex("projects/[a-z][a-z0-9-]{4,40}/subscriptions/[A-Za-z][A-Za-z0-9._~-]{2,254}")
    private const val PUBSUB = "https://pubsub.googleapis.com/v1/"
    private const val WEATHER = "https://api.open-meteo.com/v1/forecast"
    private const val CHIME = "sdm.devices.events.DoorbellChime.Chime"
    private const val RING_FRESH_MS = 90_000L   // a press older than this, found waiting, is not announced
    private val DEVICE = Regex("enterprises/[0-9a-f-]{20,64}/devices/[0-9A-Za-z_-]{10,200}")
    private val ALBUM = Regex("https://photos\\.app\\.goo\\.gl/[A-Za-z0-9]{5,40}|https://photos\\.google\\.com/share/[A-Za-z0-9_-]{20,200}\\?key=[A-Za-z0-9_-]{10,200}")

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
        synchronized(lock) { access = null; cams = null; thermo = null; thermoMode = null }
        GoogleBell.start(ctx)
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
        val album = o.optString("album").trim().takeIf { ALBUM.matches(it) }
        return Settings(id, secret, refresh, project, names.take(MOST), o.optBoolean("thermostat", false), album,
            o.optInt("photo_seconds", 60).coerceIn(60, 3600), o.optBoolean("stay_awake", false), o.optBoolean("greeting", false),
            o.optInt("rest_minutes", 10).coerceIn(0, 60),
            o.optString("events_subscription").trim().takeIf { SUBSCRIPTION.matches(it) })
    }

    // ---------------------------------------------------------------- talking to Google

    private val lock = Any()
    private var access: String? = null
    private var accessUntil = 0L
    private var cams: List<Cam>? = null
    private var thermo: String? = null       // the thermostat's device name, once found
    private var thermoMode: String? = null   // its mode when last read: decides which setpoint command is sent

    /** One call to one of Google's two addresses. The status code and the body, or -1 when it failed. */
    private fun call(url: String, bearer: String?, form: String?, json: JSONObject?, wait: Int = 12000): Pair<Int, JSONObject?> {
        if (!url.startsWith(TOKEN_URL) && !url.startsWith(API) && !url.startsWith(PUBSUB) && !url.startsWith(WEATHER)) return -1 to null
        var c: HttpURLConnection? = null
        return try {
            c = URL(url).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 5000
            c.readTimeout = wait
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

    private fun note(what: String) { try { android.util.Log.i("couch-tv", "google home: $what") } catch (e: Exception) {} }

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
            if (t == null) note("sign-in: Google answered $code ${o?.optString("error").orEmpty()}")
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
        if (code != 200 || o == null) { note("devices: Google answered $code"); return null }
        val all = mutableListOf<Cam>()
        val list = o.optJSONArray("devices") ?: JSONArray()
        var stat: String? = null
        for (k in 0 until list.length()) {
            val d = list.optJSONObject(k) ?: continue
            val device = d.optString("name")
            val traits = d.optJSONObject("traits") ?: continue
            if (stat == null && d.optString("type") == "sdm.devices.types.THERMOSTAT" && DEVICE.matches(device) &&
                device.startsWith("enterprises/${s.project}/")) stat = device
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
        synchronized(lock) { cams = out; thermo = stat }
        note("devices: ${all.size} cameras that stream, showing ${out.size}, thermostat ${if (stat != null) "found" else "none"}")
        return out
    }

    // ---------------------------------------------------------------- the doorbell

    /**
     * Waits, for up to about a minute, for someone to press a doorbell (GoogleBell.kt asks in a
     * loop while the screen is on). Google sends its devices' events to a Pub/Sub subscription in
     * the owner's own Cloud project; this reads that one subscription and nothing else. The
     * doorbells that were pressed just now, none when nothing happened, or null when Google did
     * not answer. Everything read is acknowledged, so old presses never pile up.
     */
    fun rings(s: Settings): List<Ring>? {
        val sub = s.events ?: return emptyList()
        val t = token(s) ?: return null
        val began = System.currentTimeMillis()
        val (code, o) = call("$PUBSUB$sub:pull", t, null, JSONObject().put("maxMessages", 20), wait = 80000)
        if (code == 401) { token(s, force = true); return null }
        if (code != 200 || o == null) {
            // no answer after a long wait is only a quiet minute; no answer at once is the network
            if (code == -1) return if (System.currentTimeMillis() - began > 20000) emptyList() else null
            note("doorbell: Google answered $code")
            return null
        }
        val got = o.optJSONArray("receivedMessages") ?: return emptyList()
        val list = cameras(s, false) ?: emptyList()
        val acks = JSONArray()
        val out = mutableListOf<Ring>()
        for (k in 0 until got.length()) {
            val m = got.optJSONObject(k) ?: continue
            m.optString("ackId").takeIf { it.isNotEmpty() }?.let { acks.put(it) }
            val data = try {
                JSONObject(String(android.util.Base64.decode(m.optJSONObject("message")?.optString("data").orEmpty(), android.util.Base64.DEFAULT), Charsets.UTF_8))
            } catch (e: Exception) { continue }
            val update = data.optJSONObject("resourceUpdate") ?: continue
            if (update.optJSONObject("events")?.has(CHIME) != true) continue
            val at = if (android.os.Build.VERSION.SDK_INT < 26) null
                else try { java.time.OffsetDateTime.parse(data.optString("timestamp")).toInstant().toEpochMilli() } catch (e: Exception) { null }
            if (at != null && System.currentTimeMillis() - at > RING_FRESH_MS) continue
            val n = list.indexOfFirst { it.device == update.optString("name") }
            if (n >= 0 && out.none { it.key == "c$n" }) out.add(Ring("c$n", list[n].name))
        }
        if (acks.length() > 0) call("$PUBSUB$sub:acknowledge", t, null, JSONObject().put("ackIds", acks))
        if (out.isNotEmpty()) note("doorbell: ${out.size} pressed")
        return out
    }

    // ---------------------------------------------------------------- the thermostat

    private const val T_MIN = 10.0   // Celsius: what a Nest takes (50 to 90 Fahrenheit)
    private const val T_MAX = 32.0
    private val T_MODES = listOf("HEAT", "COOL", "HEATCOOL", "OFF")

    private fun thermostat(s: Settings): String? {
        synchronized(lock) { thermo?.let { return it } }
        cameras(s, true) ?: return null
        return synchronized(lock) { thermo }
    }

    private fun num(o: JSONObject?, key: String): Double? =
        if (o != null && o.has(key) && !o.isNull(key)) o.optDouble(key).takeIf { it.isFinite() } else null

    /** The thermostat as the page draws it. Temperatures are Celsius; "scale" says what to show. */
    private fun readThermostat(s: Settings): JSONObject {
        val failed = JSONObject().put("ok", false).put("error", "failed")
        val device = thermostat(s) ?: return failed
        val (code, d) = api(s, device, null)
        val t = d?.optJSONObject("traits")
        if (code != 200 || t == null) return failed.put("code", code)
        fun trait(n: String) = t.optJSONObject("sdm.devices.traits.$n")
        val mode = trait("ThermostatMode")?.optString("mode").orEmpty()
        val modes = JSONArray()
        trait("ThermostatMode")?.optJSONArray("availableModes")?.let { a ->
            for (k in 0 until a.length()) a.optString(k).takeIf { it in T_MODES }?.let { modes.put(it) }
        }
        val eco = trait("ThermostatEco")
        val ecoModes = eco?.optJSONArray("availableModes")
        val canEco = ecoModes != null && (0 until ecoModes.length()).any { ecoModes.optString(it) == "MANUAL_ECO" }
        synchronized(lock) { thermoMode = mode.takeIf { it in T_MODES } }
        val set = trait("ThermostatTemperatureSetpoint")
        val out = JSONObject().put("ok", true)
            .put("name", trait("Info")?.optString("customName").orEmpty().take(40))
            .put("online", trait("Connectivity")?.optString("status") != "OFFLINE")
            .put("scale", if (trait("Settings")?.optString("temperatureScale") == "FAHRENHEIT") "F" else "C")
            .put("mode", mode).put("modes", modes)
            .put("eco", eco?.optString("mode") == "MANUAL_ECO").put("can_eco", canEco)
            .put("status", trait("ThermostatHvac")?.optString("status").orEmpty())
            .put("min", T_MIN).put("max", T_MAX)
        num(trait("Temperature"), "ambientTemperatureCelsius")?.let { out.put("ambient", it) }
        num(trait("Humidity"), "ambientHumidityPercent")?.let { out.put("humidity", it) }
        num(set, "heatCelsius")?.let { out.put("heat", it) }
        num(set, "coolCelsius")?.let { out.put("cool", it) }
        return out
    }

    private fun thermoCommand(s: Settings, command: String, params: JSONObject): JSONObject {
        val device = thermostat(s) ?: return JSONObject().put("ok", false).put("error", "failed")
        val (code, o) = api(s, "$device:executeCommand",
            JSONObject().put("command", "sdm.devices.commands.$command").put("params", params))
        return if (code == 200) JSONObject().put("ok", true)
        else JSONObject().put("ok", false).put("error", "failed").put("code", code)
            .put("status", o?.optJSONObject("error")?.optString("status").orEmpty())
    }

    private fun degrees(v: String): Double? = v.toDoubleOrNull()?.takeIf { it.isFinite() && it in T_MIN..T_MAX }

    /** A new target. One number while heating or cooling, "low:high" in heat and cool. Checked against the mode last read. */
    private fun setTemperature(s: Settings, value: String): JSONObject {
        val refused = JSONObject().put("ok", false).put("error", "refused")
        val set = "ThermostatTemperatureSetpoint."
        return when (synchronized(lock) { thermoMode }) {
            "HEAT" -> thermoCommand(s, set + "SetHeat", JSONObject().put("heatCelsius", degrees(value) ?: return refused))
            "COOL" -> thermoCommand(s, set + "SetCool", JSONObject().put("coolCelsius", degrees(value) ?: return refused))
            "HEATCOOL" -> {
                val parts = value.split(":")
                val low = degrees(parts.getOrNull(0).orEmpty()) ?: return refused
                val high = degrees(parts.getOrNull(1).orEmpty()) ?: return refused
                if (parts.size != 2 || high - low < 1.5) return refused
                thermoCommand(s, set + "SetRange", JSONObject().put("heatCelsius", low).put("coolCelsius", high))
            }
            else -> refused   // off, or not read yet
        }
    }

    private fun setMode(s: Settings, value: String): JSONObject {
        if (value == "ECO") return thermoCommand(s, "ThermostatEco.SetMode", JSONObject().put("mode", "MANUAL_ECO"))
        if (value !in T_MODES) return JSONObject().put("ok", false).put("error", "refused")
        // leaving eco first: Google refuses a mode change while eco is on. A failure here only means it was off already.
        thermoCommand(s, "ThermostatEco.SetMode", JSONObject().put("mode", "OFF"))
        val out = thermoCommand(s, "ThermostatMode.SetMode", JSONObject().put("mode", value))
        if (out.optBoolean("ok")) synchronized(lock) { thermoMode = value }
        return out
    }

    // ---------------------------------------------------------------- the shared photo album

    private val PHOTO_HOSTS = setOf("photos.app.goo.gl", "photos.google.com")
    private val PICTURE = Regex("\\[\"(https://lh3\\.googleusercontent\\.com/pw/[A-Za-z0-9_-]{20,400})\",(\\d{2,5}),(\\d{2,5})")
    private const val PHOTOS_MOST = 300

    /**
     * The shared album's page, as anyone with the link sees it (no sign-in, no cookies). Google has
     * no API for this, so the picture addresses are read out of the page: when Google changes the
     * page this returns nothing and the home keeps the pictures it had. Redirects are followed by
     * hand and only between Google Photos' own two addresses.
     */
    private fun albumPage(start: String, agent: String): String? {
        var at = start
        for (hop in 0 until 4) {
            val u = try { URL(at) } catch (e: Exception) { return null }
            if (u.protocol != "https" || u.host !in PHOTO_HOSTS || u.userInfo != null) return null
            var c: HttpURLConnection? = null
            try {
                c = u.openConnection() as HttpURLConnection
                c.instanceFollowRedirects = false
                c.connectTimeout = 6000
                c.readTimeout = 15000
                c.setRequestProperty("User-Agent", agent)
                c.setRequestProperty("Accept-Language", "en")
                val code = c.responseCode
                if (code in 300..399) {
                    at = URL(u, c.getHeaderField("Location") ?: return null).toString()
                    continue
                }
                if (code != 200) return null
                val out = ByteArrayOutputStream()
                c.inputStream.use { inp ->
                    val buf = ByteArray(32 * 1024)
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        if (out.size() > 8 * 1024 * 1024) return null
                    }
                }
                return String(out.toByteArray(), Charsets.UTF_8)
            } catch (e: Exception) {
                return null
            } finally {
                c?.disconnect()
            }
        }
        return null
    }

    private fun photos(s: Settings, agent: String): JSONObject {
        val album = s.album ?: return JSONObject().put("ok", false).put("error", "off")
        val page = albumPage(album, agent) ?: run { note("album: the page did not load"); return JSONObject().put("ok", false).put("error", "failed") }
        val seen = LinkedHashSet<String>()
        for (m in PICTURE.findAll(page)) {
            if (m.groupValues[2].toInt() < 200 || m.groupValues[3].toInt() < 200) continue   // not a photo: an icon or a face
            seen.add(m.groupValues[1])
            if (seen.size >= PHOTOS_MOST) break
        }
        note("album: ${seen.size} pictures (page of ${page.length} characters)")
        return JSONObject().put("ok", true).put("photos", JSONArray(seen.map { "$it=w900-h900" }))
    }

    private fun cam(s: Settings, key: String): Cam? {
        val n = key.removePrefix("c").toIntOrNull() ?: return null
        return (cameras(s, false) ?: return null).getOrNull(n)
    }

    // ---------------------------------------------------------------- what the page may ask

    private val SESSION = Regex("[\\x21-\\x7E]{8,4096}")

    private fun answer(s: Settings?, action: String, key: String, value: String, agent: String): JSONObject {
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
                if (code != 200 || r == null || r.optString("answerSdp").isEmpty()) { note("stream $key: Google answered $code ${o?.optJSONObject("error")?.optString("status").orEmpty()}"); return failed(code, o) }
                note("stream $key: started")
                JSONObject().put("ok", true).put("answer", r.optString("answerSdp"))
                    .put("session", r.optString("mediaSessionId")).put("expires", r.optString("expiresAt"))
            }
            "extend", "stop" -> {
                val c = cam(s, key) ?: return refused
                if (!SESSION.matches(value)) return refused
                val (code, o) = api(s, c.device + ":executeCommand",
                    JSONObject().put("command", LIVE + (if (action == "extend") "ExtendWebRtcStream" else "StopWebRtcStream"))
                        .put("params", JSONObject().put("mediaSessionId", value)))
                if (code != 200) { note("stream $key $action: Google answered $code"); return failed(code, o) }
                val r = o?.optJSONObject("results")
                JSONObject().put("ok", true).put("session", r?.optString("mediaSessionId").orEmpty().ifEmpty { value })
                    .put("expires", r?.optString("expiresAt").orEmpty())
            }
            "thermostat" -> if (s.thermostat) readThermostat(s) else refused
            "tset" -> if (s.thermostat) setTemperature(s, value) else refused
            "tmode" -> if (s.thermostat) setMode(s, value) else refused
            "photos" -> photos(s, agent)
            "weather" -> {
                // The page's own request to Open-Meteo sometimes fails on a TV; this is the same
                // request made from here. Only two numbers come from the page, nothing is sent but them.
                val at = value.split(",").mapNotNull { it.trim().toDoubleOrNull() }
                if (at.size != 2 || at[0] !in -90.0..90.0 || at[1] !in -180.0..180.0) return refused
                val (code, o) = call(WEATHER + "?latitude=${at[0]}&longitude=${at[1]}" +
                    "&current=temperature_2m,weather_code,is_day&timezone=auto&forecast_days=1", null, null, null)
                val c = o?.optJSONObject("current")
                if (code != 200 || c == null || !c.has("temperature_2m")) { note("weather: answered $code"); return failed(code, o) }
                JSONObject().put("ok", true).put("temp", c.optDouble("temperature_2m"))
                    .put("code", c.optInt("weather_code")).put("is_day", c.optInt("is_day") == 1)
            }
            else -> refused
        }
    }

    /**
     * window.TVGH on the page. on() says whether Google Home is set up and info() which parts (no secret, no
     * address); request() does one of the
     * allowed things and answers later through googleCams.reply(id, ...), so the page never waits
     * on the network. Only the launcher's own pages may ask: the bridge it found, or its saved copy.
     */
    class Page(ctx: Context, private val web: WebView) {
        private val app = ctx.applicationContext
        private val main = Handler(Looper.getMainLooper())
        private val io = Executors.newFixedThreadPool(3)
        private val ids = Regex("[a-z0-9]{1,16}")

        // The album's page as a computer's browser gets it: the TV's own page is cut down and lists
        // fewer pictures. Like "Request desktop site".
        private val agent = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

        @JavascriptInterface
        fun on(): Boolean = settings(app) != null

        /** One short line from the page for the log (what a camera tile is doing). Plain letters and digits only. */
        @JavascriptInterface
        fun log(what: String) {
            val line = what.take(120).filter { it in ' '..'~' }
            main.post { if (trusted(web.url)) note("page: $line") }
        }

        @JavascriptInterface
        fun info(): String {
            val s = settings(app) ?: return "{}"
            return JSONObject().put("thermostat", s.thermostat).put("photos", s.album != null)
                .put("photo_seconds", s.photoSeconds).put("greeting", s.greeting).put("rest_minutes", s.restMinutes).put("doorbell", s.events != null).toString()
        }

        @JavascriptInterface
        fun request(id: String, action: String, key: String, value: String) {
            if (!ids.matches(id)) return
            main.post {
                if (!trusted(web.url)) { reply(id, JSONObject().put("ok", false).put("error", "refused")); return@post }
                io.execute {
                    val out = try { answer(settings(app), action, key, value, agent) } catch (e: Exception) { JSONObject().put("ok", false) }
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
