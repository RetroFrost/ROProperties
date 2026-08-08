package com.frameflow.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.Base64
import kotlin.math.max
import kotlin.math.roundToInt

object FrameRenderer {
    /** Full-resolution render used by still exports. */
    fun render(project: ProjectState, frameIndex: Int): Bitmap = renderSized(project, frameIndex, null)

    /** Bounded export render for codecs/GIFs that should not allocate an arbitrary 8K frame. */
    fun renderBounded(project: ProjectState, frameIndex: Int, maxSide: Int): Bitmap =
        renderSized(project, frameIndex, maxSide.coerceIn(128, 4096))

    /** Memory-bounded editor render; never creates a full-size bitmap and then shrinks it. */
    fun renderPreview(project: ProjectState, frameIndex: Int, maxSide: Int = 1400): Bitmap =
        renderSized(project, frameIndex, maxSide.coerceIn(128, 2048))

    fun renderThumbnail(project: ProjectState, frameIndex: Int, maxSide: Int = 240): Bitmap =
        renderSized(project, frameIndex, maxSide.coerceIn(64, 512))

    private fun renderSized(project: ProjectState, frameIndex: Int, maxSide: Int?): Bitmap {
        val sourceWidth = project.canvasWidth.coerceIn(16, 8192)
        val sourceHeight = project.canvasHeight.coerceIn(16, 8192)
        val scale = if (maxSide == null) 1f else minOf(1f, maxSide.toFloat() / max(sourceWidth, sourceHeight))
        val outWidth = (sourceWidth * scale).roundToInt().coerceAtLeast(1)
        val outHeight = (sourceHeight * scale).roundToInt().coerceAtLeast(1)
        val pixelCount = outWidth.toLong() * outHeight.toLong()
        require(pixelCount <= 67_108_864L) { "Canvas is too large to render safely" }

        val bitmap = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(project.backgroundArgb)
        if (project.frames.isEmpty()) return bitmap
        val frame = project.frames[frameIndex.coerceIn(project.frames.indices)]
        val cx = sourceWidth / 2f
        val cy = sourceHeight / 2f

        canvas.save()
        canvas.scale(scale, scale)
        canvas.translate(cx + frame.cameraX.safe(0f), cy + frame.cameraY.safe(0f))
        canvas.rotate(frame.cameraRotation.safe(0f))
        val cameraZoom = frame.cameraZoom.safe(1f).coerceIn(.05f, 20f)
        canvas.scale(cameraZoom, cameraZoom)
        canvas.translate(-cx, -cy)

        frame.layers.asReversed().forEach { layer ->
            if (!layer.visible) return@forEach
            val sx = layer.scaleX.safe(1f).let { if (kotlin.math.abs(it) < .001f) .001f else it }.coerceIn(-20f, 20f)
            val sy = layer.scaleY.safe(1f).let { if (kotlin.math.abs(it) < .001f) .001f else it }.coerceIn(-20f, 20f)

            canvas.save()
            canvas.translate(cx + layer.offsetX.safe(0f), cy + layer.offsetY.safe(0f))
            canvas.rotate(layer.rotationDeg.safe(0f))
            canvas.scale(sx, sy)
            canvas.translate(-cx, -cy)

            val groupPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                alpha = (layer.opacity.safe(1f).coerceIn(0f, 1f) * 255f).roundToInt()
            }
            val group = canvas.saveLayer(RectF(0f, 0f, sourceWidth.toFloat(), sourceHeight.toFloat()), groupPaint)
            drawRaster(canvas, layer, sourceWidth, sourceHeight)
            layer.strokes.forEach { BrushEngine.draw(canvas, it) }
            canvas.restoreToCount(group)
            canvas.restore()
        }
        canvas.restore()
        return bitmap
    }

    private fun drawRaster(canvas: Canvas, layer: LayerState, canvasWidth: Int, canvasHeight: Int) {
        val encoded = layer.rasterPngBase64 ?: return
        if (encoded.length > 96 * 1024 * 1024) return
        val bytes = runCatching { Base64.decode(encoded, Base64.DEFAULT) }.getOrNull() ?: return
        if (bytes.size > 64 * 1024 * 1024) return
        val source = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull() ?: return
        try {
            if (source.width <= 0 || source.height <= 0) return
            val maxWidth = canvasWidth.toFloat()
            val maxHeight = canvasHeight.toFloat()
            val rasterScale = minOf(maxWidth / source.width, maxHeight / source.height, 1f)
            if (!rasterScale.isFinite() || rasterScale <= 0f) return
            val drawWidth = source.width * rasterScale
            val drawHeight = source.height * rasterScale
            val left = (canvasWidth - drawWidth) / 2f
            val top = (canvasHeight - drawHeight) / 2f
            val destination = RectF(left, top, left + drawWidth, top + drawHeight)
            canvas.drawBitmap(source, null, destination, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        } finally {
            if (!source.isRecycled) source.recycle()
        }
    }

    private fun Float.safe(fallback: Float): Float = if (isFinite()) this else fallback
}
