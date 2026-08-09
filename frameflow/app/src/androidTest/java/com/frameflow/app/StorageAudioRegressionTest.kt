package com.frameflow.app

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class StorageAudioRegressionTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val createdIds = mutableListOf<String>()

    @After
    fun cleanup() {
        val repository = ProjectRepository(context)
        createdIds.forEach(repository::delete)
    }

    @Test
    fun saveLoadAndDuplicateKeepFoldersClippingAndMediaTimeline() {
        val repository = ProjectRepository(context)
        val project = repository.createProject("Audio persistence")
        createdIds += project.id
        project.frames[0].layers[0].folderName = "Character"
        project.frames[0].layers[0].clipToBelow = true
        repository.save(project)

        val wav1 = writeWav("tone-one.wav", 440.0, 900)
        val wav2 = writeWav("tone-two.wav", 660.0, 700)
        val audio = AudioTimelineRepository(context)
        val clip1 = audio.importClip(project, Uri.fromFile(wav1), 0)
        val clip2 = audio.importClip(project, Uri.fromFile(wav2), 350)
        audio.update(project) { state ->
            state.clips.first { it.id == clip1.id }.apply {
                trimStartMs = 100
                trimEndMs = 700
                volume = .55f
            }
            state.clips.first { it.id == clip2.id }.muted = true
            state.markers += AudioMarkerState(timeMs = 250, type = AudioMarkerType.Manual, label = "Cue")
        }
        repository.save(project)

        val loaded = repository.load(project.id)
        assertNotNull(loaded)
        assertEquals("Character", loaded!!.frames[0].layers[0].folderName)
        assertTrue(loaded.frames[0].layers[0].clipToBelow)
        val loadedTimeline = audio.load(loaded)
        assertEquals(2, loadedTimeline.clips.size)
        assertEquals(1, loadedTimeline.markers.size)
        assertEquals(.55f, loadedTimeline.clips.first { it.id == clip1.id }.volume, .01f)

        val duplicate = repository.duplicate(loaded)
        createdIds += duplicate.id
        val duplicateTimeline = AudioTimelineRepository(context).load(duplicate)
        assertEquals(2, duplicateTimeline.clips.size)
        duplicateTimeline.clips.forEach { clip -> assertNotNull(AudioTimelineRepository(context).file(duplicate.id, clip)) }
    }

    @Test
    fun waveformAndMarkerAnalysisUseActualDecodedAudio() {
        val repository = ProjectRepository(context)
        val project = repository.createProject("Analysis")
        createdIds += project.id
        val wav = writeWav("analysis.wav", 220.0, 1200, pulse = true)
        val audio = AudioTimelineRepository(context)
        val clip = audio.importClip(project, Uri.fromFile(wav), 100)
        val file = audio.file(project.id, clip)!!
        val analysis = AudioAnalysisTools.analyze(file, clip, 240)
        assertTrue(analysis.waveform.any { it > .05f })
        assertTrue(analysis.durationMs >= 1000)
        assertTrue(analysis.markers.all { it.timeMs >= clip.startMs })
    }

    private fun writeWav(name: String, frequency: Double, durationMs: Int, pulse: Boolean = false): File {
        val sampleRate = 16_000
        val samples = sampleRate * durationMs / 1000
        val pcm = ByteArray(samples * 2)
        val pcmBuffer = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        repeat(samples) { index ->
            val time = index.toDouble() / sampleRate
            val envelope = if (!pulse) .45 else if ((index / (sampleRate / 5)) % 2 == 0) .75 else .03
            val sample = (sin(2.0 * PI * frequency * time) * Short.MAX_VALUE * envelope).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            pcmBuffer.putShort(sample.toShort())
        }
        val file = File(context.cacheDir, name)
        val dataSize = pcm.size
        val riffSize = 36 + dataSize
        file.outputStream().use { out ->
            fun ascii(value: String) = out.write(value.toByteArray(Charsets.US_ASCII))
            fun leInt(value: Int) = out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array())
            fun leShort(value: Int) = out.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort()).array())
            ascii("RIFF"); leInt(riffSize); ascii("WAVE")
            ascii("fmt "); leInt(16); leShort(1); leShort(1); leInt(sampleRate); leInt(sampleRate * 2); leShort(2); leShort(16)
            ascii("data"); leInt(dataSize); out.write(pcm)
        }
        return file
    }
}
