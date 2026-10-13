package com.example.javbrowser

import org.junit.Assert.*
import org.junit.Test
import org.mp4parser.muxer.Sample
import org.mp4parser.muxer.WrappingTrack
import org.mp4parser.muxer.container.mp4.MovieCreator
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.WritableByteChannel

class AdtsMp4AudioTest {
    private fun frame(payload: ByteArray): ByteArray {
        val size = payload.size + 7
        return byteArrayOf(0xff.toByte(), 0xf1.toByte(), 0x4c, (0x80 or (size shr 11)).toByte(),
            (size shr 3).toByte(), (((size and 7) shl 5) or 0x1f).toByte(), 0xfc.toByte()) + payload
    }

    @Test fun separatesCompoundAdtsPacketsWithoutChangingAacPayloadOrPacketTiming() {
        val source = File.createTempFile("aac-source", ".mp4")
        try {
            javaClass.getResourceAsStream("/subtitle-base.mp4")!!.use { input -> source.outputStream().use { input.copyTo(it) } }
            val movie = MovieCreator.build(source.path)
            try {
                val audio = movie.tracks.first { it.handler == "soun" }
                val groups = audio.samples.indices.toList().chunked(4)
                val packets = groups.map { indices ->
                    val bytes = indices.fold(byteArrayOf()) { acc, index ->
                        val data = audio.samples[index].asByteBuffer()
                        acc + frame(ByteArray(data.remaining()).also { data.get(it) })
                    }
                    object : Sample {
                        override fun getSize() = bytes.size.toLong()
                        override fun getSampleEntry() = audio.sampleEntries.first()
                        override fun asByteBuffer(): ByteBuffer = ByteBuffer.wrap(bytes)
                        override fun writeTo(channel: WritableByteChannel) { channel.write(asByteBuffer()) }
                    }
                }
                val packetTrack = object : WrappingTrack(audio) {
                    override fun getSamples(): List<Sample> = packets
                    override fun getSampleDurations() = groups.map { ids -> ids.sumOf { audio.sampleDurations[it] } }.toLongArray()
                }
                val normalized = AdtsMp4Audio.normalize(packetTrack) {}
                assertEquals(audio.samples.size, normalized.samples.size)
                assertArrayEquals(audio.sampleDurations, normalized.sampleDurations)
                audio.samples.zip(normalized.samples).forEach { (before, after) ->
                    assertEquals(before.asByteBuffer(), after.asByteBuffer())
                }
                assertSame(audio, AdtsMp4Audio.normalize(audio) {})
                val shortPacketTrack = object : WrappingTrack(packetTrack) {
                    override fun getSampleDurations() = groups.map { ids -> maxOf(ids.size.toLong(), ids.sumOf { audio.sampleDurations[it] } / 2) }.toLongArray()
                }
                val compressed = AdtsMp4Audio.normalize(shortPacketTrack) {}
                assertEquals(audio.samples.size, compressed.samples.size)
                assertEquals(shortPacketTrack.sampleDurations.sum(), compressed.sampleDurations.sum())
                assertTrue(compressed.sampleDurations.all { it > 0 })
            } finally { movie.tracks.forEach { it.close() } }
        } finally { source.delete() }
    }

    @Test fun rejectsTruncatedAdtsRatherThanWritingDamagedAudio() {
        val bytes = frame(byteArrayOf(1, 2, 3)).dropLast(1).toByteArray()
        assertThrows(java.io.IOException::class.java) { AdtsMp4Audio.frames(ByteBuffer.wrap(bytes)) }
    }
}
