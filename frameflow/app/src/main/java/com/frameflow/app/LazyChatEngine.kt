package com.frameflow.app

import android.graphics.Color
import kotlin.math.roundToInt

/**
 * Offline command engine behind Lazy Chat.
 *
 * It never fabricates an edit: every understood instruction maps to an existing
 * Frameflow operation and therefore produces ordinary editable project state.
 */
object LazyChatEngine {
    data class Result(
        val reply: String,
        val changedProject: Boolean,
        val understoodCommands: Int
    )

    fun execute(editor: EditorState, history: ProjectHistory, message: String): Result {
        val input = message.trim().take(1200)
        if (input.isBlank()) return Result("Tell me what to change.", false, 0)
        val lower = input.lowercase()

        if (lower.matches(Regex(".*\\b(undo|undo that|go back)\\b.*"))) {
            val changed = history.undo()
            editor.ensureIndices()
            return Result(if (changed) "Undid the last edit." else "There is nothing to undo.", changed, 1)
        }
        if (lower.matches(Regex(".*\\b(redo|redo that)\\b.*"))) {
            val changed = history.redo()
            editor.ensureIndices()
            return Result(if (changed) "Redid the edit." else "There is nothing to redo.", changed, 1)
        }

        val commands = splitCommands(input)
        val recognised = commands.filter { canHandle(it) }
        if (recognised.isEmpty()) {
            return Result(
                "I couldn't map that to a safe Frameflow edit. Try things like “clone and hold 2 seconds”, “make him wave”, “select the right arm and rotate 20”, “bounce and zoom in”, “make it smoother”, or “go to frame 4”.",
                false,
                0
            )
        }

        history.checkpoint()
        val beforeRevision = editor.project.revision
        val replies = mutableListOf<String>()
        return try {
            recognised.forEach { command -> replies += executeOne(editor, command) }
            editor.ensureIndices()
            Result(
                reply = replies.joinToString(" "),
                changedProject = editor.project.revision != beforeRevision,
                understoodCommands = recognised.size
            )
        } catch (error: Throwable) {
            val restored = history.undo()
            editor.ensureIndices()
            Result(
                reply = if (restored) {
                    "That edit failed, so I rolled the whole request back: ${error.message ?: "unknown error"}."
                } else {
                    "That edit failed before it could finish: ${error.message ?: "unknown error"}."
                },
                changedProject = false,
                understoodCommands = recognised.size
            )
        }
    }

    private fun splitCommands(input: String): List<String> {
        val primary = input
            .replace(Regex("(?i)\\b(and then|then|after that|afterwards)\\b"), "|")
            .replace(';', '|')
            .replace('\n', '|')
            .split('|')
            .map { it.trim().trim(',', '.', ' ') }
            .filter { it.isNotBlank() }

        return primary.flatMap { segment ->
            val clauses = segment.split(Regex("(?i)\\s+and\\s+")).map { it.trim() }.filter { it.isNotBlank() }
            if (clauses.size <= 1) listOf(segment)
            else {
                val allMotion = clauses.all(::isMotionPhrase)
                if (allMotion) listOf(segment) else clauses
            }
        }
    }

    private fun canHandle(command: String): Boolean {
        val text = command.lowercase()
        return isMotionPhrase(text) || listOf(
            "clone", "blank frame", "new frame", "delete frame", "remove frame", "hold", "duration",
            "select ", "rotate", "bigger", "smaller", "scale", "flip", "duplicate", "delete selection",
            "move selection", "move it", "recolour", "recolor", "colour", "color",
            "happy", "sad", "angry", "shocked", "confused", "smug", "blink",
            "neutral", "wave", "point", "shrug", "jump pose", "run pose", "fall pose",
            "missing limbs", "add limbs", "in-between", "inbetween", "smoother", "snappier",
            "fix frame", "restore missing", "continue motion", "same motion", "close loop", "loop close",
            "go to frame", "open frame", "frame number", "hide ", "show ", "rename project", "set fps", "reset camera"
        ).any { it in text }
    }

    private fun executeOne(editor: EditorState, raw: String): String {
        val text = raw.trim().lowercase()
        val number = firstNumber(text)

        expressionFor(text)?.let { expression ->
            ExpressionTools.apply(editor, expression)
            return "Applied the ${expression.label.lowercase()} expression."
        }
        poseFor(text)?.let { pose ->
            ObjectShowTools.applyPose(editor, pose)
            return "Applied the ${pose.label.lowercase()} pose."
        }

        when {
            "blink" in text -> {
                val made = ObjectShowTools.createBlinkFrame(editor)
                return if (made) "Made an editable blink frame." else "I couldn't make a safe blink from the current artwork."
            }
            "missing limbs" in text || "add limbs" in text -> {
                val count = ObjectShowTools.addMissingStickLimbs(editor)
                return if (count > 0) "Added $count missing limb layers." else "All four semantic limb layers are already present."
            }
            text.startsWith("select ") -> return selectSemanticPart(editor, text)
            text.startsWith("hide ") || text.startsWith("show ") -> return setPartVisibility(editor, text)
            "go to frame" in text || "open frame" in text || "frame number" in text -> {
                val target = ((number ?: 1f).roundToInt() - 1).coerceIn(editor.project.frames.indices)
                editor.frameIndex = target
                editor.ensureIndices()
                editor.clearSelection()
                return "Opened frame ${target + 1}."
            }
            "rename project" in text -> {
                val name = raw.substringAfter(Regex("(?i)rename project(?: to)?").find(raw)?.value ?: "rename project")
                    .trim().trim('"', '\'', ' ').take(64)
                require(name.isNotBlank()) { "Give the project a name" }
                editor.project.name = name
                editor.project.touch()
                return "Renamed the project to $name."
            }
            "set fps" in text -> {
                val fps = (number ?: 30f).roundToInt().coerceIn(1, 60)
                editor.project.fps = fps
                editor.project.touch()
                return "Set export to $fps fps."
            }
            "reset camera" in text -> {
                editor.frame.cameraX = 0f
                editor.frame.cameraY = 0f
                editor.frame.cameraZoom = 1f
                editor.frame.cameraRotation = 0f
                editor.project.touch()
                return "Reset this frame's camera."
            }
            "clone" in text -> {
                editor.cloneFrame()
                return "Cloned the current frame."
            }
            "blank frame" in text || "new frame" in text -> {
                editor.addBlankFrame()
                return "Added a blank frame."
            }
            "delete frame" in text || "remove frame" in text -> {
                editor.deleteFrame()
                return "Deleted the current frame."
            }
            "hold" in text || "duration" in text -> {
                val seconds = (number ?: 1f).coerceIn(.05f, 120f)
                editor.frame.durationMs = (seconds * 1000f).roundToInt().coerceIn(50, 120000)
                editor.project.touch()
                return "Set this frame to hold for ${"%.2f".format(seconds)} seconds."
            }
            "rotate" in text -> {
                require(hasSelection(editor)) { "Select a limb, layer or strokes first" }
                editor.rotateSelection(number ?: 15f)
                return "Rotated the selection ${"%.1f".format(number ?: 15f)}°."
            }
            "bigger" in text || "smaller" in text || "scale" in text -> {
                require(hasSelection(editor)) { "Select a limb, layer or strokes first" }
                val factor = when {
                    number != null -> if (number > 4f) number / 100f else number
                    "smaller" in text -> .9f
                    else -> 1.1f
                }.coerceIn(.05f, 20f)
                editor.scaleSelection(factor)
                return "Scaled the selection to ${"%.2f".format(factor)}×."
            }
            "flip" in text -> {
                require(hasSelection(editor)) { "Select a limb, layer or strokes first" }
                editor.flipSelectionHorizontal()
                return "Flipped the selection horizontally."
            }
            "duplicate" in text -> {
                require(hasSelection(editor)) { "Select something first" }
                editor.duplicateSelection()
                return "Duplicated the selection."
            }
            "delete selection" in text || (text == "delete" && hasSelection(editor)) -> {
                require(hasSelection(editor)) { "Select something first" }
                editor.deleteSelection()
                return "Deleted the selection."
            }
            "move selection" in text || "move it" in text -> return moveSelection(editor, text, number)
            "recolour" in text || "recolor" in text || text.startsWith("colour ") || text.startsWith("color ") -> {
                require(editor.selectedIds.isNotEmpty()) { "Select vector strokes first to recolour them" }
                val argb = parseColour(text) ?: error("Use a hex colour like #ff3355, black, white, red, green, blue or yellow")
                editor.recolorSelection(argb)
                return "Recoloured the selected strokes."
            }
            "fix frame" in text || "restore missing" in text -> {
                val count = SmartAnimationTools.fixFrame(editor.project, editor.frameIndex)
                return if (count > 0) "Restored $count missing semantic layers." else "No missing semantic layer was found."
            }
            "snappier" in text -> {
                val count = SmartAnimationTools.makeSnappier(editor.project)
                editor.ensureIndices()
                return "Merged $count redundant frames."
            }
            "in-between" in text || "inbetween" in text || "smoother" in text -> {
                val passes = when {
                    "much smoother" in text -> 3
                    "smoother" in text -> 2
                    else -> 1
                }
                val count = SmartAnimationTools.makeSmoother(editor.project, editor.frameIndex, passes)
                return if (count > 0) "Inserted $count editable in-between frame${if (count == 1) "" else "s"}." else "A next frame is required."
            }
            "continue motion" in text || "same motion" in text -> {
                val made = SmartAnimationTools.continueMotion(editor.project, editor.frameIndex)
                return if (made) "Continued the previous motion into a new frame." else "A previous frame is required."
            }
            "close loop" in text || "loop close" in text -> {
                SmartAnimationTools.closeLoop(editor.project)
                return "Added an editable loop-closing frame."
            }
            isMotionPhrase(text) -> return AutoAnimationTools.apply(editor, raw).description + "."
            else -> error("I couldn't map “$raw” to a safe edit")
        }
    }

    private fun selectSemanticPart(editor: EditorState, text: String): String {
        editor.ensureIndices()
        val part = partFor(text) ?: error("Name a part such as body, face, left arm, right arm, left leg, right leg, eyes or mouth")
        val index = editor.frame.layers.indexOfFirst { it.part == part && !it.isRigSource }
        require(index >= 0) { "There is no ${part.label.lowercase()} layer in this frame" }
        editor.selectLayer(index)
        val layer = editor.frame.layers[index]
        if (!layer.hasRaster) {
            editor.selectedIds.clear()
            editor.selectedIds.addAll(layer.strokes.map { it.id })
            editor.selectedRasterLayerIndex = -1
        }
        return "Selected the ${part.label.lowercase()}."
    }

    private fun setPartVisibility(editor: EditorState, text: String): String {
        editor.ensureIndices()
        val part = partFor(text) ?: error("Name the part to show or hide")
        val visible = text.startsWith("show ")
        val targets = editor.frame.layers.filter { it.part == part && !it.isRigSource }
        require(targets.isNotEmpty()) { "There is no ${part.label.lowercase()} layer in this frame" }
        targets.forEach { it.visible = visible }
        editor.project.touch()
        return if (visible) "Showed the ${part.label.lowercase()}." else "Hid the ${part.label.lowercase()}."
    }

    private fun moveSelection(editor: EditorState, text: String, number: Float?): String {
        require(hasSelection(editor)) { "Select a limb, layer or strokes first" }
        val amount = (number ?: 24f).coerceIn(1f, maxOf(editor.project.canvasWidth, editor.project.canvasHeight).toFloat())
        val (dx, dy) = when {
            "left" in text -> -amount to 0f
            "right" in text -> amount to 0f
            "up" in text -> 0f to -amount
            "down" in text -> 0f to amount
            else -> error("Say left, right, up or down")
        }
        editor.moveSelection(dx, dy)
        return "Moved the selection ${amount.roundToInt()} px."
    }

    private fun expressionFor(text: String): ExpressionTools.Expression? = when {
        "happy" in text || "smile" in text -> ExpressionTools.Expression.Happy
        "sad" in text -> ExpressionTools.Expression.Sad
        "angry" in text || "mad" in text -> ExpressionTools.Expression.Angry
        "shocked" in text || "surprised" in text -> ExpressionTools.Expression.Shocked
        "confused" in text -> ExpressionTools.Expression.Confused
        "smug" in text -> ExpressionTools.Expression.Smug
        else -> null
    }

    private fun poseFor(text: String): ObjectShowTools.Pose? = when {
        text == "neutral" || "neutral pose" in text -> ObjectShowTools.Pose.Neutral
        "wave" in text -> ObjectShowTools.Pose.Wave
        "point" in text && "point" != text.substringAfter("move ", "") -> ObjectShowTools.Pose.Point
        "shrug" in text -> ObjectShowTools.Pose.Shrug
        "jump pose" in text -> ObjectShowTools.Pose.Jump
        "run pose" in text -> ObjectShowTools.Pose.Run
        "fall pose" in text -> ObjectShowTools.Pose.Fall
        else -> null
    }

    private fun partFor(text: String): Part? = when {
        "left arm" in text -> Part.LeftArm
        "right arm" in text -> Part.RightArm
        "left leg" in text -> Part.LeftLeg
        "right leg" in text -> Part.RightLeg
        "eyebrow" in text -> Part.Eyebrows
        "eyes" in text || "eye" in text -> Part.Eyes
        "mouth" in text -> Part.Mouth
        "face" in text -> Part.Face
        "body" in text || "torso" in text -> Part.Body
        "background" in text -> Part.Background
        "accessory" in text -> Part.Accessory
        else -> null
    }

    private fun parseColour(text: String): Int? {
        Regex("#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})").find(text)?.groupValues?.getOrNull(1)?.let { hex ->
            return runCatching {
                if (hex.length == 6) (0xFF000000L or hex.toLong(16)).toInt() else hex.toLong(16).toInt()
            }.getOrNull()
        }
        return when {
            "black" in text -> Color.BLACK
            "white" in text -> Color.WHITE
            "red" in text -> Color.RED
            "green" in text -> Color.GREEN
            "blue" in text -> Color.BLUE
            "yellow" in text -> Color.YELLOW
            else -> null
        }
    }

    private fun firstNumber(text: String): Float? = Regex("(-?\\d+(?:[.,]\\d+)?)")
        .find(text)?.groupValues?.getOrNull(1)?.replace(',', '.')?.toFloatOrNull()

    private fun hasSelection(editor: EditorState): Boolean = editor.selectedRasterLayerIndex >= 0 || editor.selectedIds.isNotEmpty()

    private fun isMotionPhrase(text: String): Boolean = listOf(
        "bounce", "jump", "shake", "wobble", "spin", "zoom in", "zoom out", "closer", "farther", "further",
        "walk left", "walk right", "move left", "move right", "slide left", "slide right", "fade in", "fade out"
    ).any { it in text.lowercase() }
}
