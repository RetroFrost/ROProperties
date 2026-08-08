package com.frameflow.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReleaseSmartSheet(editor: EditorState, history: ProjectHistory, onMessage: (String) -> Unit, dismiss: () -> Unit) {
    var command by rememberSaveable(editor.project.id) { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = dismiss) {
        Column(Modifier.fillMaxWidth().padding(16.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("Smart tools", style = MaterialTheme.typography.titleLarge)
            Text("Every action below edits normal frames/layers and can be undone.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(enabled = !busy, onClick = {
                    busy = true
                    SmartPartOperations.identifyAndSplit(
                        editor, history,
                        onSuccess = { count -> busy = false; onMessage("Identified $count whole painted parts") },
                        onFailure = { error -> busy = false; onMessage("Limb identification failed: ${error.message ?: "unknown error"}") }
                    )
                }) { Text(if (busy) "Identifying…" else "Identify limbs") }
                TextButton(onClick = {
                    history.checkpoint()
                    val count = ObjectShowTools.addMissingStickLimbs(editor)
                    onMessage(if (count == 0) "No limb layers were missing" else "Added $count stick-limb layers")
                }) { Text("Missing limbs") }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                item { AssistChip(onClick = { history.checkpoint(); onMessage(if (SmartAnimationTools.insertInbetween(editor.project, editor.frameIndex)) "Inserted an editable in-between" else "A next frame is required") }, label = { Text("In-between") }) }
                item { AssistChip(onClick = { history.checkpoint(); onMessage(if (SmartAnimationTools.continueMotion(editor.project, editor.frameIndex)) "Continued the motion" else "A previous frame is required") }, label = { Text("Continue motion") }) }
                item { AssistChip(onClick = { history.checkpoint(); val n = SmartAnimationTools.makeSmoother(editor.project, editor.frameIndex); onMessage(if (n > 0) "Inserted $n smoothing frame" else "A next frame is required") }, label = { Text("Make smoother") }) }
                item { AssistChip(onClick = { history.checkpoint(); val n = SmartAnimationTools.makeSnappier(editor.project); editor.ensureIndices(); onMessage("Merged $n visually identical frames") }, label = { Text("Make snappier") }) }
                item { AssistChip(onClick = { history.checkpoint(); val n = SmartAnimationTools.fixFrame(editor.project, editor.frameIndex); onMessage(if (n > 0) "Restored $n missing semantic layers" else "No missing semantic layer found") }, label = { Text("Fix frame") }) }
                item { AssistChip(onClick = { history.checkpoint(); SmartAnimationTools.closeLoop(editor.project); onMessage("Added an editable loop-closing frame") }, label = { Text("Close loop") }) }
            }
            ReleaseSectionTitle("Pose presets")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                ObjectShowTools.Pose.entries.forEach { pose ->
                    item { SuggestionChip(onClick = { history.checkpoint(); ObjectShowTools.applyPose(editor, pose) }, label = { Text(pose.label) }) }
                }
            }
            ReleaseSectionTitle("Command", "Local deterministic command interpreter; no network or API key")
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    command,
                    { command = it.take(220) },
                    modifier = Modifier.weight(1f),
                    label = { Text("e.g. clone, hold 2 seconds, rotate 20, bounce and zoom in") },
                    singleLine = true
                )
                Button(onClick = {
                    history.checkpoint()
                    runCatching { SmartAnimationTools.applyCommand(editor, command) }
                        .onSuccess(onMessage)
                        .onFailure { onMessage(it.message ?: "Command failed") }
                }) { Text("Run") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReleaseAudioSheet(
    editor: EditorState,
    history: ProjectHistory,
    repository: ProjectRepository,
    onImportAudio: () -> Unit,
    onMessage: (String) -> Unit,
    dismiss: () -> Unit
) {
    val project = editor.project
    val scope = rememberCoroutineScope()
    val file = remember(project.audioFileName, project.revision) { repository.audioFile(project) }
    var previousVolume by remember { mutableFloatStateOf(if (project.audioVolume > 0f) project.audioVolume else 1f) }
    val waveform by produceState<FloatArray?>(initialValue = null, file?.absolutePath, file?.lastModified()) {
        value = file?.let { audio -> runCatching { withContext(Dispatchers.IO) { AudioWaveformTools.decodeWaveform(audio) } }.getOrNull() }
    }

    ModalBottomSheet(onDismissRequest = dismiss) {
        Column(Modifier.fillMaxWidth().padding(18.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Audio", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                FilledTonalButton(onClick = onImportAudio) { Text(if (file == null) "Import audio" else "Replace") }
            }
            if (file == null) {
                Text("No audio track. Import any format supported by Android's media codecs.", style = MaterialTheme.typography.bodyMedium)
            } else {
                Text(file.name, style = MaterialTheme.typography.titleSmall)
                Surface(Modifier.fillMaxWidth().height(92.dp), shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                    if (waveform == null) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp)) }
                    } else {
                        WaveformView(waveform ?: FloatArray(0))
                    }
                }
                HistorySliderRow("audio-volume", "Volume", project.audioVolume, 0f..1f, "${(project.audioVolume * 100).roundToInt()}%", history) {
                    if (it > 0f) previousVolume = it
                    project.audioVolume = it; project.touch()
                }
                HistorySliderRow("audio-offset", "Timeline offset", project.audioOffsetMs.toFloat(), -10_000f..10_000f, "${"%.2f".format(project.audioOffsetMs / 1000f)} s", history) {
                    project.audioOffsetMs = it.roundToInt(); project.touch()
                }
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    TextButton(onClick = {
                        history.checkpoint()
                        if (project.audioVolume > 0f) { previousVolume = project.audioVolume; project.audioVolume = 0f }
                        else project.audioVolume = previousVolume.coerceIn(.01f, 1f)
                        project.touch()
                    }) { Text(if (project.audioVolume == 0f) "Unmute" else "Mute") }
                    TextButton(onClick = {
                        scope.launch {
                            runCatching { withContext(Dispatchers.IO) { repository.removeAudio(project) } }
                                .onSuccess { onMessage("Audio removed") }
                                .onFailure { onMessage("Could not remove audio: ${it.message ?: "storage error"}") }
                        }
                    }) { Text("Remove audio") }
                }
                Text("The offset is respected by playback and MP4 export. Positive values delay audio; negative values start it later in the source track.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun WaveformView(values: FloatArray) {
    val colour = MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 7.dp)) {
        if (values.isEmpty()) return@Canvas
        val centre = size.height / 2f
        val step = size.width / values.size.coerceAtLeast(1)
        values.forEachIndexed { index, amplitude ->
            val half = (amplitude.coerceIn(.015f, 1f) * centre * .9f).coerceAtLeast(1f)
            val x = index * step + step / 2f
            drawLine(colour, Offset(x, centre - half), Offset(x, centre + half), strokeWidth = step.coerceIn(1f, 4f), cap = StrokeCap.Round)
        }
    }
}
