package com.example.javbrowser

import org.junit.Assert.*
import org.junit.Test

class DownloadSubtitleStateTest {
    private fun record(status: String, message: String) = VideoDownloadRecord(
        id = "download", title = "video", sourceUrl = "https://example.com/video.mp4", status = status,
        progress = 99, speedBytesPerSecond = 0, message = message, fileName = "video.mp4",
        fileUri = "content://downloads/1", fileSizeBytes = 1000, javCode = null, mimeType = "video/mp4",
        storageLocation = "Downloads", createdAt = 1, completedAt = null, referer = "", cookieSourceUrl = "",
        subtitleSources = "[{\"src\":\"https://subtitle.avking.xyz/test.vtt\"}]"
    )

    @Test fun localScanPreservesSubtitleSuccessAndFailureMessages() {
        listOf("下載完成（已內嵌字幕）", "下載完成（字幕合併失敗，影片已保留）").forEach { message ->
            assertEquals(message, DownloadRepository.localScanMessage(DownloadRepository.STATUS_COMPLETED, message, true))
        }
        assertEquals("下載完成", DownloadRepository.localScanMessage(DownloadRepository.STATUS_MISSING, "檔案已移動", true))
    }

    @Test fun scannedDuplicateDoesNotCompleteActivePackaging() {
        val active = record(DownloadRepository.STATUS_DOWNLOADING, "正在合併字幕")
        val scanned = active.copy(id = "local-scanned", sourceUrl = "", status = DownloadRepository.STATUS_COMPLETED,
            progress = 100, message = "本地影片")
        listOf(active to scanned, scanned to active).forEach { (first, second) ->
            val merged = DownloadRepository.mergeDuplicateRecords(first, second)
            assertEquals("download", merged.id)
            assertEquals(DownloadRepository.STATUS_DOWNLOADING, merged.status)
            assertEquals("正在合併字幕", merged.message)
            assertEquals(99, merged.progress)
        }
    }

    @Test fun completedVideoWithoutSubtitlesCanBeRepairedWithoutRedownload() {
        val failed = record(DownloadRepository.STATUS_COMPLETED, "下載完成（字幕合併失敗，影片已保留）")
        assertTrue(DownloadRepository.canRepairSubtitles(failed))
        assertTrue(DownloadRepository.canRepairSubtitles(failed.copy(message = "下載完成（已內嵌字幕）")))
        assertFalse(DownloadRepository.canRepairSubtitles(failed.copy(message = "下載完成（已內嵌字幕）", packagingRevision = 2)))
        assertFalse(DownloadRepository.canRepairSubtitles(failed.copy(status = DownloadRepository.STATUS_DOWNLOADING)))
        assertFalse(DownloadRepository.canRepairSubtitles(failed.copy(subtitleSources = "[]")))
    }
}
