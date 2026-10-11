package au.edu.unimelb.campuscompanion.ui.profile

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import au.edu.unimelb.campuscompanion.auth.SupabaseProvider
import au.edu.unimelb.campuscompanion.profile.AvatarImages
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.CancellationException

@Composable
fun ProfileAvatar(name: String, reference: String?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val accountId = SupabaseProvider.client?.auth?.currentUserOrNull()?.id
    val image by produceState<ImageBitmap?>(null, reference, accountId) {
        value = null
        if (!reference.isNullOrBlank()) {
            try { value = AvatarImages.load(context, reference)?.asImageBitmap() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* A missing/offline image falls back to initials. */ }
        }
    }
    Surface(modifier, shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
        Box(contentAlignment = Alignment.Center) {
            val loaded = image
            if (loaded == null) Text(name.trim().take(1).uppercase(), style = MaterialTheme.typography.headlineSmall)
            else Image(loaded, contentDescription = "$name profile photo", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
    }
}
