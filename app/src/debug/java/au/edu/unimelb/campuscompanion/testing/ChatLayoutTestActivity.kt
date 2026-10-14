package au.edu.unimelb.campuscompanion.testing

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import au.edu.unimelb.campuscompanion.R
import au.edu.unimelb.campuscompanion.data.AttachmentPhase
import au.edu.unimelb.campuscompanion.data.AttachmentUpload
import au.edu.unimelb.campuscompanion.data.AttachmentUploadQueue
import au.edu.unimelb.campuscompanion.data.fake.FakeChatRepository
import au.edu.unimelb.campuscompanion.data.fake.FakeFileRepository
import au.edu.unimelb.campuscompanion.data.repository.ChatMessageSender
import au.edu.unimelb.campuscompanion.ui.CampusScreenScaffold
import au.edu.unimelb.campuscompanion.ui.model.CourseGroup
import au.edu.unimelb.campuscompanion.ui.model.PendingDocument
import au.edu.unimelb.campuscompanion.ui.screens.GroupChatScreen
import au.edu.unimelb.campuscompanion.ui.theme.CampusCompanionTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.time.Instant

/** Local fixtures only: no real groups, uploads or message delivery. Not present in release builds. */
class ChatLayoutTestActivity : ComponentActivity() {
    private val fixtureScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val userId = "chat-layout-user"
        val group = CourseGroup("chat-layout-group", "", "Chat layout check", 2, 0, "", null)
        val files = FakeFileRepository(userId, latencyMillis = 0)
        val chat = FakeChatRepository(userId, latencyMillis = 0)
        val pending = intent.getBooleanExtra("pending", false)
        val upload = AttachmentUpload("layout-upload", userId, group.id, "content://layout/photo",
            FILE_NAME, "image/jpeg", Instant.now().toString(), phase = AttachmentPhase.Failed,
            error = getString(R.string.error_try_again_if_it_keeps_happening_restart_the))
        val uploads = AttachmentUploadQueue(files, fixtureScope, { userId }, { byteArrayOf(1) },
            { it.resolve(resources) }, restored = if (pending) emptyList() else listOf(upload))
        val sender = ChatMessageSender(chat, fixtureScope)
        setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, intent.getFloatExtra("font_scale", 1f))) {
                CampusCompanionTheme(darkTheme = false) {
                    CampusScreenScaffold("group_chat/{groupId}", group, false, {}, {}, {}) {
                        GroupChatScreen(
                            group = group, currentUserId = userId,
                            pendingDocuments = if (pending) listOf(PendingDocument("content://layout/document", FILE_NAME, 2048)) else emptyList(),
                            pendingDocumentError = null, capturedCameraUri = null,
                            onPickDocuments = {}, onPendingDocumentRemoved = {}, onPendingDocumentsCleared = {},
                            onTakePhoto = {}, onCapturedCameraPhotoConsumed = {},
                            chatRepository = chat, fileRepository = files, messageSender = sender, uploadQueue = uploads
                        )
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        fixtureScope.cancel()
        super.onDestroy()
    }

    companion object { const val FILE_NAME = "group-photo-1394518627324864956.jpg" }
}
