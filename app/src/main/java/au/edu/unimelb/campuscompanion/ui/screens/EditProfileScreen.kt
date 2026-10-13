package au.edu.unimelb.campuscompanion.ui.screens

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import au.edu.unimelb.campuscompanion.ui.text.textResources
import au.edu.unimelb.campuscompanion.R
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
    val textResources = textResources()
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
            TextButton(enabled = !state.saving, onClick = { picker.launch(arrayOf("image/*")) }) { Text(textResources.getString(R.string.edit_profile_change_photo)) }
            if (photo != null || (!user.avatarUrl.isNullOrBlank() && !removePhoto)) {
                TextButton(enabled = !state.saving, onClick = { photo = null; removePhoto = true; onClearError() }) { Text(textResources.getString(R.string.edit_profile_remove_photo)) }
            }
            Text(textResources.getString(R.string.edit_profile_photos_are_cropped_to_a_square), style = MaterialTheme.typography.bodySmall)
        }
        OutlinedTextField(
            value = name, onValueChange = { name = it.take(80); touched = true; onClearError() },
            enabled = !state.saving, singleLine = true, modifier = Modifier.fillMaxWidth().semantics {
                if (touched && nameError != null) error(nameError.resolve(textResources))
            },
            label = { Text(textResources.getString(R.string.auths_display_name)) }, isError = touched && nameError != null,
            supportingText = { Text(if (touched && nameError != null) nameError.resolve(textResources) else textResources.getString(R.string.profile_name_character_count, name.length)) }
        )
        Text(textResources.getString(R.string.edit_profile_this_name_is_used_in_groups_where_you), style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(value = user.email.orEmpty(), onValueChange = {}, readOnly = true,
            modifier = Modifier.fillMaxWidth(), label = { Text(textResources.getString(R.string.edit_profile_account_email)) },
            supportingText = { Text(textResources.getString(R.string.edit_profile_your_sign_in_email)) })
        state.error?.let { Text(it.resolve(textResources), color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        Button(enabled = !state.saving && changed && nameError == null, modifier = Modifier.fillMaxWidth(), onClick = {
            focus.clearFocus()
            onSave(name, photo?.let(Uri::parse), removePhoto)
        }) {
            if (state.saving) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
            Text(if (state.saving) textResources.getString(R.string.chat_attachments_saving) else textResources.getString(R.string.edit_profile_save_changes))
        }
        OutlinedButton(enabled = !state.saving, onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text(textResources.getString(R.string.action_cancel)) }
    }
}
