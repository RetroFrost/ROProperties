package com.frameflow.app

import android.graphics.BitmapFactory
import android.graphics.RectF
import android.util.Base64
import java.util.LinkedHashMap
import kotlin.math.cos
import kotlin.math.sin

object SmartTransformTools {
    private data class CacheKey(val hash: Int, val length: Int)
    private val boundsCache = object : LinkedHashMap<CacheKey, RectF>(40, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CacheKey, RectF>?): Boolean = size > 32
    }

    fun rotateSelection(editor: EditorState, degrees: Float) {
        editor.ensureIndices()
        val index = editor.selectedRasterLayerIndex
        if (index !in editor.frame.layers.indices) {
            editor.rotateSelection(degrees)
            return
        }
        val layer = editor.frame.layers[index]
        val part = layer.part
        if (part !in setOf(Part.LeftArm, Part.RightArm, Part.LeftLeg, Part.RightLeg)) {
            editor.rotateSelection(degrees)
            return
        }
        val rawPivot = estimateJoint(editor.project, layer) ?: run {
            editor.rotateSelection(degrees)
            return
        }
        rotateLayerAroundRawPivot(editor.project, layer, rawPivot, degrees)
        editor.project.touch()
    }

    fun estimateJoint(project: ProjectState, layer: LayerState): CanvasPoint? {
        val bounds = alphaBounds(project, layer) ?: return null
        val centerX = project.canvasWidth / 2f
        return when (layer.part) {
            Part.LeftArm, Part.RightArm -> {
                val leftDistance = kotlin.math.abs(bounds.left - centerX)
                val rightDistance = kotlin.math.abs(bounds.right - centerX)
                val x = if (leftDistance <= rightDistance) bounds.left else bounds.right
                CanvasPoint(x, bounds.top + bounds.height() * .2f)
            }
            Part.LeftLeg, Part.RightLeg -> CanvasPoint(bounds.centerX(), bounds.top + bounds.height() * .08f)
            else -> CanvasPoint(bounds.centerX(), bounds.centerY())
        }
    }

    fun alphaBounds(project: ProjectState, layer: LayerState): RectF? {
        val encoded = layer.rasterPngBase64 ?: return null
        val key = CacheKey(encoded.hashCode(), encoded.length)
        synchronized(boundsCache) { boundsCache[key]?.let { return RectF(it) } }
        if (encoded.length > 96 * 1024 * 1024) return null
        val bytes = runCatching { Base64.decode(encoded, Base64.DEFAULT) }.getOrNull() ?: return null
        if (bytes.size > 64 * 1024 * 1024) return null
        val options = BitmapFactory.Options().apply { inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888 }
        val bitmap = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) }.getOrNull() ?: return null
        try {
            var minX = bitmap.width
            var minY = bitmap.height
            var maxX = -1
            var maxY = -1
            val row = IntArray(bitmap.width)
            for (y in 0 until bitmap.height) {
                bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
                for (x in row.indices) {
                    if ((row[x] ushr 24) > 12) {
                        if (x < minX) minX = x
                        if (x > maxX) maxX = x
                        if (y < minY) minY = y
                        if (y > maxY) maxY = y
                    }
                }
            }
            if (maxX < minX || maxY < minY) return null
            val rasterScale = minOf(project.canvasWidth.toFloat() / bitmap.width, project.canvasHeight.toFloat() / bitmap.height, 1f)
            val drawWidth = bitmap.width * rasterScale
            val drawHeight = bitmap.height * rasterScale
            val left = (project.canvasWidth - drawWidth) / 2f
            val top = (project.canvasHeight - drawHeight) / 2f
            val result = RectF(
                left + minX * rasterScale,
                top + minY * rasterScale,
                left + (maxX + 1) * rasterScale,
                top + (maxY + 1) * rasterScale
            )
            synchronized(boundsCache) { boundsCache[key] = RectF(result) }
            return result
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    private fun rotateLayerAroundRawPivot(project: ProjectState, layer: LayerState, rawPivot: CanvasPoint, degrees: Float) {
        val c = CanvasPoint(project.canvasWidth / 2f, project.canvasHeight / 2f)
        val pivot = transformLayerPoint(layer, c, rawPivot)
        val currentCenter = CanvasPoint(c.x + layer.offsetX, c.y + layer.offsetY)
        val radians = Math.toRadians(degrees.toDouble())
        val cos = cos(radians).toFloat()
        val sin = sin(radians).toFloat()
        val dx = currentCenter.x - pivot.x
        val dy = currentCenter.y - pivot.y
        val newCenter = CanvasPoint(pivot.x + dx * cos - dy * sin, pivot.y + dx * sin + dy * cos)
        layer.offsetX = newCenter.x - c.x
        layer.offsetY = newCenter.y - c.y
        layer.rotationDeg += degrees
    }

    private fun transformLayerPoint(layer: LayerState, center: CanvasPoint, point: CanvasPoint): CanvasPoint {
        val x = (point.x - center.x) * layer.scaleX
        val y = (point.y - center.y) * layer.scaleY
        val radians = Math.toRadians(layer.rotationDeg.toDouble())
        val c = cos(radians).toFloat()
        val s = sin(radians).toFloat()
        return CanvasPoint(
            center.x + layer.offsetX + x * c - y * s,
            center.y + layer.offsetY + x * s + y * c
        )
    }
}
