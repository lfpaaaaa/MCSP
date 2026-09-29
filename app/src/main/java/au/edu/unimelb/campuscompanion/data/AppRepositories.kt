package au.edu.unimelb.campuscompanion.data

import android.content.Context
import au.edu.unimelb.campuscompanion.auth.SupabaseProvider
import au.edu.unimelb.campuscompanion.context.TravelEngine
import au.edu.unimelb.campuscompanion.data.building.BuildingLocationRepository
import au.edu.unimelb.campuscompanion.data.fake.FakeChatRepository
import au.edu.unimelb.campuscompanion.data.fake.FakeEtaRepository
import au.edu.unimelb.campuscompanion.data.fake.FakeFileRepository
import au.edu.unimelb.campuscompanion.data.fake.FakeGroupRepository
import au.edu.unimelb.campuscompanion.data.fake.FakeInviteRepository
import au.edu.unimelb.campuscompanion.data.invite.JoinLinkInbox
import au.edu.unimelb.campuscompanion.data.local.CampusDatabase
import au.edu.unimelb.campuscompanion.data.model.CurrentUser
import au.edu.unimelb.campuscompanion.data.model.MessageStatus
import au.edu.unimelb.campuscompanion.data.remote.GroupRemoteDataSource
import au.edu.unimelb.campuscompanion.data.remote.OpenMeteoWeatherDataSource
import au.edu.unimelb.campuscompanion.data.remote.SupabaseChatDataSource
import au.edu.unimelb.campuscompanion.data.remote.SupabaseFileDataSource
import au.edu.unimelb.campuscompanion.data.remote.SupabaseGroupDataSource
import au.edu.unimelb.campuscompanion.data.remote.SupabasePushDataSource
import au.edu.unimelb.campuscompanion.data.remote.SupabaseRouteDataSource
import au.edu.unimelb.campuscompanion.data.repository.ChatRepository
import au.edu.unimelb.campuscompanion.data.repository.DefaultChatRepository
import au.edu.unimelb.campuscompanion.data.repository.DefaultFileRepository
import au.edu.unimelb.campuscompanion.data.repository.DefaultGroupRepository
import au.edu.unimelb.campuscompanion.data.repository.DefaultInviteRepository
import au.edu.unimelb.campuscompanion.data.repository.DefaultWeatherRepository
import au.edu.unimelb.campuscompanion.data.repository.EtaRepository
import au.edu.unimelb.campuscompanion.data.repository.FileRepository
import au.edu.unimelb.campuscompanion.data.repository.GroupRepository
import au.edu.unimelb.campuscompanion.data.repository.InviteRepository
import au.edu.unimelb.campuscompanion.data.repository.RoutedEtaRepository
import au.edu.unimelb.campuscompanion.data.repository.WeatherRepository
import au.edu.unimelb.campuscompanion.push.FirebaseTokenSource
import au.edu.unimelb.campuscompanion.push.PushTokens
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
        applicationScope.launch { travel.run() }
        SupabaseProvider.client?.let { client ->
            applicationScope.launch {
                client.auth.sessionStatus.collect { status ->
                    when (status) {
                        is SessionStatus.Authenticated -> push.onSignedIn()
                        // Cached group data belongs to the user who was signed in.
                        is SessionStatus.NotAuthenticated -> database.messageDao().deleteAll()
                        else -> Unit
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

    val files: FileRepository by lazy {
        val client = SupabaseProvider.client
        if (client == null) {
            FakeFileRepository()
        } else {
            DefaultFileRepository(SupabaseFileDataSource(client), currentUser = { client.currentUser() })
        }
    }

    /** One shared instance, so its cache and request limits apply across all screens. */
    val eta: EtaRepository by lazy {
        val client = SupabaseProvider.client
        if (client == null) {
            FakeEtaRepository()
        } else {
            RoutedEtaRepository(SupabaseRouteDataSource(client))
        }
    }

    /** This device's push token on the server; does nothing when Firebase or Supabase is not configured. */
    val push: PushTokens by lazy {
        val client = SupabaseProvider.client
        PushTokens(
            remote = client?.let { SupabasePushDataSource(it) },
            source = FirebaseTokenSource.ifConfigured(appContext),
            isSignedIn = { client?.auth?.currentSessionOrNull() != null },
            scope = applicationScope
        )
    }

    /** Weather at the class's building; Open-Meteo needs no key, so it is always the real service. */
    val weather: WeatherRepository by lazy { DefaultWeatherRepository(OpenMeteoWeatherDataSource()) }

    /** Tracks the trip to the next class from the timetable, the sensors, [eta] and [weather]; runs from [init]. */
    val travel: TravelEngine by lazy {
        TravelEngine(
            eta = eta,
            buildings = BuildingLocationRepository(appContext),
            preferences = { TravelPreferencesStore(appContext).load() },
            weather = weather
        )
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
