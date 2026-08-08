package com.frameflow.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ReleaseSliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueText: String,
    modifier: Modifier = Modifier,
    onValueChange: (Float) -> Unit
) {
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
            Text(valueText, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(value = value.coerceIn(range.start, range.endInclusive), onValueChange = onValueChange, valueRange = range)
    }
}

@Composable
fun HistorySliderRow(
    key: String,
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueText: String,
    history: ProjectHistory,
    onValueChange: (Float) -> Unit
) {
    var lastChangeAt by remember(key) { mutableLongStateOf(0L) }
    ReleaseSliderRow(label, value, range, valueText) { next ->
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastChangeAt > 350L) history.checkpoint()
        lastChangeAt = now
        onValueChange(next)
    }
}

@Composable
fun ReleaseSectionTitle(title: String, subtitle: String? = null) {
    Column(Modifier.padding(top = 6.dp, bottom = 3.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
