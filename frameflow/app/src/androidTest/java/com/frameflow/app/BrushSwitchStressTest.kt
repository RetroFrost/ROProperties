package com.frameflow.app

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
                clickLastText(name)
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
                clickLastText(name)
                compose.waitForIdle()
                compose.onNodeWithText("Export").assertExists()
            }

            // Exercise family filtering. The family chips are a LazyRow, so scroll the
            // requested chip into composition instead of depending on the row's old position.
            compose.onNodeWithText("Brush").performClick()
            compose.waitForIdle()
            val search = compose.onNodeWithText("Search")
            search.performTextClearance()
            val family = listOf("Ink", "Marker", "Texture")[pass]
            compose.onNodeWithText(family).performScrollTo().performClick()
            compose.waitForIdle()
            val familyPreset = "$family 2"
            search.performTextInput(familyPreset)
            compose.waitForIdle()
            clickLastText(familyPreset)
            compose.waitForIdle()
            compose.onNodeWithText("Export").assertExists()
        }
    }

    private fun clickLastText(text: String) {
        val matches = compose.onAllNodesWithText(text)
        val count = matches.fetchSemanticsNodes().size
        check(count > 0) { "No semantics node found for $text" }
        matches[count - 1].performClick()
    }

    private fun openProject() {
        compose.waitForIdle()
        if (compose.onAllNodesWithText("Export").fetchSemanticsNodes().isNotEmpty()) return
        compose.onNodeWithText("+ New animation").assertExists().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Create").assertExists().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Export").assertExists()
    }
}
