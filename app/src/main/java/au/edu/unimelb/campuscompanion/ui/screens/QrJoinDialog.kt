package au.edu.unimelb.campuscompanion.ui.screens

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.runtime.setValue

import androidx.compose.runtime.getValue

import au.edu.unimelb.campuscompanion.ui.text.textResources
import au.edu.unimelb.campuscompanion.R
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import au.edu.unimelb.campuscompanion.data.toUserMessage
import au.edu.unimelb.campuscompanion.ui.model.groupInviteFromQr
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun QrJoinDialog(
    onDismiss: () -> Unit,
    onEnterCode: () -> Unit,
    onJoinGroup: suspend (String) -> Result<Unit>
) {
    val textResources = textResources()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var invite by rememberSaveable { mutableStateOf<String?>(null) }
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    var permissionDenied by rememberSaveable { mutableStateOf(false) }
    var joined by rememberSaveable { mutableStateOf(false) }
    // In-flight work belongs to this composition, so never restore a stale loading state.
    var joining by remember { mutableStateOf(false) }
    var scanning by rememberSaveable { mutableStateOf(false) }
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        scanning = false
        result.contents?.let { contents ->
            invite = groupInviteFromQr(contents)
            message = if (invite == null) {
                textResources.getString(R.string.qr_join_this_qr_code_is_not_a_campus_companion)
            } else null
        }
    }
    fun launchScanner() {
        message = null
        permissionDenied = false
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            message = textResources.getString(R.string.qr_join_no_camera_is_available_you_can_still_enter)
            return
        }
        try {
            scanning = true
            scanner.launch(
                ScanOptions()
                    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    .setPrompt(textResources.getString(R.string.qr_join_scan_a_campus_companion_group_invitation))
                    .setBeepEnabled(false)
                    .setOrientationLocked(false)
            )
        } catch (_: Exception) {
            scanning = false
            message = textResources.getString(R.string.qr_join_the_camera_could_not_be_opened_try_again)
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchScanner() else {
            permissionDenied = true
            message = textResources.getString(R.string.qr_join_camera_access_is_needed_to_scan_allow_it)
        }
    }

    AlertDialog(
        onDismissRequest = { if (!joining && !scanning) onDismiss() },
        title = { Text(if (joined) textResources.getString(R.string.groups_group_joined) else textResources.getString(R.string.qr_join_scan_to_join)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(when {
                    joined -> textResources.getString(R.string.qr_join_the_group_now_appears_in_your_active_groups)
                    joining -> textResources.getString(R.string.qr_join_checking_the_invitation_and_joining_the_group)
                    invite != null -> textResources.getString(R.string.qr_join_group_invitation_found_tap_join_to_accept_it)
                    else -> textResources.getString(R.string.qr_join_scan_the_qr_code_shown_by_a_group)
                }, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                if (joining) CircularProgressIndicator(modifier = Modifier.size(28.dp))
                message?.let { Text(it, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                if (permissionDenied) {
                    TextButton(onClick = {
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:${context.packageName}")))
                    }) { Text(textResources.getString(R.string.class_navigation_open_settings)) }
                }
                if (!joined && !joining && !scanning) {
                    TextButton(onClick = onEnterCode) { Text(textResources.getString(R.string.qr_join_enter_code_instead)) }
                    if (invite != null) {
                        TextButton(onClick = { invite = null; message = null }) { Text(textResources.getString(R.string.qr_join_use_another_qr_code)) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !joining && !scanning,
                onClick = {
                    val scannedInvite = invite
                    when {
                        joined -> onDismiss()
                        scannedInvite != null -> {
                            joining = true
                            message = null
                            scope.launch {
                                try {
                                    onJoinGroup(scannedInvite).fold(
                                        onSuccess = { joined = true },
                                        onFailure = { message = it.toUserMessage().body.resolve(textResources) }
                                    )
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (error: Exception) {
                                    message = error.toUserMessage().body.resolve(textResources)
                                } finally {
                                    joining = false
                                }
                            }
                        }
                        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                            PackageManager.PERMISSION_GRANTED -> launchScanner()
                        else -> permission.launch(Manifest.permission.CAMERA)
                    }
                }
            ) { Text(if (joined) textResources.getString(R.string.course_reminder_settings_done) else if (invite != null) textResources.getString(R.string.groups_join) else textResources.getString(R.string.qr_join_open_scanner)) }
        },
        dismissButton = {
            if (!joined) {
                TextButton(onClick = onDismiss, enabled = !joining && !scanning) { Text(textResources.getString(R.string.action_cancel)) }
            }
        }
    )
}
