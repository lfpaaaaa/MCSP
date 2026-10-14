package au.edu.unimelb.campuscompanion.sensing.activity

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.ActivityRecognition

/** Only outstanding registration identities persist; recognized activity never survives a run. */
internal object ActivityRegistrationCleanup {
    private const val PREFERENCES = "activity_registration_cleanup"
    private const val RECORDS = "outstanding_actions"
    private const val LEGACY_QUEUED = "legacy_cleanup_queued"
    private const val LEGACY_TRANSITION = "legacy:transition"
    private const val LEGACY_SEED = "legacy:seed"

    fun remember(context: Context, action: String) {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val records = preferences.getStringSet(RECORDS, emptySet()).orEmpty().toMutableSet()
        records.add(action)
        // Save before the Play services request, so process death cannot orphan the seed identity.
        preferences.edit().putStringSet(RECORDS, records).commit()
    }

    fun removeOrphans(context: Context) {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        if (!preferences.getBoolean(LEGACY_QUEUED, false)) {
            val records = preferences.getStringSet(RECORDS, emptySet()).orEmpty().toMutableSet()
            records.add(LEGACY_TRANSITION)
            records.add(LEGACY_SEED)
            preferences.edit().putStringSet(RECORDS, records).putBoolean(LEGACY_QUEUED, true).commit()
        }
        preferences.getStringSet(RECORDS, emptySet()).orEmpty().toList().forEach { remove(context, it) }
    }

    fun removeObsoleteBroadcast(context: Context, action: String?) {
        if (action == null) {
            remove(context, LEGACY_TRANSITION)
            remove(context, LEGACY_SEED)
        } else if (action.startsWith("${context.packageName}.activity.TRANSITIONS.") ||
            action.startsWith("${context.packageName}.activity.SEED.")
        ) {
            remove(context, action)
        }
    }

    @SuppressLint("MissingPermission")
    fun remove(context: Context, action: String, originalIntent: PendingIntent? = null) {
        val isSeed = action == LEGACY_SEED || action.startsWith("${context.packageName}.activity.SEED.")
        val isLegacy = action == LEGACY_TRANSITION || action == LEGACY_SEED
        if (!isLegacy && !isSeed && !action.startsWith("${context.packageName}.activity.TRANSITIONS.")) return
        remember(context, action)
        val intent = originalIntent ?: PendingIntent.getBroadcast(
            context, if (isSeed && !isLegacy) 2 else 1,
            Intent(context, ActivityTransitionReceiver::class.java).apply { if (!isLegacy) setAction(action) },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        try {
            val client = ActivityRecognition.getClient(context)
            val removal = if (isSeed) client.removeActivityUpdates(intent)
                else client.removeActivityTransitionUpdates(intent)
            removal.addOnSuccessListener {
                val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                val remaining = preferences.getStringSet(RECORDS, emptySet()).orEmpty().toMutableSet()
                remaining.remove(action)
                preferences.edit().putStringSet(RECORDS, remaining).commit()
                intent.cancel()
            }.addOnFailureListener { error ->
                // Keep this exact identity for the next start/broadcast retry, without affecting a new run.
                Log.w("ActivityRecognition", "Activity registration cleanup failed", error)
            }
        } catch (error: RuntimeException) {
            Log.w("ActivityRecognition", "Unable to clean activity registration", error)
        }
    }
}
