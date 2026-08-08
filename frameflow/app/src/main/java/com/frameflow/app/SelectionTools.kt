package com.frameflow.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Base64
import java.io.ByteArrayOutputStream
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

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
            val hitStroke = layer.strokes.asReversed().firstOrNull { hitStrokeLocal(it, localPoint) }
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
                try {
                    val pixel = rasterPixel(editor.project, bitmap, localPoint)
                    if (pixel != null && Color.alpha(pixel) > 12) {
                        editor.selectedRasterLayerIndex = index
                        editor.layerIndex = index
                        editor.selectedIds.clear()
                        return
                    }
                } finally {
                    if (!bitmap.isRecycled) bitmap.recycle()
                }
            }
            val hit = layer.strokes.asReversed().firstOrNull { !it.erase && hitStrokeLocal(it, localPoint) }
            if (hit != null) {
                editor.selectedRasterLayerIndex = -1
                editor.selectedIds.clear()
                frame.layers.flatMap { it.strokes }
                    .filter { !it.erase && colorDistanceLocal(it.colorArgb, hit.colorArgb) < tolerance.coerceIn(0f, 1f) }
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
        return try {
            val target = rasterPixel(project, bitmap, localPoint) ?: return 0
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            var changed = 0
            val safeTolerance = tolerance.coerceIn(0f, 1f)
            for (i in pixels.indices) {
                val pixel = pixels[i]
                if (Color.alpha(pixel) > 0 && colorDistanceLocal(pixel, target) <= safeTolerance) {
                    pixels[i] = if (erase) Color.TRANSPARENT else newArgb
                    changed++
                }
            }
            if (changed > 0) {
                bitmap.setPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                layer.rasterPngBase64 = encode(bitmap)
                project.touch()
            }
            changed
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
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
        val zoom = frame.cameraZoom.takeIf { it.isFinite() && kotlin.math.abs(it) >= .0001f } ?: 1f
        x = rx / zoom
        y = ry / zoom
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
        val sx = layer.scaleX.takeIf { it.isFinite() && kotlin.math.abs(it) >= .0001f } ?: 1f
        val sy = layer.scaleY.takeIf { it.isFinite() && kotlin.math.abs(it) >= .0001f } ?: 1f
        return CanvasPoint(rx / sx + cx, ry / sy + cy)
    }

    private fun rasterAlphaAt(project: ProjectState, layer: LayerState, point: CanvasPoint): Int {
        val bitmap = decode(layer.rasterPngBase64) ?: return 0
        return try {
            rasterPixel(project, bitmap, point)?.let(Color::alpha) ?: 0
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    private fun rasterPixel(project: ProjectState, bitmap: Bitmap, point: CanvasPoint): Int? {
        if (bitmap.width <= 0 || bitmap.height <= 0) return null
        val scale = minOf(project.canvasWidth.toFloat() / bitmap.width, project.canvasHeight.toFloat() / bitmap.height, 1f)
        if (!scale.isFinite() || scale <= 0f) return null
        val drawWidth = bitmap.width * scale
        val drawHeight = bitmap.height * scale
        val left = (project.canvasWidth - drawWidth) / 2f
        val top = (project.canvasHeight - drawHeight) / 2f
        if (point.x < left || point.y < top || point.x >= left + drawWidth || point.y >= top + drawHeight) return null
        val x = ((point.x - left) / scale).toInt().coerceIn(0, bitmap.width - 1)
        val y = ((point.y - top) / scale).toInt().coerceIn(0, bitmap.height - 1)
        return bitmap.getPixel(x, y)
    }

    private fun hitStrokeLocal(stroke: StrokeData, point: CanvasPoint): Boolean {
        if (stroke.points.isEmpty()) return false
        val threshold = stroke.width.takeIf { it.isFinite() }?.coerceAtLeast(1f)?.div(2f)?.plus(18f) ?: 18f
        return stroke.points.any { p ->
            p.x.isFinite() && p.y.isFinite() && point.x.isFinite() && point.y.isFinite() &&
                hypot((p.x - point.x).toDouble(), (p.y - point.y).toDouble()) <= threshold
        }
    }

    private fun colorDistanceLocal(a: Int, b: Int): Float {
        val ar = (a shr 16 and 0xFF) / 255f
        val ag = (a shr 8 and 0xFF) / 255f
        val ab = (a and 0xFF) / 255f
        val br = (b shr 16 and 0xFF) / 255f
        val bg = (b shr 8 and 0xFF) / 255f
        val bb = (b and 0xFF) / 255f
        return sqrt((ar - br) * (ar - br) + (ag - bg) * (ag - bg) + (ab - bb) * (ab - bb))
    }

    private fun decode(base64: String?): Bitmap? {
        if (base64.isNullOrBlank()) return null
        val bytes = runCatching { Base64.decode(base64, Base64.DEFAULT) }.getOrNull() ?: return null
        if (bytes.size > 64 * 1024 * 1024) return null
        return runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
    }

    private fun encode(bitmap: Bitmap): String {
        val bytes = ByteArrayOutputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            output.toByteArray()
        }
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
}
