package au.edu.unimelb.campuscompanion.ui.text

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext

/** Captures locale-aware resources for both UI text and non-composable event callbacks. */
@Composable
@ReadOnlyComposable
fun textResources(): Resources {
    LocalConfiguration.current
    return LocalContext.current.resources
}
