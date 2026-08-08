package com.frameflow.app

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.util.BitSet
import kotlin.math.abs

private const val PICK_PREVIEW_SIDE = 1400
private const val FILL_WORKING_SIDE = 1024
private const val MAX_FILL_PIXELS = 1_100_000

object RasterEditingTools {
    fun sampleVisibleColor(project: ProjectState, frameIndex: Int, point: CanvasPoint): Int? {
        if (project.frames.isEmpty()) return null
        if (point.x !in 0f..<project.canvasWidth.toFloat() || point.y !in 0f..<project.canvasHeight.toFloat()) return null
        val bitmap = FrameRenderer.renderPreview(project, frameIndex.coerceIn(project.frames.indices), PICK_PREVIEW_SIDE)
        return try {
            val px = projectToBitmapX(point.x, project.canvasWidth, bitmap.width)
            val py = projectToBitmapY(point.y, project.canvasHeight, bitmap.height)
            bitmap.getPixel(px, py)
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    /**
     * Flood-fills a bounded working render and places the result on its own
     * transparent raster layer. The layer is scaled back to project coordinates,
     * so large canvases never require several full-resolution arrays for one tap.
     */
    fun floodFillVisible(
        project: ProjectState,
        frameIndex: Int,
        point: CanvasPoint,
        fillArgb: Int,
        tolerance: Float = .08f
    ): Int {
        require(project.frames.isNotEmpty()) { "Project has no frames" }
        require(point.x.isFinite() && point.y.isFinite()) { "Invalid fill point" }
        if (point.x !in 0f..<project.canvasWidth.toFloat() || point.y !in 0f..<project.canvasHeight.toFloat()) return 0

        val safeFrameIndex = frameIndex.coerceIn(project.frames.indices)
        val frame = project.frames[safeFrameIndex]
        val sourceBitmap = FrameRenderer.renderPreview(project, safeFrameIndex, FILL_WORKING_SIDE)
        val width = sourceBitmap.width
        val height = sourceBitmap.height
        val totalLong = width.toLong() * height.toLong()
        require(totalLong in 1..MAX_FILL_PIXELS.toLong()) { "Fill working image is too large" }
        val total = totalLong.toInt()
        val sx = projectToBitmapX(point.x, project.canvasWidth, width)
        val sy = projectToBitmapY(point.y, project.canvasHeight, height)

        val source = IntArray(total)
        try {
            sourceBitmap.getPixels(source, 0, width, 0, 0, width, height)
        } finally {
            if (!sourceBitmap.isRecycled) sourceBitmap.recycle()
        }

        val target = source[sy * width + sx]
        val safeTolerance = tolerance.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: .08f
        if (distance(target, fillArgb) <= .005f) return 0

        val output = IntArray(total)
        val visited = BitSet(total)
        // A bounded stack avoids repeated array growth and cannot exceed the working image.
        val stack = IntArray(total)
        var stackSize = 0
        fun push(index: Int) {
            if (index in 0 until total && stackSize < stack.size) stack[stackSize++] = index
        }
        push(sy * width + sx)
        var changed = 0

        fun matches(index: Int): Boolean = index in 0 until total && !visited[index] && distance(source[index], target) <= safeTolerance

        while (stackSize > 0) {
            val seed = stack[--stackSize]
            if (!matches(seed)) continue
            val y = seed / width
            var x = seed % width
            while (x > 0 && matches(y * width + x - 1)) x--

            var spanAbove = false
            var spanBelow = false
            while (x < width) {
                val index = y * width + x
                if (!matches(index)) break
                visited.set(index)
                output[index] = fillArgb
                changed++

                if (y > 0) {
                    val above = index - width
                    if (matches(above)) {
                        if (!spanAbove) push(above)
                        spanAbove = true
                    } else spanAbove = false
                }
                if (y < height - 1) {
                    val below = index + width
                    if (matches(below)) {
                        if (!spanBelow) push(below)
                        spanBelow = true
                    } else spanBelow = false
                }
                x++
            }
        }

        if (changed == 0) return 0
        val mask = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val encoded = try {
            mask.setPixels(output, 0, width, 0, 0, width, height)
            encode(mask)
        } finally {
            if (!mask.isRecycled) mask.recycle()
        }

        // FrameRenderer intentionally does not upscale raster assets by default.
        // Scaling the layer around canvas centre maps this bounded mask 1:1 back
        // onto the full project coordinate system.
        val scaleX = project.canvasWidth.toFloat() / width.toFloat()
        val scaleY = project.canvasHeight.toFloat() / height.toFloat()
        frame.layers.add(
            0,
            LayerState(
                name = "Fill",
                part = Part.None,
                rasterPngBase64 = encoded,
                rasterName = "Fill",
                scaleX = scaleX.coerceIn(.01f, 20f),
                scaleY = scaleY.coerceIn(.01f, 20f)
            )
        )
        project.touch()
        return changed
    }

    fun addLine(layer: LayerState, start: CanvasPoint, end: CanvasPoint, color: Int, width: Float, alpha: Float) {
        layer.strokes.add(StrokeData(points = listOf(start, end), colorArgb = color, width = width, alpha = alpha, erase = false))
    }

    fun addRectangle(layer: LayerState, start: CanvasPoint, end: CanvasPoint, color: Int, width: Float, alpha: Float) {
        val left = minOf(start.x, end.x)
        val right = maxOf(start.x, end.x)
        val top = minOf(start.y, end.y)
        val bottom = maxOf(start.y, end.y)
        val points = listOf(
            CanvasPoint(left, top), CanvasPoint(right, top), CanvasPoint(right, bottom),
            CanvasPoint(left, bottom), CanvasPoint(left, top)
        )
        layer.strokes.add(StrokeData(points = points, colorArgb = color, width = width, alpha = alpha, erase = false))
    }

    fun addEllipse(layer: LayerState, start: CanvasPoint, end: CanvasPoint, color: Int, width: Float, alpha: Float) {
        val cx = (start.x + end.x) / 2f
        val cy = (start.y + end.y) / 2f
        val rx = abs(end.x - start.x) / 2f
        val ry = abs(end.y - start.y) / 2f
        val points = List(65) { i ->
            val angle = Math.PI * 2.0 * i / 64.0
            CanvasPoint(
                cx + (kotlin.math.cos(angle) * rx).toFloat(),
                cy + (kotlin.math.sin(angle) * ry).toFloat()
            )
        }
        layer.strokes.add(StrokeData(points = points, colorArgb = color, width = width, alpha = alpha, erase = false))
    }

    fun selectInsidePolygon(editor: EditorState, polygon: List<CanvasPoint>) {
        if (polygon.size < 3) return
        editor.selectedRasterLayerIndex = -1
        editor.selectedIds.clear()
        editor.frame.layers.forEach { layer ->
            if (!layer.visible) return@forEach
            layer.strokes.forEach { stroke ->
                if (stroke.points.any { pointInPolygon(it, polygon) }) editor.selectedIds.add(stroke.id)
            }
        }
    }

    private fun projectToBitmapX(x: Float, projectWidth: Int, bitmapWidth: Int): Int =
        ((x / projectWidth.coerceAtLeast(1)) * bitmapWidth).toInt().coerceIn(0, bitmapWidth - 1)

    private fun projectToBitmapY(y: Float, projectHeight: Int, bitmapHeight: Int): Int =
        ((y / projectHeight.coerceAtLeast(1)) * bitmapHeight).toInt().coerceIn(0, bitmapHeight - 1)

    private fun pointInPolygon(point: CanvasPoint, polygon: List<CanvasPoint>): Boolean {
        var inside = false
        var j = polygon.lastIndex
        for (i in polygon.indices) {
            val a = polygon[i]
            val b = polygon[j]
            val crosses = (a.y > point.y) != (b.y > point.y) &&
                point.x < (b.x - a.x) * (point.y - a.y) / ((b.y - a.y).takeIf { abs(it) > .00001f } ?: .00001f) + a.x
            if (crosses) inside = !inside
            j = i
        }
        return inside
    }

    private fun distance(a: Int, b: Int): Float {
        val da = (Color.alpha(a) - Color.alpha(b)) / 255f
        val dr = (Color.red(a) - Color.red(b)) / 255f
        val dg = (Color.green(a) - Color.green(b)) / 255f
        val db = (Color.blue(a) - Color.blue(b)) / 255f
        return kotlin.math.sqrt((da * da + dr * dr + dg * dg + db * db) / 4f)
    }

    private fun encode(bitmap: Bitmap): String {
        val bytes = ByteArrayOutputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            output.toByteArray()
        }
        require(bytes.size <= 64 * 1024 * 1024) { "Fill layer is too large" }
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
}
