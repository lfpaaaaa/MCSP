package au.edu.unimelb.campuscompanion.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import au.edu.unimelb.campuscompanion.auth.AuthenticatedUser
import au.edu.unimelb.campuscompanion.profile.ProfileSaveState
import au.edu.unimelb.campuscompanion.profile.normalizedProfileName
import au.edu.unimelb.campuscompanion.profile.profileNameError
import au.edu.unimelb.campuscompanion.ui.profile.ProfileAvatar

@Composable
fun EditProfileScreen(
    user: AuthenticatedUser,
    state: ProfileSaveState,
    onSave: (String, Uri?, Boolean) -> Unit,
    onClearError: () -> Unit,
    onCancel: () -> Unit,
    onSaved: () -> Unit
) {
    var name by rememberSaveable(user.id) { mutableStateOf(user.profileName) }
    var photo by rememberSaveable(user.id) { mutableStateOf<String?>(null) }
    var removePhoto by rememberSaveable(user.id) { mutableStateOf(false) }
    var touched by rememberSaveable(user.id) { mutableStateOf(false) }
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            photo = uri.toString()
            removePhoto = false
            onClearError()
        }
    }
    BackHandler(enabled = state.saving) { /* Keep this save visible until the server answers. */ }
    LaunchedEffect(state.saved) { if (state.saved) onSaved() }
    val nameError = profileNameError(name)
    val changed = normalizedProfileName(name) != user.displayName || photo != null || removePhoto
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            ProfileAvatar(name, photo ?: user.avatarUrl.takeUnless { removePhoto }, Modifier.size(104.dp))
            TextButton(enabled = !state.saving, onClick = { picker.launch(arrayOf("image/*")) }) { Text("Change photo") }
            if (photo != null || (!user.avatarUrl.isNullOrBlank() && !removePhoto)) {
                TextButton(enabled = !state.saving, onClick = { photo = null; removePhoto = true; onClearError() }) { Text("Remove photo") }
            }
            Text("Photos are cropped to a square.", style = MaterialTheme.typography.bodySmall)
        }
        OutlinedTextField(
            value = name, onValueChange = { name = it.take(80); touched = true; onClearError() },
            enabled = !state.saving, singleLine = true, modifier = Modifier.fillMaxWidth(),
            label = { Text("Display name") }, isError = touched && nameError != null,
            supportingText = { Text(if (touched && nameError != null) nameError else "${name.length}/80") }
        )
        Text("This name is used in groups where you haven't set a group nickname.", style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(value = user.email.orEmpty(), onValueChange = {}, readOnly = true,
            modifier = Modifier.fillMaxWidth(), label = { Text("Account email") },
            supportingText = { Text("Your sign-in email") })
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(enabled = !state.saving && changed && nameError == null, modifier = Modifier.fillMaxWidth(), onClick = {
            focus.clearFocus()
            onSave(name, photo?.let(Uri::parse), removePhoto)
        }) {
            if (state.saving) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
            Text(if (state.saving) "Saving…" else "Save changes")
        }
        OutlinedButton(enabled = !state.saving, onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
    }
}
