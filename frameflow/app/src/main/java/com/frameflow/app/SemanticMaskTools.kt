package com.frameflow.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.ByteArrayOutputStream
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object SemanticMaskTools {
    enum class Mode { Add, Remove }

    fun canRefine(editor: EditorState): Boolean {
        editor.ensureIndices()
        return editor.layer.hasRaster && editor.layer.part !in setOf(Part.None, Part.Background) && editor.frame.layers.any { it.isRigSource && it.hasRaster }
    }

    fun refine(editor: EditorState, points: List<CanvasPoint>, radiusProjectPx: Float, mode: Mode): Int {
        editor.ensureIndices()
        val target = editor.layer
        require(target.hasRaster && target.part !in setOf(Part.None, Part.Background)) { "Select a detected semantic part first" }
        val sourceLayer = editor.frame.layers.firstOrNull { it.isRigSource && it.hasRaster }
            ?: error("This rig has no preserved source artwork")
        if (points.isEmpty()) return 0

        val targetBitmap = decode(target.rasterPngBase64) ?: error("Selected part image is corrupt")
        val sourceBitmap = decode(sourceLayer.rasterPngBase64) ?: run {
            targetBitmap.recycle()
            error("Rig source image is corrupt")
        }
        try {
            require(targetBitmap.width == sourceBitmap.width && targetBitmap.height == sourceBitmap.height) {
                "Rig source and semantic part dimensions do not match"
            }
            val width = targetBitmap.width
            val height = targetBitmap.height
            val total = width.toLong() * height.toLong()
            require(total <= 16_777_216L) { "Semantic image is too large to refine safely" }
            val targetPixels = IntArray(total.toInt())
            val sourcePixels = IntArray(total.toInt())
            targetBitmap.getPixels(targetPixels, 0, width, 0, 0, width, height)
            sourceBitmap.getPixels(sourcePixels, 0, width, 0, 0, width, height)

            val rasterScale = minOf(
                editor.project.canvasWidth.toFloat() / width,
                editor.project.canvasHeight.toFloat() / height,
                1f
            ).takeIf { it.isFinite() && it > 0f } ?: 1f
            val drawWidth = width * rasterScale
            val drawHeight = height * rasterScale
            val left = (editor.project.canvasWidth - drawWidth) / 2f
            val top = (editor.project.canvasHeight - drawHeight) / 2f
            val radius = (radiusProjectPx.coerceIn(1f, 1024f) / rasterScale).roundToInt().coerceIn(1, 512)

            fun toBitmap(point: CanvasPoint): Pair<Float, Float> =
                ((point.x - left) / rasterScale) to ((point.y - top) / rasterScale)

            val samples = ArrayList<Pair<Float, Float>>()
            var previous = toBitmap(points.first())
            samples += previous
            for (i in 1 until points.size) {
                val current = toBitmap(points[i])
                val distance = hypot((current.first - previous.first).toDouble(), (current.second - previous.second).toDouble()).toFloat()
                val steps = max(1, ceil(distance / max(1f, radius * .45f)).toInt()).coerceAtMost(2000)
                for (step in 1..steps) {
                    val t = step / steps.toFloat()
                    samples += (previous.first + (current.first - previous.first) * t) to (previous.second + (current.second - previous.second) * t)
                }
                previous = current
                if (samples.size > 20_000) break
            }

            var changed = 0
            val radiusSq = radius * radius
            samples.forEach { (fx, fy) ->
                val cx = fx.roundToInt()
                val cy = fy.roundToInt()
                val minX = max(0, cx - radius)
                val maxX = min(width - 1, cx + radius)
                val minY = max(0, cy - radius)
                val maxY = min(height - 1, cy + radius)
                for (y in minY..maxY) {
                    val dy = y - cy
                    for (x in minX..maxX) {
                        val dx = x - cx
                        if (dx * dx + dy * dy > radiusSq) continue
                        val index = y * width + x
                        val next = if (mode == Mode.Remove) 0 else sourcePixels[index]
                        if (targetPixels[index] != next) {
                            targetPixels[index] = next
                            changed++
                        }
                    }
                }
            }

            if (changed > 0) {
                targetBitmap.setPixels(targetPixels, 0, width, 0, 0, width, height)
                target.rasterPngBase64 = encode(targetBitmap)
                editor.project.touch()
            }
            return changed
        } finally {
            if (!targetBitmap.isRecycled) targetBitmap.recycle()
            if (!sourceBitmap.isRecycled) sourceBitmap.recycle()
        }
    }

    private fun decode(encoded: String?): Bitmap? {
        if (encoded.isNullOrBlank() || encoded.length > 96 * 1024 * 1024) return null
        val bytes = runCatching { Base64.decode(encoded, Base64.DEFAULT) }.getOrNull() ?: return null
        if (bytes.size > 64 * 1024 * 1024) return null
        return runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
    }

    private fun encode(bitmap: Bitmap): String {
        val bytes = ByteArrayOutputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            output.toByteArray()
        }
        require(bytes.size <= 64 * 1024 * 1024) { "Refined part is too large" }
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
}
