package com.example.javbrowser

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaDataSource
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.provider.MediaStore
import android.util.Base64
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.appcompat.content.res.AppCompatResources
import androidx.documentfile.provider.DocumentFile
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.channels.Channels
import org.json.JSONArray
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class VideoDownloadService : Service() {

    companion object {
        const val ACTION_START = "com.example.javbrowser.DOWNLOAD_START"
        const val ACTION_CANCEL = "com.example.javbrowser.DOWNLOAD_CANCEL"
        const val ACTION_EMBED_SUBTITLES = "com.example.javbrowser.EMBED_SUBTITLES"
        const val EXTRA_URL = "url"
        const val EXTRA_REFERER = "referer"
        const val EXTRA_COOKIES = "cookies"
        const val EXTRA_COOKIE_SOURCE_URL = "cookie_source_url"
        const val EXTRA_FILE_NAME = "file_name"
        const val EXTRA_DOWNLOAD_ID = "download_id"
        const val EXTRA_JAVRATE_LEASE = "javrate_lease"
        const val EXTRA_SUBTITLE_SOURCES = "subtitle_sources"

        private const val CHANNEL_ID = "video_downloads"
        private const val LEGACY_SUMMARY_NOTIFICATION_ID = 4201
        private const val HLS_PARALLEL_DOWNLOADS = 3
        private const val MISSAV_PARALLEL_DOWNLOADS = 2
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        private val nextNotificationId = AtomicInteger(4300)
    }

    private val executor = Executors.newFixedThreadPool(3)
    private val activeTasks = ConcurrentHashMap<String, DownloadTask>()
    private val surritHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .build()
    }
    private val currentTask = ThreadLocal<DownloadTask>()
    private val foregroundLock = Any()
    @Volatile private var foregroundStarted = false
    private var foregroundNotificationId: Int? = null
    private lateinit var notificationManager: NotificationManager

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        createNotificationChannel()
        notificationManager.cancel(LEGACY_SUMMARY_NOTIFICATION_ID)
        cleanupOldHlsCaches()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            val downloadId = intent.getStringExtra(EXTRA_DOWNLOAD_ID)
            val task = downloadId?.let(activeTasks::get)
            if (task != null) {
                task.canceled.set(true)
                task.activeConnections.forEach { it.disconnect() }
                task.activeCalls.forEach { it.cancel() }
                notificationManager.notify(
                    task.notificationId,
                    buildProgressNotification("正在取消下載...", 0, true, downloadId)
                )
            }
            return START_NOT_STICKY
        }

        val url = intent?.getStringExtra(EXTRA_URL) ?: return START_NOT_STICKY
        val referer = intent.getStringExtra(EXTRA_REFERER).orEmpty()
        val cookies = intent.getStringExtra(EXTRA_COOKIES).orEmpty()
        val cookieSourceUrl = intent.getStringExtra(EXTRA_COOKIE_SOURCE_URL).orEmpty()
        val requestedName = intent.getStringExtra(EXTRA_FILE_NAME).orEmpty()
        val downloadId = intent.getStringExtra(EXTRA_DOWNLOAD_ID)
            ?: DownloadRepository.create(this, requestedName, url, referer, cookieSourceUrl).id
        val repairRecord = if (intent.action == ACTION_EMBED_SUBTITLES) {
            DownloadRepository.list(this).firstOrNull { it.id == downloadId && DownloadRepository.canRepairSubtitles(it) }
                ?: return START_NOT_STICKY
        } else null

        val task = DownloadTask(downloadId, nextNotificationId.getAndIncrement())
        val accepted = synchronized(foregroundLock) {
            if (activeTasks.putIfAbsent(downloadId, task) != null) {
                false
            } else {
                if (!foregroundStarted) {
                    startForeground(
                        task.notificationId,
                        buildProgressNotification("已加入下載佇列", 0, true, downloadId)
                    )
                    foregroundStarted = true
                    foregroundNotificationId = task.notificationId
                }
                true
            }
        }
        if (!accepted) {
            return START_NOT_STICKY
        }
        if (repairRecord == null) AppTelemetry.download(AppTelemetry.DownloadOutcome.STARTED)
        task.javrateLease.set(JavratePlaybackLease.acquire(intent.getStringExtra(EXTRA_JAVRATE_LEASE)))
        task.subtitleSources = intent.getStringExtra(EXTRA_SUBTITLE_SOURCES)
            ?: DownloadRepository.list(this).firstOrNull { it.id == downloadId }?.subtitleSources ?: "[]"

        DownloadRepository.update(this, downloadId) {
            it.copy(status = DownloadRepository.STATUS_PENDING, message = if (repairRecord != null) "等待補入字幕" else "等待下載",
                subtitleSources = task.subtitleSources, subtitleError = null)
        }
        notificationManager.notify(
            task.notificationId,
            buildProgressNotification("已加入下載佇列", 0, true, downloadId)
        )
        executor.execute {
            currentTask.set(task)
            try {
                DownloadRepository.update(this, downloadId) {
                    it.copy(status = DownloadRepository.STATUS_DOWNLOADING, message = "準備下載")
                }
                notificationManager.notify(
                    task.notificationId,
                    buildProgressNotification("準備下載...", 0, true, downloadId)
                )
                if (repairRecord != null) runSubtitleRepair(repairRecord)
                else runDownload(url, referer, cookies, cookieSourceUrl, requestedName, downloadId)
            } finally {
                task.javrateLease.getAndSet(null)?.release()
                currentTask.remove()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        activeTasks.values.forEach { task ->
            task.canceled.set(true)
            task.javrateLease.getAndSet(null)?.release()
            task.activeConnections.forEach { it.disconnect() }
            task.activeCalls.forEach { it.cancel() }
        }
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun runSubtitleRepair(record: VideoDownloadRecord) {
        val source = Uri.parse(requireNotNull(record.fileUri))
        var finalUri = source
        var completionMessage = "下載完成（字幕合併失敗，影片已保留）"
        try {
            checkCanceled()
            updateProgress(record.id, 99, "正在合併字幕", 0L)
            val existing = runCatching {
                val descriptor = contentResolver.openFileDescriptor(source, "r") ?: throw IOException("Unable to open video")
                android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { EmbeddedSubtitles.read(it.channel) }
            }.getOrDefault(emptyList())
            val subtitles = existing.ifEmpty { fetchDownloadSubtitles(currentDownloadTask().subtitleSources) }
            require(subtitles.isNotEmpty()) { "No subtitles available" }
            val result = tryEmbedSubtitles(source, sanitizeFileName(record.title), record.id, subtitles)
                ?: throw IOException(currentDownloadTask().subtitleError ?: "Subtitle embedding failed")
            finalUri = result.uri
            completionMessage = "下載完成（已內嵌字幕）"
        } catch (error: DownloadCanceledException) {
            completionMessage = "字幕補入已取消，影片已保留"
        } catch (error: Exception) {
            currentDownloadTask().subtitleError = subtitleErrorMessage(error)
            android.util.Log.w("DOWNLOAD_SUBTITLES", "Subtitle repair failed; original video kept", error)
        } finally {
            DownloadRepository.update(this, record.id) {
                it.copy(status = DownloadRepository.STATUS_COMPLETED, progress = 100, message = completionMessage,
                    fileUri = finalUri.toString(), fileSizeBytes = LocalVideoRepository.querySize(this, finalUri),
                    subtitleError = currentDownloadTask().subtitleError, speedBytesPerSecond = 0L,
                    packagingRevision = if (finalUri != source) 2 else it.packagingRevision)
            }
            notificationManager.notify(currentDownloadTask().notificationId, buildResultNotification(completionMessage, finalUri))
            finishTask(record.id)
        }
    }

    private fun runDownload(
        url: String,
        referer: String,
        cookies: String,
        cookieSourceUrl: String,
        requestedName: String,
        downloadId: String
    ) {
        var target: DownloadTarget? = null
        try {
            checkCanceled()
            val baseName = sanitizeFileName(requestedName)
            var subtitleFailure = false
            val subtitles = try {
                fetchDownloadSubtitles(currentDownloadTask().subtitleSources)
            } catch (error: DownloadCanceledException) {
                throw error
            } catch (error: Exception) {
                subtitleFailure = true
                currentDownloadTask().subtitleError = subtitleErrorMessage(error)
                android.util.Log.w("DOWNLOAD_SUBTITLES", "Subtitle download failed; keeping video download", error)
                emptyList()
            }
            val result = if (looksLikeHls(url)) {
                val playlist = resolveMediaPlaylist(url, referer, cookies, cookieSourceUrl)
                val parts = parseMediaPlaylist(playlist.url, playlist.text)
                val isFragmentedMp4 = parts.any { it.isInit || it.url.substringBefore('?').endsWith(".m4s", true) }
                val extension = if (isFragmentedMp4) "mp4" else "ts"
                val mimeType = if (isFragmentedMp4) "video/mp4" else "video/mp2t"
                if (isMissAvDownload(url, referer)) {
                    val partsDirectory = prepareHlsCache(url, parts)
                    try {
                        downloadHlsParts(
                            parts,
                            partsDirectory,
                            referer,
                            cookies,
                            cookieSourceUrl,
                            downloadId,
                            MISSAV_PARALLEL_DOWNLOADS
                        )
                    } catch (e: DownloadCanceledException) {
                        throw e
                    } catch (parallelError: Exception) {
                        val task = currentDownloadTask()
                        task.activeCalls.toList().forEach { it.cancel() }
                        task.activeConnections.toList().forEach { it.disconnect() }
                        android.util.Log.w(
                            "VIDEO_DOWNLOAD_HTTP",
                            "MissAV parallel download failed; falling back to sequential",
                            parallelError
                        )
                        updateProgress(
                            downloadId,
                            task.progress.get(),
                            "並發受限，切換單線下載",
                            0L
                        )
                        notificationManager.notify(
                            task.notificationId,
                            buildProgressNotification(
                                "並發受限，切換單線下載",
                                task.progress.get(),
                                false,
                                downloadId
                            )
                        )
                        downloadHlsPartsToCacheSequential(
                            parts,
                            partsDirectory,
                            referer,
                            cookies,
                            cookieSourceUrl,
                            downloadId
                        )
                    }
                    checkCanceled()
                    val downloadTarget = packageHlsParts(partsDirectory, parts.size, baseName, extension, mimeType, downloadId)
                    target = downloadTarget
                    partsDirectory.deleteRecursively()
                    DownloadResult(downloadTarget.uri, downloadTarget.fileName.endsWith(".ts"))
                } else {
                    val partsDirectory = prepareHlsCache(url, parts)
                    downloadHlsParts(parts, partsDirectory, referer, cookies, cookieSourceUrl, downloadId)
                    checkCanceled()
                    val downloadTarget = packageHlsParts(partsDirectory, parts.size, baseName, extension, mimeType, downloadId)
                    target = downloadTarget
                    partsDirectory.deleteRecursively()
                    DownloadResult(downloadTarget.uri, downloadTarget.fileName.endsWith(".ts"))
                }
            } else {
                val downloadTarget = createDownloadTarget(baseName, "mp4", "video/mp4")
                target = downloadTarget
                updateTargetRecord(downloadId, downloadTarget, "video/mp4")
                downloadDirect(url, downloadTarget.output, referer, cookies, cookieSourceUrl, downloadId)
                DownloadResult(downloadTarget.uri, false)
            }

            val completedTarget = target ?: throw IOException("下載輸出檔狀態遺失")
            completedTarget.finish(true)
            var finalTarget = completedTarget
            var finalUri = result.uri
            if (result.remuxTsToMp4) {
                tryRemuxTsToMp4(result.uri, baseName, downloadId)?.let { mp4Target ->
                    target = mp4Target
                    finalTarget = mp4Target
                    finalUri = mp4Target.uri
                }
            }
            if (subtitles.isNotEmpty()) {
                val embedded = tryEmbedSubtitles(finalUri, baseName, downloadId, subtitles)
                if (embedded != null) {
                    target = embedded
                    finalTarget = embedded
                    finalUri = embedded.uri
                } else subtitleFailure = true
            }
            val completionMessage = when {
                subtitleFailure -> "下載完成（字幕合併失敗，影片已保留）"
                subtitles.isNotEmpty() -> "下載完成（已內嵌字幕）"
                else -> "下載完成"
            }
            DownloadRepository.update(this, downloadId) {
                it.copy(
                    status = DownloadRepository.STATUS_COMPLETED,
                    progress = 100,
                    message = completionMessage,
                    speedBytesPerSecond = 0L,
                    fileSizeBytes = LocalVideoRepository.querySize(this, finalUri),
                    completedAt = System.currentTimeMillis(),
                    subtitleError = currentDownloadTask().subtitleError,
                    packagingRevision = if (!subtitleFailure && subtitles.isNotEmpty()) 2 else it.packagingRevision
                )
            }
            notificationManager.notify(
                currentDownloadTask().notificationId,
                buildResultNotification("$completionMessage：${finalTarget.fileName}", finalUri)
            )
            AppTelemetry.download(AppTelemetry.DownloadOutcome.COMPLETED)
        } catch (e: DownloadCanceledException) {
            AppTelemetry.download(AppTelemetry.DownloadOutcome.CANCELED)
            target?.finish(false)
            DownloadRepository.update(this, downloadId) {
                it.copy(
                    status = DownloadRepository.STATUS_CANCELED,
                    message = "下載已取消",
                    speedBytesPerSecond = 0L,
                    fileUri = null,
                    completedAt = System.currentTimeMillis()
                )
            }
            notificationManager.notify(
                currentDownloadTask().notificationId,
                buildResultNotification("下載已取消", null)
            )
        } catch (e: Exception) {
            AppTelemetry.download(AppTelemetry.DownloadOutcome.FAILED, e)
            target?.finish(false)
            val message = e.message?.take(120) ?: "未知錯誤"
            DownloadRepository.update(this, downloadId) {
                it.copy(
                    status = DownloadRepository.STATUS_FAILED,
                    message = message,
                    speedBytesPerSecond = 0L,
                    fileUri = null,
                    completedAt = System.currentTimeMillis()
                )
            }
            notificationManager.notify(
                currentDownloadTask().notificationId,
                buildResultNotification("下載失敗：$message", null)
            )
        } finally {
            finishTask(downloadId)
        }
    }

    private fun resolveMediaPlaylist(
        initialUrl: String,
        referer: String,
        cookies: String,
        cookieSourceUrl: String
    ): PlaylistResult {
        var currentUrl = initialUrl
        repeat(4) {
            checkCanceled()
            val text = fetchText(currentUrl, referer, cookies, cookieSourceUrl)
            val variants = parseMasterVariants(currentUrl, text)
            if (variants.isEmpty()) return PlaylistResult(currentUrl, text)
            currentUrl = variants.maxByOrNull { it.bandwidth }?.url
                ?: throw IOException("找不到 HLS 畫質清單")
        }
        throw IOException("HLS 清單層級過深")
    }

    private fun parseMasterVariants(baseUrl: String, playlist: String): List<HlsVariant> {
        val lines = playlist.lineSequence().map { it.trim() }.toList()
        val variants = mutableListOf<HlsVariant>()
        for (index in lines.indices) {
            val line = lines[index]
            if (!line.startsWith("#EXT-X-STREAM-INF", true)) continue
            val bandwidth = Regex("BANDWIDTH=(\\d+)", RegexOption.IGNORE_CASE)
                .find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            val uriLine = lines.drop(index + 1).firstOrNull { it.isNotBlank() && !it.startsWith("#") }
                ?: continue
            variants.add(HlsVariant(resolveUrl(baseUrl, uriLine), bandwidth))
        }
        return variants
    }

    private fun parseMediaPlaylist(baseUrl: String, playlist: String): List<HlsPart> {
        if (!playlist.contains("#EXTM3U")) throw IOException("回應不是 HLS 清單")
        if (!playlist.contains("#EXT-X-ENDLIST")) throw IOException("目前不支援直播下載")
        if (playlist.contains("#EXT-X-BYTERANGE")) throw IOException("目前不支援 Byte-Range HLS")

        val parts = mutableListOf<HlsPart>()
        var currentKey: HlsKey? = null
        var sequence = Regex("#EXT-X-MEDIA-SEQUENCE:(\\d+)", RegexOption.IGNORE_CASE)
            .find(playlist)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        playlist.lineSequence().map { it.trim() }.forEach { line ->
            when {
                line.startsWith("#EXT-X-KEY", true) -> {
                    val method = Regex("METHOD=([^,]+)", RegexOption.IGNORE_CASE)
                        .find(line)?.groupValues?.get(1)?.trim()?.uppercase(Locale.US)
                        ?: throw IOException("無法解析 HLS 加密方式")
                    currentKey = when (method) {
                        "NONE" -> null
                        "AES-128" -> {
                            val uri = readHlsUriAttribute(line)
                                ?: throw IOException("AES-128 缺少金鑰網址")
                            val iv = Regex("IV=([^,]+)", RegexOption.IGNORE_CASE)
                                .find(line)?.groupValues?.get(1)?.trim()
                            HlsKey(resolveUrl(baseUrl, uri), iv)
                        }
                        else -> throw IOException("不支援 $method 加密或 DRM")
                    }
                }
                line.startsWith("#EXT-X-MAP", true) -> {
                    val uri = readHlsUriAttribute(line)
                        ?: throw IOException("無法解析 HLS 初始化片段")
                    if (currentKey != null && currentKey?.ivHex == null) {
                        throw IOException("加密初始化片段缺少 IV")
                    }
                    parts.add(HlsPart(resolveUrl(baseUrl, uri), true, currentKey, sequence))
                }
                line.isNotEmpty() && !line.startsWith("#") -> {
                    parts.add(HlsPart(resolveUrl(baseUrl, line), false, currentKey, sequence))
                    sequence++
                }
            }
        }
        if (parts.none { !it.isInit }) throw IOException("HLS 清單沒有影片分段")
        return parts
    }

    private fun readHlsUriAttribute(tag: String): String? {
        val match = Regex("""URI=(?:\"([^\"]+)\"|'([^']+)'|([^,\s]+))""", RegexOption.IGNORE_CASE)
            .find(tag) ?: return null
        return match.groupValues.drop(1).firstOrNull { it.isNotEmpty() }
    }

    private fun downloadHlsParts(
        parts: List<HlsPart>,
        partsDirectory: File,
        referer: String,
        cookies: String,
        cookieSourceUrl: String,
        downloadId: String,
        parallelDownloads: Int = HLS_PARALLEL_DOWNLOADS
    ) {
        val task = currentDownloadTask()
        parts.mapNotNull { it.key }.distinctBy { it.url }.forEach { key ->
            if (!task.hlsKeyCache.containsKey(key.url)) {
                task.hlsKeyCache[key.url] = normalizeAes128Key(fetchBytesWithRetry(
                    key.url,
                    referer,
                    cookies,
                    cookieSourceUrl
                ))
            }
        }

        val cachedIndexes = parts.indices.filter { hlsPartFile(partsDirectory, it).length() > 0L }.toSet()
        val missingIndexes = parts.indices.filterNot(cachedIndexes::contains)
        var completedParts = cachedIndexes.size
        var networkBytes = 0L
        var sampleBytes = 0L
        var sampleTime = android.os.SystemClock.elapsedRealtime()
        val resumed = cachedIndexes.isNotEmpty()

        fun reportProgress(force: Boolean = false) {
            val now = android.os.SystemClock.elapsedRealtime()
            val elapsed = (now - sampleTime).coerceAtLeast(1L)
            if (!force && elapsed < 500L) return
            val speed = ((networkBytes - sampleBytes) * 1000L / elapsed).coerceAtLeast(0L)
            sampleBytes = networkBytes
            sampleTime = now
            val progress = (completedParts * 98 / parts.size).coerceIn(0, 98)
            val message = "${if (resumed) "接續分段" else "下載分段"} $completedParts/${parts.size}"
            updateProgress(downloadId, progress, message, speed)
            notificationManager.notify(
                task.notificationId,
                buildProgressNotification(
                    "$message · ${formatSpeed(speed)}",
                    progress,
                    false,
                    downloadId
                )
            )
        }

        if (missingIndexes.isEmpty()) {
            completedParts = parts.size
            reportProgress(true)
            return
        }

        val partExecutor = Executors.newFixedThreadPool(parallelDownloads.coerceAtLeast(1))
        val completion = ExecutorCompletionService<HlsPartDownloadResult>(partExecutor)
        try {
            missingIndexes.forEach { index ->
                completion.submit {
                    currentTask.set(task)
                    try {
                        checkCanceled()
                        val part = parts[index]
                        val downloaded = fetchBytesWithRetry(part.url, referer, cookies, cookieSourceUrl)
                        val bytes = decryptHlsPart(downloaded, part, referer, cookies, cookieSourceUrl)
                        val partFile = hlsPartFile(partsDirectory, index)
                        val tempFile = File(partsDirectory, ".${index}-${task.notificationId}.tmp")
                        tempFile.outputStream().buffered().use { it.write(bytes) }
                        if (partFile.exists()) {
                            tempFile.delete()
                        } else if (!tempFile.renameTo(partFile)) {
                            tempFile.delete()
                            if (!partFile.exists()) throw IOException("無法儲存 HLS 暫存分段")
                        }
                        partsDirectory.setLastModified(System.currentTimeMillis())
                        HlsPartDownloadResult(bytes.size.toLong())
                    } finally {
                        currentTask.remove()
                    }
                }
            }
            repeat(missingIndexes.size) {
                checkCanceled()
                val result = try {
                    completion.take().get()
                } catch (e: ExecutionException) {
                    throw (e.cause as? Exception ?: IOException("HLS 分段下載失敗", e))
                }
                completedParts++
                networkBytes += result.bytes
                reportProgress(completedParts == parts.size)
            }
        } finally {
            partExecutor.shutdownNow()
            if (task.canceled.get()) task.activeConnections.forEach { it.disconnect() }
        }
    }

    private fun downloadHlsPartsToCacheSequential(
        parts: List<HlsPart>,
        partsDirectory: File,
        referer: String,
        cookies: String,
        cookieSourceUrl: String,
        downloadId: String
    ) {
        val task = currentDownloadTask()
        var completedParts = parts.indices.count { hlsPartFile(partsDirectory, it).length() > 0L }
        var networkBytes = 0L
        var sampleBytes = 0L
        var sampleTime = android.os.SystemClock.elapsedRealtime()

        parts.forEachIndexed { index, part ->
            checkCanceled()
            val partFile = hlsPartFile(partsDirectory, index)
            if (partFile.length() > 0L) return@forEachIndexed

            val downloaded = fetchBytesWithRetry(part.url, referer, cookies, cookieSourceUrl)
            val bytes = decryptHlsPart(downloaded, part, referer, cookies, cookieSourceUrl)
            val tempFile = File(partsDirectory, ".${index}-${task.notificationId}-sequential.tmp")
            tempFile.outputStream().buffered().use { it.write(bytes) }
            if (partFile.exists()) {
                tempFile.delete()
            } else if (!tempFile.renameTo(partFile)) {
                tempFile.delete()
                if (!partFile.exists()) throw IOException("無法儲存 HLS 暫存分段")
            }
            partsDirectory.setLastModified(System.currentTimeMillis())
            completedParts++
            networkBytes += downloaded.size

            val now = android.os.SystemClock.elapsedRealtime()
            val elapsed = (now - sampleTime).coerceAtLeast(1L)
            val speed = if (elapsed >= 500L || completedParts == parts.size) {
                ((networkBytes - sampleBytes) * 1000L / elapsed).coerceAtLeast(0L).also {
                    sampleBytes = networkBytes
                    sampleTime = now
                }
            } else {
                0L
            }
            val progress = (completedParts * 98 / parts.size).coerceIn(0, 98)
            val message = "單線下載分段 $completedParts/${parts.size}"
            updateProgress(downloadId, progress, message, speed)
            notificationManager.notify(
                task.notificationId,
                buildProgressNotification(
                    "$message · ${formatSpeed(speed)}",
                    progress,
                    false,
                    downloadId
                )
            )
        }
    }

    private fun downloadHlsPartsSequential(
        parts: List<HlsPart>,
        output: OutputStream,
        referer: String,
        cookies: String,
        cookieSourceUrl: String,
        downloadId: String
    ) {
        val task = currentDownloadTask()
        var networkBytes = 0L
        var sampleBytes = 0L
        var sampleTime = android.os.SystemClock.elapsedRealtime()

        output.buffered().use { target ->
            parts.forEachIndexed { index, part ->
                checkCanceled()
                val downloaded = fetchBytesWithRetry(part.url, referer, cookies, cookieSourceUrl)
                val bytes = decryptHlsPart(downloaded, part, referer, cookies, cookieSourceUrl)
                target.write(bytes)
                networkBytes += downloaded.size

                val now = android.os.SystemClock.elapsedRealtime()
                val elapsed = (now - sampleTime).coerceAtLeast(1L)
                val speed = if (elapsed >= 500L || index == parts.lastIndex) {
                    ((networkBytes - sampleBytes) * 1000L / elapsed).coerceAtLeast(0L).also {
                        sampleBytes = networkBytes
                        sampleTime = now
                    }
                } else {
                    0L
                }
                val completed = index + 1
                val progress = (completed * 98 / parts.size).coerceIn(0, 98)
                val message = "下載分段 $completed/${parts.size}"
                updateProgress(downloadId, progress, message, speed)
                notificationManager.notify(
                    task.notificationId,
                    buildProgressNotification(
                        "$message · ${formatSpeed(speed)}",
                        progress,
                        false,
                        downloadId
                    )
                )
            }
        }
    }

    private fun packageHlsParts(partsDirectory: File, partCount: Int, baseName: String,
        extension: String, mimeType: String, downloadId: String): DownloadTarget {
        // Keep all cached parts until success. Unsupported sources/providers retain
        // the existing merged-file path, and cancellation never starts a fallback.
        if (extension == "ts" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            var direct: DownloadTarget? = null
            try {
                checkCanceled()
                direct = createDownloadTarget(baseName, "mp4", "video/mp4")
                updateTargetRecord(downloadId, direct, "video/mp4")
                reportPackagingProgress(downloadId, 0)
                direct.closeOutput()
                withPackagingWakeLock {
                    HlsPartsMediaDataSource(List(partCount) { hlsPartFile(partsDirectory, it) }).use { source ->
                        remuxToFile(source, direct.uri, downloadId)
                    }
                }
                checkCanceled()
                return direct
            } catch (error: DownloadCanceledException) {
                direct?.finish(false)
                throw error
            } catch (error: Exception) {
                direct?.finish(false)
                android.util.Log.w("VIDEO_DOWNLOAD_REMUX", "Direct part remux failed; using merged TS fallback", error)
            }
        }
        val target = createDownloadTarget(baseName, extension, mimeType)
        try {
            updateTargetRecord(downloadId, target, mimeType)
            mergeHlsParts(partsDirectory, partCount, target.output, downloadId)
            return target
        } catch (error: Exception) {
            target.finish(false)
            throw error
        }
    }

    private fun mergeHlsParts(
        partsDirectory: File,
        partCount: Int,
        output: OutputStream,
        downloadId: String
    ) {
        updateProgress(downloadId, 99, "正在合併影片", 0L)
        notificationManager.notify(
            currentDownloadTask().notificationId,
            buildProgressNotification("正在合併影片", 99, false, downloadId)
        )
        output.buffered(256 * 1024).use { target ->
            val buffer = ByteArray(256 * 1024)
            repeat(partCount) { index ->
                checkCanceled()
                val partFile = hlsPartFile(partsDirectory, index)
                if (!partFile.exists()) throw IOException("HLS 暫存分段遺失：${index + 1}")
                partFile.inputStream().use { input ->
                    while (true) {
                        checkCanceled()
                        val count = input.read(buffer)
                        if (count < 0) break
                        target.write(buffer, 0, count)
                    }
                }
            }
        }
    }

    private fun fetchDownloadSubtitles(sources: String): List<EmbeddedSubtitles.Subtitle> {
        val tracks = JSONArray(sources)
        require(tracks.length() <= 20) { "Too many subtitle tracks" }
        return (0 until tracks.length()).map { index ->
            checkCanceled()
            val track = tracks.getJSONObject(index)
            val url = track.getString("src")
            require(JavrateSubtitles.isAllowed(url)) { "Invalid subtitle address" }
            val bytes = fetchSubtitleBytes(url)
            val text = bytes.toString(Charsets.UTF_8)
            EmbeddedSubtitles.parseVtt(text)
            EmbeddedSubtitles.Subtitle(track.optString("label"), track.optString("lang"), text)
        }
    }

    private fun fetchSubtitleBytes(url: String): ByteArray {
        var lastError: Exception? = null
        repeat(3) { attempt ->
            checkCanceled()
            try {
                val connection = openConnection(url, "https://www.javrate.com/", "", "")
                return try {
                    connection.inputStream.use { input ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            checkCanceled()
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (output.size() + count > 5_000_000) throw IOException("Subtitle too large")
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    }
                } finally { closeConnection(connection) }
            } catch (error: DownloadCanceledException) {
                throw error
            } catch (error: IOException) {
                lastError = error
                if (attempt < 2) Thread.sleep(500L shl attempt)
            }
        }
        throw lastError ?: IOException("Subtitle download failed")
    }

    private fun subtitleErrorMessage(error: Exception): String =
        "${error.javaClass.simpleName}: ${error.message.orEmpty().take(160)}"

    /** Only hold the CPU awake while packaging; release on completion, failure or cancellation. */
    private fun <T> withPackagingWakeLock(work: () -> T): T {
        val power = getSystemService(PowerManager::class.java)
        val wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:mp4-packaging")
        wakeLock.setReferenceCounted(false)
        wakeLock.acquire(TimeUnit.HOURS.toMillis(1))
        try { return work() }
        finally { if (wakeLock.isHeld) wakeLock.release() }
    }

    private fun tryEmbedSubtitles(source: Uri, baseName: String, downloadId: String,
        subtitles: List<EmbeddedSubtitles.Subtitle>): DownloadTarget? {
        var output: DownloadTarget? = null
        return try {
            checkCanceled()
            updateProgress(downloadId, 99, "正在合併字幕", 0L)
            notificationManager.notify(currentDownloadTask().notificationId,
                buildProgressNotification("正在合併字幕", 99, false, downloadId))
            val descriptor = openDownloadFileDescriptor(source, write = false)
                ?: throw IOException("無法讀取影片以合併字幕")
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                val destination = createDownloadTarget(baseName, "mp4", "video/mp4")
                output = destination
                Channels.newChannel(destination.output).use { channel ->
                    withPackagingWakeLock {
                        EmbeddedSubtitles.mux(input.channel, channel, subtitles, ::checkCanceled)
                    }
                }
            }
            checkCanceled()
            val completed = requireNotNull(output)
            completed.finish(true)
            updateTargetRecord(downloadId, completed, "video/mp4")
            deleteDownloadUri(source)
            completed
        } catch (error: DownloadCanceledException) {
            output?.finish(false)
            throw error
        } catch (error: Exception) {
            output?.finish(false)
            currentDownloadTask().subtitleError = subtitleErrorMessage(error)
            android.util.Log.w("DOWNLOAD_SUBTITLES", "Subtitle mux failed; original video kept", error)
            null
        }
    }

    private fun tryRemuxTsToMp4(
        sourceUri: Uri,
        baseName: String,
        downloadId: String
    ): DownloadTarget? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        val task = currentDownloadTask()
        var mp4Target: DownloadTarget? = null
        return try {
            updateProgress(downloadId, 99, "正在封裝 MP4", 0L)
            notificationManager.notify(
                task.notificationId,
                buildProgressNotification("正在封裝 MP4", 99, false, downloadId)
            )
            mp4Target = createDownloadTarget(baseName, "mp4", "video/mp4")
            mp4Target.closeOutput()
            withPackagingWakeLock { remuxTsToMp4(sourceUri, mp4Target.uri, downloadId) }
            mp4Target.finish(true)
            updateTargetRecord(downloadId, mp4Target, "video/mp4")
            deleteDownloadUri(sourceUri)
            mp4Target
        } catch (error: DownloadCanceledException) {
            mp4Target?.finish(false)
            throw error
        } catch (error: Exception) {
            mp4Target?.finish(false)
            android.util.Log.w(
                "VIDEO_DOWNLOAD_REMUX",
                "TS to MP4 remux failed; keeping TS",
                error
            )
            updateProgress(downloadId, 99, "MP4 封裝失敗，保留 TS", 0L)
            notificationManager.notify(
                task.notificationId,
                buildProgressNotification("MP4 封裝失敗，保留 TS", 99, false, downloadId)
            )
            null
        }
    }

    private fun remuxTsToMp4(sourceUri: Uri, outputUri: Uri, downloadId: String) {
        val inputDescriptor = openDownloadFileDescriptor(sourceUri, write = false)
            ?: throw IOException("無法開啟 TS 來源檔")
        ParcelFileDescriptor.AutoCloseInputStream(inputDescriptor).use { stream ->
            BufferedFileMediaDataSource(stream.channel).use { source ->
                remuxToFile(source, outputUri, downloadId)
            }
        }
    }

    private fun reportPackagingProgress(downloadId: String, percent: Int) {
        val message = if (percent > 0) "正在封裝 MP4 $percent%" else "正在封裝 MP4"
        updateProgress(downloadId, 99, message, 0L)
        notificationManager.notify(currentDownloadTask().notificationId,
            buildProgressNotification(message, 99, false, downloadId))
    }

    private fun remuxToFile(source: MediaDataSource, outputUri: Uri, downloadId: String) {
        val outputDescriptor = openDownloadFileDescriptor(outputUri, write = true)
            ?: throw IOException("無法建立 MP4 輸出檔")
        val started = android.os.SystemClock.elapsedRealtime()
        outputDescriptor.use { output ->
            // MediaMuxer emits many small writes. On shared/document storage these
            // can be much slower than private-cache writes plus one buffered copy.
            // Reserve space for both the scratch MP4 and its final copy; low-space
            // devices keep the direct-output path without an extra scratch file.
            val scratch = if (outputUri.scheme == "content" && source.size > 0 &&
                cacheDir.usableSpace > source.size * 2 + 64L * 1024 * 1024) {
                runCatching { File.createTempFile("download-remux-", ".mp4", cacheDir) }.getOrNull()
            } else null
            fun remux(descriptor: java.io.FileDescriptor) {
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(source)
                    TsMp4Remuxer.remux(extractor, descriptor, source.size, ::checkCanceled) { percent ->
                        reportPackagingProgress(downloadId, percent)
                    }
                } finally { extractor.release() }
            }
            try {
                if (scratch == null) remux(output.fileDescriptor)
                else {
                    scratch.outputStream().use { remux(it.fd) }
                    checkCanceled()
                    val message = "正在儲存 MP4"
                    updateProgress(downloadId, 99, message, 0L)
                    notificationManager.notify(currentDownloadTask().notificationId,
                        buildProgressNotification(message, 99, false, downloadId))
                    scratch.inputStream().use { input ->
                        ParcelFileDescriptor.AutoCloseOutputStream(ParcelFileDescriptor.dup(output.fileDescriptor)).use { destination ->
                            val buffer = ByteArray(256 * 1024)
                            while (true) {
                                checkCanceled()
                                val count = input.read(buffer)
                                if (count < 0) break
                                destination.write(buffer, 0, count)
                            }
                        }
                    }
                }
            } finally { scratch?.delete() }
        }
        android.util.Log.i("VIDEO_DOWNLOAD_REMUX",
            "MP4 packaging completed bytes=${source.size} elapsedMs=${android.os.SystemClock.elapsedRealtime() - started}")
    }

    private fun openDownloadFileDescriptor(uri: Uri, write: Boolean): ParcelFileDescriptor? {
        if (uri.scheme == "file") {
            val file = uri.path?.let(::File) ?: return null
            val mode = if (write) {
                ParcelFileDescriptor.MODE_READ_WRITE or
                    ParcelFileDescriptor.MODE_CREATE or
                    ParcelFileDescriptor.MODE_TRUNCATE
            } else {
                ParcelFileDescriptor.MODE_READ_ONLY
            }
            return ParcelFileDescriptor.open(file, mode)
        }
        return if (write) {
            contentResolver.openFileDescriptor(uri, "rwt")
                ?: contentResolver.openFileDescriptor(uri, "rw")
        } else {
            contentResolver.openFileDescriptor(uri, "r")
        }
    }

    private fun deleteDownloadUri(uri: Uri) {
        val deleted = runCatching {
            when (uri.scheme) {
                "file" -> uri.path?.let(::File)?.delete() == true
                "content" -> {
                    val resolverDeleted = runCatching {
                        contentResolver.delete(uri, null, null) > 0
                    }.getOrDefault(false)
                    if (resolverDeleted) {
                        true
                    } else {
                        val document = DocumentFile.fromSingleUri(this, uri)
                        document?.let { !it.exists() || it.delete() } == true
                    }
                }
                else -> false
            }
        }.getOrDefault(false)
        if (!deleted) {
            android.util.Log.w(
                "VIDEO_DOWNLOAD_REMUX",
                "Unable to remove remuxed TS source; keeping MP4 available"
            )
        }
    }

    private fun decryptHlsPart(
        bytes: ByteArray,
        part: HlsPart,
        referer: String,
        cookies: String,
        cookieSourceUrl: String
    ): ByteArray {
        val keyInfo = part.key ?: return bytes
        val keyBytes = currentDownloadTask().hlsKeyCache.getOrPut(keyInfo.url) {
            normalizeAes128Key(fetchBytesWithRetry(keyInfo.url, referer, cookies, cookieSourceUrl))
        }
        val iv = keyInfo.ivHex?.let(::parseIv) ?: sequenceIv(part.sequence)
        val key = SecretKeySpec(keyBytes, "AES")
        val ivSpec = IvParameterSpec(iv)
        return runCatching {
            Cipher.getInstance("AES/CBC/PKCS5Padding").run {
                init(Cipher.DECRYPT_MODE, key, ivSpec)
                doFinal(bytes)
            }
        }.recoverCatching {
            // 部分 HLS CDN 的 AES-128 分段沒有 PKCS#7 padding，但長度仍為 16 的倍數。
            if (bytes.size % 16 != 0) throw it
            Cipher.getInstance("AES/CBC/NoPadding").run {
                init(Cipher.DECRYPT_MODE, key, ivSpec)
                doFinal(bytes)
            }
        }.getOrElse { throw IOException("AES-128 分段解密失敗", it) }
    }

    private fun normalizeAes128Key(response: ByteArray): ByteArray {
        if (response.size == 16) return response

        val text = response.toString(Charsets.US_ASCII).trim()
        if (text.length == 16) return text.toByteArray(Charsets.US_ASCII)
        if (text.length == 32 && text.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            return ByteArray(16) { index ->
                text.substring(index * 2, index * 2 + 2).toInt(16).toByte()
            }
        }

        val decoded = runCatching { Base64.decode(text, Base64.DEFAULT) }.getOrNull()
        if (decoded?.size == 16) return decoded

        throw IOException("AES-128 金鑰格式錯誤：回應 ${response.size} bytes")
    }

    private fun parseIv(raw: String): ByteArray {
        val hex = raw.removePrefix("0x").removePrefix("0X").padStart(32, '0')
        if (hex.length != 32 || !hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            throw IOException("AES-128 IV 格式錯誤")
        }
        return ByteArray(16) { index ->
            hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private fun sequenceIv(sequence: Long): ByteArray {
        val iv = ByteArray(16)
        var value = sequence
        for (index in 15 downTo 8) {
            iv[index] = (value and 0xff).toByte()
            value = value ushr 8
        }
        return iv
    }

    private fun downloadDirect(
        url: String,
        output: OutputStream,
        referer: String,
        cookies: String,
        cookieSourceUrl: String,
        downloadId: String
    ) {
        val connection = openConnection(url, referer, cookies, cookieSourceUrl)
        try {
            val total = connection.contentLengthLong
            var downloaded = 0L
            var sampleBytes = 0L
            var sampleTime = android.os.SystemClock.elapsedRealtime()
            connection.inputStream.use { input ->
                output.buffered().use { bufferedOutput ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        checkCanceled()
                        val count = input.read(buffer)
                        if (count < 0) break
                        bufferedOutput.write(buffer, 0, count)
                        downloaded += count
                        val progress = if (total > 0) ((downloaded * 100) / total).toInt() else 0
                        val now = android.os.SystemClock.elapsedRealtime()
                        val elapsed = now - sampleTime
                        if (elapsed >= 500L) {
                            val speed = ((downloaded - sampleBytes) * 1000L / elapsed).coerceAtLeast(0L)
                            sampleBytes = downloaded
                            sampleTime = now
                            updateProgress(downloadId, progress, "下載影片中", speed)
                            notificationManager.notify(
                                currentDownloadTask().notificationId,
                                buildProgressNotification(
                                    "下載影片中 · ${formatSpeed(speed)}",
                                    progress,
                                    total <= 0,
                                    downloadId
                                )
                            )
                        }
                    }
                }
            }
        } finally {
            closeConnection(connection)
        }
    }

    private fun fetchText(
        url: String,
        referer: String,
        cookies: String,
        cookieSourceUrl: String
    ): String = fetchBytesWithRetry(url, referer, cookies, cookieSourceUrl).toString(Charsets.UTF_8)

    private fun fetchBytesWithRetry(
        url: String,
        referer: String,
        cookies: String,
        cookieSourceUrl: String
    ): ByteArray {
        var lastError: Exception? = null
        repeat(4) { attempt ->
            checkCanceled()
            try {
                if (isSurritUrl(url)) {
                    return fetchSurritBytes(url, referer, cookies, cookieSourceUrl)
                }
                val connection = openConnection(url, referer, cookies, cookieSourceUrl)
                return try {
                    connection.inputStream.use { it.readBytes() }
                } finally {
                    closeConnection(connection)
                }
            } catch (e: DownloadCanceledException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                if (attempt < 3) {
                    val task = currentDownloadTask()
                    val message = "連線中斷，正在重試 ${attempt + 1}/3"
                    DownloadRepository.update(this, task.downloadId) {
                        it.copy(message = message, speedBytesPerSecond = 0L)
                    }
                    notificationManager.notify(
                        task.notificationId,
                        buildProgressNotification(
                            message,
                            task.progress.get(),
                            task.progress.get() <= 0,
                            task.downloadId
                        )
                    )
                    Thread.sleep(1000L shl attempt)
                }
            }
        }
        throw lastError ?: IOException("下載請求失敗")
    }

    private fun fetchSurritBytes(
        url: String,
        referer: String,
        cookies: String,
        cookieSourceUrl: String
    ): ByteArray {
        val requestCookies = cookiesForRequest(url, cookies, cookieSourceUrl)
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "*/*")
            .header("Accept-Encoding", "identity")
            .apply {
                if (referer.isNotBlank()) {
                    header("Referer", referer)
                    originFor(referer)?.let { header("Origin", it) }
                }
                if (requestCookies.isNotEmpty()) {
                    header("Cookie", requestCookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
                }
            }
            .build()
        val call = surritHttpClient.newCall(request)
        val task = currentDownloadTask()
        task.activeCalls.add(call)
        try {
            call.execute().use { response ->
                rememberResponseCookies(url, response.headers.values("Set-Cookie"))
                android.util.Log.i(
                    "VIDEO_DOWNLOAD_HTTP",
                    "OKHTTP ${response.code} ${redactUrlForLog(url)} protocol=${response.protocol} " +
                        "sendCookies=${requestCookies.isNotEmpty()} cookieNames=${requestCookies.keys.joinToString(",").ifEmpty { "-" }} " +
                        "cfRay=${response.header("CF-Ray").orEmpty()}"
                )
                val body = response.body ?: throw IOException("HTTP ${response.code} 空白回應")
                if (!response.isSuccessful) {
                    val snippet = body.string().replace(Regex("\\s+"), " ").trim().take(100)
                    throw IOException("HTTP ${response.code} $snippet")
                }
                return body.bytes()
            }
        } finally {
            task.activeCalls.remove(call)
        }
    }

    private fun openConnection(
        url: String,
        referer: String,
        cookies: String,
        cookieSourceUrl: String
    ): HttpURLConnection {
        val requestOrigin = originFor(referer)
        val requestLabel = redactUrlForLog(url)
        val requestCookies = cookiesForRequest(url, cookies, cookieSourceUrl)
        val cookieNames = requestCookies.keys.joinToString(",")
        android.util.Log.i(
            "VIDEO_DOWNLOAD_HTTP",
            "GET $requestLabel referer=${redactUrlForLog(referer)} origin=$requestOrigin " +
                "sendCookies=${requestCookies.isNotEmpty()} cookieNames=${cookieNames.ifEmpty { "-" }}"
        )
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "*/*")
            setRequestProperty("Accept-Encoding", "identity")
            if (referer.isNotBlank()) {
                setRequestProperty("Referer", referer)
                requestOrigin?.let { setRequestProperty("Origin", it) }
            }
            if (requestCookies.isNotEmpty()) {
                setRequestProperty("Cookie", requestCookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
            }
        }
        currentDownloadTask().activeConnections.add(connection)
        val responseCode = connection.responseCode
        rememberResponseCookies(url, connection.headerFields)
        android.util.Log.i(
            "VIDEO_DOWNLOAD_HTTP",
            "HTTP $responseCode $requestLabel cfRay=${connection.getHeaderField("CF-Ray").orEmpty()} " +
                "setCookieNames=${setCookieNames(connection.headerFields)}"
        )
        if (responseCode !in 200..299) {
            val snippet = runCatching {
                connection.errorStream?.bufferedReader()?.use { it.readText().take(100) }
            }.getOrNull().orEmpty()
            closeConnection(connection)
            throw IOException("HTTP $responseCode ${snippet.replace(Regex("\\s+"), " ").trim()}")
        }
        return connection
    }

    private fun cookiesForRequest(
        requestUrl: String,
        pageCookies: String,
        cookieSourceUrl: String
    ): Map<String, String> {
        val result = linkedMapOf<String, String>()
        if (pageCookies.isNotBlank() && isSameCookieDomain(requestUrl, cookieSourceUrl)) {
            pageCookies.split(';').forEach { item ->
                val name = item.substringBefore('=').trim()
                val value = item.substringAfter('=', "").trim()
                if (name.isNotEmpty()) result[name] = value
            }
        }
        val host = runCatching { URL(requestUrl).host.lowercase(Locale.US) }.getOrNull()
        host?.let { currentDownloadTask().responseCookies[it] }?.let(result::putAll)
        return result
    }

    private fun rememberResponseCookies(url: String, headers: Map<String?, List<String>>) {
        rememberResponseCookies(
            url,
            headers.entries
                .filter { (name, _) -> name.equals("Set-Cookie", ignoreCase = true) }
                .flatMap { it.value }
        )
    }

    private fun rememberResponseCookies(url: String, setCookieHeaders: List<String>) {
        val host = runCatching { URL(url).host.lowercase(Locale.US) }.getOrNull() ?: return
        val jar = currentDownloadTask().responseCookies.getOrPut(host) { ConcurrentHashMap() }
        setCookieHeaders.forEach { header ->
                val pair = header.substringBefore(';')
                val name = pair.substringBefore('=').trim()
                val value = pair.substringAfter('=', "").trim()
                if (name.isNotEmpty()) jar[name] = value
        }
    }

    private fun redactUrlForLog(url: String): String = runCatching {
        val parsed = URL(url)
        val port = if (parsed.port == -1 || parsed.port == parsed.defaultPort) "" else ":${parsed.port}"
        "${parsed.protocol}://${parsed.host}$port${parsed.path}"
    }.getOrDefault(url.substringBefore('?').take(180))

    private fun setCookieNames(headers: Map<String?, List<String>>): String = headers.entries
        .filter { (name, _) -> name.equals("Set-Cookie", ignoreCase = true) }
        .flatMap { it.value }
        .mapNotNull { value -> value.substringBefore('=').trim().takeIf { it.isNotEmpty() } }
        .joinToString(",")

    private fun closeConnection(connection: HttpURLConnection) {
        currentTask.get()?.activeConnections?.remove(connection)
        connection.disconnect()
    }

    private fun prepareHlsCache(sourceUrl: String, parts: List<HlsPart>): File {
        val root = File(cacheDir, "hls_download_parts").apply { mkdirs() }
        val directory = File(root, sha256(sourceUrl).take(24))
        val fingerprint = sha256(parts.joinToString("\n") { part ->
            "${part.url}|${part.isInit}|${part.key?.url}|${part.key?.ivHex}|${part.sequence}"
        })
        val marker = File(directory, "playlist.sha256")
        val storedFingerprint = runCatching {
            marker.takeIf { it.exists() }?.readText(Charsets.UTF_8)
        }.getOrNull()
        if (storedFingerprint != fingerprint) {
            directory.deleteRecursively()
            if (!directory.mkdirs()) throw IOException("無法建立 HLS 接續暫存區")
            marker.writeText(fingerprint, Charsets.UTF_8)
        } else if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("無法開啟 HLS 接續暫存區")
        }
        directory.setLastModified(System.currentTimeMillis())
        return directory
    }

    private fun hlsPartFile(directory: File, index: Int): File =
        File(directory, String.format(Locale.US, "%06d.part", index))

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun cleanupOldHlsCaches() {
        val expiry = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
        cacheDir.listFiles { file -> file.name.startsWith("download-remux-") && file.extension == "mp4" }
            ?.filter { it.lastModified() < expiry }?.forEach { it.delete() }
        File(cacheDir, "hls_download_parts").listFiles()?.forEach { directory ->
            if (directory.lastModified() < expiry) directory.deleteRecursively()
        }
    }

    private fun createDownloadTarget(baseName: String, extension: String, mimeType: String): DownloadTarget {
        val time = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val fileName = "$baseName-$time.$extension"
        val customTreeUri = DownloadRepository.storageTreeUri(this)
        if (customTreeUri != null) {
            val directory = DocumentFile.fromTreeUri(this, customTreeUri)
                ?.takeIf { it.exists() && it.canWrite() }
                ?: throw IOException("自訂儲存資料夾已失效，請重新選擇")
            val document = directory.createFile(mimeType, fileName)
                ?: throw IOException("無法在自訂資料夾建立檔案")
            val output = contentResolver.openOutputStream(document.uri, "w")
                ?: run {
                    document.delete()
                    throw IOException("無法開啟自訂下載檔案")
                }
            return DownloadTarget(document.uri, output, document.name ?: fileName) { success ->
                if (!success) document.delete()
            }
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/JAV Browser")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("無法建立下載檔案")
            val output = contentResolver.openOutputStream(uri, "w")
                ?: run {
                    contentResolver.delete(uri, null, null)
                    throw IOException("無法開啟下載檔案")
                }
            DownloadTarget(uri, output, fileName) { success ->
                if (success) {
                    val completed = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                    contentResolver.update(uri, completed, null, null)
                } else {
                    contentResolver.delete(uri, null, null)
                }
            }
        } else {
            val directory = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "JAV Browser")
            if (!directory.exists() && !directory.mkdirs()) throw IOException("無法建立下載資料夾")
            var file = File(directory, fileName)
            var suffix = 1
            while (!file.createNewFile()) {
                file = File(directory, "$baseName-$time-${suffix++}.$extension")
            }
            val destination = file
            DownloadTarget(Uri.fromFile(destination), destination.outputStream(), destination.name) { success ->
                if (!success) destination.delete()
            }
        }
    }

    private fun buildProgressNotification(
        text: String,
        progress: Int,
        indeterminate: Boolean,
        downloadId: String?
    ): android.app.Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(notificationIcon())
            .setContentTitle(LanguageManager.text(this, "${PrivacySettings(this).currentAppLabel} 影片下載", "${PrivacySettings(this).currentAppLabel} Video Download"))
            .setContentText(LanguageManager.downloadMessage(this, text))
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, progress, indeterminate)
        notificationLargeIcon()?.let(builder::setLargeIcon)
        if (downloadId != null) {
            builder.addAction(0, LanguageManager.text(this, "取消", "Cancel"), cancelPendingIntent(downloadId))
        }
        return builder.build()
    }

    private fun buildResultNotification(
        text: String,
        uri: Uri?
    ): android.app.Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(notificationIcon())
            .setContentTitle(LanguageManager.text(this, "${PrivacySettings(this).currentAppLabel} 影片下載", "${PrivacySettings(this).currentAppLabel} Video Download"))
            .setContentText(LanguageManager.downloadMessage(this, text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(LanguageManager.downloadMessage(this, text)))
            .setAutoCancel(true)
        notificationLargeIcon()?.let(builder::setLargeIcon)
        if (uri?.scheme == "content") {
            val openIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "video/*")
                clipData = ClipData.newRawUri("video", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(openIntent, LanguageManager.text(this, "選擇播放器", "Choose player"))
            builder.setContentIntent(
                PendingIntent.getActivity(
                    this,
                    currentDownloadTask().notificationId,
                    chooser,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
        }
        return builder.build()
    }

    private fun cancelPendingIntent(downloadId: String): PendingIntent {
        val task = activeTasks[downloadId]
        val requestCode = task?.notificationId ?: downloadId.hashCode()
        val intent = Intent(this, VideoDownloadService::class.java).apply {
            action = ACTION_CANCEL
            putExtra(EXTRA_DOWNLOAD_ID, downloadId)
        }
        return PendingIntent.getService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, LanguageManager.text(this, "影片下載", "Video Downloads"), NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun notificationIcon(): Int = PrivacySettings(this).currentIconResourceId

    private fun notificationLargeIcon(): Bitmap? = runCatching {
        AppCompatResources.getDrawable(this, PrivacySettings(this).currentIconResourceId)
            ?.toBitmap(128, 128, Bitmap.Config.ARGB_8888)
    }.getOrNull()

    private fun finishTask(downloadId: String) {
        synchronized(foregroundLock) {
            val finishedTask = activeTasks.remove(downloadId)
            if (activeTasks.isEmpty()) {
                if (foregroundStarted) stopForeground(STOP_FOREGROUND_DETACH)
                foregroundStarted = false
                foregroundNotificationId = null
                stopSelf()
            } else if (finishedTask?.notificationId == foregroundNotificationId) {
                val nextTask = activeTasks.values.first()
                val record = DownloadRepository.list(this).firstOrNull { it.id == nextTask.downloadId }
                val progress = record?.progress ?: nextTask.progress.get()
                val speed = record?.speedBytesPerSecond ?: 0L
                val text = buildString {
                    append(record?.message ?: "下載中")
                    if (speed > 0L) append(" · ${formatSpeed(speed)}")
                }
                startForeground(
                    nextTask.notificationId,
                    buildProgressNotification(
                        text,
                        progress,
                        progress <= 0,
                        nextTask.downloadId
                    )
                )
                foregroundNotificationId = nextTask.notificationId
            }
        }
    }

    private fun currentDownloadTask(): DownloadTask =
        currentTask.get() ?: throw IllegalStateException("下載工作狀態遺失")

    private fun updateTargetRecord(downloadId: String, target: DownloadTarget, mimeType: String) {
        DownloadRepository.update(this, downloadId) {
            it.copy(
                fileName = target.fileName,
                fileUri = target.uri.toString(),
                javCode = LocalVideoRepository.extractJavCode(target.fileName) ?: it.javCode,
                mimeType = mimeType,
                storageLocation = DownloadRepository.storageDisplayName(this),
                message = "開始下載",
                speedBytesPerSecond = 0L
            )
        }
    }

    private fun updateProgress(
        downloadId: String,
        progress: Int,
        message: String,
        speedBytesPerSecond: Long = 0L
    ) {
        currentTask.get()?.progress?.set(progress.coerceIn(0, 100))
        DownloadRepository.update(this, downloadId) {
            it.copy(
                status = DownloadRepository.STATUS_DOWNLOADING,
                progress = progress.coerceIn(0, 100),
                speedBytesPerSecond = speedBytesPerSecond.coerceAtLeast(0L),
                message = message
            )
        }
    }

    private fun looksLikeHls(url: String): Boolean {
        return MediaStreamFormats.isHls(url)
    }

    private fun resolveUrl(baseUrl: String, child: String): String =
        if (JavrateStreams.isSigned(baseUrl)) JavrateStreams.resolve(baseUrl, child)
        else URI(baseUrl).resolve(child).toString()

    private fun originFor(url: String): String? = runCatching {
        val parsed = URL(url)
        val port = if (parsed.port == -1 || parsed.port == parsed.defaultPort) "" else ":${parsed.port}"
        "${parsed.protocol}://${parsed.host}$port"
    }.getOrNull()

    private fun isMissAvDownload(url: String, referer: String): Boolean {
        val refererHost = runCatching { URL(referer).host.lowercase(Locale.US) }.getOrDefault("")
        val requestHost = runCatching { URL(url).host.lowercase(Locale.US) }.getOrDefault("")
        return refererHost.contains("missav") ||
            requestHost == "surrit.com" ||
            requestHost.endsWith(".surrit.com")
    }

    private fun isSurritUrl(url: String): Boolean = runCatching {
        val host = URL(url).host.lowercase(Locale.US)
        host == "surrit.com" || host.endsWith(".surrit.com")
    }.getOrDefault(false)

    private fun isSameCookieDomain(requestUrl: String, cookieSourceUrl: String): Boolean = runCatching {
        val requestHost = URL(requestUrl).host.lowercase(Locale.US)
        val cookieHost = URL(cookieSourceUrl).host.lowercase(Locale.US)
        requestHost == cookieHost || requestHost.endsWith(".$cookieHost") || cookieHost.endsWith(".$requestHost")
    }.getOrDefault(false)

    private fun sanitizeFileName(raw: String): String {
        val cleaned = raw.replace(Regex("[\\\\/:*?\"<>|\\r\\n]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(60)
        return cleaned.ifBlank { "video" }
    }

    private fun formatSpeed(bytesPerSecond: Long): String = when {
        bytesPerSecond >= 1024L * 1024L -> String.format(
            Locale.US,
            "%.2f MB/s",
            bytesPerSecond / (1024.0 * 1024.0)
        )
        bytesPerSecond >= 1024L -> String.format(Locale.US, "%.1f KB/s", bytesPerSecond / 1024.0)
        else -> "$bytesPerSecond B/s"
    }

    private fun checkCanceled() {
        if (currentDownloadTask().canceled.get() || Thread.currentThread().isInterrupted) {
            throw DownloadCanceledException()
        }
    }

    private data class PlaylistResult(val url: String, val text: String)
    private data class HlsVariant(val url: String, val bandwidth: Long)
    private data class HlsKey(val url: String, val ivHex: String?)
    private data class HlsPart(
        val url: String,
        val isInit: Boolean,
        val key: HlsKey?,
        val sequence: Long
    )
    private data class DownloadResult(val uri: Uri, val remuxTsToMp4: Boolean)
    private data class HlsPartDownloadResult(val bytes: Long)
    private data class DownloadTask(
        val downloadId: String,
        val notificationId: Int,
        var subtitleSources: String = "[]",
        var subtitleError: String? = null,
        val javrateLease: java.util.concurrent.atomic.AtomicReference<JavratePlaybackLease?> = java.util.concurrent.atomic.AtomicReference(null),
        val canceled: AtomicBoolean = AtomicBoolean(false),
        val progress: AtomicInteger = AtomicInteger(0),
        val hlsKeyCache: MutableMap<String, ByteArray> = ConcurrentHashMap(),
        val responseCookies: MutableMap<String, MutableMap<String, String>> = ConcurrentHashMap(),
        val activeCalls: MutableSet<Call> = ConcurrentHashMap.newKeySet(),
        val activeConnections: MutableSet<HttpURLConnection> = ConcurrentHashMap.newKeySet()
    )

    private class DownloadCanceledException : IOException("下載已取消")

    private class DownloadTarget(
        val uri: Uri,
        val output: OutputStream,
        val fileName: String,
        private val completion: (Boolean) -> Unit
    ) {
        private val finished = AtomicBoolean(false)

        fun closeOutput() {
            runCatching { output.close() }
        }

        fun finish(success: Boolean) {
            if (!finished.compareAndSet(false, true)) return
            closeOutput()
            completion(success)
        }
    }
}
