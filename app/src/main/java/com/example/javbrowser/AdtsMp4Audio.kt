package com.example.javbrowser

import org.mp4parser.muxer.Sample
import org.mp4parser.muxer.Track
import org.mp4parser.muxer.WrappingTrack
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.WritableByteChannel

/** MP4 AAC samples contain one raw access unit, not a TS packet containing multiple ADTS frames. */
internal object AdtsMp4Audio {
    data class Frame(val offset: Int, val length: Int, val duration: Long)

    fun frames(bytes: ByteBuffer): List<Frame>? {
        val data = bytes.slice()
        if (data.remaining() < 2 || data.get(0).toInt() and 255 != 255 ||
            data.get(1).toInt() and 0xf6 != 0xf0) return null
        val result = ArrayList<Frame>()
        var offset = 0
        fun byte(index: Int) = data.get(index).toInt() and 255
        while (offset < data.limit()) {
            if (data.limit() - offset < 7 || byte(offset) != 255 || byte(offset + 1) and 0xf6 != 0xf0)
                throw IOException("Invalid ADTS frame boundary")
            val header = if (byte(offset + 1) and 1 != 0) 7 else 9
            val length = ((byte(offset + 3) and 3) shl 11) or (byte(offset + 4) shl 3) or (byte(offset + 5) shr 5)
            if (length <= header || offset + length > data.limit()) throw IOException("Truncated ADTS frame")
            val blocks = (byte(offset + 6) and 3) + 1
            // Multiple raw blocks need CRC-aware parsing; never write an invalid MP4 sample.
            if (blocks != 1) throw IOException("Unsupported multi-block ADTS frame")
            result.add(Frame(offset + header, length - header, 1024))
            offset += length
        }
        return result
    }

    fun normalize(track: Track, checkCanceled: () -> Unit): Track {
        if (track.handler != "soun" || track.sampleEntries.none { it.type == "mp4a" } || track.samples.isEmpty()) return track
        if (frames(track.samples.first().asByteBuffer()) == null) return track
        val samples = ArrayList<Sample>()
        val durations = ArrayList<Long>()
        track.samples.forEachIndexed { index, sample ->
            checkCanceled()
            val frames = frames(sample.asByteBuffer()) ?: throw IOException("Mixed ADTS and raw AAC samples")
            val packetDuration = track.sampleDurations[index]
            if (packetDuration < frames.size) throw IOException("Invalid AAC packet duration")
            val compressedTiming = packetDuration <= frames.dropLast(1).sumOf { it.duration }
            var elapsed = 0L
            frames.forEachIndexed { frameIndex, frame ->
                samples.add(object : Sample {
                    override fun getSize() = frame.length.toLong()
                    override fun getSampleEntry() = sample.sampleEntry
                    override fun asByteBuffer(): ByteBuffer = sample.asByteBuffer().duplicate().apply {
                        position(frame.offset); limit(frame.offset + frame.length)
                    }.slice()
                    override fun writeTo(channel: WritableByteChannel) {
                        val data = asByteBuffer()
                        while (data.hasRemaining()) { checkCanceled(); channel.write(data) }
                    }
                })
                // Keep packet boundaries/timestamps, including source discontinuities.
                val duration = if (compressedTiming) {
                    // Some TS extractors report a shorter interval than the contained AAC frames.
                    // Spread timestamps across that interval; retain all encoded access units.
                    packetDuration * (frameIndex + 1) / frames.size - packetDuration * frameIndex / frames.size
                } else if (frameIndex == frames.lastIndex) packetDuration - elapsed else frame.duration
                if (duration <= 0) throw IOException("Invalid AAC packet timing")
                durations.add(duration)
                elapsed += duration
            }
        }
        return object : WrappingTrack(track) {
            private val sampleTimes = durations.toLongArray()
            override fun getSamples(): List<Sample> = samples
            override fun getSampleDurations(): LongArray = sampleTimes
        }
    }
}
