// Javhd Player Helper — background script.
//
// Two jobs:
// 1. Block the 11 ad hosts (same list as the old JavhdEmbedPlayerActivity
//    AD_HOSTS). tsyndicate.com (the VAST pre-roll) is NEVER blocked:
//    the feature only loads after the VAST is skipped, so blocking it
//    welds the gate shut.
// 2. Relay content-script messages (video state) to the native app
//    (Kotlin) over the native-messaging port, and vice versa.

const AD_HOSTS = [
  "whitetrafsa",
  "content-sync.xyz",
  "alaphoid.com",
  "udzpel.com",
  "jads.co",
  "wpadmngr.com",
  "xlviirdr.com",
  "xlirdr.com",
  "javhdporn.live",
  "javhd-trk.com",
  "vivodemisrentas.net"
];

function isAdHost(hostname) {
  const host = hostname.toLowerCase();
  for (const h of AD_HOSTS) {
    if (host === h || host.endsWith("." + h)) return true;
  }
  return false;
}

browser.webRequest.onBeforeRequest.addListener(
  function (details) {
    try {
      const url = new URL(details.url);
      if (isAdHost(url.hostname)) {
        return { cancel: true };
      }
    } catch (e) {
      // Ignore malformed URLs; let them through.
    }
    return {};
  },
  { urls: ["<all_urls>"] },
  ["blocking"]
);

// --- Live media sniffing (Issue 2: 7mmtv static HTML only yields ads) ---
//
// Observes (never blocks) every request. When the player actually asks
// for a video file (.m3u8/.mp4, extension must terminate the path —
// same rule as VideoPageSniffer, so "...Episode-1.mp4_snapshot_
// 05.21_....webp" thumbnails never match), and the URL is not an ad,
// it is reported to Kotlin as a mediaCandidate. The native side only
// collects while a sniff session is attached (GeckoSniffActivity);
// otherwise the messages are dropped harmlessly.
//
// Ad filter (same as VideoPageSniffer.adSegment): a URL segment that
// is exactly ad/ads/preview/trailer/thumb/sample/snapshot, a host in
// AD_HOSTS, or tsyndicate.com (VAST — the pre-roll itself is an ad
// video, never a feature candidate). VAST is never blocked either.
const AD_SEGMENTS = new Set(["ad", "ads", "preview", "trailer", "thumb", "sample", "snapshot"]);

function isVideoFile(url) {
  try {
    const clean = url.split("?")[0].split("#")[0].toLowerCase();
    return clean.endsWith(".m3u8") || clean.endsWith(".mp4");
  } catch (e) {
    return false;
  }
}

function isAdUrl(url) {
  try {
    const u = new URL(url);
    const host = u.hostname.toLowerCase();
    if (host === "tsyndicate.com" || host.endsWith(".tsyndicate.com")) return true;
    if (isAdHost(host)) return true;
    const tokens = url.toLowerCase().split(/[^a-z0-9]+/);
    for (const t of tokens) {
      if (AD_SEGMENTS.has(t)) return true;
    }
    return false;
  } catch (e) {
    return false;
  }
}

function reportMediaCandidate(url) {
  if (!nativePort) return;
  try {
    nativePort.postMessage({ type: "mediaCandidate", url: url });
  } catch (e) {
    // Port closed; drop.
  }
}

browser.webRequest.onBeforeRequest.addListener(
  function (details) {
    try {
      if (isVideoFile(details.url) && !isAdUrl(details.url)) {
        reportMediaCandidate(details.url);
      }
    } catch (e) {
      // Ignore; never break the request.
    }
    return {};
  },
  { urls: ["<all_urls>"] },
  ["blocking"]
);

// Native-messaging port to Kotlin (JavhdGeckoExtension sets the
// MessageDelegate for the native app name "javhdPlayer").
let nativePort = null;
try {
  nativePort = browser.runtime.connectNative("javhdPlayer");
  nativePort.onDisconnect.addListener(() => {
    nativePort = null;
  });
} catch (e) {
  // Native messaging unavailable; content-script messages are dropped.
}

// Content script -> background -> native (Kotlin).
browser.runtime.onMessage.addListener((message, sender) => {
  if (nativePort) {
    try {
      nativePort.postMessage(message);
    } catch (e) {
      // Port closed; drop.
    }
  }
});

// Native (Kotlin) -> background -> active tab content script.
// (Currently unused: the content script runs its skip polling
// autonomously and reports video state on its own. Kept for
// future commands, e.g. a manual "read video state" request.)
if (nativePort) {
  nativePort.onMessage.addListener((message) => {
    if (message && message.type === "toContent" && message.payload) {
      browser.tabs.query({ active: true, currentWindow: true }).then((tabs) => {
        if (tabs.length > 0) {
          browser.tabs.sendMessage(tabs[0].id, message.payload).catch(() => {});
        }
      });
    }
  });
}
