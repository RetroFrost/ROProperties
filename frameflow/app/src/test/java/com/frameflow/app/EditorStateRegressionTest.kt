package com.frameflow.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorStateRegressionTest {
    @Test
    fun frameAndLayerAccessNeverRecurses() {
        val project = ProjectState()
        val editor = EditorState(project)

        repeat(2_000) {
            assertNotNull(editor.frame)
            assertNotNull(editor.layer)
            editor.ensureIndices()
        }

        assertEquals(0, editor.frameIndex)
        assertTrue(editor.frame.layers.isNotEmpty())
    }

    @Test
    fun changingEveryBrushAndEraserKeepsEditorUsable() {
        val editor = EditorState(ProjectState())

        brushes.forEach { preset ->
            editor.brush = preset
            editor.tool = Tool.Brush
            assertEquals(preset, editor.brush)
            assertNotNull(editor.frame)
            assertNotNull(editor.layer)
        }

        erasers.forEach { preset ->
            editor.eraser = preset
            editor.tool = Tool.Eraser
            assertEquals(preset, editor.eraser)
            assertNotNull(editor.frame)
            assertNotNull(editor.layer)
        }
    }

    @Test
    fun emptyProjectAndLayerCollectionsSelfHeal() {
        val project = ProjectState()
        val editor = EditorState(project)

        project.frames.clear()
        editor.ensureIndices()
        assertEquals(1, project.frames.size)

        project.frames[0].layers.clear()
        editor.ensureIndices()
        assertEquals(1, project.frames[0].layers.size)
        assertNotNull(editor.layer)
    }

    @Test
    fun rapidCloneDeleteAndLayerMutationKeepsIndicesValid() {
        val project = ProjectState()
        val editor = EditorState(project)

        repeat(250) { editor.cloneFrame() }
        assertEquals(251, project.frames.size)

        repeat(250) { editor.deleteFrame() }
        assertEquals(1, project.frames.size)
        assertEquals(0, editor.frameIndex)

        repeat(30) { editor.addLayer() }
        repeat(30) { editor.deleteLayer() }
        editor.ensureIndices()
        assertTrue(editor.layerIndex in editor.frame.layers.indices)
    }
}
