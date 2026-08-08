package com.frameflow.app

import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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

        rule.onNodeWithText("Brush").assertExists().performClick()
        rule.onNodeWithText("Ink 1").assertExists().performClick()
        rule.waitForIdle()

        rule.onNodeWithText("Brush").performClick()
        rule.onNodeWithText("Marker 8").assertExists().performClick()
        rule.waitForIdle()

        rule.onNodeWithText("Eraser").assertExists().performClick()
        rule.onNodeWithText("Eraser 1").assertExists().performClick()
        rule.waitForIdle()

        rule.onNodeWithText("Eraser").performClick()
        rule.onNodeWithText("Eraser 13").assertExists().performClick()
        rule.waitForIdle()

        rule.onNodeWithText("Select").assertExists().performClick()
        rule.onNodeWithText("Colour repeat").assertExists().performClick()
        rule.onNodeWithText("Brush").performClick()
        rule.onNodeWithText("Pencil 2").assertExists().performClick()
        rule.waitForIdle()

        rule.onNodeWithText("+ Clone").assertExists().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Play").assertExists()
    }
}
