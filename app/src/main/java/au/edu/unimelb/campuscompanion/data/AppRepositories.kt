package au.edu.unimelb.campuscompanion.data

import android.content.Context
import android.util.Log
import au.edu.unimelb.campuscompanion.auth.SupabaseProvider
import au.edu.unimelb.campuscompanion.context.DepartureReminders
import au.edu.unimelb.campuscompanion.context.TravelEngine
import au.edu.unimelb.campuscompanion.data.building.BuildingLocationRepository
import au.edu.unimelb.campuscompanion.data.fake.FakeChatRepository
import au.edu.unimelb.campuscompanion.data.fake.FakeEtaRepository
import au.edu.unimelb.campuscompanion.data.fake.FakeFileRepository
import au.edu.unimelb.campuscompanion.data.fake.FakeGroupRepository
import au.edu.unimelb.campuscompanion.data.fake.FakeInviteRepository
import au.edu.unimelb.campuscompanion.data.invite.JoinLinkInbox
import au.edu.unimelb.campuscompanion.data.local.CampusDatabase
import au.edu.unimelb.campuscompanion.data.local.FileGroupListCache
import au.edu.unimelb.campuscompanion.data.local.FileSharedFileListCache
import au.edu.unimelb.campuscompanion.data.model.CurrentUser
import au.edu.unimelb.campuscompanion.data.model.MessageStatus
import au.edu.unimelb.campuscompanion.data.remote.GroupRemoteDataSource
import au.edu.unimelb.campuscompanion.data.remote.OpenMeteoWeatherDataSource
import au.edu.unimelb.campuscompanion.data.remote.SupabaseChatDataSource
import au.edu.unimelb.campuscompanion.data.remote.SupabaseFileDataSource
import au.edu.unimelb.campuscompanion.data.remote.SupabaseGroupDataSource
import au.edu.unimelb.campuscompanion.data.remote.SupabasePushDataSource
import au.edu.unimelb.campuscompanion.data.remote.SupabaseRouteDataSource
import au.edu.unimelb.campuscompanion.data.repository.ChatMessageSender
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
import au.edu.unimelb.campuscompanion.ui.CourseReminderPreferencesStore
import au.edu.unimelb.campuscompanion.ui.model.CourseReminderPreference
import au.edu.unimelb.campuscompanion.ui.model.CourseSession
import au.edu.unimelb.campuscompanion.ui.model.reminderSeriesKey
import au.edu.unimelb.campuscompanion.ui.model.legacyReminderSeriesKey
import kotlinx.coroutines.flow.map
import au.edu.unimelb.campuscompanion.push.DepartureNotifier
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.contentOrNull
import java.io.File

/** Logcat tag of the chat latency samples; the chat screen logs the received ones under it too. */
private const val CHAT_LATENCY_TAG = "ChatLatency"

/**
 * App-wide repositories. The Supabase-backed versions are used when the project URL and
 * publishable key are set in local.properties; otherwise the in-memory fakes with sample data
 * are used, so that screens can still be built and previewed.
 */
object AppRepositories {
    /** Join links received by the main activity. */
    val joinLinks = JoinLinkInbox()

    private lateinit var appContext: Context
    private var timetableUserId: String? = null
    private val departureHistory by lazy { au.edu.unimelb.campuscompanion.push.DepartureReminderHistory(appContext) }
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Work that should finish even when the screen that started it goes away, such as file uploads. */
    val backgroundScope: CoroutineScope
        get() = applicationScope

    /** Prepares the on-device cache. Called once from the application's onCreate. */
    fun init(context: Context) {
        appContext = context.applicationContext
        // An interrupted process cannot keep tracking its in-memory route.
        au.edu.unimelb.campuscompanion.navigation.TransitNotifier.tracking(appContext, false)
        au.edu.unimelb.campuscompanion.navigation.TransitNotifier.cancelAlert(appContext)
        applicationScope.launch {
            // Messages that were still sending when the app stopped can now be retried by the user.
            database.messageDao().replaceStatus(MessageStatus.Sending.name, MessageStatus.Failed.name)
        }
        // Resolve reminders for the tracked course directly, even without an open settings screen.
        applicationScope.launch { travel.run() }
        applicationScope.launch {
            DepartureReminders(
                snapshots = travel.snapshot,
                preferences = ::courseReminderPreference,
                notify = { reminder -> DepartureNotifier.show(appContext, reminder) },
                preferenceChanges = CourseReminderPreferencesStore.changes.map { Unit },
                cancelCourse = { key -> DepartureNotifier.cancelCourse(appContext, key) },
                wasSent = { session, stage -> SupabaseProvider.client?.auth?.currentUserOrNull()?.id?.let {
                    departureHistory.wasSent(it, session, stage)
                } ?: true },
                rememberSent = { session, stage -> SupabaseProvider.client?.auth?.currentUserOrNull()?.id?.let {
                    departureHistory.record(it, session, stage)
                }; Unit }
            ).run()
        }
        SupabaseProvider.client?.let { client ->
            applicationScope.launch {
                client.auth.sessionStatus.collect { status ->
                    when (status) {
                        is SessionStatus.Authenticated -> {
                            val userId = client.auth.currentUserOrNull()?.id
                            if (userId != timetableUserId) {
                                timetableUserId = userId
                                travel.updateSessions(userId?.let { TimetableSubscriptionStore(appContext).load(it).timetable?.sessions }.orEmpty())
                            }
                            push.onSignedIn()
                        }
                        // Cached group data belongs to the user who was signed in.
                        is SessionStatus.NotAuthenticated -> {
                            groups.clearSession()
                            database.messageDao().deleteAll()
                            navigation.clear()
                            timetableUserId = null
                            travel.updateSessions(emptyList())
                        }
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
            DefaultGroupRepository(
                remote = remote,
                currentUserId = { client.auth.currentUserOrNull()?.id },
                cache = FileGroupListCache(lazy { File(appContext.filesDir, "groups") })
            )
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

    val chatSender: ChatMessageSender by lazy {
        ChatMessageSender(
            repository = chat,
            scope = applicationScope,
            onConfirmed = { millis -> Log.i(CHAT_LATENCY_TAG, "send_confirmed ms=$millis") }
        ) { groups.refresh() }
    }

    val files: FileRepository by lazy {
        val client = SupabaseProvider.client
        if (client == null) {
            FakeFileRepository()
        } else {
            DefaultFileRepository(
                remote = SupabaseFileDataSource(client),
                currentUser = { client.currentUser() },
                cache = FileSharedFileListCache(lazy { File(appContext.filesDir, "files") })
            )
        }
    }

    val attachmentUploads: AttachmentUploadQueue by lazy {
        val preferences = appContext.getSharedPreferences("attachment-uploads", Context.MODE_PRIVATE)
        val serializer = ListSerializer(AttachmentUpload.serializer())
        val json = Json { ignoreUnknownKeys = true }
        val restored = runCatching { json.decodeFromString(serializer, preferences.getString("queue", "[]")!!) }.getOrDefault(emptyList())
        AttachmentUploadQueue(files, applicationScope,
            resolveText = { it.resolve(appContext.resources) },
            currentUserId = { SupabaseProvider.client?.auth?.currentUserOrNull()?.id },
            readBytes = { readAttachment(appContext, it) }, restored = restored,
            persist = { preferences.edit().putString("queue", json.encodeToString(serializer, it)).apply() },
            onCompleted = { groups.refresh() },
            releaseSource = { discardLocalRecording(appContext, it) })
    }

    val buildings by lazy { BuildingLocationRepository(appContext) }

    val navigation by lazy {
        au.edu.unimelb.campuscompanion.navigation.NavigationSession(
            au.edu.unimelb.campuscompanion.navigation.GoogleRouteRepository(SupabaseProvider.client),
            applicationScope,
            onAlert = { au.edu.unimelb.campuscompanion.navigation.TransitNotifier.show(appContext, it) },
            onStop = { au.edu.unimelb.campuscompanion.navigation.TransitNotifier.cancelAlert(appContext) },
            onActiveChanged = { au.edu.unimelb.campuscompanion.navigation.TransitNotifier.tracking(appContext, it) }
        )
    }

    /** One shared instance, so its cache and request limits apply across all screens. */
    val eta: EtaRepository by lazy {
        val client = SupabaseProvider.client
        if (client == null) {
            FakeEtaRepository()
        } else {
            RoutedEtaRepository(au.edu.unimelb.campuscompanion.data.remote.GoogleEtaDataSource(client, SupabaseRouteDataSource(client)))
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

    private fun courseReminderPreference(session: CourseSession): CourseReminderPreference {
        val userId = SupabaseProvider.client?.auth?.currentUserOrNull()?.id
            ?: return CourseReminderPreference(enabled = false)
        return CourseReminderPreferencesStore(appContext, userId).load(
            session.reminderSeriesKey(), session.legacyReminderSeriesKey()
        )
    }

    /** Tracks the trip to the next class from the timetable, the sensors, [eta] and [weather]; runs from [init]. */
    val travel: TravelEngine by lazy {
        TravelEngine(
            eta = eta,
            buildings = buildings,
            preferences = { TravelPreferencesStore(appContext).load() },
            weather = weather,
            reminderPreference = ::courseReminderPreference,
            reminderChanges = CourseReminderPreferencesStore.changes.map { Unit }
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
