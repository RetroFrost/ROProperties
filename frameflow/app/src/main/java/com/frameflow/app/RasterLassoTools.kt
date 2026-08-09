package com.frameflow.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object RasterLassoTools {
    /** Cuts the lassoed raster pixels into a normal selected layer. */
    fun cutSelection(editor: EditorState, polygonVisible: List<CanvasPoint>): Int {
        editor.ensureIndices()
        val source = editor.layer
        require(source.hasRaster && !source.locked && !source.isRigSource) { "Select an unlocked image layer" }
        require(polygonVisible.size >= 3) { "Draw a lasso first" }
        val encoded = source.rasterPngBase64 ?: error("Image data is missing")
        require(encoded.length <= 96 * 1024 * 1024) { "Image is too large" }
        val bytes = Base64.decode(encoded, Base64.DEFAULT)
        require(bytes.size <= 64 * 1024 * 1024) { "Image is too large" }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("Image is corrupt")
        try {
            val count = bitmap.width.toLong() * bitmap.height.toLong()
            require(count <= 16_777_216L) { "Raster lasso is limited to 16 megapixels" }
            val project = editor.project
            val frame = editor.frame
            val localPolygon = polygonVisible.map { visible ->
                SelectionTools.inverseLayer(project, source, SelectionTools.inverseCamera(project, frame, visible))
            }
            val rasterScale = minOf(project.canvasWidth.toFloat() / bitmap.width, project.canvasHeight.toFloat() / bitmap.height, 1f)
                .takeIf { it.isFinite() && it > 0f } ?: 1f
            val drawWidth = bitmap.width * rasterScale
            val drawHeight = bitmap.height * rasterScale
            val left = (project.canvasWidth - drawWidth) / 2f
            val top = (project.canvasHeight - drawHeight) / 2f

            val minPx = localPolygon.minOf { it.x }.coerceIn(left, left + drawWidth)
            val maxPx = localPolygon.maxOf { it.x }.coerceIn(left, left + drawWidth)
            val minPy = localPolygon.minOf { it.y }.coerceIn(top, top + drawHeight)
            val maxPy = localPolygon.maxOf { it.y }.coerceIn(top, top + drawHeight)
            if (maxPx <= minPx || maxPy <= minPy) return 0

            val minX = ((minPx - left) / rasterScale).toInt().coerceIn(0, bitmap.width - 1)
            val maxX = ((maxPx - left) / rasterScale).toInt().coerceIn(0, bitmap.width - 1)
            val minY = ((minPy - top) / rasterScale).toInt().coerceIn(0, bitmap.height - 1)
            val maxY = ((maxPy - top) / rasterScale).toInt().coerceIn(0, bitmap.height - 1)

            val sourcePixels = IntArray(count.toInt())
            bitmap.getPixels(sourcePixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val selectedPixels = IntArray(sourcePixels.size)
            var changed = 0
            for (y in minY..maxY) {
                val projectY = top + (y + .5f) * rasterScale
                for (x in minX..maxX) {
                    val projectX = left + (x + .5f) * rasterScale
                    if (!contains(CanvasPoint(projectX, projectY), localPolygon)) continue
                    val index = y * bitmap.width + x
                    val pixel = sourcePixels[index]
                    if (pixel ushr 24 == 0) continue
                    selectedPixels[index] = pixel
                    sourcePixels[index] = 0
                    changed++
                }
            }
            if (changed == 0) return 0

            val selectedBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            try {
                bitmap.setPixels(sourcePixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                selectedBitmap.setPixels(selectedPixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                source.rasterPngBase64 = encode(bitmap)
                val selection = LayerState(
                    name = "Lasso · ${source.name}",
                    part = source.part,
                    opacity = source.opacity,
                    offsetX = source.offsetX,
                    offsetY = source.offsetY,
                    scaleX = source.scaleX,
                    scaleY = source.scaleY,
                    rotationDeg = source.rotationDeg,
                    rasterPngBase64 = encode(selectedBitmap),
                    rasterName = source.rasterName,
                    folderName = source.folderName
                )
                val sourceIndex = editor.frame.layers.indexOf(source).coerceAtLeast(0)
                editor.frame.layers.add(sourceIndex, selection)
                editor.layerIndex = sourceIndex
                editor.selectedRasterLayerIndex = sourceIndex
                editor.selectedIds.clear()
                project.touch()
                return changed
            } finally {
                if (!selectedBitmap.isRecycled) selectedBitmap.recycle()
            }
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    private fun encode(bitmap: Bitmap): String {
        val data = ByteArrayOutputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "Unable to encode lasso selection" }
            output.toByteArray()
        }
        require(data.size <= 64 * 1024 * 1024) { "Lasso result is too large" }
        return Base64.encodeToString(data, Base64.NO_WRAP)
    }

    private fun contains(point: CanvasPoint, polygon: List<CanvasPoint>): Boolean {
        var inside = false
        var j = polygon.lastIndex
        for (i in polygon.indices) {
            val a = polygon[i]; val b = polygon[j]
            val denominator = (b.y - a.y).takeIf { abs(it) > .00001f } ?: .00001f
            if ((a.y > point.y) != (b.y > point.y) && point.x < (b.x - a.x) * (point.y - a.y) / denominator + a.x) inside = !inside
            j = i
        }
        return inside
    }
}
