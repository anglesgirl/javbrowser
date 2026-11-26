package com.example.javbrowser

import android.content.Context
import com.bumptech.glide.Glide
import com.bumptech.glide.Registry
import com.bumptech.glide.annotation.GlideModule
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.data.DataFetcher
import com.bumptech.glide.load.model.ModelLoader
import com.bumptech.glide.load.model.ModelLoaderFactory
import com.bumptech.glide.load.model.MultiModelLoaderFactory
import com.bumptech.glide.module.AppGlideModule
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * Routes Glide image loads through the ECH / Cronet transports
 * ([EchImageFetcher]) instead of Glide's default plain
 * HttpURLConnection (TCP), which gets RST on the user's network
 * for image CDNs such as Whos.tv's v.hersav.me.
 *
 * Registered with [Registry.prepend] for String → InputStream, so
 * it takes precedence over Glide's default HttpUrl loader for
 * http(s) URLs. [EchImageModelLoader.handles] returns false for
 * non-http(s) Strings (file paths, content URIs), so those still
 * use Glide's default loaders.
 *
 * Fallback safety: [EchImageFetcher] itself implements the full
 * ECH → Cronet → plain chain (plain as the Cronet route's
 * fallback), so this loader is a strict superset of plain Glide —
 * it can only make more images load, never fewer. The one
 * exception is the ECH route, which is fail-closed (no plaintext
 * fallback, same SNI-leak rule as search): an ECH host whose ECH
 * handshake fails shows the error placeholder, exactly as it
 * does today.
 */
@GlideModule
class EchGlideModule : AppGlideModule() {
    override fun registerComponents(context: Context, glide: Glide, registry: Registry) {
        registerEchImageLoader(registry)
    }

    override fun isManifestParsingEnabled(): Boolean = false

    companion object {
        @Volatile private var registered = false

        /**
         * Programmatic fallback for [registerComponents]: the kapt
         * annotation processor is supposed to discover this module,
         * but a missed processor run leaves Glide on its default
         * plain-TCP fetcher with no error (grey covers, 1.2.38 /
         * 1.2.40). Prepending the loader directly guarantees the ECH
         * route is used even if the generated module is not picked
         * up. Idempotent; safe to call from every image bind.
         */
        @Synchronized
        fun ensureRegistered(context: Context) {
            if (registered) return
            Glide.get(context).registry.let { registry ->
                registerEchImageLoader(registry)
            }
            registered = true
        }

        private fun registerEchImageLoader(registry: Registry) {
            registry.prepend(
                String::class.java,
                InputStream::class.java,
                EchImageModelLoader.Factory(),
            )
        }
    }
}

internal class EchImageModelLoader : ModelLoader<String, InputStream> {
    override fun buildLoadData(
        model: String,
        width: Int,
        height: Int,
        options: Options,
    ): ModelLoader.LoadData<InputStream>? {
        if (!handles(model)) return null
        return ModelLoader.LoadData(
            com.bumptech.glide.signature.ObjectKey(model),
            EchImageDataFetcher(model),
        )
    }

    override fun handles(model: String): Boolean {
        val lower = model.lowercase()
        return lower.startsWith("http://") || lower.startsWith("https://")
    }

    class Factory : ModelLoaderFactory<String, InputStream> {
        override fun build(multiFactory: MultiModelLoaderFactory): ModelLoader<String, InputStream> =
            EchImageModelLoader()

        override fun teardown() {}
    }
}

private class EchImageDataFetcher(private val url: String) : DataFetcher<InputStream> {
    @Volatile private var cancelled = false

    override fun loadData(priority: com.bumptech.glide.Priority, callback: DataFetcher.DataCallback<in InputStream>) {
        if (cancelled) {
            callback.onLoadFailed(Exception("canceled"))
            return
        }
        val bytes = EchImageFetcher.fetchBytes(url)
        if (cancelled) {
            callback.onLoadFailed(Exception("canceled"))
            return
        }
        if (bytes == null || bytes.isEmpty()) {
            callback.onLoadFailed(Exception("ECH image fetch failed: $url"))
            return
        }
        callback.onDataReady(ByteArrayInputStream(bytes))
    }

    override fun cleanup() {}

    override fun cancel() {
        cancelled = true
    }

    override fun getDataClass(): Class<InputStream> = InputStream::class.java

    override fun getDataSource(): DataSource = DataSource.REMOTE
}
