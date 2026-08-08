package com.frameflow.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode

object FrameRenderer {
    fun render(project: ProjectState, frameIndex: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(project.canvasWidth, project.canvasHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(project.backgroundArgb)
        val frame = project.frames[frameIndex.coerceIn(project.frames.indices)]
        frame.layers.asReversed().forEach { layer ->
            if (!layer.visible) return@forEach
            val layerBitmap = Bitmap.createBitmap(project.canvasWidth, project.canvasHeight, Bitmap.Config.ARGB_8888)
            val layerCanvas = Canvas(layerBitmap)
            layer.strokes.forEach { drawStroke(layerCanvas, it) }
            canvas.drawBitmap(layerBitmap, 0f, 0f, null)
            layerBitmap.recycle()
        }
        return bitmap
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
