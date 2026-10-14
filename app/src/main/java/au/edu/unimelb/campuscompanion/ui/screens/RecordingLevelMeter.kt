package au.edu.unimelb.campuscompanion.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import au.edu.unimelb.campuscompanion.ui.model.RecordingInputStatus
import au.edu.unimelb.campuscompanion.ui.model.recordingInputStatus

/** Bars are driven exclusively by real recorder input; idle/unavailable input lights no bars. */
@Composable
internal fun RecordingLevelMeter(level: Float?, active: Boolean, modifier: Modifier = Modifier) {
    val input = level?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)
    val status = when (recordingInputStatus(input, active)) {
        RecordingInputStatus.Inactive -> "Microphone inactive"
        RecordingInputStatus.Unavailable -> "Microphone level unavailable"
        RecordingInputStatus.Quiet -> "Quiet input"
        RecordingInputStatus.Detected -> "Microphone input detected"
        RecordingInputStatus.High -> "High input level"
    }
    val colors = MaterialTheme.colorScheme
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Column(modifier.fillMaxWidth().clearAndSetSemantics {
        contentDescription = "Microphone level"
        stateDescription = status
    }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Microphone level", style = MaterialTheme.typography.labelLarge)
        Canvas(Modifier.fillMaxWidth().height(24.dp)) {
            val count = 20
            val gap = 3.dp.toPx()
            val width = ((size.width - gap * (count - 1)) / count).coerceAtLeast(0f)
            val visibleLevel = if (active) input ?: 0f else 0f
            repeat(count) { index ->
                val lit = visibleLevel > index.toFloat() / count
                val color = when {
                    !lit -> colors.outlineVariant
                    index >= 18 -> colors.error
                    index >= 15 -> colors.tertiary
                    else -> colors.primary
                }
                val left = index * (width + gap)
                drawRoundRect(color, topLeft = Offset(if (rtl) size.width - left - width else left, 0f),
                    size = Size(width, size.height), cornerRadius = CornerRadius(2.dp.toPx()))
            }
        }
        Text(status, style = MaterialTheme.typography.bodySmall,
            color = if (active && input != null && input >= 0.95f) colors.error else colors.onSurfaceVariant)
    }
}
