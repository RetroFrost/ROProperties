package com.frameflow.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.Base64
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import java.io.ByteArrayOutputStream
import kotlin.math.hypot
import kotlin.math.max

object SmartLimbIdentifier {
    data class IdentifiedPart(val name: String, val part: Part, val pngBase64: String)

    fun splitLayer(
        layer: LayerState,
        onSuccess: (List<IdentifiedPart>) -> Unit,
        onFailure: (Throwable) -> Unit
    ) {
        val source = decode(layer.rasterPngBase64) ?: run {
            onFailure(IllegalArgumentException("Selected layer has no raster artwork"))
            return
        }
        val options = PoseDetectorOptions.Builder()
            .setDetectorMode(PoseDetectorOptions.SINGLE_IMAGE_MODE)
            .build()
        val detector = PoseDetection.getClient(options)
        detector.process(InputImage.fromBitmap(source, 0))
            .addOnSuccessListener { pose ->
                runCatching {
                    val parts = if (hasUsablePose(pose)) splitHumanPose(source, pose) else splitGeometryFallback(source)
                    source.recycle()
                    detector.close()
                    parts
                }.onSuccess(onSuccess).onFailure(onFailure)
            }
            .addOnFailureListener { error ->
                runCatching { splitGeometryFallback(source) }
                    .onSuccess {
                        source.recycle()
                        detector.close()
                        onSuccess(it)
                    }
                    .onFailure {
                        source.recycle()
                        detector.close()
                        onFailure(error)
                    }
            }
    }

    private fun hasUsablePose(pose: Pose): Boolean {
        val required = listOf(
            PoseLandmark.LEFT_SHOULDER, PoseLandmark.RIGHT_SHOULDER,
            PoseLandmark.LEFT_HIP, PoseLandmark.RIGHT_HIP
        )
        return required.all { type ->
            pose.getPoseLandmark(type)?.inFrameLikelihood?.let { it >= .25f } == true
        }
    }

    private fun splitHumanPose(source: Bitmap, pose: Pose): List<IdentifiedPart> {
        fun p(type: Int) = pose.getPoseLandmark(type)?.position
        val ls = p(PoseLandmark.LEFT_SHOULDER)!!
        val rs = p(PoseLandmark.RIGHT_SHOULDER)!!
        val lh = p(PoseLandmark.LEFT_HIP)!!
        val rh = p(PoseLandmark.RIGHT_HIP)!!
        val shoulder = hypot(ls.x - rs.x, ls.y - rs.y).coerceAtLeast(source.width * .12f)
        val limbWidth = max(12f, shoulder * .22f)
        val legWidth = max(14f, shoulder * .28f)
        val masks = mutableListOf<Pair<Part, Bitmap>>()

        fun limb(part: Part, a: Int, b: Int, c: Int, width: Float) {
            val pa = p(a) ?: return
            val pb = p(b) ?: return
            val pc = p(c) ?: return
            masks += part to strokeMask(source.width, source.height, listOf(pa.x to pa.y, pb.x to pb.y, pc.x to pc.y), width)
        }

        val nose = p(PoseLandmark.NOSE)
        val le = p(PoseLandmark.LEFT_EAR)
        val re = p(PoseLandmark.RIGHT_EAR)
        val headCenterX = nose?.x ?: (ls.x + rs.x) / 2f
        val headCenterY = nose?.y ?: (ls.y + rs.y) / 2f - shoulder * .55f
        val earSpan = if (le != null && re != null) hypot(le.x - re.x, le.y - re.y) else shoulder * .5f
        masks += Part.Face to circleMask(source.width, source.height, headCenterX, headCenterY, max(earSpan * .75f, shoulder * .33f))

        limb(Part.LeftArm, PoseLandmark.LEFT_SHOULDER, PoseLandmark.LEFT_ELBOW, PoseLandmark.LEFT_WRIST, limbWidth)
        limb(Part.RightArm, PoseLandmark.RIGHT_SHOULDER, PoseLandmark.RIGHT_ELBOW, PoseLandmark.RIGHT_WRIST, limbWidth)
        limb(Part.LeftLeg, PoseLandmark.LEFT_HIP, PoseLandmark.LEFT_KNEE, PoseLandmark.LEFT_ANKLE, legWidth)
        limb(Part.RightLeg, PoseLandmark.RIGHT_HIP, PoseLandmark.RIGHT_KNEE, PoseLandmark.RIGHT_ANKLE, legWidth)
        masks += Part.Body to polygonMask(
            source.width,
            source.height,
            listOf(
                ls.x to ls.y,
                rs.x to rs.y,
                rh.x to rh.y,
                lh.x to lh.y
            ),
            shoulder * .10f
        )
        return extractParts(source, masks)
    }

    private fun splitGeometryFallback(source: Bitmap): List<IdentifiedPart> {
        val pixels = IntArray(source.width * source.height)
        source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
        var minX = source.width
        var minY = source.height
        var maxX = 0
        var maxY = 0
        for (y in 0 until source.height) for (x in 0 until source.width) {
            if (Color.alpha(pixels[y * source.width + x]) > 24) {
                minX = minOf(minX, x); maxX = maxOf(maxX, x)
                minY = minOf(minY, y); maxY = maxOf(maxY, y)
            }
        }
        if (minX >= maxX || minY >= maxY) return emptyList()
        val w = (maxX - minX).toFloat()
        val h = (maxY - minY).toFloat()
        fun rect(part: Part, left: Float, top: Float, right: Float, bottom: Float): Pair<Part, Bitmap> {
            val mask = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
            Canvas(mask).drawRect(minX + w * left, minY + h * top, minX + w * right, minY + h * bottom, Paint().apply { color = Color.WHITE })
            return part to mask
        }
        val masks = listOf(
            rect(Part.Face, .30f, 0f, .70f, .30f),
            rect(Part.LeftArm, 0f, .20f, .30f, .72f),
            rect(Part.RightArm, .70f, .20f, 1f, .72f),
            rect(Part.LeftLeg, .20f, .66f, .50f, 1f),
            rect(Part.RightLeg, .50f, .66f, .80f, 1f),
            rect(Part.Body, .26f, .24f, .74f, .72f)
        )
        return extractParts(source, masks)
    }

    private fun extractParts(source: Bitmap, masks: List<Pair<Part, Bitmap>>): List<IdentifiedPart> {
        val width = source.width
        val height = source.height
        val sourcePixels = IntArray(width * height)
        source.getPixels(sourcePixels, 0, width, 0, 0, width, height)
        val remaining = sourcePixels.copyOf()
        val result = mutableListOf<IdentifiedPart>()

        masks.forEach { (part, mask) ->
            val maskPixels = IntArray(width * height)
            mask.getPixels(maskPixels, 0, width, 0, 0, width, height)
            mask.recycle()
            val extracted = IntArray(width * height)
            var nonTransparent = 0
            for (i in extracted.indices) {
                if (Color.alpha(maskPixels[i]) > 24 && Color.alpha(remaining[i]) > 12) {
                    extracted[i] = remaining[i]
                    remaining[i] = Color.TRANSPARENT
                    nonTransparent++
                }
            }
            if (nonTransparent > width * height / 1200) {
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bitmap.setPixels(extracted, 0, width, 0, 0, width, height)
                result += IdentifiedPart(part.label, part, encode(bitmap))
                bitmap.recycle()
            }
        }

        val remainderCount = remaining.count { Color.alpha(it) > 12 }
        if (remainderCount > width * height / 1200) {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.setPixels(remaining, 0, width, 0, 0, width, height)
            result += IdentifiedPart("Remaining artwork", Part.Accessory, encode(bitmap))
            bitmap.recycle()
        }
        return result
    }

    private fun strokeMask(width: Int, height: Int, points: List<Pair<Float, Float>>, thickness: Float): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = thickness
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val path = Path().apply {
            moveTo(points.first().first, points.first().second)
            points.drop(1).forEach { lineTo(it.first, it.second) }
        }
        Canvas(bitmap).drawPath(path, paint)
        return bitmap
    }

    private fun circleMask(width: Int, height: Int, x: Float, y: Float, radius: Float): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            Canvas(it).drawCircle(x, y, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        }

    private fun polygonMask(width: Int, height: Int, points: List<Pair<Float, Float>>, expand: Float): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val path = Path().apply {
            moveTo(points.first().first, points.first().second)
            points.drop(1).forEach { lineTo(it.first, it.second) }
            close()
        }
        val canvas = Canvas(bitmap)
        canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL })
        canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = expand * 2f; strokeJoin = Paint.Join.ROUND })
        return bitmap
    }

    private fun decode(base64: String?): Bitmap? {
        if (base64.isNullOrBlank()) return null
        val bytes = runCatching { Base64.decode(base64, Base64.DEFAULT) }.getOrNull() ?: return null
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    private fun encode(bitmap: Bitmap): String {
        val bytes = ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            output.toByteArray()
        }
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
}
