(function () {
  "use strict";

  var pendingCalls = [];
  var pendingMessages = [];
  var port = null;
  var reconnectTimer = null;
  var flushing = false;
  var MAX_PENDING_CALLS = 256;

  function logError(message, error) {
    try {
      console.error("[landing-client] " + message, error || "");
    } catch (_) {}
  }

  function jsonArgs(args) {
    var values = Array.prototype.slice.call(args);
    try {
      return JSON.stringify(values);
    } catch (error) {
      return JSON.stringify(values.map(function (value) {
        try { return JSON.parse(JSON.stringify(value)); }
        catch (_) { return String(value); }
      }));
    }
  }

  function flushCalls() {
    if (!port || flushing) return;
    flushing = true;
    try {
      while (port && pendingCalls.length) {
        var message = pendingCalls[0];
        try {
          port.postMessage(message);
          pendingCalls.shift();
        } catch (error) {
          logError("Port send failed; retaining the queued call", error);
          disconnectPort();
        }
      }
    } finally {
      flushing = false;
    }
  }

  function sendCall(method, args) {
    var message = { type: "bridgeCall", method: method, argsJson: jsonArgs(args) };
    if (pendingCalls.length >= MAX_PENDING_CALLS) {
      logError("Bridge call queue is full; dropping " + method);
      return;
    }
    pendingCalls.push(message);
    flushCalls();
  }

  function scheduleConnect() {
    if (reconnectTimer !== null) return;
    reconnectTimer = window.setTimeout(function () {
      reconnectTimer = null;
      connect();
    }, 1000);
  }

  function disconnectPort() {
    var oldPort = port;
    port = null;
    if (oldPort) {
      try { oldPort.disconnect(); } catch (_) {}
    }
    scheduleConnect();
  }

  function onPortMessage(message) {
    if (!message || typeof message.type !== "string") return;
    if (message.type === "ping") {
      if (port) {
        try { port.postMessage({ type: "pong", id: message.id }); }
        catch (error) {
          logError("Could not answer bridge ping", error);
          disconnectPort();
        }
      }
      return;
    }
    if (message.type === "clipboard" || message.type === "prefill" || message.type === "batchProgress") {
      pendingMessages.push(message);
      flushMessages();
    }
  }

  function flushMessages() {
    while (pendingMessages.length) {
      var message = pendingMessages[0];
      try {
        if (message.type === "clipboard") {
          if (typeof window.onClipboardResult !== "function") return;
          window.onClipboardResult(message.text, message.link);
        } else if (message.type === "prefill") {
          if (typeof window.prefillJavCodes !== "function") return;
          window.prefillJavCodes(JSON.stringify(message.codes));
        } else if (message.type === "batchProgress") {
          if (typeof window.updateBatchProgress !== "function") return;
          window.updateBatchProgress(message.done, message.total);
        }
        pendingMessages.shift();
      } catch (error) {
        // Keep the message queued for an explicit later flush; never lose
        // a native result because page initialization raced the port.
        logError("Could not handle " + message.type + " message", error);
        return;
      }
    }
  }

  window.__flushLandingBridgeMessages = flushMessages;

  function connect() {
    if (port) return;
    try {
      var nextPort = browser.runtime.connect({ name: "landing-page" });
      port = nextPort;
      nextPort.onMessage.addListener(onPortMessage);
      nextPort.onDisconnect.addListener(function () {
        if (port === nextPort) port = null;
        scheduleConnect();
      });
      flushCalls();
    } catch (error) {
      port = null;
      logError("Could not connect to background", error);
      scheduleConnect();
    }
  }

  var allowedMethods = [
    "checkClipboard",
    "openClipboardLink",
    "dismissClipboardLink",
    "openUnifiedSearch",
    "saveJavCodes",
    "openTodayNew",
    "openSalesRankings",
    "openSiteBrowse",
    "navigateToUrl",
    "openPlaybackHistory",
    "showHelpDialog"
  ];
  var androidApi = {};
  allowedMethods.forEach(function (method) {
    androidApi[method] = function () {
      sendCall(method, arguments);
    };
  });
  try {
    Object.defineProperty(window, "Android", {
      configurable: true,
      enumerable: true,
      writable: false,
      value: Object.freeze(androidApi)
    });
  } catch (error) {
    logError("Could not define the Android page API", error);
  }

  // Start the connection before landing-page.js loads; outgoing calls and
  // early native messages are retained until their respective consumers exist.
  connect();
})();
