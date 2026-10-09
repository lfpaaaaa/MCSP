package au.edu.unimelb.campuscompanion.push

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import au.edu.unimelb.campuscompanion.MainActivity
import au.edu.unimelb.campuscompanion.R
import au.edu.unimelb.campuscompanion.auth.SupabaseProvider
import au.edu.unimelb.campuscompanion.data.AppRepositories
import au.edu.unimelb.campuscompanion.ui.GroupChatPreferencesStore
import au.edu.unimelb.campuscompanion.ui.chat.OpenChat
import io.github.jan.supabase.auth.auth
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.launch

/** Receives Firebase Cloud Messaging events: token rotation and new group messages. */
class CampusMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        AppRepositories.push.onTokenRefreshed(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val push = MessagePush.from(message.data) ?: return
        // The group list shows the latest message and unread count, so it is refreshed as well.
        AppRepositories.backgroundScope.launch { AppRepositories.groups.refresh() }
        showNotification(this, push)
    }

    companion object {
        const val CHANNEL_ID = "messages"
        const val EXTRA_GROUP_ID = "group_id"

        @SuppressLint("MissingPermission")
        fun showNotification(context: Context, push: MessagePush) {
            if (!canNotify(context)) return
            // The chat that is on screen already shows the message, and muted groups stay quiet.
            if (push.groupId == OpenChat.groupId || isMuted(context, push.groupId)) return
            createChannel(context)

            val open = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_GROUP_ID, push.groupId)
            }
            val tap = PendingIntent.getActivity(
                context,
                push.notificationId,
                open,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(push.title)
                .setContentText(push.text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(push.text))
                .setContentIntent(tap)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .build()
            NotificationManagerCompat.from(context).notify(push.notificationId, notification)
        }

        private fun isMuted(context: Context, groupId: String): Boolean {
            val userId = SupabaseProvider.client?.auth?.currentUserOrNull()?.id ?: return false
            return GroupChatPreferencesStore(context, userId).load(groupId).muted
        }

        private fun canNotify(context: Context): Boolean {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
                if (!granted) return false
            }
            return NotificationManagerCompat.from(context).areNotificationsEnabled()
        }

        private fun createChannel(context: Context) {
            val channel = NotificationChannel(CHANNEL_ID, "Group messages", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "New messages in your groups"
            }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}
