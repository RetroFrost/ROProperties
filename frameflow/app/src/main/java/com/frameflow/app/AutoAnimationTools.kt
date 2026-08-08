package com.frameflow.app

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

object AutoAnimationTools {
    data class Result(val generatedFrames: Int, val description: String)

    fun apply(editor: EditorState, command: String): Result {
        editor.ensureIndices()
        val text = command.trim().lowercase()
        require(text.isNotBlank()) { "Type what should happen" }

        val bounce = "bounce" in text || "jump" in text
        val shake = "shake" in text || "wobble" in text
        val spin = "spin" in text || "rotate" in text
        val zoomIn = "zoom in" in text || "closer" in text
        val zoomOut = "zoom out" in text || "farther" in text || "further" in text
        val walkLeft = ("walk" in text || "move" in text || "slide" in text) && "left" in text
        val walkRight = ("walk" in text || "move" in text || "slide" in text) && "right" in text
        val fadeIn = "fade in" in text
        val fadeOut = "fade out" in text || ("fade" in text && !fadeIn)
        val holdOnly = ("hold" in text || "stay" in text) && !(bounce || shake || spin || zoomIn || zoomOut || walkLeft || walkRight || fadeIn || fadeOut)
        val loop = "loop" in text || "seamless" in text

        val seconds = Regex("(-?\\d+(?:[.,]\\d+)?)\\s*(?:s|sec|second)")
            .find(text)?.groupValues?.getOrNull(1)?.replace(',', '.')?.toFloatOrNull()?.coerceIn(.1f, 20f)
            ?: if (holdOnly) 1f else 1.2f

        if (holdOnly) {
            editor.frame.durationMs = (seconds * 1000f).roundToInt().coerceIn(50, 120000)
            editor.project.touch()
            return Result(0, "Held the current frame for ${"%.2f".format(seconds)} s")
        }

        require(bounce || shake || spin || zoomIn || zoomOut || walkLeft || walkRight || fadeIn || fadeOut) {
            "I can currently animate bounce/jump, walk or move left/right, zoom, shake, spin, fade, hold and loops"
        }

        val project = editor.project
        val source = editor.frame.cloneFrame()
        val steps = ((seconds * 10f).roundToInt()).coerceIn(4, 80)
        val frameDuration = ((seconds * 1000f) / steps).roundToInt().coerceIn(50, 1000)
        val insertion = editor.frameIndex + 1
        val generated = ArrayList<FrameState>(steps)
        val amountX = project.canvasWidth * .22f
        val amountY = project.canvasHeight * .13f

        for (step in 1..steps) {
            val t = step / steps.toFloat()
            val frame = source.cloneFrame().apply {
                durationMs = frameDuration
                label = "Auto ${step}/${steps}"
            }
            if (bounce) {
                val y = -abs(sin(t * PI).toFloat()) * amountY
                moveForeground(frame, 0f, y)
                alternateLegs(frame, sin(t * PI * 4).toFloat() * 24f)
            }
            if (walkLeft || walkRight) {
                val direction = if (walkLeft) -1f else 1f
                moveForeground(frame, direction * amountX * t, 0f)
                alternateLegs(frame, sin(t * PI * 4).toFloat() * 30f)
                alternateArms(frame, sin(t * PI * 4).toFloat() * 22f)
            }
            if (shake) {
                frame.cameraX += sin(t * PI * 10).toFloat() * project.canvasWidth * .018f
                frame.cameraRotation += sin(t * PI * 8).toFloat() * 2.3f
            }
            if (spin) frame.cameraRotation += 360f * t
            if (zoomIn) frame.cameraZoom = (source.cameraZoom * (1f + .45f * t)).coerceIn(.05f, 20f)
            if (zoomOut) frame.cameraZoom = (source.cameraZoom * (1f - .32f * t)).coerceIn(.05f, 20f)
            if (fadeOut || fadeIn) {
                val opacity = if (fadeOut) 1f - t else t
                frame.layers.filter { it.part != Part.Background }.forEach { it.opacity = (it.opacity * opacity).coerceIn(0f, 1f) }
            }
            generated += frame
        }

        if (loop && generated.isNotEmpty()) {
            generated[generated.lastIndex] = source.cloneFrame().apply {
                durationMs = frameDuration
                label = "Auto loop close"
            }
        }
        project.frames.addAll(insertion, generated)
        editor.frameIndex = (insertion + generated.lastIndex).coerceIn(project.frames.indices)
        editor.clearSelection()
        project.touch()

        val actions = buildList {
            if (bounce) add("bounce")
            if (walkLeft) add("walk left")
            if (walkRight) add("walk right")
            if (zoomIn) add("zoom in")
            if (zoomOut) add("zoom out")
            if (shake) add("shake")
            if (spin) add("spin")
            if (fadeIn) add("fade in")
            if (fadeOut) add("fade out")
            if (loop) add("loop")
        }
        return Result(generated.size, "Generated ${generated.size} editable frames: ${actions.joinToString()}")
    }

    private fun moveForeground(frame: FrameState, dx: Float, dy: Float) {
        frame.layers.filter { it.part != Part.Background }.forEach {
            it.offsetX += dx
            it.offsetY += dy
        }
    }

    private fun alternateLegs(frame: FrameState, angle: Float) {
        frame.layers.firstOrNull { it.part == Part.LeftLeg }?.rotationDeg = angle
        frame.layers.firstOrNull { it.part == Part.RightLeg }?.rotationDeg = -angle
    }

    private fun alternateArms(frame: FrameState, angle: Float) {
        frame.layers.firstOrNull { it.part == Part.LeftArm }?.rotationDeg = -angle
        frame.layers.firstOrNull { it.part == Part.RightArm }?.rotationDeg = angle
    }
}
