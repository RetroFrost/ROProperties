package com.frameflow.app

import android.content.Context
import android.graphics.Bitmap
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import com.squareup.gifencoder.FloydSteinbergDitherer
import com.squareup.gifencoder.GifEncoder
import com.squareup.gifencoder.ImageOptions
import com.squareup.gifencoder.KMeansQuantizer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val MAX_GIF_SIDE = 1600
private const val MAX_VIDEO_SIDE = 3840
private const val MAX_VIDEO_DURATION_MS = 6 * 60 * 60 * 1000L

object MediaExporter {
    fun exportGif(context: Context, project: ProjectState, uri: Uri) {
        require(project.frames.isNotEmpty()) { "Project has no frames" }
        var completed = false
        try {
            val first = FrameRenderer.renderBounded(project, 0, MAX_GIF_SIDE)
            val width = first.width
            val height = first.height
            context.contentResolver.openOutputStream(uri, "w")?.use { output ->
                val encoder = GifEncoder(output, width, height, 0)
                project.frames.forEachIndexed { index, frame ->
                    val bitmap = if (index == 0) first else FrameRenderer.renderBounded(project, index, MAX_GIF_SIDE)
                    try {
                        val options = ImageOptions()
                            .setDelay(frame.durationMs.toLong().coerceAtLeast(20L), TimeUnit.MILLISECONDS)
                            .setColorQuantizer(KMeansQuantizer.INSTANCE)
                            .setDitherer(FloydSteinbergDitherer.INSTANCE)
                        encoder.addImage(bitmapToGifPixels(bitmap), options)
                    } finally {
                        if (!bitmap.isRecycled) bitmap.recycle()
                    }
                }
                encoder.finishEncoding()
            } ?: error("Unable to create GIF")
            completed = true
        } finally {
            if (!completed) runCatching { context.contentResolver.delete(uri, null, null) }
        }
    }

    fun exportMp4(
        context: Context,
        project: ProjectState,
        uri: Uri,
        fps: Int = 30,
        bitrate: Int = 6_000_000
    ) {
        require(project.frames.isNotEmpty()) { "Project has no frames" }
        require(project.totalDurationMs.toLong() in 1..MAX_VIDEO_DURATION_MS) { "Video is too long to export safely" }
        val temporaryVideo = File.createTempFile("frameflow-video-", ".mp4", context.cacheDir)
        val temporaryAudio = File.createTempFile("frameflow-audio-", ".mp4", context.cacheDir)
        var completed = false
        try {
            encodeVideo(project, temporaryVideo, fps, bitrate)
            val sourceAudio = project.audioFileName?.let { name ->
                File(File(File(context.filesDir, "frameflow-media"), project.id), name.substringAfterLast('/').substringAfterLast('\\'))
                    .takeIf { it.isFile && it.length() > 0 }
            }

            val projectDurationUs = project.totalDurationMs.toLong() * 1000L
            val offsetUs = project.audioOffsetMs.toLong() * 1000L
            val sourceStartUs = if (offsetUs < 0L) -offsetUs else 0L
            val destinationOffsetUs = max(0L, offsetUs)
            val availableDurationUs = (projectDurationUs - destinationOffsetUs).coerceAtLeast(0L)
            val preparedAudio = if (
                sourceAudio != null && project.audioVolume > .0001f && availableDurationUs > 0L
            ) {
                runCatching {
                    transcodeAudioToAac(
                        source = sourceAudio,
                        destination = temporaryAudio,
                        volume = project.audioVolume.coerceIn(0f, 1f),
                        sourceStartUs = sourceStartUs,
                        maxDurationUs = availableDurationUs
                    )
                }.getOrNull()?.takeIf { it && temporaryAudio.isFile && temporaryAudio.length() > 0 }?.let { temporaryAudio }
            } else null

            if (preparedAudio == null) {
                copyFileToUri(context, temporaryVideo, uri)
            } else {
                muxVideoAndAac(context, projectDurationUs, temporaryVideo, preparedAudio, destinationOffsetUs, uri)
            }
            completed = true
        } finally {
            temporaryVideo.delete()
            temporaryAudio.delete()
            if (!completed) runCatching { context.contentResolver.delete(uri, null, null) }
        }
    }

    private fun encodeVideo(project: ProjectState, destination: File, requestedFps: Int, requestedBitrate: Int) {
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        var started = false
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        try {
            val capabilities = codec.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
            val colorFormat = chooseYuvFormat(capabilities.colorFormats)
            val videoCapabilities = capabilities.videoCapabilities
            var width: Int
            var height: Int
            run {
                val scale = minOf(1f, MAX_VIDEO_SIDE.toFloat() / max(project.canvasWidth, project.canvasHeight).coerceAtLeast(1))
                width = even((project.canvasWidth * scale).roundToInt().coerceAtLeast(64))
                height = even((project.canvasHeight * scale).roundToInt().coerceAtLeast(64))
            }
            while (!videoCapabilities.isSizeSupported(width, height) && max(width, height) > 320) {
                width = even((width * .8f).roundToInt().coerceAtLeast(64))
                height = even((height * .8f).roundToInt().coerceAtLeast(64))
            }
            require(videoCapabilities.isSizeSupported(width, height)) { "This device's H.264 encoder does not support the project aspect/resolution" }

            val rateRange = runCatching { videoCapabilities.getSupportedFrameRatesFor(width, height) }.getOrNull()
            val frameRate = requestedFps.coerceIn(12, 60).let { value ->
                if (rateRange == null) value else value.coerceIn(rateRange.lower.toInt().coerceAtLeast(1), rateRange.upper.toInt().coerceAtLeast(1))
            }
            val bitrate = requestedBitrate.coerceAtLeast(width * height * 2).let { requested ->
                val range = videoCapabilities.bitrateRange
                requested.coerceIn(range.lower, range.upper)
            }

            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            started = true

            muxer = MediaMuxer(destination.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var trackIndex = -1
            val info = MediaCodec.BufferInfo()
            var frameNumber = 0L

            fun drain(end: Boolean) {
                var idle = 0
                while (true) {
                    val status = codec.dequeueOutputBuffer(info, if (end) 10_000L else 0L)
                    when {
                        status == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            if (!end || ++idle > 200) return
                        }
                        status == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            check(!muxerStarted) { "Encoder format changed twice" }
                            trackIndex = muxer!!.addTrack(codec.outputFormat)
                            muxer!!.start()
                            muxerStarted = true
                        }
                        status >= 0 -> {
                            idle = 0
                            val buffer = codec.getOutputBuffer(status)
                            if (buffer != null && info.size > 0 && muxerStarted && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                muxer!!.writeSampleData(trackIndex, buffer, info)
                            }
                            val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            codec.releaseOutputBuffer(status, false)
                            if (eos) return
                        }
                    }
                }
            }

            val maxSide = max(width, height)
            project.frames.forEachIndexed { index, frame ->
                val source = FrameRenderer.renderBounded(project, index, maxSide)
                val bitmap = if (source.width == width && source.height == height) source
                else Bitmap.createScaledBitmap(source, width, height, true).also { if (it !== source) source.recycle() }
                val yuv = try { bitmapToYuv420(bitmap, colorFormat) } finally { if (!bitmap.isRecycled) bitmap.recycle() }
                val repeats = max(1, (frame.durationMs / 1000f * frameRate).roundToInt())
                repeat(repeats) {
                    var queued = false
                    var attempts = 0
                    while (!queued) {
                        val inputIndex = codec.dequeueInputBuffer(10_000L)
                        if (inputIndex >= 0) {
                            val input = codec.getInputBuffer(inputIndex) ?: error("Encoder input buffer unavailable")
                            input.clear()
                            require(input.capacity() >= yuv.size) { "H.264 encoder input buffer is too small for this resolution" }
                            input.put(yuv)
                            val ptsUs = frameNumber * 1_000_000L / frameRate
                            codec.queueInputBuffer(inputIndex, 0, yuv.size, ptsUs, 0)
                            frameNumber++
                            queued = true
                        } else {
                            drain(false)
                            require(++attempts < 500) { "H.264 encoder stopped accepting frames" }
                        }
                    }
                    drain(false)
                }
            }

            var eosQueued = false
            var attempts = 0
            while (!eosQueued) {
                val inputIndex = codec.dequeueInputBuffer(10_000L)
                if (inputIndex >= 0) {
                    val ptsUs = frameNumber * 1_000_000L / frameRate
                    codec.queueInputBuffer(inputIndex, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    eosQueued = true
                } else {
                    drain(false)
                    require(++attempts < 500) { "H.264 encoder could not finish" }
                }
            }
            drain(true)
            require(muxerStarted && destination.length() > 0) { "H.264 encoder produced no video" }
        } finally {
            if (started) runCatching { codec.stop() }
            runCatching { codec.release() }
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
        }
    }

    private fun transcodeAudioToAac(
        source: File,
        destination: File,
        volume: Float,
        sourceStartUs: Long,
        maxDurationUs: Long
    ): Boolean {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var decoderStarted = false
        var encoderStarted = false
        var muxerStarted = false
        try {
            extractor.setDataSource(source.absolutePath)
            val sourceTrack = findTrack(extractor, "audio/") ?: return false
            val sourceFormat = extractor.getTrackFormat(sourceTrack)
            val sourceMime = sourceFormat.getString(MediaFormat.KEY_MIME) ?: return false
            val sampleRate = sourceFormat.getIntegerOr(MediaFormat.KEY_SAMPLE_RATE, 44_100).coerceIn(8_000, 192_000)
            val channels = sourceFormat.getIntegerOr(MediaFormat.KEY_CHANNEL_COUNT, 2).coerceIn(1, 8)
            extractor.selectTrack(sourceTrack)
            extractor.seekTo(sourceStartUs.coerceAtLeast(0L), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            decoder = MediaCodec.createDecoderByType(sourceMime)
            decoder.configure(sourceFormat, null, null, 0)
            decoder.start()
            decoderStarted = true

            val encoderFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, (96_000 * channels).coerceIn(96_000, 256_000))
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
            }
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            encoder.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            encoderStarted = true

            muxer = MediaMuxer(destination.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val decoderInfo = MediaCodec.BufferInfo()
            val encoderInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var decoderDone = false
            var encoderEosQueued = false
            var encoderDone = false
            var outputTrack = -1
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
            val sourceEndUs = sourceStartUs.coerceAtLeast(0L) + maxDurationUs.coerceAtLeast(1L)

            while (!encoderDone) {
                if (!inputDone) {
                    val inputIndex = decoder.dequeueInputBuffer(5_000L)
                    if (inputIndex >= 0) {
                        val input = decoder.getInputBuffer(inputIndex) ?: error("Audio decoder input unavailable")
                        input.clear()
                        val sampleTime = extractor.sampleTime
                        val size = if (sampleTime < 0L || sampleTime >= sourceEndUs) -1 else extractor.readSampleData(input, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(inputIndex, 0, 0, max(sourceStartUs, sampleTime), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val sampleFlags = extractor.sampleFlags
                  require(sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED == 0) { "Encrypted audio is not supported" }
                  val codecFlags = if (sampleFlags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME != 0) {
                      MediaCodec.BUFFER_FLAG_PARTIAL_FRAME
                  } else 0
                  decoder.queueInputBuffer(inputIndex, 0, size, sampleTime.coerceAtLeast(0L), codecFlags)
                            extractor.advance()
                        }
                    }
                }

                if (!decoderDone) {
                    when (val outputIndex = decoder.dequeueOutputBuffer(decoderInfo, 5_000L)) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            pcmEncoding = decoder.outputFormat.getIntegerOr(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                        }
                        MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                        else -> if (outputIndex >= 0) {
                            val isEos = decoderInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            val presentationUs = decoderInfo.presentationTimeUs
                            val buffer = decoder.getOutputBuffer(outputIndex)
                            if (buffer != null && decoderInfo.size > 0 && presentationUs >= sourceStartUs && presentationUs < sourceEndUs) {
                                val pcm16 = pcmTo16(buffer, decoderInfo, pcmEncoding, volume)
                                queuePcmToAac(
                                    encoder = encoder,
                                    bytes = pcm16,
                                    basePtsUs = (presentationUs - sourceStartUs).coerceAtLeast(0L),
                                    sampleRate = sampleRate,
                                    channels = channels
                                )
                            }
                            decoder.releaseOutputBuffer(outputIndex, false)
                            if (isEos || presentationUs >= sourceEndUs) decoderDone = true
                        }
                    }
                }

                if (decoderDone && !encoderEosQueued) {
                    var attempts = 0
                    while (!encoderEosQueued) {
                        val inputIndex = encoder.dequeueInputBuffer(5_000L)
                        if (inputIndex >= 0) {
                            encoder.queueInputBuffer(inputIndex, 0, 0, maxDurationUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            encoderEosQueued = true
                        } else if (++attempts > 200) error("AAC encoder could not finish")
                        drainAacEncoder(encoder, muxer, encoderInfo, onFormat = { format ->
                            if (!muxerStarted) {
                                outputTrack = muxer.addTrack(format)
                                muxer.start()
                                muxerStarted = true
                            }
                        }, outputTrackProvider = { outputTrack }) { encoderDone = true }
                    }
                }

                drainAacEncoder(encoder, muxer, encoderInfo, onFormat = { format ->
                    if (!muxerStarted) {
                        outputTrack = muxer.addTrack(format)
                        muxer.start()
                        muxerStarted = true
                    }
                }, outputTrackProvider = { outputTrack }) { encoderDone = true }
            }
            return muxerStarted && destination.length() > 0
        } finally {
            extractor.release()
            if (decoderStarted) runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            if (encoderStarted) runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
        }
    }

    private fun queuePcmToAac(
        encoder: MediaCodec,
        bytes: ByteArray,
        basePtsUs: Long,
        sampleRate: Int,
        channels: Int
    ) {
        var offset = 0
        val bytesPerFrame = (channels * 2).coerceAtLeast(2)
        while (offset < bytes.size) {
            var attempts = 0
            var queued = false
            while (!queued) {
                val inputIndex = encoder.dequeueInputBuffer(5_000L)
                if (inputIndex >= 0) {
                    val input = encoder.getInputBuffer(inputIndex) ?: error("AAC encoder input unavailable")
                    input.clear()
                    var count = min(input.capacity(), bytes.size - offset)
                    count -= count % bytesPerFrame
                    if (count <= 0) error("AAC encoder input buffer is too small")
                    input.put(bytes, offset, count)
                    val consumedFrames = offset / bytesPerFrame
                    val pts = basePtsUs + consumedFrames * 1_000_000L / sampleRate
                    encoder.queueInputBuffer(inputIndex, 0, count, pts, 0)
                    offset += count
                    queued = true
                } else if (++attempts > 200) error("AAC encoder stopped accepting audio")
            }
        }
    }

    private fun drainAacEncoder(
        encoder: MediaCodec,
        muxer: MediaMuxer,
        info: MediaCodec.BufferInfo,
        onFormat: (MediaFormat) -> Unit,
        outputTrackProvider: () -> Int,
        onEos: () -> Unit
    ) {
        while (true) {
            when (val outputIndex = encoder.dequeueOutputBuffer(info, 0L)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> return
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> onFormat(encoder.outputFormat)
                else -> if (outputIndex >= 0) {
                    val buffer = encoder.getOutputBuffer(outputIndex)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (buffer != null && info.size > 0 && outputTrackProvider() >= 0) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        muxer.writeSampleData(outputTrackProvider(), buffer, info)
                    }
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    encoder.releaseOutputBuffer(outputIndex, false)
                    if (eos) {
                        onEos()
                        return
                    }
                }
            }
        }
    }

    private fun pcmTo16(buffer: ByteBuffer, info: MediaCodec.BufferInfo, encoding: Int, volume: Float): ByteArray {
        val source = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        source.position(info.offset)
        source.limit(info.offset + info.size)
        val gain = volume.coerceIn(0f, 1f)
        return when (encoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> {
                val floats = source.slice().order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                val out = ByteBuffer.allocate(floats.remaining() * 2).order(ByteOrder.LITTLE_ENDIAN)
                while (floats.hasRemaining()) {
                    val sample = (floats.get().coerceIn(-1f, 1f) * gain * Short.MAX_VALUE).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    out.putShort(sample.toShort())
                }
                out.array()
            }
            AudioFormat.ENCODING_PCM_8BIT -> {
                val out = ByteBuffer.allocate(source.remaining() * 2).order(ByteOrder.LITTLE_ENDIAN)
                while (source.hasRemaining()) {
                    val centered = (source.get().toInt() and 0xFF) - 128
                    val sample = (centered * 256f * gain).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    out.putShort(sample.toShort())
                }
                out.array()
            }
            else -> {
                val shorts = source.slice().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                val out = ByteBuffer.allocate(shorts.remaining() * 2).order(ByteOrder.LITTLE_ENDIAN)
                while (shorts.hasRemaining()) {
                    val sample = (shorts.get().toInt() * gain).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    out.putShort(sample.toShort())
                }
                out.array()
            }
        }
    }

    private fun muxVideoAndAac(
        context: Context,
        projectDurationUs: Long,
        videoFile: File,
        audioFile: File,
        audioOffsetUs: Long,
        destination: Uri
    ) {
        val videoExtractor = MediaExtractor()
        val audioExtractor = MediaExtractor()
        val pfd = context.contentResolver.openFileDescriptor(destination, "rw") ?: error("Unable to create MP4")
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        try {
            videoExtractor.setDataSource(videoFile.absolutePath)
            audioExtractor.setDataSource(audioFile.absolutePath)
            val videoSourceTrack = findTrack(videoExtractor, "video/") ?: error("Encoded video contains no video track")
            val audioSourceTrack = findTrack(audioExtractor, "audio/") ?: error("Transcoded audio contains no AAC track")
            videoExtractor.selectTrack(videoSourceTrack)
            audioExtractor.selectTrack(audioSourceTrack)

            muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val videoOutTrack = muxer.addTrack(videoExtractor.getTrackFormat(videoSourceTrack))
            val audioOutTrack = muxer.addTrack(audioExtractor.getTrackFormat(audioSourceTrack))
            muxer.start()
            muxerStarted = true

            val maxInput = max(
                videoExtractor.getTrackFormat(videoSourceTrack).getIntegerOr(MediaFormat.KEY_MAX_INPUT_SIZE, 4 * 1024 * 1024),
                audioExtractor.getTrackFormat(audioSourceTrack).getIntegerOr(MediaFormat.KEY_MAX_INPUT_SIZE, 256 * 1024)
            ).coerceIn(256 * 1024, 16 * 1024 * 1024)
            val buffer = ByteBuffer.allocateDirect(maxInput)
            val info = MediaCodec.BufferInfo()
            copyTrack(videoExtractor, muxer, videoOutTrack, buffer, info, 0L, projectDurationUs)
            audioExtractor.seekTo(0L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            copyTrack(audioExtractor, muxer, audioOutTrack, buffer, info, audioOffsetUs.coerceAtLeast(0L), projectDurationUs)
        } finally {
            videoExtractor.release()
            audioExtractor.release()
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            pfd.close()
        }
    }

    private fun copyFileToUri(context: Context, file: File, uri: Uri) {
        context.contentResolver.openOutputStream(uri, "w")?.use { output ->
            file.inputStream().use { input -> input.copyTo(output) }
        } ?: error("Unable to create MP4")
    }

    private fun findTrack(extractor: MediaExtractor, prefix: String): Int? {
        for (index in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime.startsWith(prefix)) return index
        }
        return null
    }

    private fun copyTrack(
        extractor: MediaExtractor,
        muxer: MediaMuxer,
        outputTrack: Int,
        buffer: ByteBuffer,
        info: MediaCodec.BufferInfo,
        offsetUs: Long,
        endUs: Long
    ) {
        while (true) {
            buffer.clear()
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            require(size <= buffer.capacity()) { "Encoded media sample is too large" }
            val outputPts = extractor.sampleTime + offsetUs
            if (outputPts >= endUs) break
            if (outputPts >= 0L) {
                val sampleFlags = extractor.sampleFlags
      require(sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED == 0) { "Encrypted media samples are not supported" }
      var codecFlags = 0
      if (sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
          codecFlags = codecFlags or MediaCodec.BUFFER_FLAG_KEY_FRAME
      }
      if (sampleFlags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME != 0) {
          codecFlags = codecFlags or MediaCodec.BUFFER_FLAG_PARTIAL_FRAME
      }
      info.set(0, size, outputPts, codecFlags)
                muxer.writeSampleData(outputTrack, buffer, info)
            }
            if (!extractor.advance()) break
        }
    }

    private fun chooseYuvFormat(formats: IntArray): Int {
        val preferred = listOf(
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
        )
        return preferred.firstOrNull { it in formats }
            ?: error("This device's H.264 encoder does not expose a compatible YUV420 input format")
    }

    private fun bitmapToYuv420(bitmap: Bitmap, format: Int): ByteArray {
        val width = bitmap.width
        val height = bitmap.height
        require(width % 2 == 0 && height % 2 == 0) { "H.264 frame dimensions must be even" }
        val argb = IntArray(width * height)
        bitmap.getPixels(argb, 0, width, 0, 0, width, height)
        val frameSize = width * height
        val out = ByteArray(frameSize * 3 / 2)
        var yIndex = 0
        var uIndex = frameSize
        var vIndex = frameSize + frameSize / 4
        var uvIndex = frameSize
        val semiPlanar = format == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar

        for (y in 0 until height) {
            for (x in 0 until width) {
                val color = argb[y * width + x]
                val r = color shr 16 and 0xFF
                val g = color shr 8 and 0xFF
                val b = color and 0xFF
                val yy = (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).coerceIn(0, 255)
                val uu = (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).coerceIn(0, 255)
                val vv = (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).coerceIn(0, 255)
                out[yIndex++] = yy.toByte()
                if (y % 2 == 0 && x % 2 == 0) {
                    if (semiPlanar) {
                        out[uvIndex++] = uu.toByte()
                        out[uvIndex++] = vv.toByte()
                    } else {
                        out[uIndex++] = uu.toByte()
                        out[vIndex++] = vv.toByte()
                    }
                }
            }
        }
        return out
    }

    private fun bitmapToGifPixels(bitmap: Bitmap): Array<IntArray> {
        val raw = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(raw, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return Array(bitmap.width) { x -> IntArray(bitmap.height) { y -> raw[y * bitmap.width + x] } }
    }

    private fun even(value: Int): Int = (value and 0xFFFFFFFE.toInt()).coerceAtLeast(64)
    private fun MediaFormat.getIntegerOr(key: String, fallback: Int): Int = runCatching { getInteger(key) }.getOrDefault(fallback)
}
