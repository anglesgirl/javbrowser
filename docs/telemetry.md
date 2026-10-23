# Usage analytics and crash reporting

Firebase Analytics and Crashlytics start automatically in this build, including the debug APK distributed through GitHub. There is no consent dialog or in-app opt-out. Settings displays a collection notice in Traditional Chinese or English.

## Build setup

Register the Android package `com.example.javbrowser` in Firebase and enable Google Analytics for that Firebase project. Copy its downloaded configuration to `app/google-services.json` before building. The project-specific file is ignored by Git; CI environments must supply their own copy. No service-account key belongs in the app.

The Firebase BoM is pinned to 33.7.0 to preserve compatibility with the existing Kotlin 1.9 toolchain. Analytics and Crashlytics use the main Firebase modules. The Google Services plugin generates configuration resources; the Crashlytics plugin supplies its build identifier.

## Collected events

| Event | Parameters | Meaning |
| --- | --- | --- |
| SDK first open / session / engagement | Firebase standard app metadata | First observed use after installation/reinstallation, activity, and engagement |
| `feature_open` | `feature`: browser, player, downloads, history, search, settings | Creation of a supported feature activity, excluding restored activity state; browser includes HOME and webpage browsing |
| `player_attempt` | `mode`: online, offline | Opening the internal player or selecting another supported episode |
| `player_started` | `mode` | First HTML video `playing` event for that attempt; pauses and buffering do not add successes |
| `player_error` | `mode`, `phase`: startup, playback, authorization; `media_error_code`: 0..4 | First observed failure for that attempt; 0 denotes a native setup/authorization failure |
| `download_result` | `outcome`: started, completed, canceled, failed; `error_category`: permission, io, other | One event for an accepted download and one for its normal terminal path; excludes subtitle-repair-only jobs |

App versions, Android/device information, SDK-generated installation identifiers, session information, and crash diagnostics are also processed by Firebase. These identifiers are pseudonymous, not guaranteed anonymous. No account identity, hardware identifier, or custom user ID is assigned by the app.

Custom event APIs accept fixed enums, booleans, and bounded media codes. They do not accept webpage URLs, search terms, titles, actress names, history records, download IDs, filenames, cookies, tokens, or arbitrary JavaScript strings. Automatic screen reporting and advertising ID collection are disabled, advertising consent is denied, and advertising ID/AdServices permissions are removed from the merged app manifest.

Java/Kotlin uncaught exceptions are copied before reaching the Firebase default crash handler. Exception messages are replaced with exception class names; code locations are retained. Causes and suppressed errors are sanitized recursively with cycle/depth limits. Nonfatal download errors use the same sanitizer. The app does not forward Android logcat or its existing browser/player diagnostic strings to Crashlytics. Crashlytics also generates its own SDK/device/thread diagnostics; the Java exception sanitizer does not rewrite those system-generated fields or native/ANR diagnostics.

## Measurement limits

- APK downloads on GitHub are not installations. Analytics first-open events require the updated app to run and upload data; installations that never launch are not measurable this way.
- Reinstalling, clearing app data, or using multiple devices can create additional measured users. Without account login, these metrics estimate app instances rather than unique people.
- Previous app builds cannot retrospectively report their activity. Collection starts with this build.
- Offline devices, blocked endpoints, process termination, or uninstalling before upload can reduce reported counts. A started download interrupted by process termination may have no terminal event.
- A failed playback attempt can later recover and start successfully. Analyze startup errors and playback errors separately; they are not mutually exclusive complements of successful attempts.
- The SDK can report activity before any custom feature event. Service-only process starts are not explicitly logged as app launches by this integration.
- Analytics reports can be delayed. Use Firebase DebugView during development; disable debug mode before ordinary measurement.

## Validation

Build with `assembleDebug` and run `TelemetryErrorsTest` to verify that nested/cyclic errors cannot leak their messages through the sanitizer. Inspect the merged manifest for collection flags, removed advertising permissions, and the Application class. Verify the Firebase app ID generated from the local configuration.

For end-to-end validation, install the new APK, set `adb shell setprop debug.firebase.analytics.app com.example.javbrowser`, open the app, and inspect Firebase Analytics DebugView. Disable this with `adb shell setprop debug.firebase.analytics.app .none.` afterward. Confirm Crashlytics ingestion separately using an explicitly requested test crash on a development device, then relaunch the app. Production crash reporting should not be tested by deliberately crashing a user's current playback session.

References: [Firebase Analytics](https://firebase.google.com/docs/analytics), [Android Crashlytics setup](https://firebase.google.com/docs/crashlytics/android/get-started), [automatic events](https://support.google.com/analytics/answer/9234069).
