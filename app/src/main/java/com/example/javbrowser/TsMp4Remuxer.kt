package com.example.javbrowser

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import androidx.annotation.RequiresApi
import java.io.FileDescriptor
import java.io.IOException
import java.nio.ByteBuffer

/** Copies encoded samples, preserving presentation timestamps and normalizing ADTS AAC. */
@RequiresApi(26)
internal object TsMp4Remuxer {
    fun remux(extractor: MediaExtractor, output: FileDescriptor, sourceSize: Long,
        checkCanceled: () -> Unit = {}, reportProgress: (Int) -> Unit = {}) {
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        try {
            val trackMap = IntArray(extractor.trackCount) { -1 }
            val aacSampleRates = IntArray(extractor.trackCount)
            var videoTrackFound = false
            var maxInputSize = 4 * 1024 * 1024
            val activeMuxer = MediaMuxer(
                output,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            )
            muxer = activeMuxer

            for (trackIndex in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(trackIndex)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (!mime.startsWith("video/") && !mime.startsWith("audio/")) continue
                if (mime == "audio/mp4a-latm") {
                    aacSampleRates[trackIndex] = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    format.setInteger(MediaFormat.KEY_IS_ADTS, 0)
                }
                trackMap[trackIndex] = activeMuxer.addTrack(format)
                extractor.selectTrack(trackIndex)
                if (mime.startsWith("video/")) {
                    videoTrackFound = true
                    if (format.containsKey(MediaFormat.KEY_ROTATION)) {
                        activeMuxer.setOrientationHint(format.getInteger(MediaFormat.KEY_ROTATION))
                    }
                }
                if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                    maxInputSize = maxOf(maxInputSize, format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                }
            }
            if (!videoTrackFound) throw IOException("TS 中找不到可封裝的影片軌")

            activeMuxer.start()
            muxerStarted = true
            val buffer = ByteBuffer.allocateDirect(maxInputSize.coerceAtMost(32 * 1024 * 1024))
            val info = MediaCodec.BufferInfo()
            var processedBytes = 0L
            var lastReportTime = android.os.SystemClock.elapsedRealtime()

            while (true) {
                checkCanceled()
                buffer.clear()
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break
                val sourceTrack = extractor.sampleTrackIndex
                val destinationTrack = trackMap.getOrElse(sourceTrack) { -1 }
                if (destinationTrack >= 0) {
                    // These getters cross into the extractor service. An ADTS packet may
                    // contain many AAC frames; fetch the packet metadata only once.
                    val presentationTime = extractor.sampleTime
                    val sampleFlags = extractor.sampleFlags
                    // Keep the source presentation timestamp. Forcing it to be strictly
                    // increasing destroys B-frame composition timing; the old 1 us nudge
                    // was also rounded away at the MP4 track timescale, creating many
                    // duplicate timestamps and visible dropped frames.
                    buffer.position(0)
                    buffer.limit(sampleSize)
                    val sampleRate = aacSampleRates.getOrElse(sourceTrack) { 0 }
                    val frames = if (sampleRate > 0) AdtsMp4Audio.frames(buffer) else null
                    if (frames == null) {
                        info.set(0, sampleSize, presentationTime, sampleFlags)
                        activeMuxer.writeSampleData(destinationTrack, buffer, info)
                    } else {
                        var elapsedSamples = 0L
                        frames.forEach { frame ->
                            info.set(frame.offset, frame.length,
                                presentationTime + elapsedSamples * 1_000_000L / sampleRate, sampleFlags)
                            activeMuxer.writeSampleData(destinationTrack, buffer, info)
                            elapsedSamples += frame.duration
                        }
                    }
                    processedBytes += sampleSize
                }
                extractor.advance()

                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastReportTime >= 1000L) {
                    val remuxPercent = if (sourceSize > 0L) {
                        (processedBytes * 100L / sourceSize).toInt().coerceIn(0, 99)
                    } else {
                        0
                    }
                    reportProgress(remuxPercent)
                    lastReportTime = now
                }
            }
            activeMuxer.stop()
            muxerStarted = false
        } finally {
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
        }
    }
}
