package com.frameflow.app

import kotlin.math.min

object SmartAnimationTools {
    fun insertInbetween(project: ProjectState, index: Int): Boolean {
        if (index !in 0 until project.frames.lastIndex) return false
        val a = project.frames[index]
        val b = project.frames[index + 1]
        val middle = a.cloneFrame().apply {
            durationMs = ((a.durationMs + b.durationMs) / 2).coerceAtLeast(50)
            cameraX = (a.cameraX + b.cameraX) / 2f
            cameraY = (a.cameraY + b.cameraY) / 2f
            cameraZoom = (a.cameraZoom + b.cameraZoom) / 2f
            cameraRotation = averageAngle(a.cameraRotation, b.cameraRotation)
            label = "In-between"
        }
        middle.layers.forEachIndexed { layerIndex, layer ->
            val target = b.layers.getOrNull(layerIndex) ?: return@forEachIndexed
            layer.offsetX = (layer.offsetX + target.offsetX) / 2f
            layer.offsetY = (layer.offsetY + target.offsetY) / 2f
            layer.scaleX = (layer.scaleX + target.scaleX) / 2f
            layer.scaleY = (layer.scaleY + target.scaleY) / 2f
            layer.rotationDeg = averageAngle(layer.rotationDeg, target.rotationDeg)
            val targetById = target.strokes.associateBy { it.id }
            for (strokeIndex in layer.strokes.indices) {
                val sourceStroke = layer.strokes[strokeIndex]
                val targetStroke = targetById[sourceStroke.id] ?: continue
                if (sourceStroke.points.size != targetStroke.points.size) continue
                layer.strokes[strokeIndex] = sourceStroke.copy(
                    points = sourceStroke.points.zip(targetStroke.points).map { (p1, p2) ->
                        CanvasPoint((p1.x + p2.x) / 2f, (p1.y + p2.y) / 2f)
                    }
                )
            }
        }
        project.frames.add(index + 1, middle)
        project.touch()
        return true
    }

    fun continueMotion(project: ProjectState, index: Int): Boolean {
        if (index <= 0 || index !in project.frames.indices) return false
        val previous = project.frames[index - 1]
        val current = project.frames[index]
        val next = current.cloneFrame().apply {
            label = "Motion continuation"
            cameraX += current.cameraX - previous.cameraX
            cameraY += current.cameraY - previous.cameraY
            cameraZoom = (current.cameraZoom + (current.cameraZoom - previous.cameraZoom)).coerceAtLeast(.05f)
            cameraRotation += angleDelta(previous.cameraRotation, current.cameraRotation)
        }
        next.layers.forEachIndexed { layerIndex, layer ->
            val prevLayer = previous.layers.getOrNull(layerIndex) ?: return@forEachIndexed
            val curLayer = current.layers.getOrNull(layerIndex) ?: return@forEachIndexed
            layer.offsetX = curLayer.offsetX + (curLayer.offsetX - prevLayer.offsetX)
            layer.offsetY = curLayer.offsetY + (curLayer.offsetY - prevLayer.offsetY)
            layer.scaleX = curLayer.scaleX + (curLayer.scaleX - prevLayer.scaleX)
            layer.scaleY = curLayer.scaleY + (curLayer.scaleY - prevLayer.scaleY)
            layer.rotationDeg = curLayer.rotationDeg + angleDelta(prevLayer.rotationDeg, curLayer.rotationDeg)
            val prevById = prevLayer.strokes.associateBy { it.id }
            val curById = curLayer.strokes.associateBy { it.id }
            for (strokeIndex in layer.strokes.indices) {
                val nextStroke = layer.strokes[strokeIndex]
                val p = prevById[nextStroke.id] ?: continue
                val c = curById[nextStroke.id] ?: continue
                val count = min(p.points.size, c.points.size)
                if (count == 0 || nextStroke.points.size != c.points.size) continue
                layer.strokes[strokeIndex] = nextStroke.copy(
                    points = c.points.mapIndexed { pointIndex, cp ->
                        if (pointIndex >= p.points.size) cp
                        else CanvasPoint(cp.x + (cp.x - p.points[pointIndex].x), cp.y + (cp.y - p.points[pointIndex].y))
                    }
                )
            }
        }
        project.frames.add(index + 1, next)
        project.touch()
        return true
    }

    fun closeLoop(project: ProjectState) {
        if (project.frames.isEmpty()) return
        val end = project.frames.first().cloneFrame().apply { label = "Loop close" }
        project.frames.add(end)
        project.touch()
    }

    fun applyCommand(editor: EditorState, command: String): String {
        val text = command.trim().lowercase()
        if (text.isBlank()) return "Type an animation command"
        val number = Regex("(-?\\d+(?:\\.\\d+)?)").find(text)?.groupValues?.getOrNull(1)?.toFloatOrNull()
        return when {
            "clone" in text -> { editor.cloneFrame(); "Cloned the current frame" }
            "delete frame" in text -> { editor.deleteFrame(); "Deleted the current frame" }
            "hold" in text || "second" in text -> {
                val seconds = number ?: 1f
                editor.frame.durationMs = (seconds * 1000f).toInt().coerceIn(50, 60000)
                editor.project.touch()
                "Frame hold set to ${seconds}s"
            }
            "rotate" in text -> {
                editor.rotateSelection(number ?: 15f)
                "Rotated the selection"
            }
            "bigger" in text || "scale" in text || "zoom selected" in text -> {
                editor.scaleSelection(number?.let { if (it > 3f) it / 100f else it } ?: 1.1f)
                "Scaled the selection"
            }
            "flip" in text -> { editor.flipSelectionHorizontal(); "Flipped the selection" }
            "duplicate" in text -> { editor.duplicateSelection(); "Duplicated the selection" }
            "delete" in text -> { editor.deleteSelection(); "Deleted the selection" }
            "in-between" in text || "inbetween" in text || "smoother" in text -> {
                if (insertInbetween(editor.project, editor.frameIndex)) "Inserted a smart in-between" else "A next frame is required"
            }
            "continue" in text || "same motion" in text -> {
                if (continueMotion(editor.project, editor.frameIndex)) "Continued the previous motion" else "A previous frame is required"
            }
            "loop" in text -> { closeLoop(editor.project); "Added a loop-closing frame" }
            else -> "I couldn't map that command yet"
        }
    }

    private fun averageAngle(a: Float, b: Float): Float = a + angleDelta(a, b) / 2f

    private fun angleDelta(a: Float, b: Float): Float {
        var delta = (b - a) % 360f
        if (delta > 180f) delta -= 360f
        if (delta < -180f) delta += 360f
        return delta
    }
}
