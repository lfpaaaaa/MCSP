package au.edu.unimelb.campuscompanion.auth

import android.content.Intent
import au.edu.unimelb.campuscompanion.BuildConfig
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.ExternalAuthAction
import io.github.jan.supabase.auth.FlowType
import io.github.jan.supabase.auth.handleDeeplinks
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.functions.Functions
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.storage.Storage
import io.ktor.client.engine.okhttp.OkHttp

object SupabaseProvider {
    const val redirectUrl = "campuscompanion://login-callback"

    val isConfigured: Boolean
        get() = BuildConfig.SUPABASE_URL.isNotBlank() &&
            BuildConfig.SUPABASE_PUBLISHABLE_KEY.isNotBlank()

    val client: SupabaseClient? by lazy {
        if (!isConfigured) {
            null
        } else {
            createSupabaseClient(
                supabaseUrl = BuildConfig.SUPABASE_URL,
                supabaseKey = BuildConfig.SUPABASE_PUBLISHABLE_KEY
            ) {
                httpEngine = OkHttp.create()
                install(Auth) {
                    scheme = "campuscompanion"
                    host = "login-callback"
                    defaultRedirectUrl = redirectUrl
                    flowType = FlowType.PKCE
                    defaultExternalAuthAction = ExternalAuthAction.CustomTabs()
                }
                install(Postgrest)
                install(Realtime)
                install(Storage)
                install(Functions)
            }
        }
    }

    fun handleDeepLink(intent: Intent) {
        client?.handleDeeplinks(intent)
    }
}
