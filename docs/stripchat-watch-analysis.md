# Stripchat watch status investigation - October 26, 2026

## Reported behavior

The model's webpage was playing a live broadcast while the floating watch control continued to say it was waiting for the broadcast. The clarified report concerns the webpage watch/recording control, not the bookmark status badge.

## Findings

The existing controls reduced watch state to two booleans: watching and recording. Every active watch session that was not recording displayed the same waiting label, including API lookup failures and recording-start failures.

During development-machine checks, the username-to-ID endpoint and the public model directory returned JSON, while both numeric and legacy username `/cam` endpoints returned HTTP 403. This is evidence that a playable page and an unavailable native status request can coexist; it does not establish the exact server response on the user's phone.

The recorder depended on a successful API lookup before attempting to start. Its successful-live path reloaded the webpage before recording even when the current main video was already playing.

## Changes

- Both page and internal-overlay controls obtain the same native state label: inactive, recording, starting, live/retrying recording, waiting for a confirmed unavailable public broadcast, checking, or unknown/retrying.
- A failed lookup is not displayed as a confirmed offline broadcast.
- A main-player probe compares two samples 1.25 seconds apart. Confirmation requires the same model, video object and source, an infinite media duration, nonpaused/nonended playback, decoded dimensions, a visible player-sized element, and advancing playback time. The sample gap must remain between one and five seconds.
- Preview/recommendation/card videos, finite-duration previews, and overlays associated with another model are excluded. An existing model overlay is reused on retry.
- Positive webpage evidence supplements an unknown API result. An explicit offline/private/unavailable API result is never overridden by this fallback. No media permission or website access restriction is bypassed.
- Recording starts from an already playing page video when available. Otherwise the existing API-confirmed-live reload path remains.
- Unknown checks retry after 15 seconds, recording startup expires after 35 seconds, and unknown results reset the consecutive offline-confirmation count.
- The user also reported missing controls on `zt.stripchat.com`. Mount controls on page commit and visited-history updates, not only page-finished callbacks. A single page-local refresh timer restores controls after body replacement, remounts them across SPA navigation, and updates state labels.

## Validation and limits

Five JavaScript tests cover advancing playback, source/model changes, paused/stalled/finite-preview rejection, foreign domains, stale overlays, localized-domain control mounting, delayed body creation, and SPA remounts. Two Kotlin tests cover unknown versus waiting states and recording/startup/retry precedence. Debug compilation and replacement installation succeeded.

The initial user confirmation that the control appeared was insufficient to establish recording success: the user subsequently reported an error popup. Direct WebView inspection and native file checks were therefore performed.

The reproduced popup corresponded to the unconditional `InternalPlayback` debug toast showing a successful `overlay-ready` response. Remove this success toast; actual overlay failures now have a user-facing failure message. Recording errors now log a bounded, URL-redacted error detail and a specific capability/source/canvas/storage/encoder/start/write stage. File creation and first-chunk writes also have INFO-level local diagnostic records.

The startup function previously swallowed exceptions after native file creation, potentially leaving the native recording store active when the encoder had failed to start. It now finalizes that attempt as failed and closes its storage. Chunk-write failures report their cause and stop recording. Three additional tests execute the actual embedded startup function and cover unsupported capture, post-file-creation encoder failure, and failed chunk writes.

A directly operated diagnostic session produced a 149.12-second, 74,342,660-byte MP4 containing H.264 1280 x 720 video and AAC audio. Both streams had strictly increasing original packet DTS values. Full decoding passed with the decoded-video output time base set to microseconds; the null muxer's default frame time base otherwise rounded some variable-rate frames to the same output timestamp. Diagnostic sessions were finalized and left on the phone.

After installing the error-reporting update, direct inspection confirmed a new active session, native storage-ready/first-chunk-write records, and a growing MP4. The current user session was left running. Automatic stop/reconnect transitions remain unverified.

The fallback deliberately cannot confirm a finite-duration/DVR player, an unrecognized player element, or a player that is paused or not advancing. Such a case remains unknown when the API is unavailable. Unknown results do not force-stop an existing recording.
