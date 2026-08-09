package com.frameflow.app

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object AudioMixdownTools {
    private const val TARGET_RATE = 44_100
    private const val TARGET_CHANNELS = 2
    private const val BYTES_PER_TARGET_FRAME = 4
    private const val MAX_MIX_DURATION_MS = 60 * 60 * 1000

    fun renderMixedAac(context: Context, project: ProjectState, destination: File): Boolean {
        val repository = AudioTimelineRepository(context)
        val timeline = repository.load(project)
        val clips = timeline.clips.filter { !it.muted && it.volume > .0001f && repository.file(project.id, it) != null }
        if (clips.isEmpty()) return false
        val durationMs = project.totalDurationMs.coerceIn(1, MAX_MIX_DURATION_MS)
        require(project.totalDurationMs <= MAX_MIX_DURATION_MS) { "Audio mixdown is limited to 60 minutes per export" }
        val raw = File.createTempFile("frameflow-mix-", ".pcm", context.cacheDir)
        try {
            val totalFrames = durationMs.toLong() * TARGET_RATE / 1000L
            val rawBytes = totalFrames * BYTES_PER_TARGET_FRAME
            require(rawBytes in 1..1_000_000_000L) { "Audio mixdown is too large" }
            RandomAccessFile(raw, "rw").use { mixFile ->
                mixFile.setLength(rawBytes)
                clips.forEach { clip ->
                    repository.file(project.id, clip)?.let { source ->
                        decodeAndMix(source, clip, mixFile, totalFrames)
                    }
                }
            }
            return encodePcmToAac(raw, totalFrames, destination)
        } finally {
            raw.delete()
        }
    }

    private fun decodeAndMix(source: File, clip: AudioClipState, mixFile: RandomAccessFile, totalTargetFrames: Long) {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var started = false
        try {
            extractor.setDataSource(source.absolutePath)
            var track = -1
            var inputFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val candidate = extractor.getTrackFormat(i)
                val mime = candidate.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) { track = i; inputFormat = candidate; break }
            }
            if (track < 0 || inputFormat == null) return
            extractor.selectTrack(track)
            val trimStartUs = clip.trimStartMs.toLong() * 1000L
            val trimEndUs = clip.trimEndMs.toLong() * 1000L
            extractor.seekTo(trimStartUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: return
            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()
            started = true

            var sampleRate = inputFormat.getIntegerOr(MediaFormat.KEY_SAMPLE_RATE, TARGET_RATE).coerceIn(8_000, 192_000)
            var channels = inputFormat.getIntegerOr(MediaFormat.KEY_CHANNEL_COUNT, 2).coerceIn(1, 8)
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
            var inputDone = false
            var outputDone = false
            val info = MediaCodec.BufferInfo()

            while (!outputDone) {
                if (!inputDone) {
                    val inputIndex = decoder.dequeueInputBuffer(10_000L)
                    if (inputIndex >= 0) {
                        val buffer = decoder.getInputBuffer(inputIndex) ?: continue
                        buffer.clear()
                        val time = extractor.sampleTime
                        val size = if (time < 0L || time >= trimEndUs) -1 else extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(inputIndex, 0, 0, max(trimStartUs, time), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inputIndex, 0, size, time.coerceAtLeast(0L), extractor.sampleFlags)
                            extractor.advance()
                        }
                    }
                }
                when (val outputIndex = decoder.dequeueOutputBuffer(info, 10_000L)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val format = decoder.outputFormat
                        sampleRate = format.getIntegerOr(MediaFormat.KEY_SAMPLE_RATE, sampleRate).coerceIn(8_000, 192_000)
                        channels = format.getIntegerOr(MediaFormat.KEY_CHANNEL_COUNT, channels).coerceIn(1, 8)
                        pcmEncoding = format.getIntegerOr(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (outputIndex >= 0) {
                        val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        val buffer = decoder.getOutputBuffer(outputIndex)
                        if (buffer != null && info.size > 0 && info.presentationTimeUs < trimEndUs) {
                            val stereo = decodeBlockToStereo(buffer, info, pcmEncoding, channels)
                            if (stereo.isNotEmpty()) {
                                mixResampledBlock(
                                    mixFile = mixFile,
                                    stereo = stereo,
                                    sourceRate = sampleRate,
                                    sourcePtsUs = info.presentationTimeUs,
                                    trimStartUs = trimStartUs,
                                    trimEndUs = trimEndUs,
                                    clipStartUs = clip.startMs.toLong() * 1000L,
                                    gain = clip.volume.coerceIn(0f, 1f),
                                    totalTargetFrames = totalTargetFrames
                                )
                            }
                        }
                        decoder.releaseOutputBuffer(outputIndex, false)
                        if (eos) outputDone = true
                    }
                }
            }
        } finally {
            extractor.release()
            if (started) runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
        }
    }

    /** Interleaved stereo floats in [-1,1]. */
    private fun decodeBlockToStereo(buffer: ByteBuffer, info: MediaCodec.BufferInfo, encoding: Int, channels: Int): FloatArray {
        val source = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        source.position(info.offset)
        source.limit(info.offset + info.size)
        val bytesPerSample = when (encoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> 4
            AudioFormat.ENCODING_PCM_8BIT -> 1
            else -> 2
        }
        val frameCount = source.remaining() / (bytesPerSample * channels).coerceAtLeast(1)
        if (frameCount <= 0) return FloatArray(0)
        val out = FloatArray(frameCount * 2)
        fun sample(): Float = when (encoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> source.float.coerceIn(-1f, 1f)
            AudioFormat.ENCODING_PCM_8BIT -> ((source.get().toInt() and 0xFF) - 128) / 128f
            else -> source.short / 32768f
        }
        repeat(frameCount) { frame ->
            var left = 0f
            var right = 0f
            var sum = 0f
            repeat(channels) { channel ->
                val value = sample()
                sum += value
                if (channel == 0) left = value
                if (channel == 1) right = value
            }
            if (channels == 1) right = left
            else if (channels > 2) {
                val average = sum / channels
                left = (left * .75f + average * .25f).coerceIn(-1f, 1f)
                right = (right * .75f + average * .25f).coerceIn(-1f, 1f)
            }
            out[frame * 2] = left
            out[frame * 2 + 1] = right
        }
        return out
    }

    private fun mixResampledBlock(
        mixFile: RandomAccessFile,
        stereo: FloatArray,
        sourceRate: Int,
        sourcePtsUs: Long,
        trimStartUs: Long,
        trimEndUs: Long,
        clipStartUs: Long,
        gain: Float,
        totalTargetFrames: Long
    ) {
        val sourceFrames = stereo.size / 2
        if (sourceFrames <= 0) return
        val outputFrames = max(1, (sourceFrames.toDouble() * TARGET_RATE / sourceRate).roundToInt())
        var firstDest = Long.MAX_VALUE
        var lastDest = Long.MIN_VALUE
        val destinationIndices = LongArray(outputFrames) { -1L }
        val values = FloatArray(outputFrames * 2)
        for (i in 0 until outputFrames) {
            val sourcePosition = i.toDouble() * sourceRate / TARGET_RATE
            val base = floor(sourcePosition).toInt().coerceIn(0, sourceFrames - 1)
            val next = min(base + 1, sourceFrames - 1)
            val fraction = (sourcePosition - base).toFloat()
            val sourceTimeUs = sourcePtsUs + (sourcePosition * 1_000_000.0 / sourceRate).toLong()
            if (sourceTimeUs < trimStartUs || sourceTimeUs >= trimEndUs) continue
            val timelineUs = clipStartUs + (sourceTimeUs - trimStartUs)
            val dest = (timelineUs * TARGET_RATE / 1_000_000L)
            if (dest !in 0 until totalTargetFrames) continue
            val left = stereo[base * 2] + (stereo[next * 2] - stereo[base * 2]) * fraction
            val right = stereo[base * 2 + 1] + (stereo[next * 2 + 1] - stereo[base * 2 + 1]) * fraction
            destinationIndices[i] = dest
            values[i * 2] = left * gain
            values[i * 2 + 1] = right * gain
            if (dest < firstDest) firstDest = dest
            if (dest > lastDest) lastDest = dest
        }
        if (firstDest == Long.MAX_VALUE || lastDest < firstDest) return
        val count = (lastDest - firstDest + 1L).toInt().coerceAtMost(1_000_000)
        if (count <= 0) return
        val bytes = ByteArray(count * BYTES_PER_TARGET_FRAME)
        mixFile.seek(firstDest * BYTES_PER_TARGET_FRAME)
        var read = 0
        while (read < bytes.size) {
            val amount = mixFile.read(bytes, read, bytes.size - read)
            if (amount <= 0) break
            read += amount
        }
        val existing = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val left = ShortArray(count)
        val right = ShortArray(count)
        repeat(count) { index ->
            left[index] = existing.short
            right[index] = existing.short
        }
        for (i in destinationIndices.indices) {
            val dest = destinationIndices[i]
            if (dest < firstDest || dest > lastDest) continue
            val local = (dest - firstDest).toInt()
            val mixedLeft = left[local].toInt() + (values[i * 2] * Short.MAX_VALUE).roundToInt()
            val mixedRight = right[local].toInt() + (values[i * 2 + 1] * Short.MAX_VALUE).roundToInt()
            left[local] = mixedLeft.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            right[local] = mixedRight.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        val output = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        output.clear()
        repeat(count) { index -> output.putShort(left[index]); output.putShort(right[index]) }
        mixFile.seek(firstDest * BYTES_PER_TARGET_FRAME)
        mixFile.write(bytes)
    }

    private fun encodePcmToAac(raw: File, totalFrames: Long, destination: File): Boolean {
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        var started = false
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        try {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, TARGET_RATE, TARGET_CHANNELS).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 192_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
            }
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start(); started = true
            muxer = MediaMuxer(destination.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var outputTrack = -1
            val info = MediaCodec.BufferInfo()
            var framesQueued = 0L
            var inputDone = false
            var outputDone = false
            raw.inputStream().buffered().use { input ->
                while (!outputDone) {
                    if (!inputDone) {
                        val inputIndex = encoder.dequeueInputBuffer(10_000L)
                        if (inputIndex >= 0) {
                            val buffer = encoder.getInputBuffer(inputIndex) ?: error("AAC encoder input unavailable")
                            buffer.clear()
                            val capacity = buffer.remaining() - (buffer.remaining() % BYTES_PER_TARGET_FRAME)
                            val bytes = ByteArray(capacity.coerceAtLeast(BYTES_PER_TARGET_FRAME))
                            val read = input.read(bytes, 0, bytes.size)
                            if (read < 0 || framesQueued >= totalFrames) {
                                encoder.queueInputBuffer(inputIndex, 0, 0, framesQueued * 1_000_000L / TARGET_RATE, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                val aligned = read - (read % BYTES_PER_TARGET_FRAME)
                                buffer.put(bytes, 0, aligned)
                                encoder.queueInputBuffer(inputIndex, 0, aligned, framesQueued * 1_000_000L / TARGET_RATE, 0)
                                framesQueued += aligned / BYTES_PER_TARGET_FRAME
                            }
                        }
                    }
                    when (val outputIndex = encoder.dequeueOutputBuffer(info, 10_000L)) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            check(!muxerStarted) { "AAC encoder format changed twice" }
                            outputTrack = muxer.addTrack(encoder.outputFormat)
                            muxer.start(); muxerStarted = true
                        }
                        MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                        else -> if (outputIndex >= 0) {
                            val buffer = encoder.getOutputBuffer(outputIndex)
                            if (buffer != null && info.size > 0 && muxerStarted && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                buffer.position(info.offset); buffer.limit(info.offset + info.size)
                                muxer.writeSampleData(outputTrack, buffer, info)
                            }
                            outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            encoder.releaseOutputBuffer(outputIndex, false)
                        }
                    }
                }
            }
            return muxerStarted && destination.length() > 0
        } finally {
            if (started) runCatching { encoder.stop() }
            runCatching { encoder.release() }
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
        }
    }

    private fun MediaFormat.getIntegerOr(key: String, fallback: Int): Int = runCatching { getInteger(key) }.getOrDefault(fallback)
}
