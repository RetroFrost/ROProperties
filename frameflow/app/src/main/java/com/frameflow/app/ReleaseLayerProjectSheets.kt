package com.frameflow.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReleaseLayersSheet(editor: EditorState, history: ProjectHistory, onImportImage: () -> Unit, dismiss: () -> Unit) {
    editor.ensureIndices()
    var renameLayer by remember { mutableStateOf<LayerState?>(null) }
    var renameText by remember { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = dismiss) {
        Column(Modifier.fillMaxWidth().padding(14.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Layers", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = onImportImage) { Text("Import image") }
                FilledTonalButton(onClick = { history.checkpoint(); editor.addLayer() }) { Text("+ Layer") }
            }
            LazyColumn(Modifier.heightIn(max = 300.dp)) {
                items(editor.frame.layers.indices.toList(), key = { editor.frame.layers[it] }) { index ->
                    val layer = editor.frame.layers[index]
                    ListItem(
                        headlineContent = { Text(layer.name, fontWeight = if (index == editor.layerIndex) FontWeight.Bold else FontWeight.Normal) },
                        supportingContent = { Text("${layer.part.label} · ${if (layer.hasRaster) "image + " else ""}${layer.strokes.size} strokes") },
                        leadingContent = { Checkbox(layer.visible, onCheckedChange = { history.checkpoint(); layer.visible = it; editor.project.touch() }) },
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = { history.checkpoint(); layer.locked = !layer.locked; editor.project.touch() }) { Text(if (layer.locked) "🔒" else "Lock") }
                            }
                        },
                        modifier = Modifier.clickable { editor.selectLayer(index) }
                    )
                }
            }
            val layer = editor.layer
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { renameLayer = layer; renameText = layer.name }) { Text("Rename") }
                TextButton(onClick = { history.checkpoint(); editor.duplicateLayer() }) { Text("Duplicate") }
                TextButton(enabled = editor.layerIndex > 0, onClick = { history.checkpoint(); editor.moveLayer(-1) }) { Text("Up") }
                TextButton(enabled = editor.layerIndex < editor.frame.layers.lastIndex, onClick = { history.checkpoint(); editor.moveLayer(1) }) { Text("Down") }
                TextButton(onClick = { history.checkpoint(); editor.deleteLayer() }) { Text("Delete") }
            }
            ReleaseSectionTitle("Semantic part", "Tagging makes whole-limb selection and Object Show poses persistent across cloned frames")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                items(Part.entries) { part ->
                    FilterChip(layer.part == part, onClick = { history.checkpoint(); layer.part = part; editor.project.touch() }, label = { Text(part.label) })
                }
            }
            HistorySliderRow("layer-opacity-${editor.layerIndex}", "Opacity", layer.opacity, 0f..1f, "${(layer.opacity * 100).roundToInt()}%", history) {
                layer.opacity = it; editor.project.touch()
            }
            HistorySliderRow("layer-x-${editor.layerIndex}", "X", layer.offsetX, -editor.project.canvasWidth.toFloat()..editor.project.canvasWidth.toFloat(), layer.offsetX.roundToInt().toString(), history) {
                layer.offsetX = it; editor.project.touch()
            }
            HistorySliderRow("layer-y-${editor.layerIndex}", "Y", layer.offsetY, -editor.project.canvasHeight.toFloat()..editor.project.canvasHeight.toFloat(), layer.offsetY.roundToInt().toString(), history) {
                layer.offsetY = it; editor.project.touch()
            }
            HistorySliderRow("layer-scale-${editor.layerIndex}", "Scale", abs(layer.scaleX).coerceAtLeast(.1f), .1f..4f, "${"%.2f".format(abs(layer.scaleX))}×", history) { value ->
                val sxSign = if (layer.scaleX < 0f) -1f else 1f
                val sySign = if (layer.scaleY < 0f) -1f else 1f
                layer.scaleX = value * sxSign; layer.scaleY = value * sySign; editor.project.touch()
            }
            HistorySliderRow("layer-rotation-${editor.layerIndex}", "Rotation", normaliseAngle(layer.rotationDeg), -180f..180f, "${normaliseAngle(layer.rotationDeg).roundToInt()}°", history) {
                layer.rotationDeg = it; editor.project.touch()
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = { history.checkpoint(); layer.scaleX *= -1f; editor.project.touch() }) { Text("Flip horizontal") }
                TextButton(onClick = { history.checkpoint(); layer.scaleY *= -1f; editor.project.touch() }) { Text("Flip vertical") }
                TextButton(onClick = {
                    history.checkpoint(); layer.offsetX = 0f; layer.offsetY = 0f; layer.scaleX = 1f; layer.scaleY = 1f; layer.rotationDeg = 0f; editor.project.touch()
                }) { Text("Reset transform") }
            }
        }
    }

    renameLayer?.let { target ->
        AlertDialog(
            onDismissRequest = { renameLayer = null },
            title = { Text("Rename layer") },
            text = { OutlinedTextField(renameText, { renameText = it.take(64) }, singleLine = true) },
            confirmButton = {
                Button(onClick = {
                    history.checkpoint(); target.name = renameText.trim().ifBlank { "Layer" }; editor.project.touch(); renameLayer = null
                }) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { renameLayer = null }) { Text("Cancel") } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReleaseCameraSheet(editor: EditorState, history: ProjectHistory, viewZoom: Float, onViewZoom: (Float) -> Unit, onResetView: () -> Unit, dismiss: () -> Unit) {
    val frame = editor.frame
    ModalBottomSheet(onDismissRequest = dismiss) {
        Column(Modifier.fillMaxWidth().padding(18.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("Camera", style = MaterialTheme.typography.titleLarge)
            Text("Camera motion is stored per frame and exported to GIF/MP4. Editor view zoom is separate.", style = MaterialTheme.typography.bodySmall)
            HistorySliderRow("camera-x-${editor.frameIndex}", "Camera X", frame.cameraX, -editor.project.canvasWidth.toFloat()..editor.project.canvasWidth.toFloat(), frame.cameraX.roundToInt().toString(), history) { frame.cameraX = it; editor.project.touch() }
            HistorySliderRow("camera-y-${editor.frameIndex}", "Camera Y", frame.cameraY, -editor.project.canvasHeight.toFloat()..editor.project.canvasHeight.toFloat(), frame.cameraY.roundToInt().toString(), history) { frame.cameraY = it; editor.project.touch() }
            HistorySliderRow("camera-zoom-${editor.frameIndex}", "Camera zoom", frame.cameraZoom, .1f..4f, "${"%.2f".format(frame.cameraZoom)}×", history) { frame.cameraZoom = it; editor.project.touch() }
            HistorySliderRow("camera-rotation-${editor.frameIndex}", "Camera rotation", normaliseAngle(frame.cameraRotation), -180f..180f, "${normaliseAngle(frame.cameraRotation).roundToInt()}°", history) { frame.cameraRotation = it; editor.project.touch() }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = { history.checkpoint(); frame.cameraX = 0f; frame.cameraY = 0f; frame.cameraZoom = 1f; frame.cameraRotation = 0f; editor.project.touch() }) { Text("Reset camera") }
            }
            HorizontalDivider()
            ReleaseSliderRow("Editor view zoom", viewZoom, .25f..8f, "${"%.2f".format(viewZoom)}×", onValueChange = onViewZoom)
            TextButton(onClick = onResetView) { Text("Fit editor view") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReleaseProjectSheet(project: ProjectState, history: ProjectHistory, onSave: () -> Unit, dismiss: () -> Unit) {
    var name by remember(project.id, project.name) { mutableStateOf(project.name) }
    var width by remember(project.id, project.canvasWidth) { mutableStateOf(project.canvasWidth.toString()) }
    var height by remember(project.id, project.canvasHeight) { mutableStateOf(project.canvasHeight.toString()) }
    var fps by remember(project.id, project.fps) { mutableStateOf(project.fps.toString()) }
    var snap by remember(project.id, project.snapMs) { mutableStateOf(project.snapMs.toString()) }

    ModalBottomSheet(onDismissRequest = dismiss) {
        Column(Modifier.fillMaxWidth().padding(18.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Project settings", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(name, { name = it.take(64) }, modifier = Modifier.fillMaxWidth(), label = { Text("Project name") }, singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(width, { width = it.filter(Char::isDigit).take(4) }, Modifier.weight(1f), label = { Text("Width px") }, singleLine = true)
                OutlinedTextField(height, { height = it.filter(Char::isDigit).take(4) }, Modifier.weight(1f), label = { Text("Height px") }, singleLine = true)
                Button(onClick = {
                    val w = width.toIntOrNull()?.coerceIn(64, 4096) ?: return@Button
                    val h = height.toIntOrNull()?.coerceIn(64, 4096) ?: return@Button
                    history.checkpoint(); project.canvasWidth = w; project.canvasHeight = h; project.touch()
                    width = w.toString(); height = h.toString()
                }) { Text("Resize") }
            }
            Text("Project mode", style = MaterialTheme.typography.titleSmall)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(ProjectMode.entries) { mode ->
                    FilterChip(project.mode == mode, { history.checkpoint(); project.mode = mode; project.touch() }, { Text(mode.label) })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(fps, { fps = it.filter(Char::isDigit).take(2) }, Modifier.weight(1f), label = { Text("Export FPS") }, singleLine = true)
                OutlinedTextField(snap, { snap = it.filter(Char::isDigit).take(4) }, Modifier.weight(1f), label = { Text("Timeline snap ms") }, singleLine = true)
                Button(onClick = {
                    history.checkpoint()
                    project.fps = fps.toIntOrNull()?.coerceIn(1, 60) ?: project.fps
                    project.snapMs = snap.toIntOrNull()?.coerceIn(10, 5000) ?: project.snapMs
                    project.touch(); fps = project.fps.toString(); snap = project.snapMs.toString()
                }) { Text("Apply") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(project.loopPlayback, { history.checkpoint(); project.loopPlayback = it; project.touch() })
                Spacer(Modifier.width(8.dp)); Text("Loop playback")
            }
            ReleaseSectionTitle("Background")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val backgrounds = listOf(
                    "Transparent" to 0x00000000,
                    "White" to 0xFFFFFFFF.toInt(),
                    "Black" to 0xFF000000.toInt(),
                    "Grey" to 0xFF808080.toInt()
                )
                items(backgrounds) { (label, argb) ->
                    FilterChip(project.backgroundArgb == argb, { history.checkpoint(); project.backgroundArgb = argb; project.touch() }, { Text(label) })
                }
            }
            Button(onClick = {
                history.checkpoint(); project.name = name.trim().ifBlank { "Untitled animation" }; project.touch(); onSave(); dismiss()
            }, modifier = Modifier.fillMaxWidth()) { Text("Save settings") }
        }
    }
}

private fun normaliseAngle(value: Float): Float {
    var result = value % 360f
    if (result > 180f) result -= 360f
    if (result < -180f) result += 360f
    return result
}
