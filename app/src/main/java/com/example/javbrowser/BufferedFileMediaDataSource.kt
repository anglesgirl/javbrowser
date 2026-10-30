package com.example.javbrowser

import android.media.MediaDataSource
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/** Read ahead for the extractor's small TS reads; the caller owns the channel. */
internal class BufferedFileMediaDataSource(private val channel: FileChannel) : MediaDataSource() {
    private val cache = ByteBuffer.allocate(256 * 1024)
    private var cacheStart = -1L
    private var cacheLength = 0
    private val length = channel.size()

    @Synchronized override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        require(position >= 0 && offset >= 0 && size >= 0 && offset <= buffer.size - size)
        if (size == 0) return 0
        if (position >= length) return -1
        if (position < cacheStart || position >= cacheStart + cacheLength) {
            cache.clear()
            cacheLength = channel.read(cache, position)
            cacheStart = position
            if (cacheLength <= 0) return -1
        }
        val start = (position - cacheStart).toInt()
        val count = minOf(size, cacheLength - start)
        System.arraycopy(cache.array(), start, buffer, offset, count)
        return count
    }

    override fun getSize() = length
    override fun close() = Unit
}
