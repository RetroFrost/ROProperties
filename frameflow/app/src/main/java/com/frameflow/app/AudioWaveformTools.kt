package com.frameflow.app

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max

object AudioWaveformTools {
    fun decodeWaveform(file: File, buckets: Int = 160): FloatArray {
        require(file.isFile && file.length() > 0) { "Audio file is missing" }
        val count = buckets.coerceIn(32, 600)
        val result = FloatArray(count)
        val hits = IntArray(count)
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            var track = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val candidate = extractor.getTrackFormat(i)
                val mime = candidate.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) { track = i; format = candidate; break }
            }
            require(track >= 0 && format != null) { "No decodable audio track" }
            extractor.selectTrack(track)
            val mime = format!!.getString(MediaFormat.KEY_MIME) ?: error("Audio MIME is missing")
            val durationUs = format!!.getLongOr(MediaFormat.KEY_DURATION, 1L).coerceAtLeast(1L)
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var outputPcm = AudioFormat.ENCODING_PCM_16BIT
            while (!outputDone) {
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val buffer = codec.getInputBuffer(inputIndex) ?: continue
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime.coerceAtLeast(0L), 0)
                            extractor.advance()
                        }
                    }
                }

                when (val outputIndex = codec.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val changed = codec.outputFormat
                        outputPcm = changed.getIntegerOr(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (outputIndex >= 0) {
                        val buffer = codec.getOutputBuffer(outputIndex)
                        if (buffer != null && info.size > 0) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            val amplitude = when (outputPcm) {
                                AudioFormat.ENCODING_PCM_FLOAT -> {
                                    val floats = buffer.slice().order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                                    var peak = 0f
                                    while (floats.hasRemaining()) peak = max(peak, abs(floats.get()).coerceAtMost(1f))
                                    peak
                                }
                                AudioFormat.ENCODING_PCM_8BIT -> {
                                    var peak = 0f
                                    while (buffer.hasRemaining()) peak = max(peak, abs((buffer.get().toInt() and 0xFF) - 128) / 128f)
                                    peak
                                }
                                else -> {
                                    val shorts = buffer.slice().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                                    var peak = 0f
                                    while (shorts.hasRemaining()) peak = max(peak, abs(shorts.get().toInt()) / 32768f)
                                    peak
                                }
                            }
                            val bucket = ((info.presentationTimeUs.toDouble() / durationUs) * count).toInt().coerceIn(0, count - 1)
                            result[bucket] += amplitude
                            hits[bucket]++
                        }
                        outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }
            for (i in result.indices) if (hits[i] > 0) result[i] = (result[i] / hits[i]).coerceIn(0f, 1f)
            // Fill isolated decoder gaps by linear neighbour averaging so the displayed waveform is continuous.
            for (i in 1 until result.lastIndex) if (hits[i] == 0) result[i] = (result[i - 1] + result[i + 1]) / 2f
            return result
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    private fun MediaFormat.getLongOr(key: String, fallback: Long): Long = runCatching { getLong(key) }.getOrDefault(fallback)
    private fun MediaFormat.getIntegerOr(key: String, fallback: Int): Int = runCatching { getInteger(key) }.getOrDefault(fallback)
}
