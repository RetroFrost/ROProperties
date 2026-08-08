package com.frameflow.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Base64
import java.io.ByteArrayOutputStream
import kotlin.math.cos
import kotlin.math.sin

object SelectionTools {
    fun selectPartAt(editor: EditorState, visiblePoint: CanvasPoint) {
        val frame = editor.frame
        val scenePoint = inverseCamera(editor.project, frame, visiblePoint)
        for (index in frame.layers.indices) {
            val layer = frame.layers[index]
            if (!layer.visible) continue
            val localPoint = inverseLayer(editor.project, layer, scenePoint)
            if (layer.hasRaster && rasterAlphaAt(editor.project, layer, localPoint) > 24) {
                val targetPart = layer.part
                editor.selectedIds.clear()
                if (targetPart != Part.None && targetPart != Part.Background) {
                    val samePartRaster = frame.layers.indexOfFirst { it.visible && it.part == targetPart && it.hasRaster }
                    editor.selectedRasterLayerIndex = if (samePartRaster >= 0) samePartRaster else index
                    editor.layerIndex = editor.selectedRasterLayerIndex
                } else {
                    editor.selectedRasterLayerIndex = index
                    editor.layerIndex = index
                }
                return
            }
            val hitStroke = layer.strokes.asReversed().firstOrNull { hitStroke(it, localPoint) }
            if (hitStroke != null) {
                editor.selectedRasterLayerIndex = -1
                editor.selectedIds.clear()
                val targetPart = layer.part
                if (targetPart != Part.None && targetPart != Part.Background) {
                    frame.layers.filter { it.part == targetPart }.flatMap { it.strokes }.forEach { editor.selectedIds.add(it.id) }
                } else editor.selectedIds.add(hitStroke.id)
                editor.layerIndex = index
                return
            }
        }
        editor.clearSelection()
    }

    fun selectRepeatedColorAt(editor: EditorState, visiblePoint: CanvasPoint, tolerance: Float = .08f) {
        val frame = editor.frame
        val scenePoint = inverseCamera(editor.project, frame, visiblePoint)
        for (index in frame.layers.indices) {
            val layer = frame.layers[index]
            if (!layer.visible) continue
            val localPoint = inverseLayer(editor.project, layer, scenePoint)
            if (layer.hasRaster) {
                val bitmap = decode(layer.rasterPngBase64) ?: continue
                val pixel = rasterPixel(editor.project, bitmap, localPoint)
                if (pixel != null && Color.alpha(pixel) > 12) {
                    bitmap.recycle()
                    editor.selectedRasterLayerIndex = index
                    editor.layerIndex = index
                    editor.selectedIds.clear()
                    return
                }
                bitmap.recycle()
            }
            val hit = layer.strokes.asReversed().firstOrNull { !it.erase && hitStroke(it, localPoint) }
            if (hit != null) {
                editor.selectedRasterLayerIndex = -1
                editor.selectedIds.clear()
                frame.layers.flatMap { it.strokes }
                    .filter { !it.erase && colorDistance(it.colorArgb, hit.colorArgb) < tolerance }
                    .forEach { editor.selectedIds.add(it.id) }
                return
            }
        }
        editor.clearSelection()
    }

    fun replaceRepeatedRasterColor(
        project: ProjectState,
        layer: LayerState,
        visiblePoint: CanvasPoint,
        newArgb: Int,
        tolerance: Float = .10f,
        erase: Boolean = false
    ): Int {
        val frame = project.frames.firstOrNull { layer in it.layers } ?: return 0
        val scenePoint = inverseCamera(project, frame, visiblePoint)
        val localPoint = inverseLayer(project, layer, scenePoint)
        val bitmap = decode(layer.rasterPngBase64) ?: return 0
        val target = rasterPixel(project, bitmap, localPoint) ?: run { bitmap.recycle(); return 0 }
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        var changed = 0
        for (i in pixels.indices) {
            val pixel = pixels[i]
            if (Color.alpha(pixel) > 0 && colorDistance(pixel, target) <= tolerance) {
                pixels[i] = if (erase) Color.TRANSPARENT else newArgb
                changed++
            }
        }
        if (changed > 0) {
            bitmap.setPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            layer.rasterPngBase64 = encode(bitmap)
            project.touch()
        }
        bitmap.recycle()
        return changed
    }

    fun inverseCamera(project: ProjectState, frame: FrameState, point: CanvasPoint): CanvasPoint {
        val cx = project.canvasWidth / 2f
        val cy = project.canvasHeight / 2f
        var x = point.x - cx - frame.cameraX
        var y = point.y - cy - frame.cameraY
        val radians = Math.toRadians((-frame.cameraRotation).toDouble())
        val c = cos(radians).toFloat()
        val s = sin(radians).toFloat()
        val rx = x * c - y * s
        val ry = x * s + y * c
        x = rx / frame.cameraZoom
        y = ry / frame.cameraZoom
        return CanvasPoint(x + cx, y + cy)
    }

    fun inverseLayer(project: ProjectState, layer: LayerState, point: CanvasPoint): CanvasPoint {
        val cx = project.canvasWidth / 2f
        val cy = project.canvasHeight / 2f
        var x = point.x - cx - layer.offsetX
        var y = point.y - cy - layer.offsetY
        val radians = Math.toRadians((-layer.rotationDeg).toDouble())
        val c = cos(radians).toFloat()
        val s = sin(radians).toFloat()
        val rx = x * c - y * s
        val ry = x * s + y * c
        x = if (layer.scaleX == 0f) rx else rx / layer.scaleX
        y = if (layer.scaleY == 0f) ry else ry / layer.scaleY
        return CanvasPoint(x + cx, y + cy)
    }

    private fun rasterAlphaAt(project: ProjectState, layer: LayerState, point: CanvasPoint): Int {
        val bitmap = decode(layer.rasterPngBase64) ?: return 0
        val pixel = rasterPixel(project, bitmap, point)
        bitmap.recycle()
        return pixel?.let(Color::alpha) ?: 0
    }

    private fun rasterPixel(project: ProjectState, bitmap: Bitmap, point: CanvasPoint): Int? {
        val scale = minOf(project.canvasWidth.toFloat() / bitmap.width, project.canvasHeight.toFloat() / bitmap.height, 1f)
        val drawWidth = bitmap.width * scale
        val drawHeight = bitmap.height * scale
        val left = (project.canvasWidth - drawWidth) / 2f
        val top = (project.canvasHeight - drawHeight) / 2f
        if (point.x < left || point.y < top || point.x >= left + drawWidth || point.y >= top + drawHeight) return null
        val x = ((point.x - left) / scale).toInt().coerceIn(0, bitmap.width - 1)
        val y = ((point.y - top) / scale).toInt().coerceIn(0, bitmap.height - 1)
        return bitmap.getPixel(x, y)
    }

    private fun decode(base64: String?): Bitmap? {
        if (base64.isNullOrBlank()) return null
        val bytes = runCatching { Base64.decode(base64, Base64.DEFAULT) }.getOrNull() ?: return null
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    private fun encode(bitmap: Bitmap): String {
        val bytes = ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            output.toByteArray()
        }
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
}
