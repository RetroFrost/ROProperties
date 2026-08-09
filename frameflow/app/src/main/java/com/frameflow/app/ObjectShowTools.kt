package com.frameflow.app

import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Base64
import kotlin.math.max

object ObjectShowTools {
    enum class Pose(val label: String) { Neutral("Neutral"), Wave("Wave"), Point("Point"), Shrug("Shrug"), Jump("Jump"), Run("Run"), Fall("Fall") }

    fun applyPose(editor: EditorState, pose: Pose) {
        editor.ensureIndices()
        val frame = editor.frame
        val leftArm = frame.layers.firstOrNull { it.part == Part.LeftArm }
        val rightArm = frame.layers.firstOrNull { it.part == Part.RightArm }
        val leftLeg = frame.layers.firstOrNull { it.part == Part.LeftLeg }
        val rightLeg = frame.layers.firstOrNull { it.part == Part.RightLeg }
        val foreground = frame.layers.filter { it.part != Part.Background }

        when (pose) {
            Pose.Neutral -> {
                listOfNotNull(leftArm, rightArm, leftLeg, rightLeg).forEach { it.rotationDeg = 0f }
            }
            Pose.Wave -> {
                rightArm?.let { rotateSemantic(editor.project, it, -115f) }
                leftArm?.let { rotateSemantic(editor.project, it, 12f) }
            }
            Pose.Point -> {
                rightArm?.let { rotateSemantic(editor.project, it, -82f) }
                leftArm?.let { rotateSemantic(editor.project, it, 18f) }
            }
            Pose.Shrug -> {
                leftArm?.let { rotateSemantic(editor.project, it, -48f) }
                rightArm?.let { rotateSemantic(editor.project, it, 48f) }
            }
            Pose.Jump -> {
                val lift = editor.project.canvasHeight * -.09f
                foreground.forEach { it.offsetY += lift }
                leftArm?.let { rotateSemantic(editor.project, it, -55f) }
                rightArm?.let { rotateSemantic(editor.project, it, 55f) }
                leftLeg?.let { rotateSemantic(editor.project, it, 16f) }
                rightLeg?.let { rotateSemantic(editor.project, it, -16f) }
            }
            Pose.Run -> {
                leftArm?.let { rotateSemantic(editor.project, it, -36f) }
                rightArm?.let { rotateSemantic(editor.project, it, 36f) }
                leftLeg?.let { rotateSemantic(editor.project, it, 32f) }
                rightLeg?.let { rotateSemantic(editor.project, it, -32f) }
            }
            Pose.Fall -> {
                frame.cameraRotation += 72f
                frame.cameraY += editor.project.canvasHeight * .08f
            }
        }
        editor.project.touch()
    }

    /** Creates simple stick limbs only for semantic limb layers that do not already exist. */
    fun addMissingStickLimbs(editor: EditorState): Int {
        editor.ensureIndices()
        val project = editor.project
        val frame = editor.frame
        val body = frame.layers.firstOrNull { it.part == Part.Body } ?: frame.layers.firstOrNull { it.hasRaster }
        val bounds = body?.let { SmartTransformTools.alphaBounds(project, it) }
            ?: android.graphics.RectF(project.canvasWidth * .3f, project.canvasHeight * .2f, project.canvasWidth * .7f, project.canvasHeight * .75f)
        val black = Color.BLACK
        val width = max(3f, minOf(project.canvasWidth, project.canvasHeight) * .009f)
        var created = 0

        fun addIfMissing(part: Part, name: String, points: List<CanvasPoint>) {
            if (frame.layers.any { it.part == part }) return
            val layer = LayerState(name, part)
            layer.strokes += BrushEngine.createStroke(BrushPreset("Ink 1", "Ink", width, 1f), points, black, false)
            frame.layers.add(0, layer)
            created++
        }

        val armY = bounds.top + bounds.height() * .38f
        val legY = bounds.bottom
        addIfMissing(Part.LeftArm, "Left arm", listOf(CanvasPoint(bounds.left, armY), CanvasPoint(bounds.left - bounds.width() * .38f, armY + bounds.height() * .16f)))
        addIfMissing(Part.RightArm, "Right arm", listOf(CanvasPoint(bounds.right, armY), CanvasPoint(bounds.right + bounds.width() * .38f, armY + bounds.height() * .16f)))
        addIfMissing(Part.LeftLeg, "Left leg", listOf(CanvasPoint(bounds.centerX() - bounds.width() * .16f, legY), CanvasPoint(bounds.centerX() - bounds.width() * .24f, legY + bounds.height() * .38f)))
        addIfMissing(Part.RightLeg, "Right leg", listOf(CanvasPoint(bounds.centerX() + bounds.width() * .16f, legY), CanvasPoint(bounds.centerX() + bounds.width() * .24f, legY + bounds.height() * .38f)))
        if (created > 0) project.touch()
        return created
    }

    /**
     * Makes a real blink frame for flat-colour object-show art by cloning the frame,
     * covering the eye band with the dominant body colour, then drawing closed eyes.
     * If the body colour cannot be determined safely, no destructive edit is made.
     */
    fun createBlinkFrame(editor: EditorState): Boolean {
        editor.ensureIndices()
        val project = editor.project
        val currentIndex = editor.frameIndex
        val sourceBody = editor.frame.layers.firstOrNull { it.part == Part.Body && it.hasRaster }
            ?: editor.frame.layers.firstOrNull { it.hasRaster }
            ?: return false
        val bounds = SmartTransformTools.alphaBounds(project, sourceBody) ?: return false
        val bodyColour = dominantOpaqueColour(sourceBody) ?: return false
        val clone = editor.frame.cloneFrame().apply {
            durationMs = 120
            label = "Blink"
        }
        val overlay = LayerState("Blink", Part.Face)
        val eyeY = bounds.top + bounds.height() * .34f
        val eyeGap = bounds.width() * .14f
        val eyeHalf = bounds.width() * .095f
        val patchWidth = max(6f, bounds.height() * .16f)
        overlay.strokes += BrushEngine.createStroke(
            BrushPreset("Marker 1", "Marker", patchWidth, 1f),
            listOf(CanvasPoint(bounds.centerX() - bounds.width() * .32f, eyeY), CanvasPoint(bounds.centerX() + bounds.width() * .32f, eyeY)),
            bodyColour,
            false
        )
        val lineWidth = max(2f, minOf(project.canvasWidth, project.canvasHeight) * .007f)
        val ink = BrushPreset("Ink 1", "Ink", lineWidth, 1f)
        overlay.strokes += BrushEngine.createStroke(ink, listOf(CanvasPoint(bounds.centerX() - eyeGap - eyeHalf, eyeY), CanvasPoint(bounds.centerX() - eyeGap + eyeHalf, eyeY)), Color.BLACK, false)
        overlay.strokes += BrushEngine.createStroke(ink, listOf(CanvasPoint(bounds.centerX() + eyeGap - eyeHalf, eyeY), CanvasPoint(bounds.centerX() + eyeGap + eyeHalf, eyeY)), Color.BLACK, false)
        clone.layers.add(0, overlay)
        project.frames.add(currentIndex + 1, clone)
        editor.frameIndex = currentIndex + 1
        editor.layerIndex = 0
        project.touch()
        return true
    }

    private fun rotateSemantic(project: ProjectState, layer: LayerState, degrees: Float) {
        val pivot = SmartTransformTools.estimateJoint(project, layer)
        if (pivot == null || layer.part !in setOf(Part.LeftArm, Part.RightArm, Part.LeftLeg, Part.RightLeg)) {
            layer.rotationDeg += degrees
            return
        }
        val cx = project.canvasWidth / 2f
        val cy = project.canvasHeight / 2f
        val currentCenterX = cx + layer.offsetX
        val currentCenterY = cy + layer.offsetY
        val radians = Math.toRadians(degrees.toDouble())
        val c = kotlin.math.cos(radians).toFloat()
        val s = kotlin.math.sin(radians).toFloat()
        val dx = currentCenterX - pivot.x
        val dy = currentCenterY - pivot.y
        layer.offsetX = pivot.x + dx * c - dy * s - cx
        layer.offsetY = pivot.y + dx * s + dy * c - cy
        layer.rotationDeg += degrees
    }

    private fun dominantOpaqueColour(layer: LayerState): Int? {
        val encoded = layer.rasterPngBase64 ?: return null
        if (encoded.length > 96 * 1024 * 1024) return null
        val bytes = runCatching { Base64.decode(encoded, Base64.DEFAULT) }.getOrNull() ?: return null
        val options = BitmapFactory.Options().apply { inSampleSize = 4 }
        val bitmap = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) }.getOrNull() ?: return null
        return try {
            val buckets = HashMap<Int, Int>()
            val stepX = max(1, bitmap.width / 120)
            val stepY = max(1, bitmap.height / 120)
            var y = 0
            while (y < bitmap.height) {
                var x = 0
                while (x < bitmap.width) {
                    val color = bitmap.getPixel(x, y)
                    if (Color.alpha(color) > 220) {
                        val quantized = Color.rgb((Color.red(color) / 16) * 16, (Color.green(color) / 16) * 16, (Color.blue(color) / 16) * 16)
                        buckets[quantized] = (buckets[quantized] ?: 0) + 1
                    }
                    x += stepX
                }
                y += stepY
            }
            buckets.maxByOrNull { it.value }?.key
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }
}
