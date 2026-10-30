# JavRate download interruption - October 30, 2026

## Report and observations

The user reported that the internal player worked but downloads repeatedly stopped around 9-11 percent. During direct phone inspection, a download progressed from 2 percent and subsequently failed at 13 percent. Native download logs switched from local-proxy HTTP 200 responses to HTTP 500 responses; the authorization WebView showed an HTTP 400 token-refresh request.

## Cause

The current public player SDK includes `apiSecretIssuedAt` in `/api/token/generate` requests. The app captured this configuration value but omitted it from its refresh request. Initial signed URLs worked until renewal was required. The rejected refresh then left the existing session without a usable replacement token, and the proxy's authorization wait eventually failed.

A controlled comparison in the same phone/browser session used the same current movie, session, signing secret, timestamp, and signature. The request without `apiSecretIssuedAt` returned HTTP 400 with a token-generation failure; including the supplied issue time returned HTTP 200 with a replacement token. No authorization fields were synthesized, and no website access restrictions were bypassed.

## Fix and validation

- Forward the captured issue time unchanged with the other refresh fields. Legacy configurations without the field continue to omit it through JSON serialization; normal-storage sources still do not use the token-refresh protocol.
- The existing regression test now uses a token endpoint fixture that rejects a missing or changed issue time. Both JavaScript session tests passed, including concurrent request deduplication, transient failure handling, signed token-path encoding, and normal-storage behavior.
- Debug compilation, APK signature verification, and replacement installation on the development phone succeeded.
- Observed the user's newly started download through all 1,688 segments, local MP4 packaging, and subtitle embedding. Its final status was completed at 100 percent, with no subtitle error.
- Verified the actual MP4 file on the phone: 2,058,099,147 bytes, matching the saved download record, with a valid `ftyp` header. Full-file decoding was not part of this download verification.
- Multiple ordinary refresh cycles returned HTTP 200 with replacement tokens while the download continued after the player Activity was closed. The download retained its existing authorization lease through completion.

This fix does not alter download concurrency, media packaging, or token signing. The observed failure percentage depends on transfer speed and authorization lifetime.
