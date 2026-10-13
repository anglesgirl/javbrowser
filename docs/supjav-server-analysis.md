# SUPJAV Server analysis

Observed on the physical device on October 9, 2026, using the existing SUPJAV detail page `/463712.html`. This table records player structure, not a claim that playback and downloads have passed on every Server. Signed URLs are omitted.

| Server | Observed player | Source format / handling |
| --- | --- | --- |
| EVS | `evsishere.xyz` | JW Player declares an HLS playlist ending in `master.txt` under `/hls3/`. The fetched master playlist returned HTTP 200 and references `index-f*-v1-a1.txt` variants. Treat these manifests as HLS in playback, proxy rewriting, and downloads. |
| ST | `streamtape.com` | The `/e/.../name.mp4` URL is an embed page, not the video. Detect the actual `get_video` request or video source after player activation. Verification may require user interaction. |
| VOE | `teresapoliticallearn.com` | Follows a changing player domain through the SUPJAV wrapper. A playable source was not confirmed during the initial inspection. |
| VAS | `vvaassjjav.xyz` | JW Player declares signed HLS with an `/h265/` path. Codec compatibility still depends on the phone/player. |
| LUC | `luminscdn.xyz` | JW Player declares signed HLS ending in `master.m3u8`. |

The site's `.btn-server[data-link]` controls populate `iframe#video` through `JumpChain()`, which loads `lk1.supremejav.com/supjav.php`. The app follows the currently selected iframe and its primary child players, rather than requiring all hosts to match the former FST-only domain list.

Additional physical-device inspection of `/462685.html` found that the wrapper returned a body containing `404` in the resolver's top-level WebView, while the site's iframe loaded the corresponding LUC player successfully. The resolver now starts the wrapper as an iframe with the SUPJAV page as its base URL and observes child player requests from that trusted wrapper before inspecting the child. This preserves the wrapper's iframe loading context without trusting arbitrary frames on the SUPJAV page.

The wrapper's inner iframe uses another `/supjav.php?c=...` URL that redirects to the player. Network interception alone did not discover that final player on the physical device. On compatible WebViews, a document-start reporter now reads declared media inside the isolated child frames. Reports must match the browser-provided source origin; new player origins require a trusted parent referrer and an embed/player path. The actual iframe remains intact. Older WebViews retain the network/polling fallback, which still needs device validation.

Server changes invalidate the previous resolver and hide its playback button immediately. Delayed iframe loads cannot restore a previous selection. The resolver prefers declared content playlists, filters known advertising frames/requests, retains the actual player Referer, and reports detection timeout instead of reusing an old stream. DRM-protected, verification-gated, or unavailable sources are not represented as successful detections.

## Physical-device validation after iframe reporting

LUC playback through the Java playlist proxy received HTTP 403 even after aligning the User-Agent and Origin. On the same physical device, the same playlist fetched directly from the internal player's WebView returned HTTP 200. SUPJAV internal playback therefore uses direct WebView transport with the detected player's Referer as its page origin. EVS text playlists also select HLS.js when playback bypasses the proxy.

The installed APK was tested on `/462685.html` using the native playback button without runtime source overrides. Each successful source advanced across two observations, remained unpaused, and reported readyState 4 at the final observation.

| Server | Final playback time | Final resolution | Dropped / total video frames |
| --- | ---: | --- | ---: |
| EVS | 38 seconds | 853 x 480 | 0 / 1,151 |
| FST | 41 seconds | 853 x 480 | 0 / 1,247 |
| VOE | 40 seconds | 1280 x 720 | 0 / 1,218 |
| VAS | 36 seconds | 1920 x 1080 | 1 / 1,002 |
| LUC | 36 seconds | 1280 x 720 | 0 / 1,098 |
| ST | Not verified | Website robot verification required | No confirmed media URL |

FST initially loaded slowly; a fresh native playback attempt started automatically and completed the observation above. EVS and FST adapted between 1080p and 480p during these observations. The tested VAS source was H.264; compatibility with the H.265 source on the earlier title remains unverified. These are playback checks for one detail page, not a full offline-download, seek, subtitle, or long-duration reliability test. LUC downloading through Java networking remains unverified.
