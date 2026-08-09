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
            layer.opacity = (layer.opacity + target.opacity) / 2f
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

    fun makeSmoother(project: ProjectState, index: Int, passes: Int = 1): Int {
        var inserted = 0
        var current = index
        repeat(passes.coerceIn(1, 3)) {
            if (insertInbetween(project, current)) {
                inserted++
                current++
            }
        }
        return inserted
    }

    /** Merges adjacent frames that render from the same artwork/transforms into one longer hold. */
    fun makeSnappier(project: ProjectState): Int {
        if (project.frames.size < 2) return 0
        var removed = 0
        var index = 1
        while (index < project.frames.size) {
            val previous = project.frames[index - 1]
            val current = project.frames[index]
            if (visualSignature(previous) == visualSignature(current)) {
                previous.durationMs = (previous.durationMs.toLong() + current.durationMs).coerceAtMost(120000L).toInt()
                project.frames.removeAt(index)
                removed++
            } else index++
        }
        if (removed > 0) {
            project.activeFrameIndex = project.activeFrameIndex.coerceIn(project.frames.indices)
            project.touch()
        }
        return removed
    }

    /** Restores semantic layers that disappeared only in the selected middle frame. */
    fun fixFrame(project: ProjectState, index: Int): Int {
        if (index <= 0 || index >= project.frames.lastIndex) return 0
        val previous = project.frames[index - 1]
        val current = project.frames[index]
        val next = project.frames[index + 1]
        val currentParts = current.layers.map { it.part }.toSet()
        val commonNeighbourParts = previous.layers.map { it.part }.intersect(next.layers.map { it.part }.toSet())
            .filter { it != Part.None && it != Part.Background }
        var restored = 0
        commonNeighbourParts.forEach { part ->
            if (part !in currentParts) {
                val source = previous.layers.firstOrNull { it.part == part } ?: next.layers.firstOrNull { it.part == part }
                if (source != null) {
                    current.layers.add(0, source.cloneLayer().also { it.name = "${it.name} · restored" })
                    restored++
                }
            }
        }
        if (restored > 0) project.touch()
        return restored
    }

    fun continueMotion(project: ProjectState, index: Int): Boolean {
        if (index <= 0 || index !in project.frames.indices) return false
        val previous = project.frames[index - 1]
        val current = project.frames[index]
        val next = current.cloneFrame().apply {
            label = "Motion continuation"
            cameraX += current.cameraX - previous.cameraX
            cameraY += current.cameraY - previous.cameraY
            cameraZoom = (current.cameraZoom + (current.cameraZoom - previous.cameraZoom)).coerceIn(.05f, 20f)
            cameraRotation += angleDelta(previous.cameraRotation, current.cameraRotation)
        }
        next.layers.forEachIndexed { layerIndex, layer ->
            val prevLayer = previous.layers.getOrNull(layerIndex) ?: return@forEachIndexed
            val curLayer = current.layers.getOrNull(layerIndex) ?: return@forEachIndexed
            layer.offsetX = curLayer.offsetX + (curLayer.offsetX - prevLayer.offsetX)
            layer.offsetY = curLayer.offsetY + (curLayer.offsetY - prevLayer.offsetY)
            layer.scaleX = (curLayer.scaleX + (curLayer.scaleX - prevLayer.scaleX)).coerceIn(-20f, 20f)
            layer.scaleY = (curLayer.scaleY + (curLayer.scaleY - prevLayer.scaleY)).coerceIn(-20f, 20f)
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
        val number = Regex("(-?\\d+(?:[.,]\\d+)?)").find(text)?.groupValues?.getOrNull(1)?.replace(',', '.')?.toFloatOrNull()
        return when {
            "clone" in text -> { editor.cloneFrame(); "Cloned the current frame" }
            "delete frame" in text -> { editor.deleteFrame(); "Deleted the current frame" }
            "hold" in text || "second" in text -> {
                val seconds = number ?: 1f
                editor.frame.durationMs = (seconds * 1000f).toInt().coerceIn(50, 120000)
                editor.project.touch()
                "Frame hold set to ${"%.2f".format(seconds)} s"
            }
            "rotate" in text -> {
                SmartTransformTools.rotateSelection(editor, number ?: 15f)
                "Rotated the selection"
            }
            "bigger" in text || "scale" in text || "zoom selected" in text -> {
                editor.scaleSelection(number?.let { if (it > 3f) it / 100f else it } ?: 1.1f)
                "Scaled the selection"
            }
            "flip" in text -> { editor.flipSelectionHorizontal(); "Flipped the selection" }
            "duplicate" in text -> { editor.duplicateSelection(); "Duplicated the selection" }
            "delete" in text -> { editor.deleteSelection(); "Deleted the selection" }
            "fix frame" in text || "restore missing" in text -> {
                val count = fixFrame(editor.project, editor.frameIndex)
                if (count > 0) "Restored $count missing semantic layers" else "No missing semantic layer was found between neighbouring frames"
            }
            "snappier" in text -> {
                val count = makeSnappier(editor.project)
                "Merged $count redundant frames"
            }
            "in-between" in text || "inbetween" in text || "smoother" in text -> {
                if (insertInbetween(editor.project, editor.frameIndex)) "Inserted a smart in-between" else "A next frame is required"
            }
            "continue" in text || "same motion" in text -> {
                if (continueMotion(editor.project, editor.frameIndex)) "Continued the previous motion" else "A previous frame is required"
            }
            "loop" in text -> { closeLoop(editor.project); "Added a loop-closing frame" }
            listOf("bounce", "walk", "move left", "move right", "zoom in", "zoom out", "shake", "spin", "fade").any { it in text } -> {
                AutoAnimationTools.apply(editor, command).description
            }
            else -> "I couldn't map that command. Try a motion, timing, selection, in-between, loop or cleanup instruction."
        }
    }

    private fun visualSignature(frame: FrameState): Int {
        var result = 17
        fun add(value: Any?) { result = 31 * result + (value?.hashCode() ?: 0) }
        add(frame.cameraX); add(frame.cameraY); add(frame.cameraZoom); add(frame.cameraRotation)
        frame.layers.forEach { layer ->
            add(layer.name); add(layer.part); add(layer.visible); add(layer.opacity); add(layer.offsetX); add(layer.offsetY); add(layer.scaleX); add(layer.scaleY); add(layer.rotationDeg)
            add(layer.rasterPngBase64?.hashCode()); add(layer.rasterPngBase64?.length)
            layer.strokes.forEach { stroke ->
                add(stroke.id); add(stroke.colorArgb); add(stroke.width); add(stroke.alpha); add(stroke.erase); add(stroke.points.hashCode())
            }
        }
        return result
    }

    private fun averageAngle(a: Float, b: Float): Float = a + angleDelta(a, b) / 2f

    private fun angleDelta(a: Float, b: Float): Float {
        var delta = (b - a) % 360f
        if (delta > 180f) delta -= 360f
        if (delta < -180f) delta += 360f
        return delta
    }
}
