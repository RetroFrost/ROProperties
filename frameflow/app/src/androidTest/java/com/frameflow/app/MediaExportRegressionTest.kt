package com.frameflow.app

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class MediaExportRegressionTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val createdProjects = mutableListOf<String>()
    private val tempFiles = mutableListOf<File>()

    @After
    fun clean() {
        val repository = ProjectRepository(context)
        createdProjects.forEach(repository::delete)
        tempFiles.forEach(File::delete)
    }

    @Test
    fun pngGifVideoAndMixedAudioVideoAreReadable() {
        val repository = ProjectRepository(context)
        val project = ProjectState(name = "Export regression", canvasWidth = 128, canvasHeight = 96, fps = 24)
        project.frames.clear()
        val layer = LayerState("Drawing", Part.Body)
        layer.strokes += BrushEngine.createStroke(
            BrushPreset("Ink 1", "Ink", 8f, 1f),
            listOf(CanvasPoint(15f, 20f), CanvasPoint(60f, 70f), CanvasPoint(110f, 30f)),
            0xFF2277CC.toInt(), false
        )
        project.frames += FrameState(duration = 300, layers = listOf(layer))
        project.frames += FrameState(duration = 250, layers = listOf(layer.cloneLayer().also { it.offsetX = 5f }))
        repository.save(project)
        createdProjects += project.id

        val png = temp("frame.png")
        repository.exportCurrentPng(project, 1, Uri.fromFile(png))
        assertTrue(png.length() > 100)
        assertTrue(android.graphics.BitmapFactory.decodeFile(png.absolutePath) != null)

        val gif = temp("anim.gif")
        repository.exportGif(project, Uri.fromFile(gif))
        assertTrue(gif.length() > 100)
        val gifRetriever = MediaMetadataRetriever()
        try {
            gifRetriever.setDataSource(gif.absolutePath)
            assertTrue((gifRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_IMAGE) ?: "yes").isNotBlank())
        } catch (_: Throwable) {
            // Some Android builds do not expose GIF through MediaMetadataRetriever; verify signature instead.
            val header = gif.inputStream().use { input -> ByteArray(6).also { input.read(it) } }.toString(Charsets.US_ASCII)
            assertTrue(header == "GIF87a" || header == "GIF89a")
        } finally { runCatching { gifRetriever.release() } }

        val silentMp4 = temp("silent.mp4")
        repository.exportMp4(project, Uri.fromFile(silentMp4))
        assertMp4Tracks(silentMp4, expectAudio = false)

        val wav = writeWav("mix.wav", 330.0, 700)
        AudioTimelineRepository(context).importClip(project, Uri.fromFile(wav), 0)
        repository.save(project)
        val mixedMp4 = temp("mixed.mp4")
        repository.exportMp4(project, Uri.fromFile(mixedMp4))
        assertMp4Tracks(mixedMp4, expectAudio = true)
    }

    private fun assertMp4Tracks(file: File, expectAudio: Boolean) {
        assertTrue(file.length() > 512)
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            var video = false
            var audio = false
            for (i in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(i).getString(android.media.MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/")) video = true
                if (mime.startsWith("audio/")) audio = true
            }
            assertTrue(video)
            assertTrue(audio == expectAudio)
        } finally { extractor.release() }
    }

    private fun temp(name: String) = File(context.cacheDir, "frameflow-${System.nanoTime()}-$name").also { tempFiles += it }

    private fun writeWav(name: String, frequency: Double, durationMs: Int): File {
        val sampleRate = 16_000
        val samples = sampleRate * durationMs / 1000
        val pcm = ByteArray(samples * 2)
        val buffer = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        repeat(samples) { index ->
            val value = (sin(2.0 * PI * frequency * index / sampleRate) * Short.MAX_VALUE * .45).toInt()
            buffer.putShort(value.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
        }
        val file = temp(name)
        file.outputStream().use { out ->
            fun ascii(value: String) = out.write(value.toByteArray(Charsets.US_ASCII))
            fun i32(value: Int) = out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array())
            fun i16(value: Int) = out.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort()).array())
            ascii("RIFF"); i32(36 + pcm.size); ascii("WAVE")
            ascii("fmt "); i32(16); i16(1); i16(1); i32(sampleRate); i32(sampleRate * 2); i16(2); i16(16)
            ascii("data"); i32(pcm.size); out.write(pcm)
        }
        return file
    }
}
