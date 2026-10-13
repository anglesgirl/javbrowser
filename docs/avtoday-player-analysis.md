# AVToday player analysis

On October 9, 2026, the website and the physical device's `/cht/video/MIDA-807.html` page supplied a signed MP4 through the same-origin `/player?s=...` iframe. Its `video_link` value pointed to `video.xdmm.workers.dev` with `exp`, `ua`, and `sig` parameters; `m3u8_url` was empty. Signed URLs are omitted.

The previous AVToday monitor accepted only `.m3u8` URLs and could inspect unrelated page preview sources. The updated monitor reads `video_link` and `m3u8_url` from the selected player, with declared-variable parsing as a fallback. The native callback validates the detail page, player endpoint, and media format, rejects stale page callbacks, and retains the actual player URL as Referer. Generic page sniffing no longer overwrites AVToday selections.

The signed primary MP4 was passed to the existing internal player on the physical device and played to 44 seconds at 1280 x 720, with readyState 4 and 0 dropped frames out of 1,314 decoded frames. After installation, the user opened `/cht/video/FC2PPV-4975620.html`. The new monitor automatically produced the native playback button without a runtime source override. Internal playback advanced from 7 seconds to 52 seconds at 1280 x 720, with readyState 4 and 0 dropped frames out of 1,561 decoded frames. Unit tests cover signed MP4, legacy HLS, invalid sources, and rejection of homepage previews or unrelated player pages. The real injected JavaScript also passed a runtime check for MP4/HLS selection without inspecting page previews.

The first page was selected for diagnosis; the second was opened by the user after installation. Offline downloads, token renewal, and other titles remain unverified.
