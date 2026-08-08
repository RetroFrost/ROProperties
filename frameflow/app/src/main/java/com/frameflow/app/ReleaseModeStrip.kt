package com.frameflow.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ReleaseModeStrip(
    project: ProjectState,
    editor: EditorState,
    history: ProjectHistory,
    busy: Boolean,
    onBusy: (Boolean) -> Unit,
    onImportImage: () -> Unit,
    onMessage: (String) -> Unit
) {
    when (project.mode) {
        ProjectMode.Auto -> AutoModeStrip(editor, history, onImportImage, onMessage)
        ProjectMode.ObjectShow -> ObjectShowModeStrip(editor, history, busy, onBusy, onImportImage, onMessage)
        ProjectMode.Static -> Unit
    }
}

@Composable
private fun AutoModeStrip(
    editor: EditorState,
    history: ProjectHistory,
    onImportImage: () -> Unit,
    onMessage: (String) -> Unit
) {
    var command by rememberSaveable(editor.project.id) { mutableStateOf("") }
    Surface(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp), shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp), tonalElevation = 2.dp) {
        Column(Modifier.padding(9.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = command,
                    onValueChange = { command = it.take(180) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text("Describe the motion") },
                    placeholder = { Text("bounce and zoom in") }
                )
                Button(onClick = {
                    history.checkpoint()
                    runCatching { AutoAnimationTools.apply(editor, command) }
                        .onSuccess { onMessage(it.description) }
                        .onFailure { onMessage(it.message ?: "That animation command is not supported") }
                }) { Text("Animate") }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                item { AssistChip(onClick = onImportImage, label = { Text("Import image") }) }
                listOf("bounce", "walk left", "zoom in", "shake", "spin", "fade out", "bounce and zoom in", "shake and loop").forEach { preset ->
                    item { SuggestionChip(onClick = { command = preset }, label = { Text(preset) }) }
                }
            }
        }
    }
}

@Composable
private fun ObjectShowModeStrip(
    editor: EditorState,
    history: ProjectHistory,
    busy: Boolean,
    onBusy: (Boolean) -> Unit,
    onImportImage: () -> Unit,
    onMessage: (String) -> Unit
) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp), shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp), tonalElevation = 2.dp) {
        Column(Modifier.padding(9.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Object Show", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = onImportImage) { Text("Import character") }
                Button(
                    enabled = !busy,
                    onClick = {
                        onBusy(true)
                        SmartPartOperations.identifyAndSplit(
                            editor,
                            history,
                            onSuccess = { count -> onBusy(false); onMessage("Rigged $count character parts. Tap any limb to move or rotate the whole painted part.") },
                            onFailure = { error -> onBusy(false); onMessage("Character detection failed: ${error.message ?: "unknown error"}") }
                        )
                    }
                ) { Text(if (busy) "Detecting…" else "Detect character") }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                item {
                    AssistChip(onClick = {
                        history.checkpoint()
                        val count = ObjectShowTools.addMissingStickLimbs(editor)
                        onMessage(if (count > 0) "Added $count missing stick limbs" else "All four limb layers already exist")
                    }, label = { Text("Add missing limbs") })
                }
                item {
                    AssistChip(onClick = {
                        history.checkpoint()
                        onMessage(if (ObjectShowTools.createBlinkFrame(editor)) "Created an editable blink frame" else "A visible raster body is required for automatic blink")
                    }, label = { Text("Blink frame") })
                }
                ObjectShowTools.Pose.entries.forEach { pose ->
                    item {
                        SuggestionChip(onClick = {
                            history.checkpoint()
                            ObjectShowTools.applyPose(editor, pose)
                        }, label = { Text(pose.label) })
                    }
                }
            }
        }
    }
}
