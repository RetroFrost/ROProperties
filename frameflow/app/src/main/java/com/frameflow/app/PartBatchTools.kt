package com.frameflow.app

object PartBatchTools {
    fun currentPart(editor: EditorState): Part? {
        editor.ensureIndices()
        val layer = when {
            editor.selectedRasterLayerIndex in editor.frame.layers.indices -> editor.frame.layers[editor.selectedRasterLayerIndex]
            else -> editor.layer
        }
        return layer.part.takeIf { it !in setOf(Part.None, Part.Background) }
    }

    fun matchingCount(project: ProjectState, part: Part, startFrame: Int, endFrame: Int): Int =
        frameRange(project, startFrame, endFrame).sumOf { index -> project.frames[index].layers.count { it.part == part && !it.isRigSource } }

    fun copyCurrentTransform(editor: EditorState, startFrame: Int, endFrame: Int): Int {
        editor.ensureIndices()
        val source = when {
            editor.selectedRasterLayerIndex in editor.frame.layers.indices -> editor.frame.layers[editor.selectedRasterLayerIndex]
            else -> editor.layer
        }
        val part = source.part
        require(part !in setOf(Part.None, Part.Background)) { "Select/tag a semantic part first" }
        var changed = 0
        frameRange(editor.project, startFrame, endFrame).forEach { frameIndex ->
            editor.project.frames[frameIndex].layers.filter { it.part == part && !it.isRigSource }.forEach { target ->
                if (target !== source) {
                    target.offsetX = source.offsetX
                    target.offsetY = source.offsetY
                    target.scaleX = source.scaleX
                    target.scaleY = source.scaleY
                    target.rotationDeg = source.rotationDeg
                    target.opacity = source.opacity
                    changed++
                }
            }
        }
        if (changed > 0) editor.project.touch()
        return changed
    }

    fun copyCurrentArtwork(editor: EditorState, startFrame: Int, endFrame: Int): Int {
        editor.ensureIndices()
        val source = when {
            editor.selectedRasterLayerIndex in editor.frame.layers.indices -> editor.frame.layers[editor.selectedRasterLayerIndex]
            else -> editor.layer
        }
        val part = source.part
        require(part !in setOf(Part.None, Part.Background)) { "Select/tag a semantic part first" }
        var changed = 0
        frameRange(editor.project, startFrame, endFrame).forEach { frameIndex ->
            val frame = editor.project.frames[frameIndex]
            val indices = frame.layers.indices.filter { frame.layers[it].part == part && !frame.layers[it].isRigSource }
            if (indices.isEmpty()) {
                frame.layers.add(0, source.cloneLayer())
                changed++
            } else {
                indices.forEach { index ->
                    val target = frame.layers[index]
                    val replacement = source.cloneLayer().also {
                        it.name = target.name
                        it.folderName = target.folderName ?: source.folderName
                    }
                    frame.layers[index] = replacement
                    changed++
                }
            }
        }
        if (changed > 0) editor.project.touch()
        return changed
    }

    fun rotate(editor: EditorState, startFrame: Int, endFrame: Int, degrees: Float): Int {
        val part = currentPart(editor) ?: error("Select/tag a semantic part first")
        var changed = 0
        frameRange(editor.project, startFrame, endFrame).forEach { frameIndex ->
            editor.project.frames[frameIndex].layers.filter { it.part == part && !it.isRigSource }.forEach { layer ->
                layer.rotationDeg += degrees
                changed++
            }
        }
        if (changed > 0) editor.project.touch()
        return changed
    }

    fun setVisible(editor: EditorState, startFrame: Int, endFrame: Int, visible: Boolean): Int {
        val part = currentPart(editor) ?: error("Select/tag a semantic part first")
        var changed = 0
        frameRange(editor.project, startFrame, endFrame).forEach { frameIndex ->
            editor.project.frames[frameIndex].layers.filter { it.part == part && !it.isRigSource }.forEach { layer ->
                if (layer.visible != visible) { layer.visible = visible; changed++ }
            }
        }
        if (changed > 0) editor.project.touch()
        return changed
    }

    fun delete(editor: EditorState, startFrame: Int, endFrame: Int): Int {
        val part = currentPart(editor) ?: error("Select/tag a semantic part first")
        var changed = 0
        frameRange(editor.project, startFrame, endFrame).forEach { frameIndex ->
            val frame = editor.project.frames[frameIndex]
            val removed = frame.layers.count { it.part == part && !it.isRigSource }
            if (removed > 0 && frame.layers.size > removed) {
                frame.layers.removeAll { it.part == part && !it.isRigSource }
                changed += removed
            }
        }
        editor.ensureIndices()
        if (changed > 0) editor.project.touch()
        return changed
    }

    private fun frameRange(project: ProjectState, startFrame: Int, endFrame: Int): IntRange {
        require(project.frames.isNotEmpty()) { "Project has no frames" }
        val start = minOf(startFrame, endFrame).coerceIn(project.frames.indices)
        val end = maxOf(startFrame, endFrame).coerceIn(project.frames.indices)
        return start..end
    }
}
