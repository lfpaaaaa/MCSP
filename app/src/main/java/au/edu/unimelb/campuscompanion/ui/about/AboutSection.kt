package au.edu.unimelb.campuscompanion.ui.about

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import au.edu.unimelb.campuscompanion.BuildConfig

/**
 * Credits for the services and data the app relies on, followed by the app version. Shown on
 * the Profile screen; the wording of each credit follows the provider's terms.
 */
@Composable
fun AboutSection(modifier: Modifier = Modifier) {
    val uriHandler = LocalUriHandler.current

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        DataSources.all.forEach { source ->
            DataSourceCard(
                source = source,
                onOpenLink = { link -> uriHandler.openUri(link.url) }
            )
        }
        Text(
            text = "Campus Companion ${BuildConfig.VERSION_NAME}, a COMP90018 project at the " +
                "University of Melbourne. The app's own code is released under the MIT Licence.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DataSourceCard(
    source: DataSource,
    onOpenLink: (DataSourceLink) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = source.name,
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = source.credit,
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = source.usage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (source.links.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    source.links.forEach { link ->
                        TextButton(onClick = { onOpenLink(link) }) {
                            Text(link.label)
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                                contentDescription = null,
                                modifier = Modifier
                                    .padding(start = 4.dp)
                                    .size(14.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The one-line credit that must sit next to routed travel times: the OpenStreetMap copyright and
 * a "fix the map" link, both opening in the browser.
 */
@Composable
fun RoutingCredit(modifier: Modifier = Modifier) {
    val linkStyle = TextLinkStyles(style = SpanStyle(textDecoration = TextDecoration.Underline))
    val text = buildAnnotatedString {
        append("Travel times ")
        withLink(LinkAnnotation.Url(DataSources.OSM_COPYRIGHT_URL, linkStyle)) {
            append(DataSources.OSM_CREDIT)
        }
        append("  ·  ")
        withLink(LinkAnnotation.Url(DataSources.OSM_FIX_THE_MAP_URL, linkStyle)) {
            append("Fix the map")
        }
    }
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.labelSmall,
        color = LocalContentColor.current.copy(alpha = 0.8f)
    )
}
