// Gecko Bridge — content script (isolated world).
//
// Two jobs:
// 1. Inject the page-world hook (window.Android + the eval entry
//    point) via a <script> tag. Content scripts run in an isolated
//    world and cannot see page JS variables, so the eval MUST run in
//    the page world; the hook is the only way in.
// 2. Relay window.postMessage traffic between the page hook and the
//    background script (background relays it to Kotlin).
//
// The hook is injected at document_start so window.Android exists
// before page scripts run. If documentElement is not there yet, retry
// on DOMContentLoaded.

(function () {
  "use strict";

  // --- Page-world hook (runs as page JS) ---
  //
  // Self-contained: only uses window/document, nothing from the
  // content-script scope (it is stringified and injected).
  function pageHook() {
    if (window.__jbBridgeHook) return;
    window.__jbBridgeHook = true;

    var callSeq = 0;

    function postToContent(data) {
      window.postMessage(data, "*");
    }

    // arguments is array-like, not an array: JSON.stringify would
    // produce {"0":...} instead of [...]. Convert first.
    function safeArgs(args) {
      var arr = Array.prototype.slice.call(args);
      try {
        return JSON.stringify(arr);
      } catch (e) {
        var out = [];
        for (var i = 0; i < arr.length; i++) {
          try {
            out.push(JSON.parse(JSON.stringify(arr[i])));
          } catch (e2) {
            out.push(String(arr[i]));
          }
        }
        return JSON.stringify(out);
      }
    }

    // window.Android.<method>(...args): any method name works (Proxy).
    // If the page already defines its own window.Android, do NOT
    // override it — the call then never reaches Kotlin (logged once).
    try {
      if (!window.Android || !window.Android.__jbBridge) {
        if (window.Android) {
          postToContent({
            source: "jb-bridge-page",
            type: "bridgeLog",
            message: "window.Android already exists on this page, not overriding"
          });
        } else {
          window.Android = new Proxy({}, {
            get: function (target, method) {
              if (method === "__jbBridge") return true;
              if (method === "then") return undefined;
              if (typeof method !== "string") return undefined;
              return function () {
                callSeq += 1;
                postToContent({
                  source: "jb-bridge-page",
                  type: "bridgeCall",
                  method: method,
                  argsJson: safeArgs(arguments),
                  token: window.__jbToken || null,
                  callId: "c" + Date.now() + "-" + callSeq + "-" +
                    Math.floor(Math.random() * 1e9)
                });
              };
            }
          });
        }
      }
    } catch (e) {
      // e.g. page defined Android read-only: bridge calls unavailable.
      postToContent({
        source: "jb-bridge-page",
        type: "bridgeLog",
        message: "window.Android setup failed: " + String(e && e.message || e)
      });
    }

    // WebView.evaluateJavascript-compatible result encoding:
    // strings come back JSON-quoted, undefined -> "null".
    function encodeResult(value) {
      if (value === undefined) return "null";
      try {
        var s = JSON.stringify(value);
        return s === undefined ? "null" : s;
      } catch (e) {
        return "null";
      }
    }

    window.addEventListener("message", function (event) {
      if (event.source !== window) return;
      var data = event.data;
      if (!data || data.source !== "jb-bridge-content" || data.type !== "eval") return;
      var token = window.__jbToken || null;
      // Only run evals meant for this session. If no token was ever
      // injected into this page, run anyway and report token null —
      // degraded mode: Kotlin accepts the first result that comes back.
      if (data.token && token && data.token !== token) return;
      var reply = {
        source: "jb-bridge-page",
        type: "evalResult",
        id: data.id,
        token: token
      };
      try {
        // Indirect eval: runs in global scope, like evaluateJavascript.
        var value = (0, window.eval)(data.js);
        reply.ok = true;
        reply.result = encodeResult(value);
      } catch (e) {
        reply.ok = false;
        reply.error = String((e && e.message) || e);
      }
      postToContent(reply);
    });
  }

  function injectHook() {
    try {
      if (window.__jbBridgeHook) return true;
      var script = document.createElement("script");
      script.textContent = "(" + pageHook.toString() + ")();";
      var parent = document.documentElement || document.head;
      if (!parent) return false;
      parent.appendChild(script);
      parent.removeChild(script);
      return true;
    } catch (e) {
      // Page CSP may block inline <script> injection: the hook then
      // never installs and eval/bridge are unavailable on this page
      // (cookies still work — they are background-side). See NOTES.md.
      return false;
    }
  }

  if (!injectHook()) {
    document.addEventListener("DOMContentLoaded", injectHook);
  }

  // Page hook -> content script -> background -> native.
  window.addEventListener("message", function (event) {
    if (event.source !== window) return;
    var data = event.data;
    if (!data || data.source !== "jb-bridge-page") return;
    try {
      browser.runtime.sendMessage(data);
    } catch (e) {
      // Background not ready; drop.
    }
  });

  // Background -> content script -> page hook.
  browser.runtime.onMessage.addListener(function (message) {
    if (!message || message.type !== "eval") return;
    try {
      window.postMessage(
        {
          source: "jb-bridge-content",
          type: "eval",
          id: message.id,
          token: message.token,
          js: message.js
        },
        "*"
      );
    } catch (e) {
      // Page already gone; ignore.
    }
  });
})();
