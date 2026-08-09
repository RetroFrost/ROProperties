package com.frameflow.app

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BrushSwitchStressTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun repeatedlySwitchBrushesErasersSearchAndFamiliesWithoutCrashing() {
        openProject()

        val brushNames = listOf("Ink 1", "Pencil 7", "Paint 15", "Pixel 20", "Spray 4", "Airbrush 20")
        repeat(3) { pass ->
            brushNames.forEach { name ->
                compose.onNodeWithText("Brush").performClick()
                compose.waitForIdle()
                val search = compose.onNodeWithText("Search")
                search.performTextClearance()
                search.performTextInput(name)
                compose.waitForIdle()
                compose.onNodeWithText(name).performClick()
                compose.waitForIdle()
                compose.onNodeWithText("Export").assertExists()
            }

            listOf("Eraser 1", "Eraser 23", "Eraser 41", "Eraser 60").forEach { name ->
                compose.onNodeWithText("Eraser").performClick()
                compose.waitForIdle()
                val search = compose.onNodeWithText("Search")
                search.performTextClearance()
                search.performTextInput(name)
                compose.waitForIdle()
                compose.onNodeWithText(name).performClick()
                compose.waitForIdle()
                compose.onNodeWithText("Export").assertExists()
            }

            // Re-enter the brush sheet with different category state each pass.
            compose.onNodeWithText("Brush").performClick()
            compose.waitForIdle()
            val family = listOf("Ink", "Marker", "Texture")[pass]
            compose.onNodeWithText(family).performClick()
            compose.waitForIdle()
            compose.onNodeWithText("Brushes").assertExists()
            // Choose a visible family preset, closing the sheet through a real selection.
            val familyPreset = when (family) {
                "Ink" -> "Ink 2"
                "Marker" -> "Marker 2"
                else -> "Texture 2"
            }
            compose.onNodeWithText(familyPreset).performClick()
            compose.waitForIdle()
            compose.onNodeWithText("Export").assertExists()
        }
    }

    private fun openProject() {
        compose.waitForIdle()
        // Reuse an existing project if CI state has one; otherwise create Static animation.
        val staticNodes = compose.onAllNodesWithText("Static animation")
        if (staticNodes.fetchSemanticsNodes().isNotEmpty()) {
            staticNodes[0].performClick()
            compose.waitForIdle()
        }
        if (compose.onAllNodesWithText("Export").fetchSemanticsNodes().isEmpty()) {
            compose.onAllNodesWithText("New project")[0].performClick()
            compose.waitForIdle()
            compose.onAllNodesWithText("Static animation")[0].performClick()
            compose.waitForIdle()
        }
        compose.onNodeWithText("Export").assertExists()
    }
}
