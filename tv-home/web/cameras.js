// Google Home cameras (optional): a row of live feeds above the apps.
// Only drawn when the launcher says Google Home is set up (GoogleHome.kt, docs/CONFIG.md). The
// page makes each WebRTC offer and plays the video; the launcher (window.TVGH) carries the offer
// to Google and brings the answer back, so no Google secret or device id ever reaches this page.
// A stream is asked for only while the home is showing, renewed before Google's five minutes run
// out, and stopped when an app comes to the front. The row takes the remote: Left and Right move
// along the cameras, OK opens the one in focus over the whole screen (Left and Right then change
// camera, OK or Back closes it). Plain ES5 like the rest of the home.
(function () {
  "use strict";
  var ANSWER_MS = 20000;      // how long the launcher gets to answer
  var RENEW_MS = 210000;      // Google ends a stream after five minutes: renew at three and a half
  var STAGGER_MS = 1200;      // start the cameras one after another, not all at once
  var WATCH_MS = 20000;       // a picture that stopped moving for two looks is started again
  var RETRY_MS = 15000, RETRY_MOST_MS = 300000;
  var LIMITED_MS = 70000;     // Google said "too many requests": it counts them by the minute
  var NO_PICTURE_MS = 15000;  // connected but no picture after this: the TV has run out of video decoders
  var ROTATE_MS = 60000;      // then the cameras take turns: how long one waits, held on its last picture
  var GRACE_MS = 45000;       // an app in front for less than this: the streams stay up. Google
                              // allows only a few starts a minute, so a quick look at another
                              // app must not cost three stops and three starts.

  var $ = function (id) { return document.getElementById(id); };
  var cams = [];
  var showing = !document.hidden;
  var listed = false, listTimer = null, listTries = 0, sleepTimer = null;

  var maxLive = 99;           // how many cameras this TV can play at once, once it is known
  var rotateTimer = null;
  function native() { return window.TVGH || null; }
  function log(what) { try { if (native() && native().log) native().log(String(what)); } catch (e) {} }
  function on() { try { return !!(native() && native().on()); } catch (e) { return false; } }

  // ---------------- asking the launcher ----------------
  var seq = 0, waiting = {};
  function ask(action, key, value) {
    return new Promise(function (resolve) {
      var id = "g" + (++seq).toString(36);
      var timer = setTimeout(function () { delete waiting[id]; resolve({ ok: false, error: "timeout" }); }, ANSWER_MS);
      waiting[id] = function (r) { clearTimeout(timer); resolve(r && typeof r === "object" ? r : { ok: false }); };
      try { native().request(id, action, key || "", value == null ? "" : String(value)); }
      catch (e) { clearTimeout(timer); delete waiting[id]; resolve({ ok: false, error: "native" }); }
    });
  }
  function reply(id, r) {
    var f = waiting[id];
    if (f) { delete waiting[id]; f(r); return; }
    if (window.googleHome) window.googleHome.reply(id, r);   // the dial's and the frame's asks (ghome.js)
  }

  // ---------------- one camera ----------------
  function mark(c, state) {
    if (state) c.state = state;
    c.el.className = "cam" + (c.state === "live" ? " live" : "") + (c.parked ? " still" : "") + (c.focus ? " focus" : "") + (c.big ? " big" : "") + (c.big && ringing ? " ring" : "");
  }

  // Google wants the offer whole: wait for the candidates, but never for long.
  function gathered(pc) {
    return new Promise(function (resolve) {
      if (pc.iceGatheringState === "complete") return resolve();
      var t = setTimeout(resolve, 2500);
      pc.onicegatheringstatechange = function () {
        if (pc.iceGatheringState === "complete") { clearTimeout(t); resolve(); }
      };
    });
  }

  function start(c) {
    if (!showing || c.pc || c.parked) return;
    clearTimeout(c.retry); c.retry = null;
    if (!window.RTCPeerConnection) return;   // a WebView with no WebRTC: the tile stays a name
    var pc, opts;
    try { pc = new RTCPeerConnection(); } catch (e) { return; }
    c.pc = pc;
    // Google's order: audio, video, then the data channel. Audio is asked for and never played.
    try {
      pc.addTransceiver("audio", { direction: "recvonly" });
      pc.addTransceiver("video", { direction: "recvonly" });
    } catch (e) { opts = { offerToReceiveAudio: true, offerToReceiveVideo: true }; }
    try { pc.createDataChannel("dataSendChannel"); } catch (e) {}
    pc.ontrack = function (e) {
      if (c.pc !== pc || !e.track || e.track.kind !== "video") return;
      try { c.video.srcObject = (e.streams && e.streams[0]) || new MediaStream([e.track]); } catch (x) {}
      var p = c.video.play();
      if (p && p.catch) p.catch(function () {});
    };
    pc.oniceconnectionstatechange = function () {
      if (c.pc === pc && (pc.iceConnectionState === "failed" || pc.iceConnectionState === "closed")) again(c);
    };
    pc.createOffer(opts).then(function (o) { return pc.setLocalDescription(o); })
      .then(function () { return gathered(pc); })
      .then(function () {
        if (c.pc !== pc) throw new Error("gone");
        return ask("offer", c.key, pc.localDescription.sdp);
      })
      .then(function (r) {
        if (c.pc !== pc) {   // the home went behind an app while Google was answering
          if (r && r.ok && r.session) ask("stop", c.key, r.session);
          return;
        }
        if (!r || !r.ok || !r.answer) { c.limited = !!r && r.code === 429; throw new Error("refused"); }
        c.session = r.session || null;
        c.renew = setTimeout(function () { renew(c, pc); }, RENEW_MS);
        c.noPic = setTimeout(function () { if (c.pc === pc && c.state !== "live") noPicture(c); }, NO_PICTURE_MS);
        return pc.setRemoteDescription({ type: "answer", sdp: r.answer });
      })
      .catch(function () { if (c.pc === pc) again(c); });
  }

  function renew(c, pc) {
    if (c.pc !== pc || !c.session) return;
    ask("extend", c.key, c.session).then(function (r) {
      if (c.pc !== pc) return;
      if (!r || !r.ok) { c.limited = !!r && r.code === 429; return again(c); }
      if (r.session) c.session = r.session;
      c.renew = setTimeout(function () { renew(c, pc); }, RENEW_MS);
    });
  }

  // ---- a TV that cannot play every camera at once ----
  // Some TVs have only two video decoders: the third stream connects and never shows a picture.
  // Then the cameras take turns. The one waiting keeps its last picture (a still), and the one
  // in focus over the whole screen, or whose doorbell rang, always gets a turn at once.
  function liveCams() { return cams.filter(function (x) { return x.state === "live"; }); }
  function noPicture(c) {
    var n = liveCams().length;
    c.blank = (c.blank || 0) + 1;
    log("cam " + c.key + " connected, no picture (" + c.blank + "); " + n + " others playing");
    // once can be a bad start: try that camera again. Twice in a row, with others playing, is the TV's limit.
    if (c.blank >= 2 && n >= 1 && n < cams.length) {
      maxLive = n;
      log("taking turns: " + n + " at a time");
      park(c);
      rotateSoon();
    } else again(c);
  }
  function still(c) {
    try {
      var v = c.video, k = c.canvas;
      if (!v.videoWidth) return;
      k.width = v.videoWidth; k.height = v.videoHeight;
      k.getContext("2d").drawImage(v, 0, 0, k.width, k.height);
    } catch (e) {}
  }
  function park(c) {
    if (c.state === "live") still(c);
    c.parked = true; c.parkedAt = Date.now();
    stop(c);
  }
  function rotateSoon() {
    clearTimeout(rotateTimer);
    if (maxLive >= cams.length || !showing) return;
    rotateTimer = setTimeout(function () { swap(null); rotateSoon(); }, ROTATE_MS);
  }
  // Give the camera that has waited longest (or `want`) a turn, in place of the one playing longest.
  function swap(want) {
    if (!showing) return;
    var waiting = cams.filter(function (x) { return x.parked; }).sort(function (a, b) { return a.parkedAt - b.parkedAt; });
    var inC = want && want.parked ? want : waiting[0];
    if (!inC) return;
    var keep = cams[at] && (big || focused) ? cams[at] : null;
    var playing = liveCams().filter(function (x) { return x !== keep && x !== inC; }).sort(function (a, b) { return a.since - b.since; });
    var outC = playing[0];
    if (!outC && liveCams().length >= maxLive) return;
    if (outC) park(outC);
    inC.parked = false; inC.tries = 0;
    mark(inC);
    clearTimeout(inC.retry);
    inC.retry = setTimeout(function () { start(inC); }, outC ? 900 : 0);   // a moment for the TV to free the decoder
  }

  function stop(c) {
    clearTimeout(c.noPic); c.noPic = null;
    clearTimeout(c.renew); c.renew = null;
    clearTimeout(c.retry); c.retry = null;
    var pc = c.pc, session = c.session;
    c.pc = null; c.session = null; c.last = -1; c.stalls = 0;
    if (pc) {
      pc.ontrack = null; pc.oniceconnectionstatechange = null;
      try { pc.close(); } catch (e) {}
    }
    try { c.video.srcObject = null; } catch (e) {}
    if (session) ask("stop", c.key, session);
    mark(c, "wait");
  }

  // Something went wrong: stop, and try again a little later each time.
  function again(c) {
    stop(c);
    if (!showing) return;
    var wait = Math.min(RETRY_MOST_MS, RETRY_MS * Math.pow(2, c.tries));
    if (c.limited) { wait = Math.max(wait, LIMITED_MS); c.limited = false; }
    c.tries += 1;
    c.retry = setTimeout(function () { start(c); }, wait);
  }

  // A stream can go quiet without ever saying so: look at the picture's own clock.
  setInterval(function () {
    if (!showing) return;
    for (var k = 0; k < cams.length; k++) {
      var c = cams[k];
      if (!c.pc || c.state !== "live") continue;
      var t = c.video.currentTime;
      if (t === c.last) { c.stalls += 1; if (c.stalls >= 2) again(c); }
      else { c.stalls = 0; c.last = t; }
    }
  }, WATCH_MS);

  // ---------------- the row ----------------
  function build(list) {
    var row = $("cams");
    if (!row) return;
    while (row.firstChild) row.removeChild(row.firstChild);
    cams = [];
    for (var k = 0; k < list.length; k++) {
      var el = document.createElement("div");
      var video = document.createElement("video");
      var name = document.createElement("span");
      var dot = document.createElement("i");
      video.muted = true; video.autoplay = true;
      video.setAttribute("muted", ""); video.setAttribute("playsinline", ""); video.setAttribute("aria-hidden", "true");
      name.className = "cam-name";
      name.appendChild(dot);
      name.appendChild(document.createTextNode(String(list[k].name || "")));
      var canvas = document.createElement("canvas");
      canvas.setAttribute("aria-hidden", "true");
      el.appendChild(canvas); el.appendChild(video); el.appendChild(name);
      row.appendChild(el);
      var c = { key: String(list[k].key), el: el, video: video, pc: null, session: null, renew: null, retry: null, tries: 0, state: "wait", last: -1, stalls: 0, limited: false, focus: false, big: false,
        canvas: canvas, parked: false, parkedAt: 0, since: 0, noPic: null };
      mark(c, "wait");
      (function (cam) {
        cam.video.addEventListener("playing", function () {
          if (!cam.pc) return;
          clearTimeout(cam.noPic); cam.noPic = null;
          cam.tries = 0; cam.blank = 0; cam.since = Date.now();
          if (cam.state !== "live") log("cam " + cam.key + " playing " + cam.video.videoWidth + "x" + cam.video.videoHeight);
          mark(cam, "live");
        });
      })(c);
      cams.push(c);
    }
    document.documentElement.classList.toggle("has-cams", cams.length > 0);
    row.setAttribute("data-n", String(cams.length));
  }

  // Start every camera that is not already up, one after another.
  function startAll() {
    var n = 0, going = cams.filter(function (x) { return x.pc; }).length;
    for (var k = 0; k < cams.length; k++) {
      if (cams[k].pc) continue;
      if (going >= maxLive) { if (!cams[k].parked) { cams[k].parked = true; cams[k].parkedAt = Date.now() - k; mark(cams[k]); } continue; }
      cams[k].parked = false; going += 1;
      (function (c, at) { clearTimeout(c.retry); c.retry = setTimeout(function () { start(c); }, at * STAGGER_MS); })(cams[k], n);
      n += 1;
    }
  }

  var listing = false;
  function list() {
    clearTimeout(listTimer);
    if (listed || listing || !showing) return;
    listing = true;
    ask("cameras").then(function (r) {
      listing = false;
      if (listed) return;
      if (r && r.ok && Array.isArray(r.cameras)) {
        listed = true;
        build(r.cameras);
        if (showing) startAll();
      } else {
        listTries += 1;
        listTimer = setTimeout(list, Math.min(RETRY_MOST_MS, RETRY_MS * Math.pow(2, listTries - 1)));
      }
    });
  }

  // The home is in front (again). Also called once the page's settings are in, since a preview's
  // pretend launcher (mock.js) only arrives then.
  function resume() {
    var was = showing;
    showing = true;
    clearTimeout(sleepTimer); sleepTimer = null;
    if (!on()) return;
    if (!listed) return list();
    if (was) return;
    // back from an app with every stream stopped: find out afresh how many this TV plays at once
    if (!cams.some(function (x) { return x.pc; })) { maxLive = 99; for (var j = 0; j < cams.length; j++) { cams[j].parked = false; cams[j].blank = 0; mark(cams[j]); } }
    for (var k = 0; k < cams.length; k++) cams[k].tries = 0;
    startAll();
    rotateSoon();
  }
  // An app came to the front. The streams stay up for a short while (see GRACE_MS), then stop.
  function pause() {
    if (!showing) return;
    showing = false;
    clearTimeout(listTimer);
    clearTimeout(rotateTimer);
    for (var k = 0; k < cams.length; k++) { clearTimeout(cams[k].retry); cams[k].retry = null; }
    clearTimeout(sleepTimer);
    sleepTimer = setTimeout(function () {
      sleepTimer = null;
      if (showing) return;
      for (var k = 0; k < cams.length; k++) stop(cams[k]);
    }, GRACE_MS);
  }

  document.addEventListener("visibilitychange", function () { if (document.hidden) pause(); else resume(); });

  // ---------------- the remote on the row (app.js asks, zone "cams") ----------------
  var at = 0, focused = false, big = false, ringing = false, ringTimer = null;
  function draw() {
    for (var k = 0; k < cams.length; k++) { cams[k].focus = focused && k === at; cams[k].big = big && k === at; mark(cams[k]); }
    document.documentElement.classList.toggle("cam-big", big);
  }
  function any() { return cams.length > 0; }
  function middle(k) { var b = cams[k].el.getBoundingClientRect(); return b.left + b.width / 2; }
  // Land on the camera nearest to x (the app or button the focus came from), or "first" / "last".
  function land(where) {
    if (!cams.length) return;
    if (where === "first") at = 0;
    else if (where === "last") at = cams.length - 1;
    else if (typeof where === "number") {
      var best = 0, far = Infinity;
      for (var k = 0; k < cams.length; k++) { var d = Math.abs(middle(k) - where); if (d < far) { far = d; best = k; } }
      at = best;
    }
    at = Math.max(0, Math.min(cams.length - 1, at));
  }
  function paint(yes) {
    if (!yes && !focused && !big) return;
    focused = !!yes;
    if (!yes) { big = false; quiet(); }
    draw();
  }
  // Returns "up" or "down" when the press leaves the row, else nothing.
  function move(dx, dy) {
    if (dx) { at = Math.max(0, Math.min(cams.length - 1, at + dx)); draw(); if (big && cams[at].parked) swap(cams[at]); return null; }
    if (big) return null;
    return dy < 0 ? "up" : "down";
  }
  function quiet() { clearTimeout(ringTimer); ringing = false; }
  function ok() { if (cams[at]) { big = !big; quiet(); draw(); if (big && cams[at].parked) swap(cams[at]); } }
  function back() { if (!big) return false; big = false; quiet(); draw(); return true; }
  function small() { if (big) { big = false; quiet(); draw(); } }
  // Someone pressed the doorbell (GoogleBell.kt, through tv.ring in app.js): that camera opens
  // over the whole screen with a line saying so. True when there is such a camera.
  function ring(key) {
    for (var k = 0; k < cams.length; k++) {
      if (cams[k].key !== key) continue;
      at = k; focused = true; big = true; ringing = true;
      clearTimeout(ringTimer);
      ringTimer = setTimeout(function () { ringing = false; draw(); }, 45000);
      draw();
      if (cams[k].parked) swap(cams[k]);
      else if (!cams[k].pc) { cams[k].tries = 0; start(cams[k]); }
      return true;
    }
    return false;
  }
  function x() { return cams[at] ? middle(at) : innerWidth / 2; }

  window.googleCams = { reply: reply, pause: pause, resume: resume, on: on,
    any: any, land: land, paint: paint, move: move, ok: ok, back: back, small: small, x: x, ring: ring };

  resume();
})();
