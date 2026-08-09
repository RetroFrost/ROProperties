package com.frameflow.app

import android.media.MediaMetadataRetriever
import java.io.File
import kotlin.math.max

object AudioAnalysisTools {
    data class Analysis(val markers: List<AudioMarkerState>, val waveform: FloatArray, val durationMs: Int)

    fun analyze(file: File, clip: AudioClipState, buckets: Int = 480): Analysis {
        val waveform = AudioWaveformTools.decodeWaveform(file, buckets.coerceIn(160, 960))
        val duration = mediaDurationMs(file).coerceAtLeast(clip.sourceDurationMs).coerceAtLeast(1)
        if (waveform.isEmpty()) return Analysis(emptyList(), waveform, duration)
        val bucketMs = duration.toFloat() / waveform.size
        val average = waveform.average().toFloat()
        val peakThreshold = max(.12f, average * 1.65f)
        val speechThreshold = max(.055f, average * .72f)
        val markers = mutableListOf<AudioMarkerState>()

        var lastBeatMs = Int.MIN_VALUE
        for (i in 1 until waveform.lastIndex) {
            val value = waveform[i]
            if (value >= peakThreshold && value >= waveform[i - 1] && value > waveform[i + 1]) {
                val sourceTime = (i * bucketMs).toInt()
                if (sourceTime in clip.trimStartMs..clip.trimEndMs && sourceTime - lastBeatMs >= 140) {
                    val timelineTime = clip.startMs + sourceTime - clip.trimStartMs
                    markers += AudioMarkerState(timeMs = timelineTime, type = AudioMarkerType.Beat, label = "Beat")
                    lastBeatMs = sourceTime
                }
            }
        }

        var speaking = false
        var speechStartSource = 0
        var quietBuckets = 0
        for (i in waveform.indices) {
            val sourceTime = (i * bucketMs).toInt()
            if (sourceTime < clip.trimStartMs || sourceTime > clip.trimEndMs) continue
            val active = waveform[i] >= speechThreshold
            if (!speaking && active) {
                speaking = true
                speechStartSource = sourceTime
                quietBuckets = 0
            } else if (speaking) {
                if (active) quietBuckets = 0 else quietBuckets++
                if (quietBuckets >= 3 || i == waveform.lastIndex) {
                    val endSource = (sourceTime - quietBuckets * bucketMs).toInt().coerceAtLeast(speechStartSource)
                    if (endSource - speechStartSource >= 90) {
                        markers += AudioMarkerState(
                            timeMs = clip.startMs + speechStartSource - clip.trimStartMs,
                            type = AudioMarkerType.SpeechStart,
                            label = "Speech"
                        )
                        markers += AudioMarkerState(
                            timeMs = clip.startMs + endSource - clip.trimStartMs,
                            type = AudioMarkerType.SpeechEnd,
                            label = "Speech end"
                        )
                    }
                    speaking = false
                    quietBuckets = 0
                }
            }
        }
        return Analysis(markers.sortedBy { it.timeMs }, waveform, duration)
    }

    fun sampleOpenness(waveform: FloatArray, sourceDurationMs: Int, sourceTimeMs: Int): Float {
        if (waveform.isEmpty() || sourceDurationMs <= 0) return 0f
        val index = ((sourceTimeMs.toFloat() / sourceDurationMs) * waveform.size).toInt().coerceIn(waveform.indices)
        val raw = waveform[index].coerceIn(0f, 1f)
        return when {
            raw < .04f -> 0f
            raw < .11f -> .28f
            raw < .24f -> .55f
            raw < .48f -> .78f
            else -> 1f
        }
    }

    private fun mediaDurationMs(file: File): Int {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.coerceIn(1L, 86_400_000L)?.toInt() ?: 0
        } finally { runCatching { retriever.release() } }
    }
}
