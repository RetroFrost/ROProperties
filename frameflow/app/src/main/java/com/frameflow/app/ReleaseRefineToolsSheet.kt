package com.frameflow.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReleaseRefineToolsSheet(editor: EditorState, onTool: (ReleaseTool) -> Unit, dismiss: () -> Unit) {
    val regular = listOf(
        ReleaseTool.Fill to "Flood-fill the visible connected region on a new editable raster layer",
        ReleaseTool.Eyedropper to "Pick the rendered colour under your finger",
        ReleaseTool.Lasso to "Freehand-select strokes on the active layer",
        ReleaseTool.Line to "Draw a straight line with the current brush",
        ReleaseTool.Rectangle to "Draw a rectangle with the current brush",
        ReleaseTool.Ellipse to "Draw an ellipse with the current brush",
        ReleaseTool.Pan to "Move the editor view without changing artwork"
    )
    ModalBottomSheet(onDismissRequest = dismiss) {
        Column(Modifier.fillMaxWidth().padding(14.dp).padding(bottom = 28.dp)) {
            Text("Tools", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(6.dp))
            if (SemanticMaskTools.canRefine(editor)) {
                ReleaseSectionTitle("Refine detected limb", "Paint against the preserved original art; changes alter the semantic part itself")
                ListItem(
                    headlineContent = { Text("Add to limb") },
                    supportingContent = { Text("Restore original pixels into the selected detected part") },
                    modifier = Modifier.clickable { onTool(ReleaseTool.MaskAdd); dismiss() }
                )
                ListItem(
                    headlineContent = { Text("Remove from limb") },
                    supportingContent = { Text("Erase pixels from the selected detected part mask") },
                    modifier = Modifier.clickable { onTool(ReleaseTool.MaskRemove); dismiss() }
                )
                HorizontalDivider(Modifier.padding(vertical = 5.dp))
            }
            regular.forEach { (tool, description) ->
                ListItem(
                    headlineContent = { Text(tool.label) },
                    supportingContent = { Text(description) },
                    modifier = Modifier.clickable { onTool(tool); dismiss() }
                )
            }
        }
    }
}
