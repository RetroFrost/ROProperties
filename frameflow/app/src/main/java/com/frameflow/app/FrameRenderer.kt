package com.frameflow.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.util.Base64

object FrameRenderer {
    fun render(project: ProjectState, frameIndex: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(project.canvasWidth, project.canvasHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(project.backgroundArgb)
        val frame = project.frames[frameIndex.coerceIn(project.frames.indices)]
        val cx = project.canvasWidth / 2f
        val cy = project.canvasHeight / 2f

        canvas.save()
        canvas.translate(cx + frame.cameraX, cy + frame.cameraY)
        canvas.rotate(frame.cameraRotation)
        canvas.scale(frame.cameraZoom, frame.cameraZoom)
        canvas.translate(-cx, -cy)

        frame.layers.asReversed().forEach { layer ->
            if (!layer.visible) return@forEach
            val layerBitmap = Bitmap.createBitmap(project.canvasWidth, project.canvasHeight, Bitmap.Config.ARGB_8888)
            val layerCanvas = Canvas(layerBitmap)
            drawRaster(layerCanvas, layer, project.canvasWidth, project.canvasHeight)
            layer.strokes.forEach { drawStroke(layerCanvas, it) }

            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                alpha = (layer.opacity.coerceIn(0f, 1f) * 255f).toInt()
            }
            canvas.save()
            canvas.translate(cx + layer.offsetX, cy + layer.offsetY)
            canvas.rotate(layer.rotationDeg)
            canvas.scale(layer.scaleX, layer.scaleY)
            canvas.translate(-cx, -cy)
            canvas.drawBitmap(layerBitmap, 0f, 0f, paint)
            canvas.restore()
            layerBitmap.recycle()
        }
        canvas.restore()
        return bitmap
    }

    private fun drawRaster(canvas: Canvas, layer: LayerState, canvasWidth: Int, canvasHeight: Int) {
        val encoded = layer.rasterPngBase64 ?: return
        val bytes = runCatching { Base64.decode(encoded, Base64.DEFAULT) }.getOrNull() ?: return
        val source = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return
        val maxWidth = canvasWidth.toFloat()
        val maxHeight = canvasHeight.toFloat()
        val scale = minOf(maxWidth / source.width, maxHeight / source.height, 1f)
        val drawWidth = source.width * scale
        val drawHeight = source.height * scale
        val left = (canvasWidth - drawWidth) / 2f
        val top = (canvasHeight - drawHeight) / 2f
        val destination = android.graphics.RectF(left, top, left + drawWidth, top + drawHeight)
        canvas.drawBitmap(source, null, destination, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        source.recycle()
    }

    private fun drawStroke(canvas: Canvas, stroke: StrokeData) {
        if (stroke.points.isEmpty()) return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = stroke.width
            color = stroke.colorArgb
            alpha = (stroke.alpha.coerceIn(0f, 1f) * 255f).toInt()
            if (stroke.erase) xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        }
        if (stroke.points.size == 1) {
            canvas.drawCircle(stroke.points[0].x, stroke.points[0].y, stroke.width / 2f, paint.apply { style = Paint.Style.FILL })
            return
        }
        val path = Path().apply {
            moveTo(stroke.points.first().x, stroke.points.first().y)
            stroke.points.drop(1).forEach { lineTo(it.x, it.y) }
        }
        canvas.drawPath(path, paint)
    }
}
