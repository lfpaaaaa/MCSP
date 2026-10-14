package au.edu.unimelb.campuscompanion.ui.screens

import android.content.res.Configuration
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import au.edu.unimelb.campuscompanion.R
import au.edu.unimelb.campuscompanion.ui.theme.CampusCompanionTheme

/** Only actual recorder samples light the meter; idle and unavailable input stay empty. */
@Composable
internal fun RecordingLevelMeter(level: Float?, active: Boolean, modifier: Modifier = Modifier) {
    val input = level?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)
    val label = stringResource(R.string.recording_input_level)
    val status = stringResource(when {
        !active -> R.string.recording_input_inactive
        input == null -> R.string.recording_input_unavailable
        input <= 0.1f -> R.string.recording_input_quiet
        input >= 0.95f -> R.string.recording_input_high
        else -> R.string.recording_input_detected
    })
    val animated by animateFloatAsState(
        targetValue = if (active) input ?: 0f else 0f,
        animationSpec = tween(80), label = "recording input level"
    )
    val colors = MaterialTheme.colorScheme
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Column(modifier.fillMaxWidth().clearAndSetSemantics {
        contentDescription = label
        stateDescription = status
        // Readable on focus, without announcing every microphone sample.
    }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Canvas(Modifier.fillMaxWidth().height(24.dp)) {
            val count = 20
            val gap = 3.dp.toPx()
            val width = ((size.width - gap * (count - 1)) / count).coerceAtLeast(0f)
            val visibleLevel = if (active && input != null) animated else 0f
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

@Preview(name = "Recording levels", widthDp = 300, showBackground = true)
@Preview(name = "Recording levels - dark", widthDp = 300, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Recording levels - large text", widthDp = 300, fontScale = 2f)
@Composable
private fun RecordingLevelMeterPreview() {
    CampusCompanionTheme {
        Surface {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                RecordingLevelMeter(level = 0f, active = false)
                RecordingLevelMeter(level = 0f, active = true)
                RecordingLevelMeter(level = 0.65f, active = true)
                RecordingLevelMeter(level = 1f, active = true)
                RecordingLevelMeter(level = null, active = true)
            }
        }
    }
}
