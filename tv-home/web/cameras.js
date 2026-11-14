// Google Home cameras (optional): a row of live feeds above the apps.
// Only drawn when the launcher says Google Home is set up (GoogleHome.kt, docs/CONFIG.md). The
// page makes each WebRTC offer and plays the video; the launcher (window.TVGH) carries the offer
// to Google and brings the answer back, so no Google secret or device id ever reaches this page.
// A stream is asked for only while the home is showing, renewed before Google's five minutes run
// out, and stopped when an app comes to the front. Nothing here takes the remote: the row is a
// picture, not a control. Plain ES5 like the rest of the home.
(function () {
  "use strict";
  var ANSWER_MS = 20000;      // how long the launcher gets to answer
  var RENEW_MS = 210000;      // Google ends a stream after five minutes: renew at three and a half
  var STAGGER_MS = 1200;      // start the cameras one after another, not all at once
  var WATCH_MS = 20000;       // a picture that stopped moving for two looks is started again
  var RETRY_MS = 15000, RETRY_MOST_MS = 300000;
  var LIMITED_MS = 70000;     // Google said "too many requests": it counts them by the minute
  var GRACE_MS = 45000;       // an app in front for less than this: the streams stay up. Google
                              // allows only a few starts a minute, so a quick look at another
                              // app must not cost three stops and three starts.

  var $ = function (id) { return document.getElementById(id); };
  var cams = [];
  var showing = !document.hidden;
  var listed = false, listTimer = null, listTries = 0, sleepTimer = null;

  function native() { return window.TVGH || null; }
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
    c.state = state;
    c.el.className = "cam" + (state === "live" ? " live" : "");
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
    if (!showing || c.pc) return;
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

  function stop(c) {
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
      el.appendChild(video); el.appendChild(name);
      row.appendChild(el);
      var c = { key: String(list[k].key), el: el, video: video, pc: null, session: null, renew: null, retry: null, tries: 0, state: "wait", last: -1, stalls: 0, limited: false };
      mark(c, "wait");
      (function (cam) {
        cam.video.addEventListener("playing", function () { if (cam.pc) { cam.tries = 0; mark(cam, "live"); } });
      })(c);
      cams.push(c);
    }
    document.documentElement.classList.toggle("has-cams", cams.length > 0);
    row.setAttribute("data-n", String(cams.length));
  }

  // Start every camera that is not already up, one after another.
  function startAll() {
    var n = 0;
    for (var k = 0; k < cams.length; k++) {
      if (cams[k].pc) continue;
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
    for (var k = 0; k < cams.length; k++) cams[k].tries = 0;
    startAll();
  }
  // An app came to the front. The streams stay up for a short while (see GRACE_MS), then stop.
  function pause() {
    if (!showing) return;
    showing = false;
    clearTimeout(listTimer);
    for (var k = 0; k < cams.length; k++) { clearTimeout(cams[k].retry); cams[k].retry = null; }
    clearTimeout(sleepTimer);
    sleepTimer = setTimeout(function () {
      sleepTimer = null;
      if (showing) return;
      for (var k = 0; k < cams.length; k++) stop(cams[k]);
    }, GRACE_MS);
  }

  document.addEventListener("visibilitychange", function () { if (document.hidden) pause(); else resume(); });

  window.googleCams = { reply: reply, pause: pause, resume: resume, on: on };

  resume();
})();
