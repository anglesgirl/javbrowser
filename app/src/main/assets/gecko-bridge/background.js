// Gecko Bridge — background script.
//
// The generic bridge for GeckoView sessions (the javhd-player extension
// stays untouched; this one is separate, nativeApp "geckoBridge").
//
// Three jobs, all relayed over the single native-messaging port to
// Kotlin (GeckoBridgeExtension):
// 1. eval: native -> background -> every tab's content script ->
//    page-world hook (executes the JS where page variables live) ->
//    result back the same way. The page hook only runs the JS when
//    the message token matches the page's window.__jbToken (or when
//    no token was ever injected — degraded mode, see NOTES.md).
// 2. cookies: browser.cookies.getAll / .set for a URL (background
//    side, so HttpOnly cookies are visible too).
// 3. relay: content-script messages (bridgeCall / evalResult /
//    bridgeLog) straight to the native port.
//
// The native port is the only Kotlin channel: Kotlin keeps the Port
// from MessageDelegate.onConnect and posts JSONObject messages to it;
// this side receives them in port.onMessage. If the port drops, the
// background reconnects after 1s (Kotlin queues outbound messages
// meanwhile and flushes on reconnect).

let nativePort = null;
let reconnectTimer = null;
let landingPort = null;
const pendingNativeMessages = [];
const pendingLandingMessages = [];
const MAX_PENDING_MESSAGES = 32;
const MAX_LANDING_MESSAGE_BYTES = 256 * 1024;

function isWithinLandingMessageLimit(value) {
  try {
    return new TextEncoder().encode(JSON.stringify(value)).length <= MAX_LANDING_MESSAGE_BYTES;
  } catch (e) {
    return false;
  }
}

function postNativeMessage(message) {
  if (!isWithinLandingMessageLimit(message)) return;
  if (!nativePort) {
    if (pendingNativeMessages.length >= MAX_PENDING_MESSAGES) pendingNativeMessages.shift();
    pendingNativeMessages.push(message);
    return;
  }
  try {
    nativePort.postMessage(message);
  } catch (e) {
    if (pendingNativeMessages.length >= MAX_PENDING_MESSAGES) pendingNativeMessages.shift();
    pendingNativeMessages.push(message);
  }
}

function flushPendingNativeMessages() {
  while (nativePort && pendingNativeMessages.length > 0) {
    try {
      nativePort.postMessage(pendingNativeMessages[0]);
      pendingNativeMessages.shift();
    } catch (e) {
      break;
    }
  }
}

function postLandingMessage(payload) {
  if (!isWithinLandingMessageLimit(payload)) return;
  // Do not retain private clipboard data for a later document. Native resends
  // current state after the new landing page announces readiness.
  if (!landingPort) return;
  try {
    landingPort.postMessage(payload);
  } catch (e) {
    // The document disappeared; a future ready event will refresh state.
  }
}

function flushPendingLandingMessages(port) {
  while (landingPort === port && pendingLandingMessages.length > 0) {
    try {
      port.postMessage(pendingLandingMessages[0]);
      pendingLandingMessages.shift();
    } catch (e) {
      break;
    }
  }
}

function isTrustedLandingPort(port) {
  const senderUrl = port && port.sender && port.sender.url;
  if (typeof senderUrl !== "string") return false;
  const hash = senderUrl.indexOf("#");
  const withoutHash = hash === -1 ? senderUrl : senderUrl.slice(0, hash);
  const expectedUrl = browser.runtime.getURL("landing.html");
  const expectedHash = expectedUrl.indexOf("#");
  return withoutHash === (expectedHash === -1 ? expectedUrl : expectedUrl.slice(0, expectedHash));
}

browser.runtime.onConnect.addListener((port) => {
  if (!port || port.name !== "landing-page" || !isTrustedLandingPort(port)) {
    try { port.disconnect(); } catch (e) {}
    return;
  }

  const previousPort = landingPort;
  pendingNativeMessages.length = 0;
  pendingLandingMessages.length = 0;
  landingPort = port;
  if (previousPort && previousPort !== port) {
    try { previousPort.disconnect(); } catch (e) {}
  }

  port.onMessage.addListener((message) => {
    if (landingPort !== port || !message || typeof message.type !== "string") return;
    if (message.type === "bridgeCall") {
      if (typeof message.method !== "string" || message.method.length === 0 || message.method.length > 128 ||
          typeof message.argsJson !== "string") return;
      postNativeMessage({
        type: "landingBridgeCall",
        method: message.method,
        argsJson: message.argsJson
      });
    } else if (message.type === "pong") {
      if (!isWithinLandingMessageLimit(message.id)) return;
      postNativeMessage({ type: "landingPong", id: message.id });
    }
  });

  port.onDisconnect.addListener(() => {
    if (landingPort === port) {
      landingPort = null;
      pendingNativeMessages.length = 0;
      pendingLandingMessages.length = 0;
    }
  });

  try {
    port.postMessage({ type: "landingReady" });
    postNativeMessage({ type: "landingReady" });
    flushPendingLandingMessages(port);
  } catch (e) {
    if (landingPort === port) landingPort = null;
  }
});

function connectNative() {
  try {
    const port = browser.runtime.connectNative("geckoBridge");
    nativePort = port;
    port.onMessage.addListener(onNativeMessage);
    port.onDisconnect.addListener(() => {
      if (nativePort !== port) return;
      nativePort = null;
      scheduleReconnect();
    });
    flushPendingNativeMessages();
  } catch (e) {
    nativePort = null;
    scheduleReconnect();
  }
}

function scheduleReconnect() {
  if (reconnectTimer !== null) return;
  reconnectTimer = setTimeout(() => {
    reconnectTimer = null;
    connectNative();
  }, 1000);
}

connectNative();

// Content script -> background -> native (no reply needed).
browser.runtime.onMessage.addListener((message, sender) => {
  // Ordinary pages must never be able to forge the trusted landing protocol.
  if (!message || !["bridgeCall", "evalResult", "bridgeLog"].includes(message.type)) return;
  if (nativePort) {
    try {
      nativePort.postMessage(message);
    } catch (e) {
      // Port closed; drop.
    }
  }
});

// Native -> background.
function onNativeMessage(message) {
  if (!message || typeof message.type !== "string") return;
  switch (message.type) {
    case "eval":
      broadcastToTabs(message);
      break;
    case "getCookies":
      getCookies(message);
      break;
    case "setCookie":
      setCookie(message);
      break;
    case "landingMessage":
      if (message.payload && typeof message.payload === "object" && !Array.isArray(message.payload) &&
          ["clipboard", "prefill", "ping", "batchProgress"].includes(message.payload.type)) {
        postLandingMessage(message.payload);
      }
      break;
    default:
      break;
  }
}

// Eval goes to every tab: the page hook in each tab checks the token
// and only the tab whose window.__jbToken matches executes the JS.
// Tabs without a content script (about:blank, privileged pages)
// reject sendMessage; that is expected and ignored.
function broadcastToTabs(message) {
  browser.tabs.query({}).then((tabs) => {
    for (const tab of tabs) {
      if (typeof tab.id !== "number") continue;
      browser.tabs.sendMessage(tab.id, message).catch(() => {});
    }
  }).catch(() => {});
}

function reply(payload) {
  if (nativePort) {
    try {
      nativePort.postMessage(payload);
    } catch (e) {
      // Port closed; drop.
    }
  }
}

async function getCookies(message) {
  let value = "";
  try {
    const cookies = await browser.cookies.getAll({ url: message.url });
    value = cookies.map((c) => c.name + "=" + c.value).join("; ");
  } catch (e) {
    // Unreadable URL (or no permission): return "" like an empty jar.
  }
  reply({ type: "cookieResult", id: message.id, value: value });
}

async function setCookie(message) {
  let ok = false;
  try {
    const details = parseCookieString(message.url, message.cookie);
    if (details) {
      const set = await browser.cookies.set(details);
      ok = !!set;
    }
  } catch (e) {
    ok = false;
  }
  reply({ type: "cookieSetDone", id: message.id, ok: ok });
}

// Parse "name=value; Path=/; Domain=.example.com; Expires=...;
// Max-Age=3600; Secure; SameSite=Lax" into browser.cookies.set args.
// HttpOnly cannot be set through the extension API and is ignored,
// as are unknown attributes.
function parseCookieString(url, cookie) {
  if (typeof cookie !== "string") return null;
  const parts = cookie.split(";");
  const first = parts[0].trim();
  const eq = first.indexOf("=");
  if (eq <= 0) return null;
  const details = {
    url: url,
    name: first.slice(0, eq).trim(),
    value: first.slice(eq + 1).trim()
  };
  for (let i = 1; i < parts.length; i++) {
    const attr = parts[i].trim();
    if (!attr) continue;
    const aeq = attr.indexOf("=");
    const key = (aeq === -1 ? attr : attr.slice(0, aeq)).trim().toLowerCase();
    const val = aeq === -1 ? "" : attr.slice(aeq + 1).trim();
    if (key === "path") {
      details.path = val;
    } else if (key === "domain") {
      details.domain = val;
    } else if (key === "secure") {
      details.secure = true;
    } else if (key === "samesite") {
      const v = val.toLowerCase();
      if (v === "lax") details.sameSite = "lax";
      else if (v === "strict") details.sameSite = "strict";
      else if (v === "none") details.sameSite = "no_restriction";
    } else if (key === "expires") {
      const ms = Date.parse(val);
      if (!isNaN(ms)) details.expirationDate = Math.floor(ms / 1000);
    } else if (key === "max-age") {
      const secs = parseInt(val, 10);
      if (!isNaN(secs)) details.expirationDate = Math.floor(Date.now() / 1000) + secs;
    }
    // HttpOnly / anything else: not settable via the extension API.
  }
  return details;
}
