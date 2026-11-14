// Google Home on the home (optional): the thermostat dial, the photo frame and the greeting.
// Only drawn when the launcher says so (GoogleHome.kt, window.TVGH.info(); docs/CONFIG.md). The
// cameras are in cameras.js. The page asks the launcher for everything and never sees a Google
// secret: the dial gets the thermostat's numbers, the frame gets the album's picture addresses.
//   The dial: a round thermostat at the top right that takes the remote. OK takes hold of it,
//   Up and Down change the temperature, Left and Right the mode, OK or Back lets go.
//   The frame: the shared album's pictures at the top left, taking turns.
//   The greeting: "Good evening, Name", the date, the time and the weather, in one stack.
// Plain ES5 like the rest of the home.
(function () {
  "use strict";
  var ANSWER_MS = 20000;
  var READ_MS = 60000;          // how often the thermostat is read while the home shows
  var SEND_MS = 1200;           // a change is sent once the presses stop: Google counts requests
  var SETTLE_MS = 4000;         // then read again, once the thermostat has taken it
  var ALBUM_MS = 3 * 3600000;   // how often the album is read again

  var $ = function (id) { return document.getElementById(id); };
  var root = document.documentElement;
  var INFO = null;
  var showing = !document.hidden;

  function native() { return window.TVGH || null; }
  function info() {
    if (INFO) return INFO;
    var o = {};
    try { o = JSON.parse(native().info() || "{}") || {}; } catch (e) { o = {}; }
    var rest = parseInt(o.rest_minutes, 10);
    INFO = { thermostat: o.thermostat === true, photos: o.photos === true, greeting: o.greeting === true,
      photo_seconds: Math.max(60, Math.min(3600, parseInt(o.photo_seconds, 10) || 60)),
      rest_minutes: isNaN(rest) ? 10 : Math.max(0, Math.min(60, rest)) };
    return INFO;
  }

  var seq = 0, waiting = {};
  function ask(action, key, value) {
    return new Promise(function (resolve) {
      var id = "h" + (++seq).toString(36);
      var timer = setTimeout(function () { delete waiting[id]; resolve({ ok: false, error: "timeout" }); }, ANSWER_MS);
      waiting[id] = function (r) { clearTimeout(timer); resolve(r && typeof r === "object" ? r : { ok: false }); };
      try { native().request(id, action, key || "", value == null ? "" : String(value)); }
      catch (e) { clearTimeout(timer); delete waiting[id]; resolve({ ok: false, error: "native" }); }
    });
  }
  // cameras.js owns googleCams.reply; answers to this file's asks ("h...") are passed on from there.
  function reply(id, r) {
    var f = waiting[id];
    if (f) { delete waiting[id]; f(r); return true; }
    return false;
  }

  function el(tag, cls, text) {
    var e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text != null) e.textContent = text;
    return e;
  }

  // ======================= the greeting, date, time and weather =======================
  function ordinal(n) {
    var v = n % 100;
    if (v >= 11 && v <= 13) return n + "th";
    return n + (["th", "st", "nd", "rd"][n % 10] || "th");
  }
  function hello(h) { return h >= 5 && h < 12 ? "Good morning" : h >= 12 && h < 17 ? "Good afternoon" : h >= 17 && h < 21 ? "Good evening" : "Good night"; }
  var shown = "";
  // app.js calls this from its own clock, once a second: d the time, hm "4:50", ampm "pm" or "",
  // wx the weather ({ temp: "58°F", text: "Clear" }) or null, name who is watching or "".
  function tick(d, hm, ampm, wx, name) {
    if (!started || !info().greeting) return;
    var key = [d.getDate(), d.getHours(), hm, ampm, wx ? wx.temp + wx.text : "", name].join("|");
    if (key === shown) return;
    shown = key;
    var days = ["Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"];
    var months = ["January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"];
    $("gh-hello").textContent = hello(d.getHours()) + (name ? ", " + name : "");
    $("gh-date").textContent = days[d.getDay()] + ", " + months[d.getMonth()] + " " + ordinal(d.getDate());
    var time = $("gh-time");
    time.textContent = hm;
    if (ampm) time.appendChild(el("small", "", ampm));
    var w = $("gh-wx");
    w.textContent = "";
    if (wx) { w.appendChild(el("b", "", wx.temp)); w.appendChild(document.createTextNode(" " + wx.text)); }
  }

  // ======================= the photo frame =======================
  var photos = [], at = -1, front = 0, turnTimer = null, albumAt = 0, albumBusy = false, imgs = [];

  function showNext() {
    clearTimeout(turnTimer);
    if (!showing || !photos.length) return;
    var next = (at + 1) % photos.length;
    var back = imgs[1 - front];
    // the next picture is fetched behind the one showing, and only swapped in once it has arrived
    back.onload = function () {
      back.onload = back.onerror = null;
      imgs[front].classList.remove("on");
      back.classList.add("on");
      front = 1 - front;
      at = next;
      root.classList.add("has-ghphoto");
      turnTimer = setTimeout(showNext, info().photo_seconds * 1000);
    };
    back.onerror = function () {   // a picture that left the album, or an address that ran out
      back.onload = back.onerror = null;
      at = next;
      albumAt = 0;
      turnTimer = setTimeout(function () { readAlbum(); showNext(); }, 15000);
    };
    if (photos.length === 1 && at === 0) { turnTimer = setTimeout(showNext, info().photo_seconds * 1000); return; }
    back.src = photos[next];
  }

  function readAlbum() {
    if (!info().photos || albumBusy || !showing) return;
    if (albumAt && Date.now() - albumAt < ALBUM_MS) return;
    albumBusy = true;
    ask("photos").then(function (r) {
      albumBusy = false;
      if (!r || !r.ok || !Array.isArray(r.photos)) { albumAt = Date.now() - ALBUM_MS + 10 * 60000; return; }   // try again in ten minutes, keep what shows
      albumAt = Date.now();
      // only Google's own picture addresses are ever put on the page (a preview's mock.js may use its own)
      var list = r.photos.filter(function (u) { return typeof u === "string" && (window.MOCK_APPS ? true : /^https:\/\/lh3\.googleusercontent\.com\/pw\/[A-Za-z0-9_=-]+$/.test(u)); });
      var same = list.length === photos.length && list.every(function (u, k) { return u === photos[k]; });
      if (same) return;
      var was = photos.length;
      photos = list;
      if (!photos.length) { root.classList.remove("has-ghphoto"); clearTimeout(turnTimer); at = -1; return; }
      if (at >= photos.length) at = -1;
      if (!was || at < 0) showNext();
    });
  }

  // ======================= the thermostat dial =======================
  var T = null;            // what the thermostat last said
  var focused = false, held = false, readTimer = null, sendTimer = null, busy = false;
  var want = null;         // a temperature picked on the dial and not yet confirmed by the thermostat, in shown degrees
  var wantMode = null;     // the same for the mode
  var note = "";

  function F() { return !T || T.scale === "F"; }
  function shownDeg(c) { return c == null ? null : F() ? Math.round(c * 9 / 5 + 32) : Math.round(c * 2) / 2; }
  function celsius(v) { return F() ? Math.round((v - 32) * 5 / 9 * 100) / 100 : v; }
  function lowest() { return shownDeg(T ? T.min : 10); }
  function highest() { return shownDeg(T ? T.max : 32); }
  function step() { return F() ? 1 : 0.5; }
  function gap() { return F() ? 3 : 1.5; }
  function modeNow() { return wantMode || (T && T.eco ? "ECO" : T ? T.mode : ""); }
  function modes() {
    var m = (T && Array.isArray(T.modes) ? T.modes : []).slice();
    if (T && T.can_eco) m.push("ECO");
    return m;
  }
  // The target as the dial shows it: one number, or two in heat and cool.
  function target() {
    if (want) return want;
    if (!T) return null;
    var m = modeNow();
    if (m === "HEAT" && T.heat != null) return { a: shownDeg(T.heat) };
    if (m === "COOL" && T.cool != null) return { a: shownDeg(T.cool) };
    if (m === "HEATCOOL" && T.heat != null && T.cool != null) return { a: shownDeg(T.heat), b: shownDeg(T.cool) };
    return null;
  }

  var LABEL = { HEAT: "Heat to", COOL: "Cool to", HEATCOOL: "Heat · Cool", OFF: "Off", ECO: "Eco" };

  function drawDial() {
    var d = $("ghdial");
    if (!d) return;
    var m = modeNow(), tg = target();
    var glow = !T || !T.ok ? "" : T.status === "HEATING" ? " heating" : T.status === "COOLING" ? " cooling" : "";
    d.className = "ghdial" + glow + (focused ? " focus" : "") + (held ? " held" : "") + (T && T.ok && T.online === false ? " away" : "");
    $("ghd-mode").textContent = !T ? "" : !T.ok ? "No answer" : T.online === false ? "Offline" : (LABEL[m] || "");
    var big = $("ghd-big");
    big.textContent = "";
    if (tg && (m === "HEAT" || m === "COOL" || m === "HEATCOOL")) {
      big.appendChild(document.createTextNode(String(tg.a)));
      if (tg.b != null) { big.appendChild(el("i", "", "·")); big.appendChild(document.createTextNode(String(tg.b))); }
      big.className = "ghd-big" + (tg.b != null ? " two" : "");
    } else {
      big.className = "ghd-big word";
      big.textContent = !T || !T.ok ? "–" : m === "ECO" ? "Eco" : m === "OFF" ? "Off" : "–";
    }
    var amb = T && T.ok && T.ambient != null ? shownDeg(T.ambient) + "° inside" : "";
    $("ghd-in").textContent = note || amb;
    $("ghd-hint").textContent = held ? "▲▼ temperature   ◀▶ mode" : focused ? "OK to adjust" : "";
    // the ring: one tick lit for where the target sits between the lowest and highest setting
    var ticks = d.querySelectorAll(".ghd-tick"), lit = -1;
    if (tg && (m === "HEAT" || m === "COOL" || m === "HEATCOOL")) {
      var v = tg.b != null && m === "HEATCOOL" ? (tg.a + tg.b) / 2 : tg.a;
      lit = Math.round((v - lowest()) / (highest() - lowest()) * (ticks.length - 1));
    }
    for (var k = 0; k < ticks.length; k++) ticks[k].setAttribute("class", "ghd-tick" + (k <= lit ? " on" : "") + (k === lit ? " at" : ""));
  }

  function buildDial() {
    var d = $("ghdial");
    if (!d || d.firstChild) return;
    var NS = "http://www.w3.org/2000/svg", svg = document.createElementNS(NS, "svg");
    svg.setAttribute("viewBox", "0 0 200 200"); svg.setAttribute("aria-hidden", "true");
    for (var a = -135; a <= 135; a += 9) {
      var l = document.createElementNS(NS, "line");
      l.setAttribute("x1", "100"); l.setAttribute("y1", "13"); l.setAttribute("x2", "100"); l.setAttribute("y2", "25");
      l.setAttribute("transform", "rotate(" + a + " 100 100)"); l.setAttribute("class", "ghd-tick");
      svg.appendChild(l);
    }
    d.appendChild(svg);
    var mid = el("div", "ghd-mid");
    var mode = el("div", "ghd-mode"); mode.id = "ghd-mode";
    var big = el("div", "ghd-big"); big.id = "ghd-big";
    var inside = el("div", "ghd-in"); inside.id = "ghd-in";
    mid.appendChild(mode); mid.appendChild(big); mid.appendChild(inside);
    d.appendChild(mid);
    var hint = el("div", "ghd-hint"); hint.id = "ghd-hint";
    d.appendChild(hint);
  }

  function read() {
    clearTimeout(readTimer);
    if (!info().thermostat || !showing) return;
    if (busy || sendTimer) { readTimer = setTimeout(read, 2000); return; }   // never over a change on its way
    ask("thermostat").then(function (r) {
      if (!sendTimer && !busy) {
        T = r && r.ok ? r : { ok: false, scale: T ? T.scale : "F" };
        want = null; wantMode = null;
        if (T.ok) note = "";
        drawDial();
      }
      readTimer = setTimeout(read, READ_MS);
    });
  }

  function send() {
    sendTimer = null;
    if (!T || !T.ok) return;
    var jobs = [];
    busy = true;
    var chain = Promise.resolve({ ok: true });
    if (wantMode) {
      var m = wantMode;
      chain = chain.then(function () { return ask("tmode", "", m); });
      jobs.push("mode");
    }
    if (want) {
      var w = want;
      chain = chain.then(function (r) {
        if (!r || !r.ok) return r;
        return ask("tset", "", w.b != null ? celsius(w.a) + ":" + celsius(w.b) : celsius(w.a));
      });
      jobs.push("temp");
    }
    chain.then(function (r) {
      busy = false;
      if (sendTimer) return;   // more presses came meanwhile: they are sent next
      if (!r || !r.ok) {
        note = r && r.code === 429 ? "Too many changes, wait a minute" : "The thermostat did not take that";
        want = null; wantMode = null;
        drawDial();
      }
      clearTimeout(readTimer);
      readTimer = setTimeout(read, r && r.ok ? SETTLE_MS : 1500);
    });
  }
  function sendSoon() { clearTimeout(sendTimer); sendTimer = setTimeout(send, SEND_MS); }

  function nudge(dir) {
    var m = modeNow(), tg = target();
    if (!T || !T.ok || !tg || (m !== "HEAT" && m !== "COOL" && m !== "HEATCOOL")) {
      note = m === "ECO" ? "Eco is on: change the mode first" : m === "OFF" ? "Off: change the mode first" : "";
      drawDial();
      return;
    }
    note = "";
    var a = tg.a + dir * step(), b = tg.b != null ? tg.b + dir * step() : null;
    if (b != null) { if (a < lowest() || b > highest()) return; }
    else a = Math.max(lowest(), Math.min(highest(), a));
    want = b != null ? { a: a, b: b } : { a: a };
    drawDial();
    sendSoon();
  }
  function turnMode(dir) {
    var list = modes();
    if (!T || !T.ok || list.length < 2) return;
    var k = list.indexOf(modeNow());
    wantMode = list[(k + dir + list.length) % list.length];
    want = null;   // a temperature picked for the old mode does not carry over
    note = "";
    drawDial();
    sendSoon();
  }

  // ---- what app.js asks while the remote is on the dial ----
  function thermoOn() { return started && info().thermostat; }
  function paint(on) {
    if (!on && !focused && !held) return;
    if (!on) held = false;
    focused = !!on;
    drawDial();
  }
  // Returns "down" or "left" when the press leaves the dial, else nothing.
  function move(dx, dy) {
    if (!held) return dy > 0 ? "down" : dx < 0 ? "left" : null;
    if (dy) nudge(dy < 0 ? 1 : -1);
    else if (dx) turnMode(dx);
    return null;
  }
  function ok() { held = !held; note = ""; drawDial(); }
  // Back: lets go of the dial if it was held (true), else leaves it to app.js (false).
  function back() {
    if (!held) return false;
    held = false; drawDial();
    return true;
  }

  // ======================= rest mode =======================
  // A home that never changes marks an OLED. After rest_minutes without a press the dock, the dial
  // and the labels go, the picture dims, and the clock, the cameras and the photo drift slowly so
  // nothing stays on the same pixels (app.css, html.gh-rest). The first press only brings it all back.
  var restTimer = null, restOn = false;
  // app.js calls this on every press and whenever the home comes back; ok says the home itself
  // (not a menu or Settings) has the remote.
  function active(ok) {
    clearTimeout(restTimer); restTimer = null;
    if (restOn) { restOn = false; root.classList.remove("gh-rest"); }
    if (!started || !ok || !info().rest_minutes || window.MOCK_NO_REST) return;
    restTimer = setTimeout(function () {
      restTimer = null;
      if (document.hidden) return;
      restOn = true;
      if (held) { held = false; drawDial(); }
      root.classList.add("gh-rest");
    }, window.MOCK_REST_MS || info().rest_minutes * 60000);
  }
  function resting() { return restOn; }

  // ======================= starting, pausing =======================
  var started = false;
  function resume() {
    showing = true;
    if (!started) {
      // not yet: a preview's pretend launcher (mock.js) only arrives once the page's settings are in
      try { if (!native() || !native().on()) return; } catch (e) { return; }
    }
    var i = info();
    if (!started) {
      started = true;
      if (i.greeting) root.classList.add("has-ghstack");
      if (i.thermostat) { buildDial(); root.classList.add("has-ghdial"); drawDial(); }
      if (i.photos) { imgs = [$("ghphoto-a"), $("ghphoto-b")]; }
    }
    if (i.thermostat) read();
    if (i.photos) { readAlbum(); if (photos.length && !turnTimer) showNext(); }
  }
  function pause() {
    showing = false;
    clearTimeout(readTimer);
    clearTimeout(turnTimer); turnTimer = null;
    if (held) { held = false; drawDial(); }
    clearTimeout(restTimer); restTimer = null;
    if (restOn) { restOn = false; root.classList.remove("gh-rest"); }
  }
  document.addEventListener("visibilitychange", function () { if (document.hidden) pause(); else resume(); });

  window.googleHome = { reply: reply, tick: tick, resume: resume, pause: pause,
    thermoOn: thermoOn, paint: paint, move: move, ok: ok, back: back, active: active, resting: resting };
  resume();
})();
