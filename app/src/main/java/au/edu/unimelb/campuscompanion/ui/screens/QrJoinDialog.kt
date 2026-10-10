package au.edu.unimelb.campuscompanion.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import au.edu.unimelb.campuscompanion.data.toUserMessage
import au.edu.unimelb.campuscompanion.ui.model.groupInviteFromQr
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
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
                "This QR code is not a Campus Companion invitation. Ask a group member for a new code."
            } else null
        }
    }
    fun launchScanner() {
        message = null
        permissionDenied = false
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            message = "No camera is available. You can still enter the group code."
            return
        }
        try {
            scanning = true
            scanner.launch(
                ScanOptions()
                    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    .setPrompt("Scan a Campus Companion group invitation")
                    .setBeepEnabled(false)
                    .setOrientationLocked(false)
            )
        } catch (_: Exception) {
            scanning = false
            message = "The camera could not be opened. Try again or enter the group code."
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchScanner() else {
            permissionDenied = true
            message = "Camera access is needed to scan. Allow it in Settings, or enter the group code."
        }
    }

    AlertDialog(
        onDismissRequest = { if (!joining && !scanning) onDismiss() },
        title = { Text(if (joined) "Group joined" else "Scan to join") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(when {
                    joined -> "The group now appears in your active groups."
                    joining -> "Checking the invitation and joining the group…"
                    invite != null -> "Group invitation found. Tap Join to accept it."
                    else -> "Scan the QR code shown by a group member."
                })
                if (joining) CircularProgressIndicator(modifier = Modifier.size(28.dp))
                message?.let { Text(it) }
                if (permissionDenied) {
                    TextButton(onClick = {
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:${context.packageName}")))
                    }) { Text("Open settings") }
                }
                if (!joined && !joining && !scanning) {
                    TextButton(onClick = onEnterCode) { Text("Enter code instead") }
                    if (invite != null) {
                        TextButton(onClick = { invite = null; message = null }) { Text("Use another QR code") }
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
                                        onFailure = { message = it.toUserMessage().body }
                                    )
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (error: Exception) {
                                    message = error.toUserMessage().body
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
            ) { Text(if (joined) "Done" else if (invite != null) "Join" else "Open scanner") }
        },
        dismissButton = {
            if (!joined) {
                TextButton(onClick = onDismiss, enabled = !joining && !scanning) { Text("Cancel") }
            }
        }
    )
}
