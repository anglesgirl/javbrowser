// Javhd Player Helper — background script.
//
// Two jobs:
// 1. Block the 11 ad hosts (same list as the old JavhdEmbedPlayerActivity
//    AD_HOSTS). tsyndicate.com (the VAST pre-roll) is NEVER blocked:
//    the feature only loads after the VAST is skipped, so blocking it
//    welds the gate shut.
// 2. Relay content-script messages (video state) to the native app
//    (Kotlin) over the native-messaging port, and vice versa.
//
// Live media sniffing — approach adapted from CatCatch (猫抓,
// https://github.com/xifangczy/cat-catch, GPL-3.0; see
// CAT_CATCH_LICENSE in this folder). CatCatch detects media with
// three independent signals instead of URL suffix alone:
//   (a) URL extension,
//   (b) response Content-Type (video/*, audio/*, HLS/DASH manifests),
//   (c) the browser's own resource type (details.type == "media").
// Its content script also reads the <video> element directly, which
// is the only thing that sees the address when the player uses
// blob:/MSE. We ported (a)+(b)+(c) and the <video> monitoring; we
// did NOT port its popup UI, downloader, or m3u8.js merge logic —
// the native player (Media3/ExoPlayer) plays HLS natively, including
// AES-128 (it fetches the key itself), so we only need the URL.

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
// Observes (never blocks) every request. A URL is reported as a
// mediaCandidate when ANY of these holds (CatCatch's three signals):
//   (a) the path ends in .m3u8/.mp4/.mpd (extension must terminate
//       the path — same rule as VideoPageSniffer, so
//       "...Episode-1.mp4_snapshot_05.21_....webp" thumbnails never
//       match);
//   (b) the response Content-Type is a media type (video/*,
//       audio/*, application/vnd.apple.mpegurl,
//       application/x-mpegurl, application/dash+xml) — this catches
//       signed/tokenized stream URLs that carry no extension, which
//       (a) alone misses;
//   (c) the browser itself classifies the request as media
//       (details.type == "media", i.e. a <video>/<audio> element
//       asked for it) — catches direct video loads whatever the URL
//       looks like.
// The URL must also pass the ad filter (same as
// VideoPageSniffer.adSegment): a URL segment that is exactly
// ad/ads/preview/trailer/thumb/sample/snapshot, a host in AD_HOSTS,
// or tsyndicate.com (VAST — the pre-roll itself is an ad video,
// never a feature candidate). VAST is never blocked either.
//
// The native side only collects while a sniff session is attached
// (GeckoSniffActivity); otherwise the messages are dropped
// harmlessly.
const AD_SEGMENTS = new Set(["ad", "ads", "preview", "trailer", "thumb", "sample", "snapshot"]);

// Media MIME types (CatCatch's CheckType idea, trimmed to what a
// native player can play; the type is the part before ";").
const MEDIA_MIME_PREFIXES = ["video/", "audio/"];
const MEDIA_MIME_EXACT = new Set([
  "application/vnd.apple.mpegurl",
  "application/x-mpegurl",
  "application/dash+xml"
]);

function isVideoFile(url) {
  try {
    const clean = url.split("?")[0].split("#")[0].toLowerCase();
    return clean.endsWith(".m3u8") || clean.endsWith(".mp4") || clean.endsWith(".mpd");
  } catch (e) {
    return false;
  }
}

function isMediaMimeType(contentType) {
  if (!contentType) return false;
  const type = contentType.split(";")[0].trim().toLowerCase();
  if (MEDIA_MIME_EXACT.has(type)) return true;
  for (const prefix of MEDIA_MIME_PREFIXES) {
    if (type.startsWith(prefix)) return true;
  }
  return false;
}

function responseContentType(details) {
  try {
    const headers = details.responseHeaders;
    if (!headers) return "";
    for (const h of headers) {
      if (h.name && h.name.toLowerCase() === "content-type") return h.value || "";
    }
  } catch (e) {
    // Ignore; treat as unknown.
  }
  return "";
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

// Dedup: the same playlist is re-fetched often (HLS refresh); only
// report each URL once per background lifetime. Capped like
// CatCatch (clear at 500) so the set never grows unboundedly.
const reportedUrls = new Set();
function reportMediaCandidate(url) {
  if (!nativePort) return;
  if (reportedUrls.has(url)) return;
  if (reportedUrls.size >= 500) reportedUrls.clear();
  reportedUrls.add(url);
  try {
    // 1.2.12：带上来源标注（source: "webRequest"），供嗅探诊断面
    // 板分路计数；不改过滤与上报逻辑。
    nativePort.postMessage({ type: "mediaCandidate", url: url, source: "webRequest" });
  } catch (e) {
    // Port closed; drop.
  }
}

// Signal (a): URL extension, seen before the response arrives.
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

// Signals (b)+(c): response headers + browser resource type.
// onResponseStarted fires when the first byte arrives, so the
// Content-Type is known. Purely observational (no "blocking") —
// it never touches the request.
browser.webRequest.onResponseStarted.addListener(
  function (details) {
    try {
      // (c) first: cheapest check, no header scan.
      if (details.type === "media") {
        if (!isAdUrl(details.url)) reportMediaCandidate(details.url);
        return;
      }
      // (b): media Content-Type, whatever the URL looks like.
      if (isMediaMimeType(responseContentType(details))) {
        if (!isAdUrl(details.url)) reportMediaCandidate(details.url);
      }
    } catch (e) {
      // Ignore; never break the request.
    }
  },
  { urls: ["<all_urls>"] },
  ["responseHeaders"]
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
