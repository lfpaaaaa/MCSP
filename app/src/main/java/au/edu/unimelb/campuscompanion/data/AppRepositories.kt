package au.edu.unimelb.campuscompanion.data

import android.content.Context
import au.edu.unimelb.campuscompanion.auth.SupabaseProvider
import au.edu.unimelb.campuscompanion.data.fake.FakeChatRepository
import au.edu.unimelb.campuscompanion.data.fake.FakeGroupRepository
import au.edu.unimelb.campuscompanion.data.fake.FakeInviteRepository
import au.edu.unimelb.campuscompanion.data.invite.JoinLinkInbox
import au.edu.unimelb.campuscompanion.data.local.CampusDatabase
import au.edu.unimelb.campuscompanion.data.model.CurrentUser
import au.edu.unimelb.campuscompanion.data.model.MessageStatus
import au.edu.unimelb.campuscompanion.data.remote.GroupRemoteDataSource
import au.edu.unimelb.campuscompanion.data.remote.SupabaseChatDataSource
import au.edu.unimelb.campuscompanion.data.remote.SupabaseGroupDataSource
import au.edu.unimelb.campuscompanion.data.repository.ChatRepository
import au.edu.unimelb.campuscompanion.data.repository.DefaultChatRepository
import au.edu.unimelb.campuscompanion.data.repository.DefaultGroupRepository
import au.edu.unimelb.campuscompanion.data.repository.DefaultInviteRepository
import au.edu.unimelb.campuscompanion.data.repository.GroupRepository
import au.edu.unimelb.campuscompanion.data.repository.InviteRepository
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * App-wide repositories. The Supabase-backed versions are used when the project URL and
 * publishable key are set in local.properties; otherwise the in-memory fakes with sample data
 * are used, so that screens can still be built and previewed.
 */
object AppRepositories {
    /** Join links received by the main activity. */
    val joinLinks = JoinLinkInbox()

    private lateinit var appContext: Context
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Prepares the on-device cache. Called once from the application's onCreate. */
    fun init(context: Context) {
        appContext = context.applicationContext
        applicationScope.launch {
            // Messages that were still sending when the app stopped can now be retried by the user.
            database.messageDao().replaceStatus(MessageStatus.Sending.name, MessageStatus.Failed.name)
        }
        SupabaseProvider.client?.let { client ->
            applicationScope.launch {
                client.auth.sessionStatus.collect { status ->
                    // Cached group data belongs to the user who was signed in.
                    if (status is SessionStatus.NotAuthenticated) {
                        database.messageDao().deleteAll()
                    }
                }
            }
        }
    }

    private val database: CampusDatabase by lazy { CampusDatabase.create(appContext) }

    private val groupDataSource: GroupRemoteDataSource? by lazy {
        SupabaseProvider.client?.let { SupabaseGroupDataSource(it) }
    }

    private val fakeGroups by lazy { FakeGroupRepository() }

    val groups: GroupRepository by lazy {
        val client = SupabaseProvider.client
        val remote = groupDataSource
        if (client == null || remote == null) {
            fakeGroups
        } else {
            DefaultGroupRepository(remote) { client.auth.currentUserOrNull()?.id }
        }
    }

    val invites: InviteRepository by lazy {
        val remote = groupDataSource
        if (remote == null) {
            FakeInviteRepository(fakeGroups)
        } else {
            DefaultInviteRepository(remote, groups)
        }
    }

    val chat: ChatRepository by lazy {
        val client = SupabaseProvider.client
        val remote = groupDataSource
        if (client == null || remote == null) {
            FakeChatRepository()
        } else {
            DefaultChatRepository(
                remote = SupabaseChatDataSource(client),
                groups = remote,
                messages = database.messageDao(),
                currentUser = { client.currentUser() }
            )
        }
    }
}

/** The signed-in user with the same display-name fallbacks as the profile screen. */
private fun SupabaseClient.currentUser(): CurrentUser? {
    val user = auth.currentUserOrNull() ?: return null
    val name = listOf("display_name", "full_name", "name").firstNotNullOfOrNull { key ->
        (user.userMetadata?.get(key) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    }
    return CurrentUser(id = user.id, displayName = name ?: user.email?.substringBefore('@') ?: "You")
}
