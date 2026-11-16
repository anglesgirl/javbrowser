package com.example.javbrowser.gecko

/** Maps the app-owned Gecko landing asset to its public URL for app navigation state. */
internal class LandingUrlMapper(
    private val landingAssetUrl: String,
    private val publicLandingUrl: String
) {
    private var landingAssetPendingOrShown = false

    fun beginLandingLoad() {
        landingAssetPendingOrShown = true
    }

    fun clearLandingLoad() {
        landingAssetPendingOrShown = false
    }

    fun map(url: String?): String? {
        if (url == null) return null
        if (url.startsWith(landingAssetUrl) && landingAssetPendingOrShown) {
            return publicLandingUrl
        }
        // Gecko emits a transient about:blank document when a session starts.
        // It is not a real navigation and must not consume the pending landing identity.
        if (!url.startsWith(landingAssetUrl) && url != "about:blank") {
            landingAssetPendingOrShown = false
        }
        return url
    }
}
