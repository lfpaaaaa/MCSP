package au.edu.unimelb.campuscompanion.ui.screens

import androidx.compose.runtime.setValue

import androidx.compose.runtime.getValue

import au.edu.unimelb.campuscompanion.ui.text.textResources
import au.edu.unimelb.campuscompanion.R
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Uses the system save picker so downloads work without broad storage permissions. */
@Composable
internal fun GroupQrDownload(code: String) {
    val textResources = textResources()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var message by rememberSaveable(code) { mutableStateOf<String?>(null) }
    var pendingCode by rememberSaveable { mutableStateOf<String?>(null) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        val selectedCode = pendingCode
        pendingCode = null
        if (uri != null && selectedCode != null) {
            saving = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val bitmap = createGroupQrBitmap(selectedCode, 1024)
                        try {
                            val output = context.contentResolver.openOutputStream(uri)
                                ?: error("Cannot open destination")
                            output.use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                        } finally {
                            bitmap.recycle()
                        }
                    }
                    message = textResources.getString(R.string.presentation_qr_code_saved)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    message = textResources.getString(R.string.group_qr_download_could_not_save_the_qr_code_please_try)
                } finally {
                    saving = false
                }
            }
        }
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        GroupJoinQrCode(code)
        OutlinedButton(
            enabled = !saving && pendingCode == null,
            onClick = {
                message = null
                pendingCode = code
                try {
                    save.launch("campus-group-invite.png")
                } catch (_: Exception) {
                    pendingCode = null
                    message = textResources.getString(R.string.group_qr_download_no_file_saving_app_is_available_on_this)
                }
            }
        ) { Text(if (saving) textResources.getString(R.string.chat_attachments_saving) else textResources.getString(R.string.group_qr_download_download_qr_code)) }
        message?.let { Text(it) }
    }
}
