package com.frameflow.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Frameflow's editor slider.
 *
 * Material sliders are great for settings screens, but animation controls need to
 * keep ownership of a gesture until the finger is released. This implementation
 * is intentionally delta-driven: the thumb never loses the drag just because the
 * finger leaves the visible track or drifts vertically.
 */
@Composable
fun Slider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null
) {
    val latestValue by rememberUpdatedState(value)
    val latestOnChange by rememberUpdatedState(onValueChange)
    val latestFinished by rememberUpdatedState(onValueChangeFinished)
    var widthPx by remember { mutableIntStateOf(1) }
    var workingValue by remember { mutableFloatStateOf(value) }

    val start = valueRange.start
    val end = valueRange.endInclusive
    val span = (end - start).takeIf { it > 0f } ?: 1f
    val shown = value.coerceIn(start, end)
    val fraction = ((shown - start) / span).coerceIn(0f, 1f)
    val active = MaterialTheme.colorScheme.primary
    val inactive = MaterialTheme.colorScheme.surfaceVariant
    val thumbOutline = MaterialTheme.colorScheme.surface

    Canvas(
        modifier
            .fillMaxWidth()
            .height(48.dp)
            .onSizeChanged { widthPx = it.width.coerceAtLeast(1) }
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(shown, valueRange, steps)
            }
            .pointerInput(enabled, start, end, steps) {
                if (!enabled) return@pointerInput
                detectDragGestures(
                    onDragStart = {
                        // Start from the current value. Touching elsewhere on the track
                        // never causes an accidental jump.
                        workingValue = latestValue.coerceIn(start, end)
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        val delta = dragAmount.x / widthPx.toFloat() * span
                        var next = (workingValue + delta).coerceIn(start, end)
                        if (steps > 0) {
                            val intervals = steps + 1
                            val stepSize = span / intervals
                            next = (start + kotlin.math.round((next - start) / stepSize) * stepSize)
                                .coerceIn(start, end)
                        }
                        workingValue = next
                        latestOnChange(next)
                    },
                    onDragEnd = { latestFinished?.invoke() },
                    onDragCancel = { latestFinished?.invoke() }
                )
            }
    ) {
        val side = 10.dp.toPx()
        val y = size.height / 2f
        val left = side
        val right = (size.width - side).coerceAtLeast(left + 1f)
        val thumbX = left + (right - left) * fraction
        drawLine(inactive, Offset(left, y), Offset(right, y), strokeWidth = 4.dp.toPx())
        drawLine(active, Offset(left, y), Offset(thumbX, y), strokeWidth = 5.dp.toPx())
        drawCircle(thumbOutline, radius = 11.dp.toPx(), center = Offset(thumbX, y))
        drawCircle(active, radius = 8.dp.toPx(), center = Offset(thumbX, y))
    }
}

/**
 * Stable-frame overload used by Frameflow's timeline.
 *
 * FullEditorScreen used the project's visual revision in the caller-provided key.
 * That meant every duration update recreated the timeline item while the finger
 * was still down, which cancelled the gesture and made the handle appear to
 * "stop" until the user dragged again. Frame objects themselves are stable, so
 * they are the correct identity for timeline items.
 */
inline fun LazyListScope.itemsIndexed(
    items: SnapshotStateList<FrameState>,
    noinline key: ((index: Int, item: FrameState) -> Any)? = null,
    crossinline itemContent: @Composable LazyItemScope.(index: Int, item: FrameState) -> Unit
) {
    @Suppress("UNUSED_VARIABLE")
    val callerKey = key
    items(
        count = items.size,
        key = { index -> items[index] }
    ) { index ->
        itemContent(index, items[index])
    }
}
