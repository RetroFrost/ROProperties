package com.frameflow.app

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import org.junit.Rule
import org.junit.Test

class EditorInteractionTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun brushEraserCloneAndToolSwitchingDoNotCrash() {
        rule.onNodeWithText("+ New animation").performClick()
        rule.onNodeWithText("Create").performClick()
        rule.waitForIdle()

        selectPreset("Brush", "Ink 1")
        selectPreset("Brush", "Marker 8")
        selectPreset("Eraser", "Eraser 1")
        selectPreset("Eraser", "Eraser 13")
        selectPreset("Brush", "Pencil 2")
        selectPreset("Brush", "Spray 5")
        selectPreset("Brush", "Pixel 3")

        rule.onNodeWithText("Select limb").assertExists().performClick()
        rule.onNodeWithText("Colour repeat").assertExists().performClick()
        rule.onNodeWithText("Tools").assertExists().performClick()
        rule.onNodeWithText("Line").assertExists().performClick()
        rule.waitForIdle()

        rule.onNodeWithText("+ Clone").assertExists().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Play").assertExists().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Stop").assertExists().performClick()
        rule.onNodeWithText("Undo").assertExists().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Redo").assertExists().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Export").assertExists()
    }

    private fun selectPreset(tool: String, preset: String) {
        rule.onNodeWithText(tool).assertExists().performClick()
        rule.waitForIdle()
        val search = rule.onNodeWithText("Search")
        search.performTextClearance()
        search.performTextInput(preset)
        rule.waitForIdle()
        val matches = rule.onAllNodesWithText(preset)
        val count = matches.fetchSemanticsNodes().size
        check(count > 0) { "No preset result found for $preset" }
        matches[count - 1].performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Play").assertExists()
    }
}
