package au.edu.unimelb.campuscompanion.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import au.edu.unimelb.campuscompanion.data.isManualClass
import au.edu.unimelb.campuscompanion.ui.model.CourseSession
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch

private val editorDateFormat = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ENGLISH)
private val editorTimeFormat = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)

/**
 * Adds a class by hand ([initial] null) or edits one. Editing an imported class stores a
 * correction that keeps the class's identity, so reminders and directions follow it; editing a
 * class added by hand updates it in place. The dialog reports a failed save inline and stays open.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClassEditorDialog(
    initial: CourseSession?,
    isEdited: Boolean,
    onDismiss: () -> Unit,
    onSave: suspend (CourseSession) -> Result<Unit>,
    onRemove: (suspend () -> Result<Unit>)? = null,
    onRestore: (suspend () -> Result<Unit>)? = null
) {
    val zone = remember(initial) { initial?.start?.zone ?: ZoneId.systemDefault() }
    var code by rememberSaveable(initial) { mutableStateOf(initial?.code.orEmpty()) }
    var title by rememberSaveable(initial) { mutableStateOf(initial?.title.orEmpty()) }
    var activity by rememberSaveable(initial) { mutableStateOf(initial?.activity.orEmpty()) }
    var location by rememberSaveable(initial) { mutableStateOf(initial?.location.orEmpty()) }
    var room by rememberSaveable(initial) { mutableStateOf(initial?.room.orEmpty()) }
    var date by rememberSaveable(initial) { mutableStateOf(initial?.startDate ?: LocalDate.now(zone)) }
    var startTime by rememberSaveable(initial) {
        mutableStateOf(initial?.startTime ?: LocalTime.now(zone).plusHours(1).withMinute(0).withSecond(0).withNano(0))
    }
    var endTime by rememberSaveable(initial) { mutableStateOf(initial?.endTime ?: startTime.plusHours(1)) }
    var showErrors by rememberSaveable { mutableStateOf(false) }
    var requestError by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var pickingDate by remember { mutableStateOf(false) }
    var pickingStart by remember { mutableStateOf(false) }
    var pickingEnd by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val codeMissing = code.isBlank()
    val titleMissing = title.isBlank()
    val endsBeforeStart = !endTime.isAfter(startTime)
    val isValid = !codeMissing && !titleMissing && !endsBeforeStart
    val isManual = initial?.isManualClass == true

    fun perform(action: suspend () -> Result<Unit>) {
        busy = true
        requestError = null
        scope.launch {
            try {
                action().fold(
                    onSuccess = { onDismiss() },
                    onFailure = { requestError = "Could not save the class. Try again." }
                )
            } finally {
                busy = false
            }
        }
    }

    fun save() {
        showErrors = true
        if (!isValid) return
        val session = CourseSession(
            id = initial?.id.orEmpty(),
            code = code.trim().uppercase(Locale.ENGLISH),
            title = title.trim(),
            location = location.trim(),
            room = room.trim(),
            start = ZonedDateTime.of(date, startTime, zone),
            end = ZonedDateTime.of(date, endTime, zone),
            activity = activity.trim().lowercase(Locale.ENGLISH).ifBlank { null }
        )
        perform { onSave(session) }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (initial == null) "Add class" else "Edit class") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (initial != null && !isManual) {
                    Text(
                        "Changes to an imported class are kept when the timetable refreshes. Restore its imported details at any time.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = { Text("Course code") },
                    placeholder = { Text("COMP90018") },
                    singleLine = true,
                    isError = showErrors && codeMissing,
                    supportingText = if (showErrors && codeMissing) {
                        { Text("Enter the course code.") }
                    } else null,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Class name") },
                    singleLine = true,
                    isError = showErrors && titleMissing,
                    supportingText = if (showErrors && titleMissing) {
                        { Text("Enter the class name.") }
                    } else null,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = activity,
                    onValueChange = { activity = it },
                    label = { Text("Activity (optional)") },
                    placeholder = { Text("Lecture, tutorial, workshop…") },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = location,
                    onValueChange = { location = it },
                    label = { Text("Building") },
                    placeholder = { Text("Parkville Campus, PAR-160") },
                    supportingText = { Text("A building code (PAR-160) or name lets the app find travel times.") },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = room,
                    onValueChange = { room = it },
                    label = { Text("Room (optional)") },
                    placeholder = { Text("PAR-160-B117") },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedButton(onClick = { pickingDate = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text("Date: ${date.format(editorDateFormat)}")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = { pickingStart = true }, enabled = !busy, modifier = Modifier.weight(1f)) {
                        Text("Starts ${startTime.format(editorTimeFormat)}")
                    }
                    OutlinedButton(onClick = { pickingEnd = true }, enabled = !busy, modifier = Modifier.weight(1f)) {
                        Text("Ends ${endTime.format(editorTimeFormat)}")
                    }
                }
                if (showErrors && endsBeforeStart) {
                    Text(
                        "The class must end after it starts.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                requestError?.let { message ->
                    Text(
                        message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                    )
                }
                if (initial != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        onRemove?.let { remove ->
                            TextButton(onClick = { perform(remove) }, enabled = !busy) {
                                Text(if (isManual) "Delete class" else "Hide class")
                            }
                        }
                        if (isEdited) onRestore?.let { restore ->
                            TextButton(onClick = { perform(restore) }, enabled = !busy) {
                                Text("Restore imported details")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = ::save, enabled = !busy) {
                Text(if (busy) "Saving…" else "Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") }
        }
    )

    if (pickingDate) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        date = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                    }
                    pickingDate = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { pickingDate = false }) { Text("Cancel") }
            }
        ) {
            DatePicker(state = pickerState)
        }
    }
    if (pickingStart) {
        TimePickerDialog(
            title = "Start time",
            initial = startTime,
            onDismiss = { pickingStart = false },
            onConfirm = { picked ->
                // Keep the length of the class when its start moves.
                val length = Duration.between(startTime, endTime)
                startTime = picked
                if (!length.isNegative && !length.isZero) {
                    val moved = picked.plus(length)
                    if (moved.isAfter(picked)) endTime = moved
                }
                pickingStart = false
            }
        )
    }
    if (pickingEnd) {
        TimePickerDialog(
            title = "End time",
            initial = endTime,
            onDismiss = { pickingEnd = false },
            onConfirm = { picked -> endTime = picked; pickingEnd = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerDialog(
    title: String,
    initial: LocalTime,
    onDismiss: () -> Unit,
    onConfirm: (LocalTime) -> Unit
) {
    val state = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { TimePicker(state = state) },
        confirmButton = {
            TextButton(onClick = { onConfirm(LocalTime.of(state.hour, state.minute)) }) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
