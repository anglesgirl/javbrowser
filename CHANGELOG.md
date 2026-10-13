# Changelog

## 1.2.0 Update — 2026-10-14

The app version remains 1.2.0. These updates extend the existing 1.2.0 codebase.

### Playback History

- Record the original webpage when opening the internal player, including subsequent episode selections.
- Keep the latest 200 unique pages and move replayed pages to the top. Trim older histories to the same limit.
- Display video covers, website icons, page titles, actress names when supplied by the page, and playback dates.
- Recover missing artwork and actress metadata for existing entries where the website permits access.
- Search history by title, actress, or URL; swipe left to delete individual records, or confirm before clearing the list.
- Reopen the original webpage from a history entry and return to the history list when closing the browser.
- Place a full-width dark yellow history button below all HOME website shortcuts, with another entry in Settings.

### Internal Player and Episodes

- Add ROU and JAVDAY episode selection inside the internal player and automatically advance to the next available episode when playback ends.
- Preserve episode labels and update the selected page and download source when switching episodes.
- Resolve ROU's primary stream before the webpage player has been started and prevent thumbnail playlists from replacing the video stream.
- Recognize changing ROU domains and extensionless HLS API endpoints.
- Add a clean-picture button beside Close to hide player controls and overlays; tap the picture to restore them.

### HOME and Search

- Show an optional HOME prompt for copied links belonging to supported website shortcuts while the app is in the foreground.
- Correct HOME page recognition so clipboard prompts can appear after returning to the app.
- Add separate ROU video and series search sources, preserve the selected tab during pagination, and add JAVDAY search shortcuts.
- Extend site navigation, search adapters, source filters, and cover handling for JAVDAY, SUPJAV, and JavRate.

### Site Playback, Downloads, and Subtitles

- Follow the selected SUPJAV Server and its player frames, invalidate stale selections, retain the player Referer, and recognize EVS text-based HLS manifests and Streamtape media endpoints.
- Use direct WebView transport for supported SUPJAV playback and report unavailable or verification-gated sources without retaining a previous stream.
- Detect AVToday signed MP4 and legacy HLS from the selected player iframe while excluding page previews.
- Maintain JavRate authorization sessions for playback and downloads, and isolate advertising popups from the main page.
- Import JavRate WebVTT subtitles into the internal player and embed available tracks into downloaded MP4 files without re-encoding video.
- Read embedded subtitles during offline playback and preserve subtitle packaging status and failure information in download records.
- Add subtitle and packaging repair actions for existing downloads, preserve edit-list timing, and normalize ADTS AAC samples during MP4 packaging.

### Build and Validation Notes

- Debug APK compilation and replacement installation succeeded on the development Android device.
- Website metadata remains dependent on the page layout and access restrictions; unavailable actress names use a placeholder.
- Earlier site-specific device observations are documented in `docs/avtoday-player-analysis.md` and `docs/supjav-server-analysis.md`.
- A complete regression test run was not performed for this publication.

## 1.2.0 — 2026-10-02

### Offline Downloads and Playback

- Consolidate duplicate library records that point to the same physical video file while retaining download metadata.
- Preserve source presentation timestamps during MP4 remuxing to avoid corrupting B-frame timing and causing uneven offline playback.
- Improve cleanup of the original transport-stream file after MP4 conversion.

### Video Deletion

- Delete videos through the appropriate local-file, document-provider, or MediaStore operation.
- Request system consent when Android requires permission to delete a media file.
- Retry deletion after Android 10 grants permission.
- Keep library records when file deletion fails or permission is denied, with a visible explanation.

### Browser Navigation

- Follow WebView page history before returning from a search-result or sales-ranking browser page.
- Return to the originating list when no previous page remains.
- Provide a close action that immediately returns to the originating list.
- Apply the same Back and Close behavior to original search-source pages.
- Reset earlier browser history when opening a sales-ranking item.
- Refresh the close action when an existing browser activity receives a new navigation request.

### Validation

- Debug APK compilation and replacement installation were verified on a physical Android device during development.
- Website-specific navigation and Android 10 deletion consent require additional device coverage.
