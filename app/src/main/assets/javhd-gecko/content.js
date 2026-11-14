// Javhd Player Helper — content script.
//
// Two scopes:
// 1. javhd embed pages only (video.javhdporn.net /
//    video1.javhdporn.net): auto-click the VAST skip button (DOM
//    clicks only — content scripts run in an isolated world, so the
//    page's jwplayer() object is NOT reachable here) and report
//    video-element state so the Activity can record playback
//    progress on pause/exit.
// 2. Every page: video-candidate monitoring, adapted from CatCatch
//    (猫抓, https://github.com/xifangczy/cat-catch, GPL-3.0; see
//    CAT_CATCH_LICENSE in this folder). CatCatch reads the <video>
//    element directly — when the player uses blob:/MSE, the network
//    requests carry only .ts segments and no playable URL, and only
//    the video element knows the address. We report each <video> /
//    <audio> / <source> URL as a mediaCandidate (http(s) only — a
//    blob: URL cannot be played by the native player) and report a
//    videoPlaying signal when playback actually starts, so the
//    sniffer knows the feature is playing and can finish instead of
//    timing out on ads.

(function () {
  "use strict";

  var IS_JAVHD_EMBED =
    location.hostname === "video.javhdporn.net" ||
    location.hostname === "video1.javhdporn.net";

  function sendMessage(msg) {
    try {
      browser.runtime.sendMessage(msg);
    } catch (e) {
      // Background not ready; drop.
    }
  }

  // --- 1. VAST skip polling (javhd embed only; same order as the
  // old evaluateJavascript) ---
  function trySkip() {
    try {
      // (a) JWPlayer's own skip button.
      var skip = document.querySelector(".jw-skip");
      if (skip && skip.offsetParent !== null) {
        skip.click();
        return true;
      }
      // (b) Any visible element whose text is exactly "Skip".
      var els = document.querySelectorAll("div,button,span,a");
      for (var i = 0; i < els.length; i++) {
        var el = els[i];
        if (el.textContent && el.textContent.trim() === "Skip" && el.offsetParent !== null) {
          el.click();
          return true;
        }
      }
    } catch (e) {
      // Ignore; next tick retries.
    }
    return false;
  }

  if (IS_JAVHD_EMBED) {
    // Poll every 1s while the page is alive (same cadence as before).
    var skipTimer = setInterval(trySkip, 1000);
    // One immediate attempt in case the ad is already showing.
    trySkip();
  }

  // --- 2. Video-candidate monitoring (every page) ---
  var reportedSrcs = new Set();

  function isReportableUrl(url) {
    return (
      typeof url === "string" &&
      url.length > 0 &&
      (url.indexOf("http://") === 0 || url.indexOf("https://") === 0)
    );
  }

  function reportVideoCandidate(url) {
    if (!isReportableUrl(url) || reportedSrcs.has(url)) return;
    reportedSrcs.add(url);
    sendMessage({ type: "mediaCandidate", url: url });
  }

  function scanVideoElements() {
    try {
      var media = document.querySelectorAll("video, audio");
      for (var i = 0; i < media.length; i++) {
        var el = media[i];
        // <video currentSrc> is the resolved address the element is
        // actually playing (covers <source> children too).
        reportVideoCandidate(el.currentSrc || el.src || "");
        var sources = el.querySelectorAll("source");
        for (var j = 0; j < sources.length; j++) {
          reportVideoCandidate(sources[j].src || "");
        }
      }
    } catch (e) {
      // Ignore; next tick retries.
    }
  }

  // The player element often appears after the page loads (JS-built
  // player), so watch the DOM as well as polling.
  var scanQueued = false;
  function queueScan() {
    if (scanQueued) return;
    scanQueued = true;
    setTimeout(function () {
      scanQueued = false;
      scanVideoElements();
    }, 300);
  }

  try {
    var observer = new MutationObserver(function (mutations) {
      for (var i = 0; i < mutations.length; i++) {
        var m = mutations[i];
        if (m.type === "childList" && m.addedNodes.length > 0) {
          queueScan();
          return;
        }
        if (m.type === "attributes" && (m.attributeName === "src" || m.attributeName === "currentSrc")) {
          queueScan();
          return;
        }
      }
    });
    observer.observe(document.documentElement || document, {
      childList: true,
      subtree: true,
      attributes: true,
      attributeFilter: ["src"]
    });
  } catch (e) {
    // MutationObserver unavailable; polling below still covers it.
  }

  // Poll every 2s (same cadence as the state reporting).
  var scanTimer = setInterval(scanVideoElements, 2000);
  scanVideoElements();

  // The feature actually started playing. The URL may be a blob:
  // (unplayable natively) — the signal is what matters: the
  // sniffer can finish now if it already has candidates, or keep
  // waiting for the manifest instead of timing out on ads.
  var playingReported = false;
  document.addEventListener(
    "playing",
    function (e) {
      try {
        var v = e.target;
        if (!v || (v.tagName !== "VIDEO" && v.tagName !== "AUDIO")) return;
        // Only the first playing event per page: the feature plays
        // once; replays/seeks would only re-trigger the finish.
        if (playingReported) return;
        playingReported = true;
        sendMessage({
          type: "videoPlaying",
          duration: v.duration || 0,
          src: v.currentSrc || v.src || ""
        });
      } catch (err) {
        // Ignore.
      }
    },
    true
  );

  // --- 3. Video state reporting (javhd embed only) ---
  if (!IS_JAVHD_EMBED) return;

  function readVideoState() {
    try {
      var v = document.querySelector("video");
      if (!v) return null;
      var state = {
        type: "videoState",
        currentTime: v.currentTime || 0,
        duration: v.duration || 0,
        paused: v.paused,
        ended: v.ended,
        readyState: v.readyState,
        src: v.currentSrc || v.src || "",
        title: document.title || ""
      };
      if (v.error) {
        state.errorCode = v.error.code;
        state.errorMessage = v.error.message || "";
      }
      return state;
    } catch (e) {
      return null;
    }
  }

  function sendState() {
    var state = readVideoState();
    if (state) sendMessage(state);
  }

  // Report every 2s, plus immediately on pause/ended (so the Activity
  // has a fresh state when the user leaves the player).
  var stateTimer = setInterval(sendState, 2000);
  sendState();

  document.addEventListener("pause", sendState, true);
  document.addEventListener("ended", sendState, true);
  window.addEventListener("pagehide", sendState);
})();
