package au.edu.unimelb.campuscompanion.push

import android.content.Context
import android.util.Log
import au.edu.unimelb.campuscompanion.data.remote.PushRemoteDataSource
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The device's push token, from Firebase Cloud Messaging. */
interface PushTokenSource {
    suspend fun token(): String

    /** Invalidates the token, so that the next sign-in on this device gets a new one. */
    suspend fun deleteToken()
}

/**
 * Keeps the server's record of this device's push token in step with the session: the token is
 * registered after sign-in and when Firebase rotates it, and removed and invalidated before
 * sign-out so that the next user of the device does not receive this user's messages.
 */
class PushTokens(
    private val remote: PushRemoteDataSource?,
    private val source: PushTokenSource?,
    private val isSignedIn: () -> Boolean,
    private val scope: CoroutineScope
) {
    /** False when Firebase or the backend is not configured; nothing is sent or registered then. */
    val isAvailable: Boolean
        get() = remote != null && source != null

    @Volatile
    private var registeredToken: String? = null

    fun onSignedIn() {
        val source = this.source ?: return
        if (registeredToken != null) return
        scope.launch {
            try {
                register(source.token())
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                Log.w(TAG, "Could not register the device for push notifications", error)
            }
        }
    }

    /** Called by the messaging service when Firebase issues a new token. */
    fun onTokenRefreshed(token: String) {
        if (!isSignedIn()) return
        scope.launch {
            try {
                register(token)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                Log.w(TAG, "Could not update the device's push token", error)
            }
        }
    }

    /** Removes and invalidates the token. Failures are logged; sign-out goes ahead regardless. */
    suspend fun beforeSignOut() {
        val remote = this.remote
        val source = this.source
        try {
            val token = registeredToken ?: source?.token()
            if (token != null && remote != null) {
                remote.unregisterToken(token)
            }
            source?.deleteToken()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            Log.w(TAG, "Could not remove the device's push token", error)
        } finally {
            registeredToken = null
        }
    }

    private suspend fun register(token: String) {
        remote?.registerToken(token)
        registeredToken = token
    }

    private companion object {
        const val TAG = "PushTokens"
    }
}

/** [PushTokenSource] on Firebase Cloud Messaging. */
class FirebaseTokenSource private constructor() : PushTokenSource {

    override suspend fun token(): String =
        FirebaseMessaging.getInstance().token.awaitResult() ?: error("Firebase returned no token")

    override suspend fun deleteToken() {
        FirebaseMessaging.getInstance().deleteToken().awaitResult()
    }

    companion object {
        /** The source when the app was built with google-services.json; null otherwise. */
        fun ifConfigured(context: Context): FirebaseTokenSource? =
            if (FirebaseApp.getApps(context).isEmpty()) null else FirebaseTokenSource()
    }
}

/** The task's result, or null for tasks without one. */
private suspend fun <T> Task<T>.awaitResult(): T? = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener { task ->
        val error = task.exception
        when {
            task.isCanceled -> continuation.cancel()
            error != null -> continuation.resumeWithException(error)
            else -> continuation.resume(task.result)
        }
    }
}
