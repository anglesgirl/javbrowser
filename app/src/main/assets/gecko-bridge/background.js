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

function connectNative() {
  try {
    nativePort = browser.runtime.connectNative("geckoBridge");
    nativePort.onMessage.addListener(onNativeMessage);
    nativePort.onDisconnect.addListener(() => {
      nativePort = null;
      scheduleReconnect();
    });
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
