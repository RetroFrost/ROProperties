package com.frameflow.app

import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import java.util.Random
import java.util.UUID
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/** Real brush rendering backed by the existing stable StrokeData format.
 * Brush identity is embedded in the stroke ID so old project files stay compatible.
 */
object BrushEngine {
    private const val PREFIX = "ffbrush:"

    data class Style(
        val family: String,
        val variant: Int,
        val hardness: Float,
        val spacing: Float,
        val jitter: Float,
        val angleDeg: Float,
        val density: Int
    )

    fun createStroke(
        preset: BrushPreset,
        points: List<CanvasPoint>,
        colorArgb: Int,
        erase: Boolean,
        pressureScale: Float = 1f
    ): StrokeData {
        val variant = preset.name.substringAfterLast(' ').toIntOrNull()?.coerceIn(1, 99) ?: 1
        val family = if (erase) "Eraser" else preset.family
        val id = "$PREFIX${family.replace(':', '_')}:$variant:${UUID.randomUUID()}"
        return StrokeData(
            id = id,
            points = simplify(points),
            colorArgb = colorArgb,
            width = (preset.width * pressureScale.coerceIn(.15f, 2f)).coerceIn(.25f, 4096f),
            alpha = preset.alpha.coerceIn(.02f, 1f),
            erase = erase
        )
    }

    fun style(stroke: StrokeData): Style {
        val parts = stroke.id.split(':')
        val family = if (parts.size >= 4 && parts[0] == "ffbrush") parts[1] else if (stroke.erase) "Eraser" else "Ink"
        val variant = if (parts.size >= 4 && parts[0] == "ffbrush") parts[2].toIntOrNull()?.coerceAtLeast(1) ?: 1 else 1
        val t = ((variant - 1) % 20) / 19f
        return when (family.lowercase()) {
            "pencil" -> Style(family, variant, .25f + t * .35f, .18f, .35f + t * .35f, 0f, 2 + variant % 4)
            "marker" -> Style(family, variant, .75f, .08f, .03f, 0f, 1)
            "paint" -> Style(family, variant, .45f, .14f, .12f + t * .25f, 0f, 2 + variant % 3)
            "pixel" -> Style(family, variant, 1f, 1f, 0f, 0f, 1)
            "spray" -> Style(family, variant, .15f, .22f, .8f + t * .8f, 0f, 16 + variant * 2)
            "chalk" -> Style(family, variant, .2f, .18f, .65f, 0f, 7 + variant % 8)
            "calligraphy" -> Style(family, variant, .9f, .08f, 0f, -55f + variant * 5f, 3 + variant % 3)
            "highlighter" -> Style(family, variant, .65f, .06f, 0f, 0f, 1)
            "texture" -> Style(family, variant, .2f, .2f, .55f + t * .4f, variant * 9f, 6 + variant % 10)
            "crayon" -> Style(family, variant, .3f, .16f, .45f, 0f, 5 + variant % 5)
            "airbrush" -> Style(family, variant, 0f, .18f, 1f, 0f, 10 + variant)
            "eraser" -> Style(family, variant, if (variant <= 20) 1f else if (variant <= 40) .2f else .45f, .12f, if (variant > 40) .65f else 0f, 0f, if (variant > 40) 8 else 1)
            else -> Style(family, variant, .9f, .06f, .01f * variant, 0f, 1)
        }
    }

    fun draw(canvas: Canvas, stroke: StrokeData) {
        val points = stroke.points.filter { it.x.isFinite() && it.y.isFinite() }
        if (points.isEmpty()) return
        val style = style(stroke)
        when (style.family.lowercase()) {
            "spray" -> drawSpray(canvas, stroke, points, style)
            "chalk", "texture", "crayon" -> drawTextured(canvas, stroke, points, style)
            "calligraphy" -> drawCalligraphy(canvas, stroke, points, style)
            "airbrush" -> drawAirbrush(canvas, stroke, points, style)
            "pixel" -> drawPixel(canvas, stroke, points)
            "pencil" -> drawPencil(canvas, stroke, points, style)
            "highlighter" -> drawPath(canvas, stroke, points, widthMultiplier = 1.7f, alphaMultiplier = .45f, square = true)
            "marker" -> drawPath(canvas, stroke, points, widthMultiplier = 1.15f, alphaMultiplier = .8f, square = true)
            "paint" -> {
                drawPath(canvas, stroke, points, widthMultiplier = 1f, alphaMultiplier = .9f)
                val offset = max(1f, stroke.width * .06f)
                drawPath(canvas, stroke, points.map { CanvasPoint(it.x + offset, it.y - offset) }, widthMultiplier = .78f, alphaMultiplier = .4f)
            }
            "eraser" -> if (style.variant > 40) drawTextured(canvas, stroke, points, style) else if (style.variant > 20) drawSoftEraser(canvas, stroke, points) else drawPath(canvas, stroke, points)
            else -> drawPath(canvas, stroke, points)
        }
    }

    private fun basePaint(stroke: StrokeData, width: Float = stroke.width, alphaMultiplier: Float = 1f): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = width.coerceAtLeast(.25f)
            color = stroke.colorArgb
            alpha = (stroke.alpha.coerceIn(0f, 1f) * alphaMultiplier.coerceIn(0f, 1f) * 255f).roundToInt()
            if (stroke.erase) xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        }

    private fun drawPath(canvas: Canvas, stroke: StrokeData, points: List<CanvasPoint>, widthMultiplier: Float = 1f, alphaMultiplier: Float = 1f, square: Boolean = false) {
        val paint = basePaint(stroke, stroke.width * widthMultiplier, alphaMultiplier)
        if (square) paint.strokeCap = Paint.Cap.SQUARE
        if (points.size == 1) {
            paint.style = Paint.Style.FILL
            canvas.drawCircle(points[0].x, points[0].y, paint.strokeWidth / 2f, paint)
            return
        }
        val path = Path().apply {
            moveTo(points.first().x, points.first().y)
            for (i in 1 until points.size) {
                val previous = points[i - 1]
                val current = points[i]
                val mx = (previous.x + current.x) / 2f
                val my = (previous.y + current.y) / 2f
                quadTo(previous.x, previous.y, mx, my)
            }
            lineTo(points.last().x, points.last().y)
        }
        canvas.drawPath(path, paint)
    }

    private fun drawPencil(canvas: Canvas, stroke: StrokeData, points: List<CanvasPoint>, style: Style) {
        drawPath(canvas, stroke, points, .72f, .72f)
        val random = Random(stroke.id.hashCode().toLong())
        repeat(style.density) {
            val dx = (random.nextFloat() - .5f) * stroke.width * .25f
            val dy = (random.nextFloat() - .5f) * stroke.width * .25f
            drawPath(canvas, stroke, points.map { CanvasPoint(it.x + dx, it.y + dy) }, .23f, .22f)
        }
    }

    private fun drawSpray(canvas: Canvas, stroke: StrokeData, points: List<CanvasPoint>, style: Style) {
        val random = Random(stroke.id.hashCode().toLong())
        val paint = basePaint(stroke, 1f, .55f).apply { this.style = Paint.Style.FILL }
        val step = max(1, points.size / 1800)
        for (index in points.indices step step) {
            val p = points[index]
            repeat(style.density.coerceAtMost(70)) {
                val angle = random.nextDouble() * Math.PI * 2
                val radius = random.nextDouble() * stroke.width * (.35 + style.jitter)
                val r = max(.7f, stroke.width * (.012f + random.nextFloat() * .035f))
                canvas.drawCircle(p.x + cos(angle).toFloat() * radius.toFloat(), p.y + sin(angle).toFloat() * radius.toFloat(), r, paint)
            }
        }
    }

    private fun drawTextured(canvas: Canvas, stroke: StrokeData, points: List<CanvasPoint>, style: Style) {
        val random = Random((stroke.id.hashCode() * 31L) + style.variant)
        val paint = basePaint(stroke, 1f, .58f).apply { this.style = Paint.Style.FILL }
        drawPath(canvas, stroke, points, .72f, .32f)
        val step = max(1, points.size / 1200)
        for (index in points.indices step step) {
            val p = points[index]
            repeat(style.density.coerceAtMost(24)) {
                val dx = (random.nextFloat() - .5f) * stroke.width * style.jitter
                val dy = (random.nextFloat() - .5f) * stroke.width * style.jitter
                val size = max(.55f, stroke.width * (.025f + random.nextFloat() * .08f))
                if (style.family.equals("Texture", true) && style.variant % 2 == 0) canvas.drawRect(p.x + dx, p.y + dy, p.x + dx + size * 1.8f, p.y + dy + size, paint)
                else canvas.drawCircle(p.x + dx, p.y + dy, size, paint)
            }
        }
    }

    private fun drawCalligraphy(canvas: Canvas, stroke: StrokeData, points: List<CanvasPoint>, style: Style) {
        val angle = Math.toRadians(style.angleDeg.toDouble())
        val dx = cos(angle).toFloat() * stroke.width * .18f
        val dy = sin(angle).toFloat() * stroke.width * .18f
        repeat(style.density.coerceAtMost(6)) { i ->
            val offset = i - (style.density - 1) / 2f
            drawPath(canvas, stroke, points.map { CanvasPoint(it.x + dx * offset, it.y + dy * offset) }, .34f, .8f, square = true)
        }
    }

    private fun drawAirbrush(canvas: Canvas, stroke: StrokeData, points: List<CanvasPoint>, style: Style) {
        val paint = basePaint(stroke, stroke.width, .2f).apply {
            this.style = Paint.Style.FILL
            if (!stroke.erase) maskFilter = BlurMaskFilter(max(1f, stroke.width * .38f), BlurMaskFilter.Blur.NORMAL)
        }
        val step = max(1, points.size / 900)
        for (index in points.indices step step) {
            canvas.drawCircle(points[index].x, points[index].y, max(.5f, stroke.width * (.26f + style.variant * .004f)), paint)
        }
    }

    private fun drawSoftEraser(canvas: Canvas, stroke: StrokeData, points: List<CanvasPoint>) {
        // CLEAR does not support BlurMaskFilter consistently across hardware canvases,
        // so create concentric lower-alpha clear passes for a soft deterministic edge.
        repeat(5) { pass ->
            val multiplier = 1f - pass * .13f
            drawPath(canvas, stroke.copy(alpha = stroke.alpha * (1f - pass * .15f)), points, multiplier, 1f)
        }
    }

    private fun drawPixel(canvas: Canvas, stroke: StrokeData, points: List<CanvasPoint>) {
        val size = max(1f, stroke.width.roundToInt().toFloat())
        val paint = basePaint(stroke, size).apply {
            isAntiAlias = false
            style = Paint.Style.FILL
        }
        points.forEach { p ->
            val x = (p.x / size).roundToInt() * size
            val y = (p.y / size).roundToInt() * size
            canvas.drawRect(x - size / 2f, y - size / 2f, x + size / 2f, y + size / 2f, paint)
        }
    }

    private fun simplify(points: List<CanvasPoint>): List<CanvasPoint> {
        if (points.size <= 2) return points
        val result = ArrayList<CanvasPoint>(points.size / 2 + 2)
        var previous = points.first()
        result += previous
        for (i in 1 until points.lastIndex) {
            val p = points[i]
            val dx = p.x - previous.x
            val dy = p.y - previous.y
            if (dx * dx + dy * dy >= 1.5f) {
                result += p
                previous = p
            }
        }
        if (result.last() != points.last()) result += points.last()
        return result
    }
}
