package com.example.javbrowser

import android.content.Context
import android.media.MediaExtractor
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mp4parser.muxer.container.mp4.MovieCreator
import java.io.File
import java.security.MessageDigest

/** Opt-in: place a synthetic TS at files/packaging-benchmark.ts on the development device. */
@RunWith(AndroidJUnit4::class)
class PackagingBenchmarkTest {
    @Test fun compareRemuxInputsAndVerifySamples() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = File(context.filesDir, "packaging-benchmark.ts")
        assumeTrue("Provide the synthetic fixture first", source.isFile)
        val baseline = File(context.cacheDir, "packaging-baseline.mp4")
        val buffered = File(context.cacheDir, "packaging-buffered.mp4")
        val report = File(context.cacheDir, "packaging-benchmark.txt").apply { writeText("") }
        val partDirectory = File(context.cacheDir, "packaging-test-parts").apply { mkdirs() }
        val parts = mutableListOf<File>()
        source.inputStream().use { input ->
            // Deliberately split inside TS packets; the virtual source must behave
            // exactly like concatenation even across unaligned file boundaries.
            val bytes = ByteArray(1_000_003)
            while (true) {
                val count = input.read(bytes)
                if (count < 0) break
                val part = File(partDirectory, "${parts.size}.part")
                part.outputStream().use { it.write(bytes, 0, count) }
                parts.add(part)
            }
        }
        fun remux(destination: File, readAhead: Boolean): Long {
            val started = System.nanoTime()
            source.inputStream().use { input -> destination.outputStream().use { output ->
                val extractor = MediaExtractor()
                try {
                    if (readAhead) {
                        HlsPartsMediaDataSource(parts).use { media ->
                            extractor.setDataSource(media)
                            TsMp4Remuxer.remux(extractor, output.fd, source.length())
                        }
                    } else {
                        extractor.setDataSource(input.fd)
                        TsMp4Remuxer.remux(extractor, output.fd, source.length())
                    }
                } finally { extractor.release() }
            } }
            return (System.nanoTime() - started) / 1_000_000
        }
        // Reverse order on the second pass to expose warm-cache/order effects.
        repeat(2) { round ->
            val timings = mutableMapOf<Boolean, Long>()
            (if (round == 0) listOf(false, true) else listOf(true, false)).forEach { readAhead ->
                timings[readAhead] = remux(if (readAhead) buffered else baseline, readAhead)
            }
            Log.i("PackagingBenchmark", "round=$round bytes=${source.length()} nativeMs=${timings[false]} bufferedMs=${timings[true]}")
            report.appendText("round=$round bytes=${source.length()} nativeMs=${timings[false]} bufferedMs=${timings[true]}\n")
            baseline.inputStream().use { EmbeddedSubtitles.hasMisScaledVideoEdits(it.channel) }
            val before = MovieCreator.build(baseline.path)
            val after = MovieCreator.build(buffered.path)
            try {
                assertEquals(before.tracks.size, after.tracks.size)
                before.tracks.zip(after.tracks).forEach { (a, b) ->
                    assertEquals(a.handler, b.handler)
                    assertArrayEquals(a.sampleDurations, b.sampleDurations)
                    assertEquals(a.compositionTimeEntries?.map { it.count to it.offset }, b.compositionTimeEntries?.map { it.count to it.offset })
                    assertEquals(a.samples.size, b.samples.size)
                    val digest = MessageDigest.getInstance("SHA-256")
                    a.samples.forEach { digest.update(it.asByteBuffer()) }
                    val expected = digest.digest()
                    b.samples.forEach { digest.update(it.asByteBuffer()) }
                    assertArrayEquals(expected, digest.digest())
                }
            } finally {
                before.tracks.forEach { it.close() }
                after.tracks.forEach { it.close() }
            }
        }
        parts.forEach { it.delete() }
        partDirectory.delete()
    }

    @Test fun bufferedSourceSupportsSeeksAndEndOfFile() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir, "packaging-read-test.bin")
        val data = ByteArray(800_000) { (it * 31).toByte() }
        try {
            file.writeBytes(data)
            file.inputStream().use { stream -> BufferedFileMediaDataSource(stream.channel).use { source ->
                assertEquals(data.size.toLong(), source.size)
                val buffer = ByteArray(20_000)
                listOf(0, 250_000, 260_000, 400_000, 100, 795_000).forEach { offset ->
                    val count = source.readAt(offset.toLong(), buffer, 3, buffer.size - 3)
                    assertTrue(count > 0)
                    assertArrayEquals(data.copyOfRange(offset, offset + count), buffer.copyOfRange(3, 3 + count))
                }
                assertEquals(-1, source.readAt(data.size.toLong(), buffer, 0, 1))
                assertEquals(0, source.readAt(data.size.toLong(), buffer, 0, 0))
            } }
        } finally { file.delete() }
    }

    @Test fun partSourceSupportsBoundarySeeksAndRejectsMissingParts() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val first = File(context.cacheDir, "packaging-part-a.bin")
        val second = File(context.cacheDir, "packaging-part-b.bin")
        try {
            first.writeBytes(byteArrayOf(1, 2, 3))
            second.writeBytes(byteArrayOf(4, 5))
            HlsPartsMediaDataSource(listOf(first, second)).use { source ->
                val result = ByteArray(5)
                var position = 0
                while (position < result.size) position += source.readAt(position.toLong(), result, position, result.size - position)
                assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), result)
                assertEquals(2, source.readAt(3, result, 0, 5))
                assertEquals(4.toByte(), result[0])
                assertEquals(3, source.readAt(0, result, 0, 5))
                assertEquals(1.toByte(), result[0])
                assertEquals(-1, source.readAt(5, result, 0, 1))
            }
            second.delete()
            try { HlsPartsMediaDataSource(listOf(first, second)); fail("Missing part accepted") }
            catch (_: java.io.IOException) { }
        } finally { first.delete(); second.delete() }
    }

    @Test fun remuxCancellationPropagatesAndKeepsSource() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = File(context.filesDir, "packaging-benchmark.ts")
        assumeTrue("Provide the synthetic fixture first", source.isFile)
        val length = source.length()
        val output = File(context.cacheDir, "packaging-canceled.mp4")
        val canceled = java.io.IOException("Synthetic cancellation")
        try {
            HlsPartsMediaDataSource(listOf(source)).use { media -> output.outputStream().use { stream ->
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(media)
                    var samples = 0
                    try {
                        TsMp4Remuxer.remux(extractor, stream.fd, media.size, checkCanceled = {
                            if (++samples == 10) throw canceled
                        })
                        fail("Cancellation was swallowed")
                    } catch (error: java.io.IOException) { assertSame(canceled, error) }
                } finally { extractor.release() }
            } }
            assertTrue(source.isFile)
            assertEquals(length, source.length())
        } finally { output.delete() }
    }
}
