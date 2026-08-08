package com.frameflow.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    project: ProjectState,
    onBack: () -> Unit,
    onSave: () -> Unit,
    onExportProject: () -> Unit,
    onExportCurrentPng: () -> Unit,
    onExportFramesZip: () -> Unit
) {
    val editor = remember(project.id) { EditorState(project) }
    val history = remember(project.id) { ProjectHistory(project) }
    var showBrushes by remember { mutableStateOf(false) }
    var showErasers by remember { mutableStateOf(false) }
    var showColors by remember { mutableStateOf(false) }
    var showLayers by remember { mutableStateOf(false) }
    var showSmart by remember { mutableStateOf(false) }
    var showProjectSettings by remember { mutableStateOf(false) }
    var showExportMenu by remember { mutableStateOf(false) }

    LaunchedEffect(editor.playing) {
        while (editor.playing) {
            editor.ensureIndices()
            delay(editor.frame.durationMs.toLong())
            if (project.frames.isNotEmpty()) {
                editor.frameIndex = (editor.frameIndex + 1) % project.frames.size
                editor.layerIndex = editor.layerIndex.coerceIn(editor.frame.layers.indices)
                editor.selectedIds.clear()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
                title = {
                    Column {
                        Text(project.name, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Text(
                            "Frame ${editor.frameIndex + 1}/${project.frames.size} · ${project.canvasWidth}×${project.canvasHeight}",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                },
                actions = {
                    TextButton(
                        enabled = history.canUndo,
                        onClick = {
                            if (history.undo()) {
                                editor.ensureIndices()
                                editor.selectedIds.clear()
                            }
                        }
                    ) { Text("Undo") }
                    TextButton(
                        enabled = history.canRedo,
                        onClick = {
                            if (history.redo()) {
                                editor.ensureIndices()
                                editor.selectedIds.clear()
                            }
                        }
                    ) { Text("Redo") }
                    TextButton(onClick = { editor.playing = !editor.playing }) {
                        Text(if (editor.playing) "Stop" else "Play")
                    }
                    Box {
                        TextButton(onClick = { showExportMenu = true }) { Text("Export") }
                        DropdownMenu(expanded = showExportMenu, onDismissRequest = { showExportMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Frameflow project") },
                                supportingText = { Text("Editable .frameflow file") },
                                onClick = { showExportMenu = false; onExportProject() }
                            )
                            DropdownMenuItem(
                                text = { Text("Current frame PNG") },
                                supportingText = { Text("Full-resolution still image") },
                                onClick = { showExportMenu = false; onExportCurrentPng() }
                            )
                            DropdownMenuItem(
                                text = { Text("All frames ZIP") },
                                supportingText = { Text("PNG sequence + project file") },
                                onClick = { showExportMenu = false; onExportFramesZip() }
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AssistChip(
                    onClick = { editor.onionPrevious = !editor.onionPrevious },
                    label = { Text(if (editor.onionPrevious) "Prev onion on" else "Prev onion") }
                )
                AssistChip(
                    onClick = { editor.onionNext = !editor.onionNext },
                    label = { Text(if (editor.onionNext) "Next onion on" else "Next onion") }
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onSave) { Text("Save") }
                TextButton(onClick = { showProjectSettings = true }) { Text("Project") }
            }

            EditorCanvas(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp),
                editor = editor,
                history = history
            )

            Timeline(editor = editor, history = history)
            ToolBar(
                editor = editor,
                onBrushes = { showBrushes = true },
                onErasers = { showErasers = true },
                onColors = { showColors = true },
                onLayers = { showLayers = true },
                onSmart = { showSmart = true }
            )
        }
    }

    if (showBrushes) {
        PresetSheet("Brushes · ${brushes.size}", brushes, editor.brush, { showBrushes = false }) {
            editor.brush = it
            editor.tool = Tool.Brush
        }
    }
    if (showErasers) {
        PresetSheet("Erasers · ${erasers.size}", erasers, editor.eraser, { showErasers = false }) {
            editor.eraser = it
            editor.tool = Tool.Eraser
        }
    }
    if (showColors) ColorSheet(editor) { showColors = false }
    if (showLayers) LayersSheet(editor, history) { showLayers = false }
    if (showSmart) SmartSheet(editor) { showSmart = false }
    if (showProjectSettings) ProjectSettingsSheet(project, history) { showProjectSettings = false }
}

@Composable
private fun EditorCanvas(
    modifier: Modifier,
    editor: EditorState,
    history: ProjectHistory
) {
    val project = editor.project
    var livePoints by remember { mutableStateOf<List<CanvasPoint>>(emptyList()) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var selectionMoveCheckpointed by remember { mutableStateOf(false) }

    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val projectAspect = project.canvasWidth.toFloat() / project.canvasHeight.toFloat()
        val availableAspect = if (maxHeight.value == 0f) projectAspect else maxWidth.value / maxHeight.value
        val fitModifier = if (availableAspect > projectAspect) {
            Modifier.fillMaxHeight().aspectRatio(projectAspect)
        } else {
            Modifier.fillMaxWidth().aspectRatio(projectAspect)
        }

        Box(
            fitModifier
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(16.dp))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
        ) {
            Canvas(
                Modifier.fillMaxSize()
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .onSizeChanged { canvasSize = it }
                    .pointerInput(
                        editor.tool,
                        editor.frameIndex,
                        editor.layerIndex,
                        editor.brush,
                        editor.eraser,
                        editor.color,
                        canvasSize,
                        editor.layer.locked
                    ) {
                        detectDragGestures(
                            onDragStart = { position ->
                                val point = position.toProject(canvasSize, project)
                                selectionMoveCheckpointed = false
                                when (editor.tool) {
                                    Tool.Brush, Tool.Eraser -> {
                                        if (!editor.layer.locked) {
                                            history.checkpoint()
                                            livePoints = listOf(point)
                                        }
                                    }
                                    Tool.SmartSelect -> editor.selectWholePartAt(point)
                                    Tool.ColorRepeat -> editor.selectColorAt(point)
                                }
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                when (editor.tool) {
                                    Tool.Brush, Tool.Eraser -> {
                                        if (!editor.layer.locked) {
                                            livePoints = livePoints + change.position.toProject(canvasSize, project)
                                        }
                                    }
                                    Tool.SmartSelect, Tool.ColorRepeat -> {
                                        if (editor.selectedIds.isNotEmpty()) {
                                            if (!selectionMoveCheckpointed) {
                                                history.checkpoint()
                                                selectionMoveCheckpointed = true
                                            }
                                            val dx = if (canvasSize.width == 0) 0f else dragAmount.x / canvasSize.width * project.canvasWidth
                                            val dy = if (canvasSize.height == 0) 0f else dragAmount.y / canvasSize.height * project.canvasHeight
                                            editor.moveSelection(dx, dy)
                                        }
                                    }
                                }
                            },
                            onDragEnd = {
                                if (livePoints.isNotEmpty() && !editor.layer.locked &&
                                    (editor.tool == Tool.Brush || editor.tool == Tool.Eraser)
                                ) {
                                    val preset = if (editor.tool == Tool.Eraser) editor.eraser else editor.brush
                                    editor.layer.strokes.add(
                                        StrokeData(
                                            points = livePoints,
                                            colorArgb = editor.color.toArgb(),
                                            width = preset.width,
                                            alpha = preset.alpha,
                                            erase = editor.tool == Tool.Eraser
                                        )
                                    )
                                    editor.project.touch()
                                }
                                livePoints = emptyList()
                            },
                            onDragCancel = { livePoints = emptyList() }
                        )
                    }
            ) {
                drawCheckerboard()
                drawRect(Color(project.backgroundArgb))

                if (!editor.playing && editor.onionPrevious && editor.frameIndex > 0) {
                    drawFrame(project, project.frames[editor.frameIndex - 1], editor.onionAlpha, emptySet())
                }
                if (!editor.playing && editor.onionNext && editor.frameIndex < project.frames.lastIndex) {
                    drawFrame(project, project.frames[editor.frameIndex + 1], editor.onionAlpha, emptySet())
                }
                drawFrame(project, editor.frame, 1f, editor.selectedIds.toSet())

                if (livePoints.isNotEmpty()) {
                    val preset = if (editor.tool == Tool.Eraser) editor.eraser else editor.brush
                    drawOneStroke(
                        project = project,
                        stroke = StrokeData(
                            points = livePoints,
                            colorArgb = editor.color.toArgb(),
                            width = preset.width,
                            alpha = preset.alpha,
                            erase = editor.tool == Tool.Eraser
                        ),
                        globalAlpha = 1f,
                        selected = false
                    )
                }
            }

            if (editor.layer.locked) {
                Surface(
                    modifier = Modifier.align(Alignment.TopStart).padding(10.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.errorContainer
                ) {
                    Text("Layer locked", Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium)
                }
            }

            if (editor.selectedIds.isNotEmpty()) {
                Surface(
                    modifier = Modifier.align(Alignment.TopCenter).padding(10.dp),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Row(
                        Modifier.padding(start = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("${editor.selectedIds.size} selected", style = MaterialTheme.typography.labelMedium)
                        TextButton(onClick = {
                            history.checkpoint()
                            editor.deleteSelection()
                        }) { Text("Delete") }
                    }
                }
            }
        }
    }
}

@Composable
private fun Timeline(editor: EditorState, history: ProjectHistory) {
    val project = editor.project
    val scroll = rememberScrollState()
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Row(
            Modifier.horizontalScroll(scroll).padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            project.frames.forEachIndexed { index, frame ->
                val selected = index == editor.frameIndex
                Surface(
                    modifier = Modifier.width(104.dp).height(66.dp).clickable {
                        editor.frameIndex = index
                        editor.ensureIndices()
                        editor.selectedIds.clear()
                    },
                    shape = RoundedCornerShape(15.dp),
                    color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer
                ) {
                    Column(Modifier.padding(9.dp), verticalArrangement = Arrangement.SpaceBetween) {
                        Text("Frame ${index + 1}", fontWeight = FontWeight.Medium)
                        Text("${"%.2f".format(frame.durationMs / 1000f)} s", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FilledTonalButton(onClick = { history.checkpoint(); editor.addBlankFrame() }) { Text("+ Blank") }
            FilledTonalButton(onClick = { history.checkpoint(); editor.cloneFrame() }) { Text("Clone") }
            TextButton(onClick = { history.checkpoint(); editor.deleteFrame() }) { Text("Delete") }
            TextButton(onClick = { history.checkpoint(); editor.moveFrame(-1) }, enabled = editor.frameIndex > 0) { Text("←") }
            TextButton(onClick = { history.checkpoint(); editor.moveFrame(1) }, enabled = editor.frameIndex < project.frames.lastIndex) { Text("→") }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Hold", style = MaterialTheme.typography.labelMedium)
            Slider(
                value = editor.frame.durationMs.toFloat(),
                onValueChange = {
                    editor.frame.durationMs = it.toInt().coerceIn(50, 10000)
                    project.touch()
                },
                valueRange = 50f..10000f,
                modifier = Modifier.weight(1f).padding(horizontal = 10.dp)
            )
            Text("${"%.2f".format(editor.frame.durationMs / 1000f)}s", style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun ToolBar(
    editor: EditorState,
    onBrushes: () -> Unit,
    onErasers: () -> Unit,
    onColors: () -> Unit,
    onLayers: () -> Unit,
    onSmart: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        ToolButton("Brush", editor.tool == Tool.Brush, onBrushes)
        ToolButton("Eraser", editor.tool == Tool.Eraser, onErasers)
        ToolButton("Colour", false, onColors)
        ToolButton("Layers", false, onLayers)
        ToolButton("Smart", editor.tool == Tool.SmartSelect || editor.tool == Tool.ColorRepeat, onSmart)
    }
}

@Composable
private fun ToolButton(label: String, selected: Boolean, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 13.dp),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer
        )
    ) { Text(label) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PresetSheet(
    title: String,
    list: List<BrushPreset>,
    selected: BrushPreset,
    dismiss: () -> Unit,
    choose: (BrushPreset) -> Unit
) {
    var family by remember { mutableStateOf<String?>(null) }
    val filtered = remember(family, list) { family?.let { f -> list.filter { it.family == f } } ?: list }
    ModalBottomSheet(onDismissRequest = dismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            if (list === brushes) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(selected = family == null, onClick = { family = null }, label = { Text("All") })
                    brushFamilies.forEach { item ->
                        FilterChip(selected = family == item, onClick = { family = item }, label = { Text(item) })
                    }
                }
            }
            LazyColumn(Modifier.heightIn(max = 520.dp)) {
                items(filtered) { preset ->
                    ListItem(
                        headlineContent = { Text(preset.name) },
                        supportingContent = { Text("${preset.family} · ${preset.width.toInt()} px · ${(preset.alpha * 100).toInt()}%") },
                        trailingContent = { if (preset == selected) Text("Selected", color = MaterialTheme.colorScheme.primary) },
                        modifier = Modifier.clickable { choose(preset); dismiss() }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ColorSheet(editor: EditorState, dismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = dismiss) {
        Column(
            Modifier.fillMaxWidth().padding(18.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Colour", style = MaterialTheme.typography.titleLarge)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(54.dp)
                        .background(editor.color, CircleShape)
                        .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("HSV + opacity")
                    Text("ARGB #${editor.color.toArgb().toUInt().toString(16).uppercase().padStart(8, '0')}", style = MaterialTheme.typography.labelSmall)
                }
            }
            SliderRow("Hue", editor.hue, 0f..360f) { editor.hue = it }
            SliderRow("Saturation", editor.saturation, 0f..1f) { editor.saturation = it }
            SliderRow("Brightness", editor.value, 0f..1f) { editor.value = it }
            SliderRow("Opacity", editor.alpha, 0f..1f) { editor.alpha = it }
        }
    }
}

@Composable
private fun SliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, update: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(92.dp), style = MaterialTheme.typography.labelMedium)
        Slider(value = value, onValueChange = update, valueRange = range, modifier = Modifier.weight(1f))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LayersSheet(editor: EditorState, history: ProjectHistory, dismiss: () -> Unit) {
    var renameLayer by remember { mutableStateOf<LayerState?>(null) }
    var renameText by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = dismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp).padding(bottom = 24.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Layers", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                Text("Top first", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                FilledTonalButton(onClick = { history.checkpoint(); editor.addLayer() }) { Text("+ Layer") }
                FilledTonalButton(onClick = { history.checkpoint(); editor.duplicateLayer() }) { Text("Duplicate") }
                TextButton(onClick = { history.checkpoint(); editor.deleteLayer() }) { Text("Delete") }
            }
            LazyColumn(Modifier.heightIn(max = 470.dp)) {
                items(editor.frame.layers.indices.toList()) { index ->
                    val layer = editor.frame.layers[index]
                    val selected = index == editor.layerIndex
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp).clickable {
                            editor.layerIndex = index
                            editor.selectedIds.clear()
                        },
                        shape = RoundedCornerShape(15.dp),
                        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer
                    ) {
                        Column(Modifier.padding(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(layer.name, fontWeight = FontWeight.Medium)
                                    Text("${layer.part.label} · ${layer.strokes.size} strokes", style = MaterialTheme.typography.labelSmall)
                                }
                                TextButton(onClick = {
                                    history.checkpoint()
                                    layer.visible = !layer.visible
                                    editor.project.touch()
                                }) { Text(if (layer.visible) "Visible" else "Hidden") }
                                TextButton(onClick = {
                                    history.checkpoint()
                                    layer.locked = !layer.locked
                                    editor.project.touch()
                                }) { Text(if (layer.locked) "Locked" else "Lock") }
                            }
                            if (selected) {
                                Row(
                                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    TextButton(onClick = {
                                        renameText = layer.name
                                        renameLayer = layer
                                    }) { Text("Rename") }
                                    TextButton(onClick = { history.checkpoint(); editor.moveLayer(-1) }, enabled = index > 0) { Text("Move up") }
                                    TextButton(onClick = { history.checkpoint(); editor.moveLayer(1) }, enabled = index < editor.frame.layers.lastIndex) { Text("Move down") }
                                    Part.entries.forEach { part ->
                                        FilterChip(
                                            selected = layer.part == part,
                                            onClick = {
                                                history.checkpoint()
                                                layer.part = part
                                                editor.project.touch()
                                            },
                                            label = { Text(part.label) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    renameLayer?.let { layer ->
        AlertDialog(
            onDismissRequest = { renameLayer = null },
            title = { Text("Rename layer") },
            text = {
                OutlinedTextField(value = renameText, onValueChange = { renameText = it.take(48) }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = {
                    history.checkpoint()
                    layer.name = renameText.trim().ifBlank { "Layer" }
                    editor.project.touch()
                    renameLayer = null
                }) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { renameLayer = null }) { Text("Cancel") } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SmartSheet(editor: EditorState, dismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = dismiss) {
        Column(
            Modifier.fillMaxWidth().padding(18.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Smart selection", style = MaterialTheme.typography.titleLarge)
            Text(
                "Drag a selection to move it. Part mode uses layer part labels; colour mode finds strokes with a similar colour.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(onClick = { editor.tool = Tool.SmartSelect; dismiss() }, modifier = Modifier.fillMaxWidth()) {
                Text("Select whole body part")
            }
            FilledTonalButton(onClick = { editor.tool = Tool.ColorRepeat; dismiss() }, modifier = Modifier.fillMaxWidth()) {
                Text("Select repeated colour")
            }
            if (editor.selectedIds.isNotEmpty()) {
                Text("Current selection: ${editor.selectedIds.size} strokes", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProjectSettingsSheet(project: ProjectState, history: ProjectHistory, dismiss: () -> Unit) {
    var name by remember(project.name) { mutableStateOf(project.name) }
    ModalBottomSheet(onDismissRequest = dismiss) {
        Column(
            Modifier.fillMaxWidth().padding(18.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Project settings", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(64) },
                label = { Text("Name") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Button(onClick = {
                history.checkpoint()
                project.name = name.trim().ifBlank { "Untitled animation" }
                project.touch()
            }) { Text("Apply name") }

            Text("Canvas size", style = MaterialTheme.typography.titleSmall)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                CanvasPreset("Square", 1080, 1080, project, history)
                CanvasPreset("Portrait", 1080, 1920, project, history)
                CanvasPreset("Landscape", 1920, 1080, project, history)
                CanvasPreset("720p", 1280, 720, project, history)
            }

            Text("Background", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = project.backgroundArgb == 0xFFFFFFFF.toInt(),
                    onClick = {
                        history.checkpoint()
                        project.backgroundArgb = 0xFFFFFFFF.toInt()
                        project.touch()
                    },
                    label = { Text("White") }
                )
                FilterChip(
                    selected = project.backgroundArgb == 0x00000000,
                    onClick = {
                        history.checkpoint()
                        project.backgroundArgb = 0x00000000
                        project.touch()
                    },
                    label = { Text("Transparent") }
                )
                FilterChip(
                    selected = project.backgroundArgb == 0xFF111111.toInt(),
                    onClick = {
                        history.checkpoint()
                        project.backgroundArgb = 0xFF111111.toInt()
                        project.touch()
                    },
                    label = { Text("Dark") }
                )
            }
            Text(
                "Changing canvas dimensions keeps existing stroke coordinates unchanged. New drawings use the new canvas coordinate space.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CanvasPreset(label: String, width: Int, height: Int, project: ProjectState, history: ProjectHistory) {
    FilterChip(
        selected = project.canvasWidth == width && project.canvasHeight == height,
        onClick = {
            history.checkpoint()
            project.canvasWidth = width
            project.canvasHeight = height
            project.touch()
        },
        label = { Text(label) }
    )
}

private fun Offset.toProject(size: IntSize, project: ProjectState): CanvasPoint {
    if (size.width <= 0 || size.height <= 0) return CanvasPoint(0f, 0f)
    return CanvasPoint(
        x = (x / size.width * project.canvasWidth).coerceIn(0f, project.canvasWidth.toFloat()),
        y = (y / size.height * project.canvasHeight).coerceIn(0f, project.canvasHeight.toFloat())
    )
}

private fun DrawScope.drawCheckerboard() {
    val cell = 24f
    val first = Color(0xFFECECEC)
    val second = Color(0xFFD8D8D8)
    var y = 0f
    var row = 0
    while (y < size.height) {
        var x = 0f
        var column = 0
        while (x < size.width) {
            drawRect(
                color = if ((row + column) % 2 == 0) first else second,
                topLeft = Offset(x, y),
                size = androidx.compose.ui.geometry.Size(cell, cell)
            )
            x += cell
            column++
        }
        y += cell
        row++
    }
}

private fun DrawScope.drawFrame(
    project: ProjectState,
    frame: FrameState,
    globalAlpha: Float,
    selectedIds: Set<String>
) {
    frame.layers.asReversed().forEach { layer ->
        if (!layer.visible) return@forEach
        layer.strokes.forEach { stroke ->
            drawOneStroke(project, stroke, globalAlpha, stroke.id in selectedIds)
        }
    }
}

private fun DrawScope.drawOneStroke(
    project: ProjectState,
    stroke: StrokeData,
    globalAlpha: Float,
    selected: Boolean
) {
    if (stroke.points.isEmpty()) return
    val scaleX = size.width / project.canvasWidth
    val scaleY = size.height / project.canvasHeight
    val widthScale = minOf(scaleX, scaleY)
    val path = Path().apply {
        val first = stroke.points.first()
        moveTo(first.x * scaleX, first.y * scaleY)
        stroke.points.drop(1).forEach { point -> lineTo(point.x * scaleX, point.y * scaleY) }
    }
    val style = Stroke(
        width = (stroke.width * widthScale).coerceAtLeast(1f),
        cap = StrokeCap.Round,
        join = StrokeJoin.Round
    )
    val actualAlpha = (stroke.alpha * globalAlpha).coerceIn(0f, 1f)
    if (stroke.points.size == 1) {
        val point = stroke.points.first()
        val radius = (stroke.width * widthScale / 2f).coerceAtLeast(1f)
        if (stroke.erase) {
            drawCircle(Color.Transparent, radius, Offset(point.x * scaleX, point.y * scaleY), blendMode = BlendMode.Clear)
        } else {
            drawCircle(Color(stroke.colorArgb), radius, Offset(point.x * scaleX, point.y * scaleY), alpha = actualAlpha)
        }
    } else if (stroke.erase) {
        drawPath(path, Color.Transparent, style = style, blendMode = BlendMode.Clear)
    } else {
        drawPath(path, Color(stroke.colorArgb), alpha = actualAlpha, style = style)
    }
    if (selected) {
        drawPath(
            path,
            color = Color(0xFF6750A4),
            alpha = .6f,
            style = Stroke(width = (stroke.width * widthScale + 8f).coerceAtLeast(6f), cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}
