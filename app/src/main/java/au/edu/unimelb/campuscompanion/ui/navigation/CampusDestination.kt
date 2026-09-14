package au.edu.unimelb.campuscompanion.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Event as FilledEvent
import androidx.compose.material.icons.filled.Groups as FilledGroups
import androidx.compose.material.icons.filled.Home as FilledHome
import androidx.compose.material.icons.filled.Person as FilledPerson
import androidx.compose.material.icons.outlined.Event as OutlinedEvent
import androidx.compose.material.icons.outlined.Groups as OutlinedGroups
import androidx.compose.material.icons.outlined.Home as OutlinedHome
import androidx.compose.material.icons.outlined.Person as OutlinedPerson
import androidx.compose.ui.graphics.vector.ImageVector

sealed class CampusDestination(
    val route: String,
    val title: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    data object Home : CampusDestination(
        route = "home",
        title = "Home",
        selectedIcon = Icons.Filled.FilledHome,
        unselectedIcon = Icons.Outlined.OutlinedHome
    )

    data object Schedule : CampusDestination(
        route = "schedule",
        title = "Schedule",
        selectedIcon = Icons.Filled.FilledEvent,
        unselectedIcon = Icons.Outlined.OutlinedEvent
    )

    data object Groups : CampusDestination(
        route = "groups",
        title = "Groups",
        selectedIcon = Icons.Filled.FilledGroups,
        unselectedIcon = Icons.Outlined.OutlinedGroups
    )

    data object Profile : CampusDestination(
        route = "profile",
        title = "Profile",
        selectedIcon = Icons.Filled.FilledPerson,
        unselectedIcon = Icons.Outlined.OutlinedPerson
    )

    companion object {
        val topLevelDestinations = listOf(Home, Schedule, Groups, Profile)
    }
}
