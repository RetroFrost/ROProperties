package com.frameflow.app

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReleaseBrushSheet(editor: EditorState, eraser: Boolean, onTool: (ReleaseTool) -> Unit, dismiss: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("frameflow-brush-library", 0) }
    var favourites by remember { mutableStateOf(prefs.getStringSet(if (eraser) "eraser-favourites" else "brush-favourites", emptySet())?.toSet().orEmpty()) }
    var recent by remember { mutableStateOf(prefs.getString(if (eraser) "eraser-recent" else "brush-recent", "")!!.split('|').filter { it.isNotBlank() }) }
    var query by remember { mutableStateOf("") }
    var family by remember { mutableStateOf<String?>(null) }
    var mode by remember { mutableStateOf("All") }
    val source = if (eraser) erasers else brushes
    val selected = if (eraser) editor.eraser else editor.brush

    fun persistFavourite(name: String) {
        favourites = if (name in favourites) favourites - name else favourites + name
        prefs.edit().putStringSet(if (eraser) "eraser-favourites" else "brush-favourites", favourites).apply()
    }
    fun choose(preset: BrushPreset) {
        if (eraser) editor.eraser = preset else editor.brush = preset
        recent = (listOf(preset.name) + recent.filterNot { it == preset.name }).take(12)
        prefs.edit().putString(if (eraser) "eraser-recent" else "brush-recent", recent.joinToString("|")).apply()
        onTool(if (eraser) ReleaseTool.Eraser else ReleaseTool.Brush)
    }

    val filtered = source.filter { preset ->
        (query.isBlank() || preset.name.contains(query, true) || preset.family.contains(query, true)) &&
            (family == null || preset.family == family) &&
            (mode != "Favourites" || preset.name in favourites) &&
            (mode != "Recent" || preset.name in recent)
    }

    ModalBottomSheet(onDismissRequest = dismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 26.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (eraser) "Erasers · ${erasers.size}" else "Brushes · ${brushes.size}", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = dismiss) { Text("Done") }
            }
            OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Search") }, singleLine = true)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                listOf("All", "Favourites", "Recent").forEach { item ->
                    item { FilterChip(mode == item, { mode = item }, { Text(item) }) }
                }
            }
            if (!eraser) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    item { FilterChip(family == null, { family = null }, { Text("All families") }) }
                    items(brushFamilies) { item -> FilterChip(family == item, { family = item }, { Text(item) }) }
                }
            }
            ReleaseSectionTitle("Current preset", "Size and flow are independent per selected preset")
            ReleaseSliderRow("Size", selected.width, 1f..240f, "${selected.width.roundToInt()} px") {
                if (eraser) editor.eraser = editor.eraser.copy(width = it) else editor.brush = editor.brush.copy(width = it)
            }
            ReleaseSliderRow("Flow", selected.alpha, .02f..1f, "${(selected.alpha * 100).roundToInt()}%") {
                if (eraser) editor.eraser = editor.eraser.copy(alpha = it) else editor.brush = editor.brush.copy(alpha = it)
            }
            LazyColumn(Modifier.heightIn(max = 470.dp)) {
                items(filtered, key = { it.name }) { preset ->
                    val active = preset.name == selected.name
                    ListItem(
                        headlineContent = { Text(preset.name) },
                        supportingContent = {
                            Text(
                                if (eraser) eraserDescription(preset)
                                else "${preset.family} · ${preset.width.roundToInt()} px · ${(preset.alpha * 100).roundToInt()}% · ${brushDescription(preset)}"
                            )
                        },
                        leadingContent = { RadioButton(active, onClick = { choose(preset) }) },
                        trailingContent = {
                            IconButton(onClick = { persistFavourite(preset.name) }) { Text(if (preset.name in favourites) "★" else "☆") }
                        },
                        modifier = Modifier.clickable { choose(preset) }
                    )
                }
            }
        }
    }
}

private fun brushDescription(preset: BrushPreset): String = when (preset.family) {
    "Ink" -> "smooth ink"
    "Pencil" -> "layered graphite grain"
    "Marker" -> "square translucent marker"
    "Paint" -> "wet layered edge"
    "Pixel" -> "hard pixel stamps"
    "Spray" -> "particle spray"
    "Chalk" -> "grainy chalk particles"
    "Calligraphy" -> "angled nib"
    "Highlighter" -> "wide translucent stroke"
    "Texture" -> "pattern particles"
    "Crayon" -> "wax grain"
    "Airbrush" -> "soft airbrush"
    else -> "stroke"
}

private fun eraserDescription(preset: BrushPreset): String {
    val n = preset.name.substringAfterLast(' ').toIntOrNull() ?: 1
    return when {
        n <= 20 -> "Hard eraser · ${preset.width.roundToInt()} px"
        n <= 40 -> "Soft edge eraser · ${preset.width.roundToInt()} px"
        else -> "Textured eraser · ${preset.width.roundToInt()} px"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReleaseColourSheet(editor: EditorState, recent: MutableList<Int>, dismiss: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("frameflow-colours", 0) }
    var saved by remember {
        mutableStateOf(
            prefs.getString("palette", "")!!.split(',').mapNotNull { it.toLongOrNull(16)?.toInt() }.take(32)
        )
    }
    var hex by remember(editor.color.toArgb()) { mutableStateOf("#${editor.color.toArgb().toUInt().toString(16).uppercase().padStart(8, '0')}") }
    var red by remember(editor.color.toArgb()) { mutableStateOf(AndroidColor.red(editor.color.toArgb()).toString()) }
    var green by remember(editor.color.toArgb()) { mutableStateOf(AndroidColor.green(editor.color.toArgb()).toString()) }
    var blue by remember(editor.color.toArgb()) { mutableStateOf(AndroidColor.blue(editor.color.toArgb()).toString()) }

    fun applyArgb(argb: Int) {
        val hsv = FloatArray(3)
        AndroidColor.colorToHSV(argb, hsv)
        editor.hue = hsv[0]
        editor.saturation = hsv[1]
        editor.value = hsv[2]
        editor.alpha = AndroidColor.alpha(argb) / 255f
        hex = "#${argb.toUInt().toString(16).uppercase().padStart(8, '0')}"
        red = AndroidColor.red(argb).toString(); green = AndroidColor.green(argb).toString(); blue = AndroidColor.blue(argb).toString()
        recent.remove(argb); recent.add(0, argb); while (recent.size > 16) recent.removeAt(recent.lastIndex)
    }

    ModalBottomSheet(onDismissRequest = dismiss) {
        Column(Modifier.fillMaxWidth().padding(18.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Colour", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                Box(Modifier.size(48.dp).background(editor.color, CircleShape).border(1.dp, MaterialTheme.colorScheme.outline, CircleShape))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(hex, { hex = it.take(9) }, modifier = Modifier.weight(1f), label = { Text("ARGB hex") }, singleLine = true)
                Button(onClick = {
                    parseHex(hex)?.let(::applyArgb)
                }) { Text("Apply") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(red, { red = it.filter(Char::isDigit).take(3) }, Modifier.weight(1f), label = { Text("R") }, singleLine = true)
                OutlinedTextField(green, { green = it.filter(Char::isDigit).take(3) }, Modifier.weight(1f), label = { Text("G") }, singleLine = true)
                OutlinedTextField(blue, { blue = it.filter(Char::isDigit).take(3) }, Modifier.weight(1f), label = { Text("B") }, singleLine = true)
                Button(onClick = {
                    val r = red.toIntOrNull()?.coerceIn(0, 255) ?: return@Button
                    val g = green.toIntOrNull()?.coerceIn(0, 255) ?: return@Button
                    val b = blue.toIntOrNull()?.coerceIn(0, 255) ?: return@Button
                    applyArgb(AndroidColor.argb((editor.alpha * 255).roundToInt(), r, g, b))
                }) { Text("RGB") }
            }
            ReleaseSliderRow("Hue", editor.hue, 0f..360f, "${editor.hue.roundToInt()}°") { editor.hue = it }
            ReleaseSliderRow("Saturation", editor.saturation, 0f..1f, "${(editor.saturation * 100).roundToInt()}%") { editor.saturation = it }
            ReleaseSliderRow("Brightness", editor.value, 0f..1f, "${(editor.value * 100).roundToInt()}%") { editor.value = it }
            ReleaseSliderRow("Opacity", editor.alpha, 0f..1f, "${(editor.alpha * 100).roundToInt()}%") { editor.alpha = it }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Saved palette", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    val argb = editor.color.toArgb()
                    saved = (listOf(argb) + saved.filterNot { it == argb }).take(32)
                    prefs.edit().putString("palette", saved.joinToString(",") { it.toUInt().toString(16) }).apply()
                }) { Text("+ Save colour") }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                items(saved) { argb -> colourSwatch(argb) { applyArgb(argb) } }
            }
            if (recent.isNotEmpty()) {
                Text("Recent", style = MaterialTheme.typography.titleSmall)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    items(recent) { argb -> colourSwatch(argb) { applyArgb(argb) } }
                }
            }
        }
    }
}

@Composable
private fun colourSwatch(argb: Int, click: () -> Unit) {
    Box(Modifier.size(38.dp).background(Color(argb), CircleShape).border(1.dp, MaterialTheme.colorScheme.outline, CircleShape).clickable(onClick = click))
}

private fun parseHex(text: String): Int? = runCatching {
    val raw = text.trim().removePrefix("#")
    when (raw.length) {
        3 -> {
            val r = "${raw[0]}${raw[0]}".toInt(16); val g = "${raw[1]}${raw[1]}".toInt(16); val b = "${raw[2]}${raw[2]}".toInt(16)
            AndroidColor.rgb(r, g, b)
        }
        6 -> (0xFF000000L or raw.toLong(16)).toInt()
        8 -> raw.toLong(16).toInt()
        else -> error("bad hex")
    }
}.getOrNull()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReleaseOnionSheet(
    before: Int,
    after: Int,
    alpha: Float,
    selectedLayerOnly: Boolean,
    onBefore: (Int) -> Unit,
    onAfter: (Int) -> Unit,
    onAlpha: (Float) -> Unit,
    onLayerOnly: (Boolean) -> Unit,
    dismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = dismiss) {
        Column(Modifier.fillMaxWidth().padding(18.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Onion skin", style = MaterialTheme.typography.titleLarge)
            ReleaseSliderRow("Previous frames", before.toFloat(), 0f..5f, before.toString()) { onBefore(it.roundToInt().coerceIn(0, 5)) }
            ReleaseSliderRow("Next frames", after.toFloat(), 0f..5f, after.toString()) { onAfter(it.roundToInt().coerceIn(0, 5)) }
            ReleaseSliderRow("Opacity", alpha, .02f..6f/10f, "${(alpha * 100).roundToInt()}%") { onAlpha(it) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(selectedLayerOnly, onLayerOnly)
                Spacer(Modifier.width(10.dp))
                Column { Text("Selected layer only"); Text("Useful when animating one limb over a held body", style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReleaseToolsSheet(onTool: (ReleaseTool) -> Unit, dismiss: () -> Unit) {
    val tools = listOf(
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
            tools.forEach { (tool, description) ->
                ListItem(
                    headlineContent = { Text(tool.label) },
                    supportingContent = { Text(description) },
                    modifier = Modifier.clickable { onTool(tool); dismiss() }
                )
            }
        }
    }
}
