package com.example.javbrowser

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.documentfile.provider.DocumentFile
import fi.iki.elonen.NanoHTTPD
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FilterInputStream

/** Opt-in end-to-end download from a local synthetic fixture, including the chosen storage provider. */
@RunWith(AndroidJUnit4::class)
class PackagingServiceTest {
    @Test fun downloadSyntheticHlsToConfiguredStorage() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = File(context.filesDir, "packaging-benchmark.ts")
        assumeTrue("Provide the synthetic fixture first", source.isFile)
        DownloadRepository.list(context).filter {
            it.title == "Packaging synthetic test" && it.sourceUrl.startsWith("http://127.0.0.1:") &&
                it.status == DownloadRepository.STATUS_COMPLETED
        }.forEach { old ->
            old.fileUri?.let { DocumentFile.fromSingleUri(context, Uri.parse(it))?.delete() }
            DownloadRepository.remove(context, old.id)
        }
        // Creating a record at the retention limit would evict a user's record.
        assumeTrue("Leave room for a temporary test record", DownloadRepository.list(context).size < 99)
        val partSize = 1_000_003L
        val partCount = ((source.length() + partSize - 1) / partSize).toInt()
        val server = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response {
                if (session.uri == "/test.m3u8") {
                    val playlist = "#EXTM3U\n#EXT-X-TARGETDURATION:60\n#EXT-X-MEDIA-SEQUENCE:0\n" +
                        (0 until partCount).joinToString("") { "#EXTINF:60,\n$it.ts\n" } + "#EXT-X-ENDLIST\n"
                    return newFixedLengthResponse(Response.Status.OK, "application/vnd.apple.mpegurl", playlist)
                }
                val index = session.uri.removePrefix("/").removeSuffix(".ts").toIntOrNull()
                    ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Unknown fixture")
                if (index !in 0 until partCount) return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Unknown part")
                val offset = index * partSize
                val length = minOf(partSize, source.length() - offset)
                val input = source.inputStream().apply { channel.position(offset) }
                val bounded = object : FilterInputStream(input) {
                    var remaining = length
                    override fun read(): Int {
                        if (remaining <= 0) return -1
                        return super.read().also { if (it >= 0) remaining-- }
                    }
                    override fun read(bytes: ByteArray, start: Int, size: Int): Int {
                        if (remaining <= 0) return -1
                        return `in`.read(bytes, start, minOf(size.toLong(), remaining).toInt()).also { if (it > 0) remaining -= it }
                    }
                }
                return newFixedLengthResponse(Response.Status.OK, "video/mp2t", bounded, length)
            }
        }
        server.start()
        val url = "http://127.0.0.1:${server.listeningPort}/test.m3u8"
        val record = DownloadRepository.create(context, "Packaging synthetic test", url)
        var completed = false
        try {
            val start = android.os.SystemClock.elapsedRealtime()
            context.startForegroundService(Intent(context, VideoDownloadService::class.java)
                .setAction(VideoDownloadService.ACTION_START)
                .putExtra(VideoDownloadService.EXTRA_URL, url)
                .putExtra(VideoDownloadService.EXTRA_DOWNLOAD_ID, record.id)
                .putExtra(VideoDownloadService.EXTRA_FILE_NAME, record.title))
            var packagingStarted = 0L
            var sawTs = false
            while (android.os.SystemClock.elapsedRealtime() - start < 180_000) {
                val state = DownloadRepository.list(context).first { it.id == record.id }
                if (state.progress >= 99 && packagingStarted == 0L) packagingStarted = android.os.SystemClock.elapsedRealtime()
                if (state.mimeType == "video/mp2t") sawTs = true
                if (state.status == DownloadRepository.STATUS_FAILED || state.status == DownloadRepository.STATUS_CANCELED)
                    fail("Synthetic download failed: ${state.message}")
                if (state.status == DownloadRepository.STATUS_COMPLETED) {
                    completed = true
                    val now = android.os.SystemClock.elapsedRealtime()
                    File(context.cacheDir, "packaging-service-benchmark.txt").writeText(
                        "bytes=${source.length()} totalMs=${now - start} packagingMs=${now - packagingStarted} intermediateTs=$sawTs\n")
                    assertEquals("video/mp4", state.mimeType)
                    assertEquals(100, state.progress)
                    assertTrue(state.fileSizeBytes > 0)
                    val uri = Uri.parse(requireNotNull(state.fileUri))
                    context.contentResolver.openInputStream(uri)!!.use { input ->
                        File(context.cacheDir, "packaging-service-output.mp4").outputStream().use { input.copyTo(it) }
                    }
                    break
                }
                Thread.sleep(50)
            }
            assertTrue("Synthetic download timed out", completed)
        } finally {
            if (!completed) context.startService(Intent(context, VideoDownloadService::class.java)
                .setAction(VideoDownloadService.ACTION_CANCEL).putExtra(VideoDownloadService.EXTRA_DOWNLOAD_ID, record.id))
            server.stop()
            val final = DownloadRepository.list(context).firstOrNull { it.id == record.id }
            // Delete only this test's completed output. Failed/canceled tasks retain
            // their normal service cleanup behavior and diagnostic record.
            if (completed) {
                final?.fileUri?.let { assertTrue(DocumentFile.fromSingleUri(context, Uri.parse(it))?.delete() == true) }
                DownloadRepository.remove(context, record.id)
            }
        }
    }
}
