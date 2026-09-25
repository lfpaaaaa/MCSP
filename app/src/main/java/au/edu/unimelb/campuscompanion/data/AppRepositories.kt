package au.edu.unimelb.campuscompanion.data

import au.edu.unimelb.campuscompanion.auth.SupabaseProvider
import au.edu.unimelb.campuscompanion.data.fake.FakeGroupRepository
import au.edu.unimelb.campuscompanion.data.fake.FakeInviteRepository
import au.edu.unimelb.campuscompanion.data.invite.JoinLinkInbox
import au.edu.unimelb.campuscompanion.data.remote.GroupRemoteDataSource
import au.edu.unimelb.campuscompanion.data.remote.SupabaseGroupDataSource
import au.edu.unimelb.campuscompanion.data.repository.DefaultGroupRepository
import au.edu.unimelb.campuscompanion.data.repository.DefaultInviteRepository
import au.edu.unimelb.campuscompanion.data.repository.GroupRepository
import au.edu.unimelb.campuscompanion.data.repository.InviteRepository
import io.github.jan.supabase.auth.auth

/**
 * App-wide repositories. The Supabase-backed versions are used when the project URL and
 * publishable key are set in local.properties; otherwise the in-memory fakes with sample data
 * are used, so that screens can still be built and previewed.
 */
object AppRepositories {
    /** Join links received by the main activity. */
    val joinLinks = JoinLinkInbox()

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
}
