package com.frameflow.app

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import com.squareup.gifencoder.FloydSteinbergDitherer
import com.squareup.gifencoder.GifEncoder
import com.squareup.gifencoder.ImageOptions
import com.squareup.gifencoder.KMeansQuantizer
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.roundToInt

object MediaExporter {
    fun exportGif(context: Context, project: ProjectState, uri: Uri) {
        val width = project.canvasWidth
        val height = project.canvasHeight
        context.contentResolver.openOutputStream(uri, "w")?.use { output ->
            val encoder = GifEncoder(output, width, height, 0)
            project.frames.forEachIndexed { index, frame ->
                val bitmap = FrameRenderer.render(project, index)
                val options = ImageOptions()
                    .setDelay(frame.durationMs.toLong().coerceAtLeast(20L), TimeUnit.MILLISECONDS)
                    .setColorQuantizer(KMeansQuantizer.INSTANCE)
                    .setDitherer(FloydSteinbergDitherer.INSTANCE)
                encoder.addImage(bitmapToGifPixels(bitmap), options)
                bitmap.recycle()
            }
            encoder.finishEncoding()
        } ?: error("Unable to create GIF")
    }

    fun exportMp4(
        context: Context,
        project: ProjectState,
        uri: Uri,
        fps: Int = 30,
        bitrate: Int = 6_000_000
    ) {
        val width = project.canvasWidth and 0xFFFFFFFE.toInt()
        val height = project.canvasHeight and 0xFFFFFFFE.toInt()
        require(width >= 64 && height >= 64) { "Canvas is too small for MP4" }

        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val capabilities = codec.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val colorFormat = chooseYuvFormat(capabilities.colorFormats)
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate.coerceAtLeast(width * height * 2))
            setInteger(MediaFormat.KEY_FRAME_RATE, fps.coerceIn(12, 60))
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()

        val pfd = context.contentResolver.openFileDescriptor(uri, "rw")
            ?: error("Unable to create MP4")
        val muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerStarted = false
        var trackIndex = -1
        val info = MediaCodec.BufferInfo()
        var frameNumber = 0L
        val frameRate = fps.coerceIn(12, 60)

        fun drain(end: Boolean) {
            while (true) {
                val status = codec.dequeueOutputBuffer(info, if (end) 10_000L else 0L)
                when {
                    status == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!end) return
                    status == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        check(!muxerStarted) { "Encoder format changed twice" }
                        trackIndex = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                    status >= 0 -> {
                        val buffer = codec.getOutputBuffer(status)
                        if (buffer != null && info.size > 0 && muxerStarted) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            muxer.writeSampleData(trackIndex, buffer, info)
                        }
                        val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(status, false)
                        if (eos) return
                    }
                }
            }
        }

        try {
            project.frames.forEachIndexed { index, frame ->
                val source = FrameRenderer.render(project, index)
                val bitmap = if (source.width == width && source.height == height) source
                else Bitmap.createScaledBitmap(source, width, height, true).also { source.recycle() }
                val yuv = bitmapToYuv420(bitmap, colorFormat)
                bitmap.recycle()
                val repeats = max(1, (frame.durationMs / 1000f * frameRate).roundToInt())
                repeat(repeats) {
                    while (true) {
                        val inputIndex = codec.dequeueInputBuffer(10_000L)
                        if (inputIndex >= 0) {
                            val input = codec.getInputBuffer(inputIndex) ?: error("Encoder input buffer unavailable")
                            input.clear()
                            check(input.capacity() >= yuv.size) { "Encoder input buffer is too small" }
                            input.put(yuv)
                            val ptsUs = frameNumber * 1_000_000L / frameRate
                            codec.queueInputBuffer(inputIndex, 0, yuv.size, ptsUs, 0)
                            frameNumber++
                            break
                        }
                        drain(false)
                    }
                    drain(false)
                }
            }

            while (true) {
                val inputIndex = codec.dequeueInputBuffer(10_000L)
                if (inputIndex >= 0) {
                    val ptsUs = frameNumber * 1_000_000L / frameRate
                    codec.queueInputBuffer(inputIndex, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    break
                }
                drain(false)
            }
            drain(true)
        } finally {
            runCatching { codec.stop() }
            codec.release()
            if (muxerStarted) runCatching { muxer.stop() }
            muxer.release()
            pfd.close()
        }
    }

    private fun chooseYuvFormat(formats: IntArray): Int {
        val preferred = listOf(
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
        )
        return preferred.firstOrNull { it in formats }
            ?: error("This device has no byte-buffer YUV420 H.264 encoder")
    }

    private fun bitmapToYuv420(bitmap: Bitmap, format: Int): ByteArray {
        val width = bitmap.width
        val height = bitmap.height
        val argb = IntArray(width * height)
        bitmap.getPixels(argb, 0, width, 0, 0, width, height)
        val frameSize = width * height
        val out = ByteArray(frameSize * 3 / 2)
        var yIndex = 0
        var uIndex = frameSize
        var vIndex = frameSize + frameSize / 4
        var uvIndex = frameSize

        for (y in 0 until height) {
            for (x in 0 until width) {
                val color = argb[y * width + x]
                val r = color shr 16 and 0xFF
                val g = color shr 8 and 0xFF
                val b = color and 0xFF
                val yy = ((66 * r + 129 * g + 25 * b + 128 shr 8) + 16).coerceIn(0, 255)
                val uu = ((-38 * r - 74 * g + 112 * b + 128 shr 8) + 128).coerceIn(0, 255)
                val vv = ((112 * r - 94 * g - 18 * b + 128 shr 8) + 128).coerceIn(0, 255)
                out[yIndex++] = yy.toByte()
                if (y % 2 == 0 && x % 2 == 0) {
                    if (format == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar) {
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
        return Array(bitmap.width) { x ->
            IntArray(bitmap.height) { y -> raw[y * bitmap.width + x] }
        }
    }
}
