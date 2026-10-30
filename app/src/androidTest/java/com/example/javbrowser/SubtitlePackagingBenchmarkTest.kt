package com.example.javbrowser

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mp4parser.muxer.container.mp4.MovieCreator
import java.io.File
import java.security.MessageDigest

/** Run against both APKs with the same remuxed synthetic fixture for an A/B measurement. */
@RunWith(AndroidJUnit4::class)
class SubtitlePackagingBenchmarkTest {
    @Test fun timeSubtitleEmbeddingAndVerifyEncodedSamples() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = File(context.cacheDir, "packaging-baseline.mp4")
        assumeTrue("Run the synthetic remux benchmark first", source.isFile)
        val output = File(context.cacheDir, "packaging-subtitles.mp4")
        val report = File(context.cacheDir, "packaging-subtitle-benchmark.txt").apply { writeText("") }
        repeat(3) { round ->
            val started = System.nanoTime()
            source.inputStream().use { input -> output.outputStream().use { destination ->
                EmbeddedSubtitles.mux(input.channel, destination.channel, listOf(
                    EmbeddedSubtitles.Subtitle("Test", "en", "WEBVTT\n\n00:01.000 --> 00:05.000\nPackaging test\n\n")))
            } }
            report.appendText("round=$round sourceBytes=${source.length()} elapsedMs=${(System.nanoTime() - started) / 1_000_000}\n")
            val before = MovieCreator.build(source.path)
            val after = MovieCreator.build(output.path)
            try {
                assertEquals(before.tracks.size + 1, after.tracks.size)
                before.tracks.zip(after.tracks).forEach { (a, b) ->
                    assertArrayEquals(a.sampleDurations, b.sampleDurations)
                    assertEquals(a.compositionTimeEntries?.map { it.count to it.offset }, b.compositionTimeEntries?.map { it.count to it.offset })
                    assertEquals(a.samples.size, b.samples.size)
                    val digest = MessageDigest.getInstance("SHA-256")
                    a.samples.forEach { digest.update(it.asByteBuffer()) }
                    val expected = digest.digest()
                    b.samples.forEach { digest.update(it.asByteBuffer()) }
                    assertArrayEquals(expected, digest.digest())
                }
                assertEquals("Packaging test", output.inputStream().use {
                    EmbeddedSubtitles.parseVtt(EmbeddedSubtitles.read(it.channel).single().vtt).single().text
                })
            } finally {
                before.tracks.forEach { it.close() }
                after.tracks.forEach { it.close() }
            }
        }
    }
}
