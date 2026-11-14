// Javhd Player Helper — page-world probe (page-probe.js).
//
// Runs IN the page's own JS world (injected by content.js as a
// <script src=".../page-probe.js"> tag), so it CAN see what a
// content script cannot: the page's player objects. Content scripts
// run in an isolated world — window.jwplayer(), videojs instances
// and hls.js instances are invisible there, which is exactly why
// two rounds of webRequest/content-script sniffing missed videos
// whose <video> currentSrc is a blob: URL.
//
// What it reads (best effort, everything wrapped in try/catch):
//   - window.jwplayer(): getPlaylist()/getConfig() -> file/sources
//   - window.videojs players: currentSrc()/src()
//   - hls.js: window.hls.url, plus a bounded scan of window's own
//     props for objects with a .url ending in .m3u8 (hls.js keeps
//     the manifest URL on the instance; the instance is often
//     stored under an arbitrary global name)
//   - <video>/<audio> elements: currentSrc/src/<source> (same DOM
//     the content script sees; included so the probe alone is a
//     complete reader)
// Results go back with window.postMessage({type:"JB_SNIFF_PROBE",
// urls:[...], playing:bool}); content.js listens for that marker
// and forwards the URLs as mediaCandidate. Only http(s) URLs are
// reported — a blob: URL cannot be played by the native player.
// The probe re-runs every 2s (player objects often appear after
// the page loads) and dedups within its own lifetime.
//
// If the page's CSP blocks the injected script, this silently does
// nothing — the content script's own <video> scan and the
// webRequest signals still run. Never breaks playback.
(function () {
  "use strict";

  var MARKER = "JB_SNIFF_PROBE";
  var reported = new Set();

  function isHttpUrl(u) {
    return (
      typeof u === "string" &&
      u.length > 0 &&
      (u.indexOf("http://") === 0 || u.indexOf("https://") === 0)
    );
  }

  function collect() {
    var urls = [];
    function add(u) {
      if (isHttpUrl(u) && !reported.has(u)) {
        reported.add(u);
        urls.push(u);
      }
    }
    try {
      // --- JWPlayer (the javhd embed player and many other sites) ---
      if (typeof window.jwplayer === "function") {
        try {
          var p = window.jwplayer();
          if (p) {
            try {
              var pl = p.getPlaylist && p.getPlaylist();
              if (pl) {
                for (var i = 0; i < pl.length; i++) {
                  var item = pl[i];
                  if (!item) continue;
                  add(item.file);
                  var srcs = item.sources || [];
                  for (var s = 0; s < srcs.length; s++) add(srcs[s].file);
                }
              }
            } catch (e) {}
            try {
              var cfg = p.getConfig && p.getConfig();
              if (cfg) {
                add(cfg.file);
                var csrcs = cfg.sources || [];
                for (var c = 0; c < csrcs.length; c++) add(csrcs[c].file);
              }
            } catch (e) {}
          }
        } catch (e) {}
      }

      // --- video.js ---
      if (window.videojs && typeof window.videojs.getPlayers === "function") {
        try {
          var players = window.videojs.getPlayers();
          var keys = Object.keys(players);
          for (var k = 0; k < keys.length; k++) {
            try {
              var player = players[keys[k]];
              if (!player) continue;
              var src = null;
              try {
                src = player.currentSrc && player.currentSrc();
              } catch (e) {}
              if (!src) {
                try {
                  src = player.src && player.src();
                } catch (e) {}
              }
              if (typeof src === "string") add(src);
              else if (src && typeof src.src === "string") add(src.src);
            } catch (e) {}
          }
        } catch (e) {}
      }

      // --- hls.js: the manifest URL lives on the instance (.url).
      // Native HLS keeps currentSrc as the .m3u8 itself, so this
      // only matters for MSE playback, where currentSrc is blob:.
      if (window.hls && typeof window.hls.url === "string") {
        add(window.hls.url);
      }
      // Bounded scan: own enumerable window props only, no
      // recursion, looking for objects with a .m3u8 .url.
      try {
        var wkeys = Object.keys(window);
        for (var w = 0; w < wkeys.length; w++) {
          var v;
          try {
            v = window[wkeys[w]];
          } catch (e) {
            continue;
          }
          if (
            v &&
            typeof v === "object" &&
            typeof v.url === "string" &&
            v.url.indexOf(".m3u8") !== -1
          ) {
            add(v.url);
          }
        }
      } catch (e) {}

      // --- <video>/<audio> elements (page world sees the same DOM) ---
      try {
        var media = document.querySelectorAll("video, audio");
        for (var m = 0; m < media.length; m++) {
          var el = media[m];
          add(el.currentSrc || el.src || "");
          var sources = el.querySelectorAll("source");
          for (var n = 0; n < sources.length; n++) {
            add(sources[n].src || "");
          }
        }
      } catch (e) {}
    } catch (e) {
      // Never break the page.
    }

    var playing = false;
    try {
      var vs = document.querySelectorAll("video");
      for (var q = 0; q < vs.length; q++) {
        if (!vs[q].paused && vs[q].currentTime > 0) {
          playing = true;
          break;
        }
      }
    } catch (e) {}

    if (urls.length > 0 || playing) {
      try {
        window.postMessage(
          { type: MARKER, urls: urls, playing: playing },
          "*"
        );
      } catch (e) {}
    }
  }

  collect();
  setInterval(collect, 2000);
})();
