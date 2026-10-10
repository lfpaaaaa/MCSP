package au.edu.unimelb.campuscompanion

import android.app.Application
import android.os.StrictMode
import au.edu.unimelb.campuscompanion.data.AppRepositories

class CampusCompanionApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) enableStrictMode()
        AppRepositories.init(this)
    }

    /**
     * Debug builds log main-thread disk and network access and leaked resources under the
     * "StrictMode" logcat tag, so slow work that slips onto the UI thread is noticed while
     * developing. Release builds are not affected.
     */
    private fun enableStrictMode() {
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .detectCustomSlowCalls()
                .penaltyLog()
                .build()
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectLeakedSqlLiteObjects()
                .detectLeakedClosableObjects()
                .detectActivityLeaks()
                .penaltyLog()
                .build()
        )
    }
}
