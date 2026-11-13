// Javhd Player Helper — content script for the javhd embed page
// (video.javhdporn.net / video1.javhdporn.net).
//
// Replaces the old JavhdEmbedPlayerActivity evaluateJavascript polling:
// 1. Auto-click the VAST skip button (DOM clicks only). Content scripts
//    run in an isolated world, so the page's jwplayer() object is NOT
//    reachable here; the two DOM-based attempts are kept (they are the
//    primary mechanism — the .jw-skip button is a real JWPlayer DOM
//    element and clicking it triggers the skip).
// 2. Report video-element state to the background script (which relays
//    it to Kotlin), so the Activity can record playback progress on
//    pause/exit. Reads the <video> element directly from the DOM.

(function () {
  "use strict";

  // --- VAST skip polling (same order as the old evaluateJavascript) ---
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

  // Poll every 1s while the page is alive (same cadence as before).
  var skipTimer = setInterval(trySkip, 1000);
  // One immediate attempt in case the ad is already showing.
  trySkip();

  // --- Video state reporting ---
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
    if (state) {
      try {
        browser.runtime.sendMessage(state);
      } catch (e) {
        // Background not ready; drop.
      }
    }
  }

  // Report every 2s, plus immediately on pause/ended (so the Activity
  // has a fresh state when the user leaves the player).
  var stateTimer = setInterval(sendState, 2000);
  sendState();

  document.addEventListener("pause", sendState, true);
  document.addEventListener("ended", sendState, true);
  window.addEventListener("pagehide", sendState);
})();
