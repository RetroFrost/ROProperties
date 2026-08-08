package com.frameflow.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun ReleaseTimeline(editor: EditorState, history: ProjectHistory, onExactDuration: (Int) -> Unit) {
    val project = editor.project
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var pixelsPerSecond by rememberSaveable(project.id) { mutableFloatStateOf(34f) }

    Column(Modifier.fillMaxWidth().padding(top = 3.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Timeline", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { pixelsPerSecond = (pixelsPerSecond / 1.25f).coerceAtLeast(12f) }) { Text("− Zoom") }
            TextButton(onClick = { pixelsPerSecond = (pixelsPerSecond * 1.25f).coerceAtMost(160f) }) { Text("+ Zoom") }
        }
        LazyRow(
            state = listState,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            itemsIndexed(project.frames, key = { _, frame -> frame }) { index, frame ->
                val selected = index == editor.frameIndex
                val thumb = remember(project.revision, frame) { FrameRenderer.renderThumbnail(project, index, 220) }
                DisposableEffect(thumb) { onDispose { if (!thumb.isRecycled) thumb.recycle() } }
                val width = (86f + frame.durationMs / 1000f * pixelsPerSecond).coerceIn(92f, 420f).dp
                Surface(
                    modifier = Modifier.width(width).height(82.dp).clickable {
                        editor.frameIndex = index
                        editor.ensureIndices()
                        editor.clearSelection()
                    },
                    shape = RoundedCornerShape(14.dp),
                    color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer
                ) {
                    Box {
                        Image(thumb.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = .64f)
                        Column(
                            Modifier.align(Alignment.BottomStart)
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = .84f), RoundedCornerShape(topEnd = 10.dp))
                                .padding(5.dp)
                                .clickable { onExactDuration(index) }
                        ) {
                            Text(frame.label.ifBlank { "Frame ${index + 1}" }, maxLines = 1, style = MaterialTheme.typography.labelMedium)
                            Text("${"%.2f".format(frame.durationMs / 1000f)} s", style = MaterialTheme.typography.labelSmall)
                        }
                        Box(
                            Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(26.dp)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = .36f), RoundedCornerShape(topEnd = 14.dp, bottomEnd = 14.dp))
                                .pointerInput(frame, project.snapMs, pixelsPerSecond) {
                                    var accumulatedMs = 0f
                                    var startMs = frame.durationMs
                                    detectDragGestures(
                                        onDragStart = {
                                            history.checkpoint()
                                            startMs = frame.durationMs
                                            accumulatedMs = 0f
                                        },
                                        onDrag = { change, drag ->
                                            change.consume()
                                            accumulatedMs += drag.x / pixelsPerSecond * 1000f
                                            val raw = startMs + accumulatedMs.roundToInt()
                                            val snap = project.snapMs.coerceIn(10, 5000)
                                            frame.durationMs = ((raw / snap.toFloat()).roundToInt() * snap).coerceIn(50, 120000)
                                            if (change.position.x > size.width + 16f) {
                                                scope.launch { listState.scrollBy((drag.x * .85f).coerceAtLeast(3f)) }
                                            } else if (change.position.x < -16f) {
                                                scope.launch { listState.scrollBy((drag.x * .85f).coerceAtMost(-3f)) }
                                            }
                                        },
                                        onDragEnd = { project.touch() },
                                        onDragCancel = { project.touch() }
                                    )
                                }
                        )
                    }
                }
            }
            item { Spacer(Modifier.width(96.dp)) }
        }
        LazyRow(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            item { FilledTonalButton(onClick = { history.checkpoint(); editor.cloneFrame() }) { Text("Clone") } }
            item { TextButton(onClick = { history.checkpoint(); editor.addBlankFrame() }) { Text("Blank") } }
            item { TextButton(onClick = { history.checkpoint(); editor.deleteFrame() }) { Text("Delete") } }
            item { TextButton(onClick = { history.checkpoint(); editor.moveFrame(-1) }, enabled = editor.frameIndex > 0) { Text("← Frame") } }
            item { TextButton(onClick = { history.checkpoint(); editor.moveFrame(1) }, enabled = editor.frameIndex < project.frames.lastIndex) { Text("Frame →") } }
            item { Text("${project.fps} fps export", style = MaterialTheme.typography.labelSmall) }
        }
    }
}

@Composable
fun ReleaseDurationDialog(
    frame: FrameState,
    snapMs: Int,
    onDismiss: () -> Unit,
    onApply: (Int) -> Unit
) {
    var text by remember(frame) { mutableStateOf("%.3f".format(frame.durationMs / 1000.0)) }
    val parsed = text.replace(',', '.').toDoubleOrNull()?.times(1000.0)?.roundToInt()?.coerceIn(50, 120000)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Frame duration") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(10) },
                    label = { Text("Seconds") },
                    supportingText = { Text("50 ms – 120 s · timeline snap ${snapMs} ms") },
                    singleLine = true
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(100, 250, 500, 1000, 2000, 5000).forEach { ms ->
                        AssistChip(onClick = { text = "%.3f".format(ms / 1000.0) }, label = { Text(if (ms < 1000) "${ms}ms" else "${ms / 1000}s") })
                    }
                }
            }
        },
        confirmButton = { Button(enabled = parsed != null, onClick = { parsed?.let(onApply) }) { Text("Apply") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
