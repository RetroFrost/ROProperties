package com.frameflow.app

import android.media.MediaPlayer
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.max
import kotlin.math.min

object AudioPlaybackTools {
    suspend fun playFrom(project: ProjectState, timelineStartMs: Int, keepPlaying: () -> Boolean) = coroutineScope {
        val context = FrameflowApplication.appContext
        val repository = AudioTimelineRepository(context)
        val timeline = repository.load(project)
        val projectEnd = project.totalDurationMs.coerceAtLeast(0)
        timeline.clips.filter { !it.muted && it.volume > .0001f }.forEach { clip ->
            val file = repository.file(project.id, clip) ?: return@forEach
            val clipTimelineEnd = min(projectEnd, clip.endMs)
            if (clipTimelineEnd <= timelineStartMs) return@forEach
            launch {
                val delayMs = max(0, clip.startMs - timelineStartMs)
                if (delayMs > 0) delay(delayMs.toLong())
                if (!keepPlaying()) return@launch
                val sourcePosition = clip.trimStartMs + max(0, timelineStartMs - clip.startMs)
                if (sourcePosition >= clip.trimEndMs) return@launch
                val remainingFromTrim = clip.trimEndMs - sourcePosition
                val remainingProject = projectEnd - max(timelineStartMs, clip.startMs)
                val playFor = min(remainingFromTrim, remainingProject).coerceAtLeast(0)
                if (playFor <= 0) return@launch
                playOne(file, sourcePosition, playFor, clip.volume.coerceIn(0f, 1f), keepPlaying)
            }
        }
    }

    private suspend fun playOne(file: File, seekMs: Int, durationMs: Int, volume: Float, keepPlaying: () -> Boolean) {
        var player: MediaPlayer? = null
        try {
            val created = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
                setVolume(volume, volume)
                seekTo(seekMs.coerceAtLeast(0))
            }
            player = created
            if (!keepPlaying()) return
            created.start()
            var remaining = durationMs.toLong()
            while (remaining > 0 && keepPlaying()) {
                val slice = min(remaining, 100L)
                delay(slice)
                remaining -= slice
            }
            runCatching { if (created.isPlaying) created.pause() }
        } finally {
            runCatching { player?.stop() }
            runCatching { player?.release() }
        }
    }
}
