package com.frameflow.app

object GestureMutationTools {
    /**
     * Moves the active selection without touching ProjectState.revision. The
     * caller controls preview invalidation frequency and commits a final touch()
     * on ACTION_UP, avoiding a full bitmap render for every MotionEvent sample.
     */
    fun moveSelectionTransient(editor: EditorState, dx: Float, dy: Float): Boolean {
        editor.ensureIndices()
        val safeDx = dx.takeIf { it.isFinite() } ?: 0f
        val safeDy = dy.takeIf { it.isFinite() } ?: 0f
        if (safeDx == 0f && safeDy == 0f) return false
        val frame = editor.frame
        if (editor.selectedRasterLayerIndex in frame.layers.indices) {
            val layer = frame.layers[editor.selectedRasterLayerIndex]
            if (layer.locked || layer.isRigSource) return false
            layer.offsetX = (layer.offsetX + safeDx).takeIf { it.isFinite() }?.coerceIn(-100_000f, 100_000f) ?: layer.offsetX
            layer.offsetY = (layer.offsetY + safeDy).takeIf { it.isFinite() }?.coerceIn(-100_000f, 100_000f) ?: layer.offsetY
            return true
        }
        if (editor.selectedIds.isEmpty()) return false
        val ids = editor.selectedIds.toSet()
        var changed = false
        frame.layers.filter { !it.locked && !it.isRigSource }.forEach { layer ->
            for (index in layer.strokes.indices) {
                val stroke = layer.strokes[index]
                if (stroke.id in ids) {
                    layer.strokes[index] = stroke.copy(points = stroke.points.map { point ->
                        CanvasPoint(
                            (point.x + safeDx).takeIf { it.isFinite() } ?: point.x,
                            (point.y + safeDy).takeIf { it.isFinite() } ?: point.y
                        )
                    })
                    changed = true
                }
            }
        }
        return changed
    }
}
