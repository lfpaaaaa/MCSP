package au.edu.unimelb.campuscompanion

import android.app.Application
import au.edu.unimelb.campuscompanion.data.AppRepositories

class CampusCompanionApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppRepositories.init(this)
    }
}
