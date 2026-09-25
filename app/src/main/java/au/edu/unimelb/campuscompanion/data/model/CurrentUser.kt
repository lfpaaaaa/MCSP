package au.edu.unimelb.campuscompanion.data.model

/** The signed-in user, as far as the data layer needs to know. */
data class CurrentUser(val id: String, val displayName: String)
