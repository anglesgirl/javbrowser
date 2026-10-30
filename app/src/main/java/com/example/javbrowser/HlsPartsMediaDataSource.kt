package com.example.javbrowser

import android.media.MediaDataSource
import java.io.File
import java.io.FileInputStream
import java.io.IOException

/** A seekable view of downloaded, decrypted TS parts without writing a merged TS file. */
internal class HlsPartsMediaDataSource(private val parts: List<File>) : MediaDataSource() {
    private val offsets = LongArray(parts.size + 1)
    private var currentIndex = -1
    private var input: FileInputStream? = null
    private var current: BufferedFileMediaDataSource? = null

    init {
        require(parts.isNotEmpty()) { "No HLS parts" }
        parts.forEachIndexed { index, file ->
            if (!file.isFile || file.length() <= 0) throw IOException("Missing or empty HLS part: $index")
            offsets[index + 1] = Math.addExact(offsets[index], file.length())
        }
    }

    @Synchronized override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        require(position >= 0 && offset >= 0 && size >= 0 && offset <= buffer.size - size)
        if (size == 0) return 0
        if (position >= getSize()) return -1
        val found = offsets.binarySearch(position)
        val index = if (found >= 0) found else -found - 2
        if (index != currentIndex) {
            input?.close()
            input = null
            current = null
            currentIndex = -1
            val stream = FileInputStream(parts[index])
            try {
                current = BufferedFileMediaDataSource(stream.channel)
                input = stream
                currentIndex = index
            } catch (error: Exception) {
                stream.close()
                throw error
            }
        }
        return requireNotNull(current).readAt(position - offsets[index], buffer, offset, size)
    }

    override fun getSize() = offsets.last()

    @Synchronized override fun close() {
        input?.close()
        input = null
        current = null
        currentIndex = -1
    }
}
