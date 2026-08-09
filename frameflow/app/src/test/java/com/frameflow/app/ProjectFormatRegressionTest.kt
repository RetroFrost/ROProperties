package com.frameflow.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectFormatRegressionTest {
    @Test
    fun semanticFoldersClippingAndRigMetadataRoundTrip() {
        val layer = LayerState(
            name = "Right arm shading",
            part = Part.RightArm,
            folderName = "Character",
            clipToBelow = true,
            isRigSource = false,
            opacity = .62f,
            offsetX = 12f,
            offsetY = -8f,
            scaleX = -1.2f,
            scaleY = 1.2f,
            rotationDeg = 37f,
            strokes = listOf(
                StrokeData(
                    points = listOf(CanvasPoint(12f, 24f), CanvasPoint(40f, 52f)),
                    colorArgb = 0xFF336699.toInt(),
                    width = 17f,
                    alpha = .75f,
                    erase = false
                )
            )
        )
        val rig = LayerState("Rig source", Part.None, folderName = "Rig", isRigSource = true, visible = false, locked = true)
        val project = ProjectState(
            name = "Round trip",
            frames = listOf(FrameState(duration = 733, layers = listOf(layer, rig))),
            mode = ProjectMode.ObjectShow,
            fps = 24,
            snapMs = 50
        )
        project.activeFrameIndex = 0

        val restored = projectFromJson(projectToJson(project).toString())
        assertEquals(FRAMEFLOW_FORMAT_VERSION, 4)
        assertEquals(ProjectMode.ObjectShow, restored.mode)
        assertEquals(24, restored.fps)
        assertEquals(50, restored.snapMs)
        assertEquals(733, restored.frames.single().durationMs)
        val restoredLayer = restored.frames.single().layers[0]
        assertEquals(Part.RightArm, restoredLayer.part)
        assertEquals("Character", restoredLayer.folderName)
        assertTrue(restoredLayer.clipToBelow)
        assertFalse(restoredLayer.isRigSource)
        assertEquals(.62f, restoredLayer.opacity, .001f)
        assertEquals(-1.2f, restoredLayer.scaleX, .001f)
        assertEquals(37f, restoredLayer.rotationDeg, .001f)
        assertEquals(1, restoredLayer.strokes.size)
        val restoredRig = restored.frames.single().layers[1]
        assertTrue(restoredRig.isRigSource)
        assertFalse(restoredRig.visible)
        assertTrue(restoredRig.locked)
    }

    @Test
    fun malformedNumbersAreSanitisedInsteadOfPoisoningEditorState() {
        val project = ProjectState()
        val root = projectToJson(project)
        root.put("fps", 999)
        root.put("canvasWidth", 999999)
        root.put("canvasHeight", -1)
        root.getJSONArray("frames").getJSONObject(0).put("durationMs", -100)
        // JSONObject rejects a Double.NaN at put-time. Use the valid JSON string form so
        // projectFromJson still has to parse and sanitise a non-finite numeric value.
        root.getJSONArray("frames").getJSONObject(0).put("cameraZoom", "NaN")

        val restored = projectFromJson(root.toString())
        assertEquals(60, restored.fps)
        assertEquals(8192, restored.canvasWidth)
        assertEquals(64, restored.canvasHeight)
        assertEquals(50, restored.frames[0].durationMs)
        assertTrue(restored.frames[0].cameraZoom.isFinite())
        assertTrue(restored.frames[0].cameraZoom >= .05f)
    }

    @Test
    fun editorRepairsEmptyCollectionsWithoutRecursion() {
        val project = ProjectState(frames = emptyList())
        project.frames.clear()
        val editor = EditorState(project)
        repeat(250) {
            editor.ensureIndices()
            editor.frame.layers.clear()
            editor.ensureIndices()
            assertTrue(editor.frame.layers.isNotEmpty())
        }
        assertTrue(project.frames.isNotEmpty())
    }
}
