package com.frameflow.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class FaceAssetLibrary(context: Context) {
    private val dir = File(context.filesDir, "frameflow-face-library").apply { mkdirs() }

    data class FaceAsset(val id: String, val name: String, val layers: List<LayerState>)

    fun list(): List<FaceAsset> = dir.listFiles().orEmpty()
        .filter { it.isFile && it.extension == "json" && it.length() in 1..64L * 1024L * 1024L }
        .mapNotNull { runCatching { decode(it.readText()) }.getOrNull() }
        .sortedBy { it.name.lowercase() }

    fun save(name: String, frame: FrameState): FaceAsset {
        val faceLayers = frame.layers.filter { it.part in setOf(Part.Face, Part.Eyes, Part.Mouth, Part.Eyebrows) && !it.isRigSource }
        require(faceLayers.isNotEmpty()) { "No Face/Eyes/Mouth/Eyebrows layers are available to save" }
        val asset = FaceAsset(UUID.randomUUID().toString(), name.trim().take(64).ifBlank { "Face" }, faceLayers.map { it.cloneLayer() })
        val file = File(dir, "${asset.id}.json")
        file.writeText(encode(asset).toString())
        return asset
    }

    fun apply(asset: FaceAsset, editor: EditorState): Int {
        editor.ensureIndices()
        val frame = editor.frame
        frame.layers.removeAll { it.part in setOf(Part.Face, Part.Eyes, Part.Mouth, Part.Eyebrows) && !it.isRigSource }
        val copies = asset.layers.map { it.cloneLayer().also { layer -> layer.folderName = layer.folderName ?: "Face" } }
        frame.layers.addAll(0, copies)
        editor.layerIndex = 0
        editor.clearSelection()
        editor.project.touch()
        return copies.size
    }

    fun delete(id: String): Boolean = id.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,128}")) }
        ?.let { File(dir, "$it.json").delete() } ?: false

    private fun encode(asset: FaceAsset) = JSONObject().apply {
        put("id", asset.id)
        put("name", asset.name)
        put("layers", JSONArray().apply { asset.layers.forEach { put(encodeLayer(it)) } })
    }

    private fun encodeLayer(layer: LayerState) = JSONObject().apply {
        put("name", layer.name)
        put("part", layer.part.name)
        put("visible", layer.visible)
        put("locked", layer.locked)
        put("opacity", layer.opacity.toDouble())
        put("offsetX", layer.offsetX.toDouble())
        put("offsetY", layer.offsetY.toDouble())
        put("scaleX", layer.scaleX.toDouble())
        put("scaleY", layer.scaleY.toDouble())
        put("rotationDeg", layer.rotationDeg.toDouble())
        put("rasterPngBase64", layer.rasterPngBase64 ?: JSONObject.NULL)
        put("rasterName", layer.rasterName ?: JSONObject.NULL)
        put("folderName", layer.folderName ?: JSONObject.NULL)
        put("clipToBelow", layer.clipToBelow)
        put("strokes", JSONArray().apply {
            layer.strokes.forEach { stroke ->
                put(JSONObject().apply {
                    put("id", stroke.id)
                    put("colorArgb", stroke.colorArgb)
                    put("width", stroke.width.toDouble())
                    put("alpha", stroke.alpha.toDouble())
                    put("erase", stroke.erase)
                    put("points", JSONArray().apply { stroke.points.forEach { point -> put(JSONArray().put(point.x.toDouble()).put(point.y.toDouble())) } })
                })
            }
        })
    }

    private fun decode(text: String): FaceAsset {
        require(text.length <= 64 * 1024 * 1024) { "Face asset is too large" }
        val root = JSONObject(text)
        val id = root.optString("id").takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,128}")) } ?: UUID.randomUUID().toString()
        val name = root.optString("name", "Face").take(64)
        val array = root.optJSONArray("layers") ?: JSONArray()
        require(array.length() <= 64) { "Face asset contains too many layers" }
        val layers = buildList {
            for (i in 0 until array.length()) {
                val layer = array.optJSONObject(i) ?: continue
                val strokeArray = layer.optJSONArray("strokes") ?: JSONArray()
                require(strokeArray.length() <= 100_000) { "Face asset contains too many strokes" }
                val strokes = buildList {
                    for (s in 0 until strokeArray.length()) {
                        val item = strokeArray.optJSONObject(s) ?: continue
                        val pointArray = item.optJSONArray("points") ?: JSONArray()
                        require(pointArray.length() <= 1_000_000) { "Face stroke has too many points" }
                        val points = buildList {
                            for (p in 0 until pointArray.length()) {
                                val point = pointArray.optJSONArray(p) ?: continue
                                val x = point.optDouble(0, Double.NaN).toFloat()
                                val y = point.optDouble(1, Double.NaN).toFloat()
                                if (x.isFinite() && y.isFinite()) add(CanvasPoint(x, y))
                            }
                        }
                        add(StrokeData(
                            id = item.optString("id", UUID.randomUUID().toString()),
                            points = points,
                            colorArgb = item.optInt("colorArgb", 0xFF000000.toInt()),
                            width = item.optDouble("width", 8.0).toFloat().takeIf { it.isFinite() }?.coerceIn(.1f, 4096f) ?: 8f,
                            alpha = item.optDouble("alpha", 1.0).toFloat().takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 1f,
                            erase = item.optBoolean("erase", false)
                        ))
                    }
                }
                val part = runCatching { Part.valueOf(layer.optString("part", Part.Face.name)) }.getOrDefault(Part.Face)
                if (part !in setOf(Part.Face, Part.Eyes, Part.Mouth, Part.Eyebrows)) continue
                add(LayerState(
                    name = layer.optString("name", part.label).take(64),
                    part = part,
                    strokes = strokes,
                    visible = layer.optBoolean("visible", true),
                    locked = layer.optBoolean("locked", false),
                    opacity = layer.optDouble("opacity", 1.0).toFloat().takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 1f,
                    offsetX = layer.optDouble("offsetX", 0.0).toFloat().takeIf { it.isFinite() } ?: 0f,
                    offsetY = layer.optDouble("offsetY", 0.0).toFloat().takeIf { it.isFinite() } ?: 0f,
                    scaleX = layer.optDouble("scaleX", 1.0).toFloat().takeIf { it.isFinite() }?.coerceIn(-20f, 20f) ?: 1f,
                    scaleY = layer.optDouble("scaleY", 1.0).toFloat().takeIf { it.isFinite() }?.coerceIn(-20f, 20f) ?: 1f,
                    rotationDeg = layer.optDouble("rotationDeg", 0.0).toFloat().takeIf { it.isFinite() } ?: 0f,
                    rasterPngBase64 = layer.optString("rasterPngBase64").takeIf { it.isNotBlank() && it != "null" && it.length <= 96 * 1024 * 1024 },
                    rasterName = layer.optString("rasterName").takeIf { it.isNotBlank() && it != "null" }?.take(120),
                    folderName = layer.optString("folderName").takeIf { it.isNotBlank() && it != "null" }?.take(64),
                    clipToBelow = layer.optBoolean("clipToBelow", false)
                ))
            }
        }
        require(layers.isNotEmpty()) { "Face asset is empty" }
        return FaceAsset(id, name, layers)
    }
}
