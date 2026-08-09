package com.frameflow.app

import android.graphics.Color
import java.io.File
import kotlin.math.max

object LipSyncTools {
    data class Result(val frames: Int, val durationMs: Int)

    /**
     * Generates a local, editable 8-fps mouth-cue sequence from decoded audio
     * energy. The current frame is the visual base; generated frames are normal
     * Frameflow frames and can be edited/removed/retimed afterwards.
     */
    fun generate(editor: EditorState, clip: AudioClipState, file: File, cuesPerSecond: Int = 8): Result {
        editor.ensureIndices()
        val trimmedDuration = clip.sourceTrimmedDurationMs.coerceAtLeast(1)
        require(trimmedDuration <= 120_000) { "Automatic lip-sync is limited to 2 minutes per clip; trim the clip first" }
        val analysis = AudioAnalysisTools.analyze(file, clip, buckets = (trimmedDuration / 20).coerceIn(240, 960))
        val fps = cuesPerSecond.coerceIn(4, 12)
        val stepMs = (1000f / fps).toInt().coerceAtLeast(50)
        val steps = ((trimmedDuration + stepMs - 1) / stepMs).coerceIn(1, 1200)
        val project = editor.project
        val source = editor.frame.cloneFrame()
        val insertion = editor.frameIndex + 1
        val frames = ArrayList<FrameState>(steps)

        repeat(steps) { index ->
            val elapsed = index * stepMs
            val sourceTime = (clip.trimStartMs + elapsed).coerceAtMost(clip.trimEndMs - 1)
            val openness = AudioAnalysisTools.sampleOpenness(analysis.waveform, analysis.durationMs, sourceTime)
            val frame = source.cloneFrame().apply {
                durationMs = minOf(stepMs, trimmedDuration - elapsed).coerceAtLeast(50)
                label = "Lip ${index + 1}"
            }
            frame.layers.removeAll { it.name == "Lip sync · Mouth" }
            frame.layers.add(0, mouthLayer(project, frame, openness))
            frames += frame
        }
        project.frames.addAll(insertion, frames)
        editor.frameIndex = (insertion + frames.lastIndex).coerceIn(project.frames.indices)
        editor.layerIndex = 0
        editor.clearSelection()
        project.touch()
        return Result(frames.size, trimmedDuration)
    }

    private fun mouthLayer(project: ProjectState, frame: FrameState, openness: Float): LayerState {
        val body = frame.layers.firstOrNull { it.part == Part.Body && it.hasRaster }
            ?: frame.layers.firstOrNull { it.hasRaster && !it.isRigSource }
        val bounds = body?.let { SmartTransformTools.alphaBounds(project, it) }
            ?: android.graphics.RectF(project.canvasWidth * .28f, project.canvasHeight * .18f, project.canvasWidth * .72f, project.canvasHeight * .74f)
        val cx = bounds.centerX()
        val cy = bounds.top + bounds.height() * .52f
        val halfWidth = bounds.width() * .12f
        val strokeWidth = max(2f, minOf(project.canvasWidth, project.canvasHeight) * .006f)
        val ink = BrushPreset("Ink 1", "Ink", strokeWidth, 1f)
        val layer = LayerState("Lip sync · Mouth", Part.Mouth, folderName = "Face")
        if (openness <= .05f) {
            layer.strokes += BrushEngine.createStroke(ink, listOf(CanvasPoint(cx - halfWidth, cy), CanvasPoint(cx + halfWidth, cy)), Color.BLACK, false)
            return layer
        }
        val rx = halfWidth
        val ry = halfWidth * (.18f + openness * .72f)
        val points = List(49) { i ->
            val angle = Math.PI * 2.0 * i / 48.0
            CanvasPoint(cx + kotlin.math.cos(angle).toFloat() * rx, cy + kotlin.math.sin(angle).toFloat() * ry)
        }
        layer.strokes += BrushEngine.createStroke(ink, points, Color.BLACK, false)
        return layer
    }
}
