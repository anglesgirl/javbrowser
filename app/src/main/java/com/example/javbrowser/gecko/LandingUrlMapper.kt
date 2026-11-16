package com.example.javbrowser.gecko

/** Maps the exact app-owned Gecko landing document to its public navigation identity. */
internal class LandingUrlMapper(
    private var landingAssetUrl: String,
    private val publicLandingUrl: String
) {
    private var landingAssetPendingOrShown = false

    fun beginLandingLoad(assetUrl: String = landingAssetUrl) {
        landingAssetUrl = assetUrl
        landingAssetPendingOrShown = true
    }

    fun clearLandingLoad() {
        landingAssetPendingOrShown = false
    }

    fun map(url: String?): String? {
        if (url == null) return null
        val isLandingAsset = url.substringBefore('#').substringBefore('?') == landingAssetUrl
        if (isLandingAsset && landingAssetPendingOrShown) return publicLandingUrl
        // Gecko emits a transient about:blank document when a session starts.
        if (!isLandingAsset && url != "about:blank") landingAssetPendingOrShown = false
        return url
    }
}
