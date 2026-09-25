package au.edu.unimelb.campuscompanion

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import au.edu.unimelb.campuscompanion.auth.AuthViewModel
import au.edu.unimelb.campuscompanion.auth.SupabaseProvider
import au.edu.unimelb.campuscompanion.data.AppRepositories
import au.edu.unimelb.campuscompanion.ui.CampusCompanionApp

class MainActivity : ComponentActivity() {
    private val authViewModel: AuthViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SupabaseProvider.handleDeepLink(intent)
        if (savedInstanceState == null) {
            // A recreated activity must not bring back an invitation the user already dismissed.
            AppRepositories.joinLinks.offer(intent?.dataString)
        }
        enableEdgeToEdge()
        setContent {
            CampusCompanionApp(authViewModel = authViewModel)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        SupabaseProvider.handleDeepLink(intent)
        AppRepositories.joinLinks.offer(intent.dataString)
    }
}
