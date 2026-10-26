package com.example.javbrowser

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class StripchatStreamRecordingStore(private val context: Context) {

    data class FinishResult(val completed: Boolean, val message: String)

    private var output: BufferedOutputStream? = null
    private var descriptor: android.os.ParcelFileDescriptor? = null
    private var outputUri: Uri? = null
    private var recordId: String? = null
    private var fileName: String = ""
    private var bytesWritten = 0L
    private var startedAtMs = 0L

    @Synchronized
    fun begin(mimeType: String, sourceUrl: String, width: Int, height: Int): Boolean {
        if (output != null) return false
        return try {
            val normalizedMime = if (mimeType.contains("mp4", ignoreCase = true)) "video/mp4" else "video/webm"
            val extension = if (normalizedMime == "video/mp4") "mp4" else "webm"
            fileName = buildFileName(sourceUrl, extension)
            outputUri = createPendingVideo(fileName, normalizedMime)
            descriptor = context.contentResolver.openFileDescriptor(outputUri!!, "rw")
                ?: error("無法建立錄影檔案")
            output = BufferedOutputStream(FileOutputStream(descriptor!!.fileDescriptor), 512 * 1024)
            bytesWritten = 0L
            startedAtMs = System.currentTimeMillis()

            val resolution = if (width > 0 && height > 0) "${width}x${height}" else "原始畫質"
            recordId = DownloadRepository.create(
                context,
                fileName.substringBeforeLast('.'),
                sourceUrl = "stream-recording:$sourceUrl",
                referer = sourceUrl,
                cookieSourceUrl = sourceUrl
            ).id
            recordId?.let { id ->
                DownloadRepository.update(context, id) {
                    it.copy(
                        status = DownloadRepository.STATUS_DOWNLOADING,
                        progress = 0,
                        message = "直播影音錄製中 · $resolution",
                        fileName = fileName,
                        fileUri = outputUri.toString(),
                        mimeType = normalizedMime
                    )
                }
            }
            android.util.Log.i("StripchatRecorder", "recording storage ready mime=$normalizedMime width=$width height=$height")
            true
        } catch (e: Exception) {
            android.util.Log.e("StripchatRecorder", "direct recording begin failed", e)
            cleanupFailed()
            false
        }
    }

    @Synchronized
    fun appendBase64(base64: String): Boolean {
        val stream = output ?: return false
        return try {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            stream.write(bytes)
            if (bytesWritten == 0L && bytes.isNotEmpty()) {
                android.util.Log.i("StripchatRecorder", "first media chunk written bytes=${bytes.size}")
            }
            bytesWritten += bytes.size
            true
        } catch (e: Exception) {
            android.util.Log.e("StripchatRecorder", "direct recording chunk failed", e)
            false
        }
    }

    @Synchronized
    fun finish(success: Boolean, detail: String): FinishResult {
        val uri = outputUri ?: return FinishResult(false, "目前沒有直播錄製")
        var completed = success && bytesWritten > 0L
        try {
            output?.flush()
        } catch (e: Exception) {
            completed = false
        }
        runCatching { output?.close() }
        runCatching { descriptor?.close() }
        output = null
        descriptor = null

        val mimeType = if (fileName.endsWith(".mp4", true)) "video/mp4" else "video/webm"
        val recordedDurationMs = (System.currentTimeMillis() - startedAtMs).coerceAtLeast(1L)
        val indexRebuilt = if (completed && mimeType == "video/mp4") {
            runCatching { finalizeFragmentedMp4(uri, recordedDurationMs) }
                .onFailure { android.util.Log.w("StripchatRecorder", "MP4 index finalize failed", it) }
                .getOrDefault(false)
        } else {
            false
        }
        val message: String
        if (completed) {
            if (Build.VERSION.SDK_INT >= 29) {
                context.contentResolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null,
                    null
                )
            }
            val actualSize = queryFileSize(uri).takeIf { it > 0L } ?: bytesWritten
            recordId?.let { id ->
                DownloadRepository.update(context, id) {
                    val completionMessage = when {
                        detail.contains("私人秀") -> "公開直播已結束 · 錄製完成"
                        detail.contains("下播") -> "主播已下播 · 錄製完成"
                        else -> "直播影音錄製完成"
                    }
                    it.copy(
                        status = DownloadRepository.STATUS_COMPLETED,
                        progress = 100,
                        message = if (indexRebuilt) "$completionMessage · 已建立播放索引" else completionMessage,
                        fileName = fileName,
                        fileUri = uri.toString(),
                        fileSizeBytes = actualSize,
                        mimeType = mimeType,
                        completedAt = System.currentTimeMillis()
                    )
                }
            }
            message = if (detail.isBlank()) {
                "錄製完成：$fileName"
            } else {
                "錄製完成：$fileName（$detail）"
            }
        } else {
            runCatching { context.contentResolver.delete(uri, null, null) }
            recordId?.let { id ->
                DownloadRepository.update(context, id) {
                    it.copy(
                        status = DownloadRepository.STATUS_FAILED,
                        progress = 0,
                        message = detail.ifBlank { "直播影音錄製失敗" }
                    )
                }
            }
            message = detail.ifBlank { "錄製失敗或沒有影音資料" }
        }

        android.util.Log.i("StripchatRecorder", "recording finalized completed=$completed bytes=$bytesWritten durationMs=$recordedDurationMs")
        outputUri = null
        recordId = null
        fileName = ""
        bytesWritten = 0L
        startedAtMs = 0L
        return FinishResult(completed, message)
    }

    @Synchronized
    fun isActive(): Boolean = output != null

    private fun cleanupFailed() {
        runCatching { output?.close() }
        runCatching { descriptor?.close() }
        outputUri?.let { uri -> runCatching { context.contentResolver.delete(uri, null, null) } }
        output = null
        descriptor = null
        outputUri = null
        recordId = null
        fileName = ""
        bytesWritten = 0L
        startedAtMs = 0L
    }

    private fun createPendingVideo(name: String, mimeType: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/JAV Browser")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val collection = if (Build.VERSION.SDK_INT >= 29) {
            MediaStore.Downloads.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        return context.contentResolver.insert(collection, values) ?: error("無法寫入下載資料夾")
    }

    private fun queryFileSize(uri: Uri): Long {
        return context.contentResolver.query(
            uri,
            arrayOf(MediaStore.MediaColumns.SIZE),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        } ?: 0L
    }

    /**
     * Chromium 會輸出 fragmented MP4（moof + mdat）。Android MediaMuxer 重新封裝這類
     * 動態直播 sample 可能破壞編碼資料，因此這裡完全不碰 mdat，只補影片時長與 mfra/tfra
     * 隨機存取索引。原始 H.264/AAC 位元資料會逐位元保持不變。
     */
    private fun finalizeFragmentedMp4(sourceUri: Uri, durationMs: Long): Boolean {
        val tempFile = File.createTempFile("stripchat-index-", ".mp4", context.cacheDir)
        val indexedFile = File.createTempFile("stripchat-seekable-", ".mp4", context.cacheDir)
        try {
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                tempFile.outputStream().buffered().use { output -> input.copyTo(output, 1024 * 1024) }
            } ?: return false

            var insertionOffset = 0L
            var sidx = ByteArray(0)
            var mfra = ByteArray(0)
            RandomAccessFile(tempFile, "rw").use { file ->
                val topLevel = readBoxes(file, 0L, file.length())
                val moov = topLevel.firstOrNull { it.type == "moov" } ?: return false
                val moofs = topLevel.filter { it.type == "moof" }
                if (moofs.isEmpty()) return false
                insertionOffset = moofs.first().start

                val moovChildren = readBoxes(file, moov.contentStart, moov.end)
                val mvhd = moovChildren.firstOrNull { it.type == "mvhd" }
                val movieTimescale = mvhd?.let { readTimescale(file, it) } ?: 1000L
                mvhd?.let { patchDuration(file, it, durationForScale(durationMs, movieTimescale), "mvhd") }

                val trackIds = linkedSetOf<Long>()
                val trackTimescales = linkedMapOf<Long, Long>()
                var videoTrackId: Long? = null
                moovChildren.filter { it.type == "trak" }.forEach { trak ->
                    val trakChildren = readBoxes(file, trak.contentStart, trak.end)
                    val tkhd = trakChildren.firstOrNull { it.type == "tkhd" }
                    val trackId = tkhd?.let { readTrackId(file, it) }
                    if (trackId != null) trackIds += trackId
                    tkhd?.let { patchDuration(file, it, durationForScale(durationMs, movieTimescale), "tkhd") }

                    val mdia = trakChildren.firstOrNull { it.type == "mdia" }
                    if (mdia != null && trackId != null) {
                        val mdiaChildren = readBoxes(file, mdia.contentStart, mdia.end)
                        val mdhd = mdiaChildren.firstOrNull { it.type == "mdhd" }
                        if (mdhd != null) {
                            val scale = readTimescale(file, mdhd)
                            trackTimescales[trackId] = scale
                            patchDuration(file, mdhd, durationForScale(durationMs, scale), "mdhd")
                        }
                        val hdlr = mdiaChildren.firstOrNull { it.type == "hdlr" }
                        if (hdlr != null && readHandlerType(file, hdlr) == "vide") {
                            videoTrackId = trackId
                        }
                    }
                }

                val entriesByTrack = linkedMapOf<Long, MutableList<TfraEntry>>()
                moofs.forEach { moof ->
                    val trafs = readBoxes(file, moof.contentStart, moof.end).filter { it.type == "traf" }
                    trafs.forEachIndexed { index, traf ->
                        val children = readBoxes(file, traf.contentStart, traf.end)
                        val tfhd = children.firstOrNull { it.type == "tfhd" } ?: return@forEachIndexed
                        val tfdt = children.firstOrNull { it.type == "tfdt" } ?: return@forEachIndexed
                        val trackId = readTfhdTrackId(file, tfhd)
                        if (trackId !in trackIds) return@forEachIndexed
                        val decodeTime = readTfdtTime(file, tfdt)
                        entriesByTrack.getOrPut(trackId) { mutableListOf() }.add(
                            TfraEntry(decodeTime, moof.start, index + 1)
                        )
                    }
                }
                if (entriesByTrack.isEmpty()) return false
                val indexedTrack = videoTrackId ?: return false
                val videoEntries = entriesByTrack[indexedTrack].orEmpty()
                val videoTimescale = trackTimescales[indexedTrack] ?: return false
                if (videoEntries.isEmpty()) return false

                sidx = buildSidx(
                    indexedTrack,
                    videoTimescale,
                    videoEntries,
                    moofs,
                    file.length(),
                    durationForScale(durationMs, videoTimescale)
                )
                val shiftedEntries = entriesByTrack.mapValues { (_, entries) ->
                    entries.map { it.copy(moofOffset = it.moofOffset + sidx.size) }
                }
                mfra = buildMfra(shiftedEntries)
            }

            RandomAccessFile(tempFile, "r").use { source ->
                FileOutputStream(indexedFile, false).buffered(1024 * 1024).use { destination ->
                    copyRange(source, destination, insertionOffset)
                    destination.write(sidx)
                    copyRange(source, destination, source.length() - insertionOffset)
                    destination.write(mfra)
                }
            }
            context.contentResolver.openOutputStream(sourceUri, "rwt")?.use { destination ->
                indexedFile.inputStream().buffered().use { source -> source.copyTo(destination, 1024 * 1024) }
            } ?: return false
            return true
        } finally {
            tempFile.delete()
            indexedFile.delete()
        }
    }

    private data class Mp4Box(
        val type: String,
        val start: Long,
        val size: Long,
        val headerSize: Long
    ) {
        val contentStart: Long get() = start + headerSize
        val end: Long get() = start + size
    }

    private data class TfraEntry(val time: Long, val moofOffset: Long, val trafNumber: Int)

    private fun readBoxes(file: RandomAccessFile, start: Long, end: Long): List<Mp4Box> {
        val result = mutableListOf<Mp4Box>()
        var position = start
        while (position + 8L <= end) {
            file.seek(position)
            val size32 = file.readInt().toLong() and 0xffffffffL
            val typeBytes = ByteArray(4)
            file.readFully(typeBytes)
            val type = String(typeBytes, Charsets.US_ASCII)
            var headerSize = 8L
            val size = when (size32) {
                0L -> end - position
                1L -> {
                    headerSize = 16L
                    file.readLong()
                }
                else -> size32
            }
            if (size < headerSize || position + size > end) break
            result += Mp4Box(type, position, size, headerSize)
            position += size
        }
        return result
    }

    private fun readTimescale(file: RandomAccessFile, box: Mp4Box): Long {
        file.seek(box.contentStart)
        val version = file.readUnsignedByte()
        val offset = if (version == 1) 20L else 12L
        file.seek(box.contentStart + offset)
        return (file.readInt().toLong() and 0xffffffffL).coerceAtLeast(1L)
    }

    private fun readTrackId(file: RandomAccessFile, box: Mp4Box): Long {
        file.seek(box.contentStart)
        val version = file.readUnsignedByte()
        val offset = if (version == 1) 20L else 12L
        file.seek(box.contentStart + offset)
        return file.readInt().toLong() and 0xffffffffL
    }

    private fun readHandlerType(file: RandomAccessFile, box: Mp4Box): String {
        file.seek(box.contentStart + 8L)
        val bytes = ByteArray(4)
        file.readFully(bytes)
        return String(bytes, Charsets.US_ASCII)
    }

    private fun patchDuration(
        file: RandomAccessFile,
        box: Mp4Box,
        duration: Long,
        kind: String
    ) {
        file.seek(box.contentStart)
        val version = file.readUnsignedByte()
        val offset = when (kind) {
            "tkhd" -> if (version == 1) 28L else 20L
            else -> if (version == 1) 24L else 16L
        }
        file.seek(box.contentStart + offset)
        if (version == 1) file.writeLong(duration) else file.writeInt(duration.coerceAtMost(0xffffffffL).toInt())
    }

    private fun durationForScale(durationMs: Long, timescale: Long): Long =
        ((durationMs * timescale) / 1000L).coerceAtLeast(1L)

    private fun readTfhdTrackId(file: RandomAccessFile, box: Mp4Box): Long {
        file.seek(box.contentStart + 4L)
        return file.readInt().toLong() and 0xffffffffL
    }

    private fun readTfdtTime(file: RandomAccessFile, box: Mp4Box): Long {
        file.seek(box.contentStart)
        val version = file.readUnsignedByte()
        file.seek(box.contentStart + 4L)
        return if (version == 1) file.readLong() else file.readInt().toLong() and 0xffffffffL
    }

    private fun copyRange(source: RandomAccessFile, destination: OutputStream, byteCount: Long) {
        val buffer = ByteArray(1024 * 1024)
        var remaining = byteCount
        while (remaining > 0L) {
            val count = source.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (count < 0) error("MP4 檔案提前結束")
            destination.write(buffer, 0, count)
            remaining -= count
        }
    }

    private fun buildSidx(
        trackId: Long,
        timescale: Long,
        entries: List<TfraEntry>,
        moofs: List<Mp4Box>,
        mediaEnd: Long,
        totalDuration: Long
    ): ByteArray {
        val moofStarts = moofs.map { it.start }.toSet()
        val references = entries.filter { it.moofOffset in moofStarts }
        val size = 40 + references.size * 12
        val output = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)
        output.putInt(size)
        output.put("sidx".toByteArray(Charsets.US_ASCII))
        output.putInt(0x01000000) // version 1 + flags
        output.putInt(trackId.toInt())
        output.putInt(timescale.toInt())
        output.putLong(references.firstOrNull()?.time ?: 0L)
        output.putLong(0L) // sidx 緊鄰第一個 moof，因此 first_offset = 0
        output.putShort(0)
        output.putShort(references.size.toShort())
        references.forEachIndexed { index, entry ->
            val nextOffset = references.getOrNull(index + 1)?.moofOffset ?: mediaEnd
            val referencedSize = (nextOffset - entry.moofOffset)
                .coerceIn(1L, 0x7fffffffL)
            val nextTime = references.getOrNull(index + 1)?.time ?: totalDuration
            val subsegmentDuration = (nextTime - entry.time)
                .coerceIn(1L, 0xffffffffL)
            output.putInt(referencedSize.toInt())
            output.putInt(subsegmentDuration.toInt())
            output.putInt(0x90000000.toInt()) // starts_with_SAP=1, SAP_type=1, delta=0
        }
        return output.array()
    }

    private fun buildMfra(entriesByTrack: Map<Long, List<TfraEntry>>): ByteArray {
        val tfraBoxes = entriesByTrack.map { (trackId, entries) -> buildTfra(trackId, entries) }
        val totalSize = 8 + tfraBoxes.sumOf { it.size } + 16
        val output = ByteBuffer.allocate(totalSize).order(ByteOrder.BIG_ENDIAN)
        output.putInt(totalSize)
        output.put("mfra".toByteArray(Charsets.US_ASCII))
        tfraBoxes.forEach(output::put)
        output.putInt(16)
        output.put("mfro".toByteArray(Charsets.US_ASCII))
        output.putInt(0) // version + flags
        output.putInt(totalSize)
        return output.array()
    }

    private fun buildTfra(trackId: Long, entries: List<TfraEntry>): ByteArray {
        // version 1 使用 64-bit time/offset；traf/trun/sample 編號各使用 1 byte。
        val size = 8 + 16 + entries.size * 19
        val output = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)
        output.putInt(size)
        output.put("tfra".toByteArray(Charsets.US_ASCII))
        output.putInt(0x01000000) // version 1 + flags
        output.putInt(trackId.toInt())
        output.putInt(0) // length_size_of_* = 1 byte
        output.putInt(entries.size)
        entries.forEach { entry ->
            output.putLong(entry.time)
            output.putLong(entry.moofOffset)
            output.put(entry.trafNumber.toByte())
            output.put(1.toByte()) // trun number
            output.put(1.toByte()) // sample number
        }
        return output.array()
    }

    private fun buildFileName(sourceUrl: String, extension: String): String {
        val model = runCatching { Uri.parse(sourceUrl).path.orEmpty().trim('/').substringBefore('/') }
            .getOrDefault("")
            .replace(Regex("[^A-Za-z0-9_-]"), "_")
            .ifBlank { "live" }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        return "Stripchat-$model-$stamp.$extension"
    }
}
