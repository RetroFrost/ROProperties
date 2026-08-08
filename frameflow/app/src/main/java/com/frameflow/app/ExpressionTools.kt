package com.frameflow.app

import android.graphics.Color
import kotlin.math.max

object ExpressionTools {
    enum class Expression(val label: String) {
        Happy("Happy"), Sad("Sad"), Angry("Angry"), Shocked("Shocked"), Confused("Confused"), Smug("Smug")
    }

    fun apply(editor: EditorState, expression: Expression) {
        editor.ensureIndices()
        val project = editor.project
        val frame = editor.frame
        frame.layers.removeAll { it.name.startsWith("Expression · ") }
        val body = frame.layers.firstOrNull { it.part == Part.Body && it.hasRaster }
            ?: frame.layers.firstOrNull { it.hasRaster && !it.isRigSource }
        val bounds = body?.let { SmartTransformTools.alphaBounds(project, it) }
            ?: android.graphics.RectF(project.canvasWidth * .25f, project.canvasHeight * .15f, project.canvasWidth * .75f, project.canvasHeight * .75f)
        val faceTop = bounds.top + bounds.height() * .18f
        val centreX = bounds.centerX()
        val eyeY = faceTop + bounds.height() * .12f
        val mouthY = faceTop + bounds.height() * .32f
        val eyeGap = bounds.width() * .14f
        val eyeHalf = bounds.width() * .045f
        val mouthHalf = bounds.width() * .13f
        val strokeWidth = max(2f, minOf(project.canvasWidth, project.canvasHeight) * .006f)
        val ink = BrushPreset("Ink 1", "Ink", strokeWidth, 1f)

        val eyes = LayerState("Expression · Eyes", Part.Eyes, folderName = "Face")
        val mouth = LayerState("Expression · Mouth", Part.Mouth, folderName = "Face")
        val brows = LayerState("Expression · Eyebrows", Part.Eyebrows, folderName = "Face")

        fun stroke(layer: LayerState, points: List<CanvasPoint>, widthScale: Float = 1f) {
            layer.strokes += BrushEngine.createStroke(ink.copy(width = strokeWidth * widthScale), points, Color.BLACK, false)
        }
        fun arc(cx: Float, cy: Float, rx: Float, ry: Float, startDeg: Float, endDeg: Float): List<CanvasPoint> {
            val steps = 18
            return List(steps + 1) { i ->
                val t = i / steps.toFloat()
                val angle = Math.toRadians((startDeg + (endDeg - startDeg) * t).toDouble())
                CanvasPoint(cx + kotlin.math.cos(angle).toFloat() * rx, cy + kotlin.math.sin(angle).toFloat() * ry)
            }
        }

        when (expression) {
            Expression.Happy -> {
                stroke(eyes, arc(centreX - eyeGap, eyeY, eyeHalf, eyeHalf * .65f, 200f, 340f))
                stroke(eyes, arc(centreX + eyeGap, eyeY, eyeHalf, eyeHalf * .65f, 200f, 340f))
                stroke(mouth, arc(centreX, mouthY, mouthHalf, mouthHalf * .65f, 10f, 170f), 1.15f)
            }
            Expression.Sad -> {
                stroke(eyes, listOf(CanvasPoint(centreX - eyeGap - eyeHalf, eyeY), CanvasPoint(centreX - eyeGap + eyeHalf, eyeY + eyeHalf * .35f)))
                stroke(eyes, listOf(CanvasPoint(centreX + eyeGap - eyeHalf, eyeY + eyeHalf * .35f), CanvasPoint(centreX + eyeGap + eyeHalf, eyeY)))
                stroke(mouth, arc(centreX, mouthY + mouthHalf * .25f, mouthHalf, mouthHalf * .55f, 190f, 350f), 1.1f)
            }
            Expression.Angry -> {
                stroke(eyes, listOf(CanvasPoint(centreX - eyeGap - eyeHalf, eyeY - eyeHalf * .45f), CanvasPoint(centreX - eyeGap + eyeHalf, eyeY + eyeHalf * .2f)))
                stroke(eyes, listOf(CanvasPoint(centreX + eyeGap - eyeHalf, eyeY + eyeHalf * .2f), CanvasPoint(centreX + eyeGap + eyeHalf, eyeY - eyeHalf * .45f)))
                stroke(brows, listOf(CanvasPoint(centreX - eyeGap - eyeHalf * 1.4f, eyeY - eyeHalf * 1.2f), CanvasPoint(centreX - eyeGap + eyeHalf, eyeY - eyeHalf * .55f)), 1.2f)
                stroke(brows, listOf(CanvasPoint(centreX + eyeGap - eyeHalf, eyeY - eyeHalf * .55f), CanvasPoint(centreX + eyeGap + eyeHalf * 1.4f, eyeY - eyeHalf * 1.2f)), 1.2f)
                stroke(mouth, listOf(CanvasPoint(centreX - mouthHalf, mouthY), CanvasPoint(centreX + mouthHalf, mouthY)), 1.2f)
            }
            Expression.Shocked -> {
                val eyeRadius = eyeHalf * .7f
                stroke(eyes, arc(centreX - eyeGap, eyeY, eyeRadius, eyeRadius, 0f, 360f))
                stroke(eyes, arc(centreX + eyeGap, eyeY, eyeRadius, eyeRadius, 0f, 360f))
                stroke(mouth, arc(centreX, mouthY, mouthHalf * .48f, mouthHalf * .65f, 0f, 360f), 1.2f)
            }
            Expression.Confused -> {
                stroke(eyes, listOf(CanvasPoint(centreX - eyeGap - eyeHalf, eyeY), CanvasPoint(centreX - eyeGap + eyeHalf, eyeY)))
                stroke(eyes, arc(centreX + eyeGap, eyeY, eyeHalf, eyeHalf * .65f, 200f, 340f))
                stroke(brows, listOf(CanvasPoint(centreX - eyeGap - eyeHalf, eyeY - eyeHalf), CanvasPoint(centreX - eyeGap + eyeHalf, eyeY - eyeHalf * 1.35f)))
                stroke(brows, listOf(CanvasPoint(centreX + eyeGap - eyeHalf, eyeY - eyeHalf * 1.35f), CanvasPoint(centreX + eyeGap + eyeHalf, eyeY - eyeHalf * .75f)))
                stroke(mouth, listOf(CanvasPoint(centreX - mouthHalf, mouthY), CanvasPoint(centreX, mouthY + mouthHalf * .18f), CanvasPoint(centreX + mouthHalf, mouthY - mouthHalf * .08f)))
            }
            Expression.Smug -> {
                stroke(eyes, listOf(CanvasPoint(centreX - eyeGap - eyeHalf, eyeY), CanvasPoint(centreX - eyeGap + eyeHalf, eyeY)))
                stroke(eyes, listOf(CanvasPoint(centreX + eyeGap - eyeHalf, eyeY), CanvasPoint(centreX + eyeGap + eyeHalf, eyeY - eyeHalf * .12f)))
                stroke(mouth, arc(centreX + mouthHalf * .18f, mouthY, mouthHalf, mouthHalf * .42f, 15f, 165f), 1.1f)
            }
        }

        frame.layers.add(0, mouth)
        frame.layers.add(0, eyes)
        if (brows.strokes.isNotEmpty()) frame.layers.add(0, brows)
        editor.layerIndex = 0
        project.touch()
    }
}
