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
//    timing out on ads. 1.2.10 also sends a domScan every 2s (how
//    many <video> elements, whether one is playing) so the Activity
//    knows the ground truth, and injects page-probe.js into the
//    page's own JS world to read jwplayer/videojs/hls.js objects
//    that the isolated content-script world cannot see.

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
  //
  // 1.2.10: DOM 直读是主路。除了逐条上报 mediaCandidate，每次
  // 扫描还上报一次 domScan（页面里有几个 <video>、是否在播、在
  // 播的地址），让 Activity 拿得到“视频到底在没在播”的地面真相：
  // 视频在播但只剩 blob: 地址时，Activity 知道要继续等、绝不报
  // “页面载入失败”；页面里根本没有 <video> 时，才是真的没播。
  var reportedSrcs = new Set();

  function isReportableUrl(url) {
    return (
      typeof url === "string" &&
      url.length > 0 &&
      (url.indexOf("http://") === 0 || url.indexOf("https://") === 0)
    );
  }

  function reportVideoCandidate(url, source) {
    if (!isReportableUrl(url) || reportedSrcs.has(url)) return;
    reportedSrcs.add(url);
    // 1.2.12：带上来源标注（"dom" 是内容脚本读 <video>，"probe"
    // 是页面探针 jwplayer/videojs/hls.js），供嗅探诊断面板分路计数。
    sendMessage({ type: "mediaCandidate", url: url, source: source || "dom" });
  }

  function collectVideoState() {
    var state = { videoCount: 0, playing: false, playingSrc: "", srcs: [], paused: true, currentTime: 0 };
    try {
      var media = document.querySelectorAll("video, audio");
      state.videoCount = media.length;
      var firstVideoSeen = false;
      for (var i = 0; i < media.length; i++) {
        var el = media[i];
        var src = el.currentSrc || el.src || "";
        if (isReportableUrl(src) && state.srcs.indexOf(src) === -1) {
          state.srcs.push(src);
        }
        var sources = el.querySelectorAll("source");
        for (var j = 0; j < sources.length; j++) {
          var ssrc = sources[j].src || "";
          if (isReportableUrl(ssrc) && state.srcs.indexOf(ssrc) === -1) {
            state.srcs.push(ssrc);
          }
        }
        // 1.2.12：首个 <video> 的 paused/currentTime，供诊断面板
        // 印证“播放器显示 2 小时多、视频到底在没在播”。
        if (el.tagName === "VIDEO" && !firstVideoSeen) {
          firstVideoSeen = true;
          state.paused = el.paused;
          state.currentTime = el.currentTime || 0;
        }
        if (
          el.tagName === "VIDEO" &&
          !el.paused &&
          el.currentTime > 0
        ) {
          state.playing = true;
          state.playingSrc = src;
        }
      }
    } catch (e) {
      // Ignore; next tick retries.
    }
    return state;
  }

  function sendDomScan() {
    try {
      var state = collectVideoState();
      sendMessage({
        type: "domScan",
        videoCount: state.videoCount,
        playing: state.playing,
        playingSrc: state.playingSrc,
        paused: state.paused,
        currentTime: state.currentTime,
        srcs: state.srcs
      });
    } catch (e) {
      // Ignore; next tick retries.
    }
  }

  function scanVideoElements() {
    try {
      var state = collectVideoState();
      for (var i = 0; i < state.srcs.length; i++) {
        reportVideoCandidate(state.srcs[i]);
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

  // Poll every 2s (same cadence as the state reporting): read
  // the <video> elements AND report the DOM state, so the Activity
  // gets a fresh ground truth even when nothing new is found.
  var scanTimer = setInterval(function () {
    scanVideoElements();
    sendDomScan();
  }, 2000);
  scanVideoElements();
  sendDomScan();

  // --- 2b. Page-world probe (1.2.10) ---
  //
  // The player objects (jwplayer, videojs, hls.js instances) live
  // in the page's own JS world, which this content script cannot
  // see. Inject page-probe.js as a <script> tag so it runs in the
  // page world, reads those objects, and posts the URLs back with
  // window.postMessage. The probe file is a web-accessible
  // resource (see manifest.json); if the page's CSP blocks the
  // injected script this silently does nothing and the scans above
  // still run.
  function injectPageProbe() {
    try {
      if (document.querySelector('script[data-jb-sniff-probe]')) return;
      var url = null;
      try {
        url = browser.runtime.getURL("page-probe.js");
      } catch (e) {
        return;
      }
      if (!url) return;
      var script = document.createElement("script");
      script.setAttribute("data-jb-sniff-probe", "1");
      script.src = url;
      (document.head || document.documentElement).appendChild(script);
    } catch (e) {
      // Ignore; the scans above still run.
    }
  }

  window.addEventListener("message", function (event) {
    try {
      if (!event || event.source !== window) return;
      var data = event.data;
      if (!data || data.type !== "JB_SNIFF_PROBE") return;
      var urls = data.urls || [];
      for (var i = 0; i < urls.length; i++) {
        reportVideoCandidate(urls[i], "probe");
      }
      // 1.2.12：把探针的完整上报（找到了哪些播放器对象、读到了
      // 哪些地址）原样转给 native，供嗅探诊断面板显示；候选本身
      // 仍走上面的 mediaCandidate，不改嗅探逻辑。
      sendMessage({
        type: "probeReport",
        urls: urls,
        playing: !!data.playing,
        found: data.found || {}
      });
      // The probe also knows whether a video is playing; fold that
      // into the next domScan by triggering one now if it says so.
      if (data.playing) sendDomScan();
    } catch (e) {
      // Ignore.
    }
  });

  injectPageProbe();

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
