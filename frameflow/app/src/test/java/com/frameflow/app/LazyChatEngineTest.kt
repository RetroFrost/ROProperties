package com.frameflow.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LazyChatEngineTest {
    @Test
    fun cloneThenHoldRunsBothEdits() {
        val project = ProjectState()
        val editor = EditorState(project)
        val history = ProjectHistory(project)

        val result = LazyChatEngine.execute(editor, history, "clone then hold 2 seconds")

        assertEquals(2, project.frames.size)
        assertEquals(1, editor.frameIndex)
        assertEquals(2000, editor.frame.durationMs)
        assertEquals(2, result.understoodCommands)
        assertTrue(result.changedProject)
    }

    @Test
    fun combinedMotionStaysOneAnimationPlan() {
        val project = ProjectState()
        val editor = EditorState(project)
        val history = ProjectHistory(project)

        val result = LazyChatEngine.execute(editor, history, "bounce and zoom in for 1 second")

        assertTrue(project.frames.size > 1)
        assertEquals(1, result.understoodCommands)
        assertTrue(project.frames.drop(1).any { it.cameraZoom > 1f })
        assertTrue(project.frames.drop(1).any { frame -> frame.layers.any { it.offsetY < 0f } })
    }

    @Test
    fun undoThatRestoresWholeChatRequest() {
        val project = ProjectState()
        val editor = EditorState(project)
        val history = ProjectHistory(project)

        LazyChatEngine.execute(editor, history, "clone then hold 3 seconds")
        val undo = LazyChatEngine.execute(editor, history, "undo that")

        assertEquals(1, project.frames.size)
        assertEquals(1000, project.frames[0].durationMs)
        assertTrue(undo.changedProject)
    }

    @Test
    fun goToFrameChangesSelectionWithoutInventingFrames() {
        val project = ProjectState(frames = listOf(FrameState(), FrameState(), FrameState()))
        val editor = EditorState(project)
        val history = ProjectHistory(project)

        LazyChatEngine.execute(editor, history, "go to frame 3")

        assertEquals(2, editor.frameIndex)
        assertEquals(3, project.frames.size)
    }
}
