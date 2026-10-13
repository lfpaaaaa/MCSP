package au.edu.unimelb.campuscompanion.ui.about

import androidx.compose.runtime.setValue

import androidx.compose.runtime.getValue

import au.edu.unimelb.campuscompanion.ui.text.textResources
import au.edu.unimelb.campuscompanion.R
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
    val textResources = textResources()
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
            text = textResources.getString(R.string.about_section_campus_companion_a_comp90018_project_at_the_university, BuildConfig.VERSION_NAME),
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
    val textResources = textResources()
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
                text = source.name.resolve(textResources),
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = source.credit.resolve(textResources),
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = source.usage.resolve(textResources),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (source.links.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    source.links.forEach { link ->
                        TextButton(onClick = { onOpenLink(link) }) {
                            Text(link.label.resolve(textResources))
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
    val textResources = textResources()
    val linkStyle = TextLinkStyles(style = SpanStyle(textDecoration = TextDecoration.Underline))
    val text = buildAnnotatedString {
        append(textResources.getString(R.string.about_section_travel_times))
        withLink(LinkAnnotation.Url(DataSources.OSM_COPYRIGHT_URL, linkStyle)) {
            append(DataSources.OSM_CREDIT.resolve(textResources))
        }
        append("  ·  ")
        withLink(LinkAnnotation.Url(DataSources.OSM_FIX_THE_MAP_URL, linkStyle)) {
            append(textResources.getString(R.string.about_section_fix_the_map))
        }
    }
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.labelSmall,
        color = LocalContentColor.current.copy(alpha = 0.8f)
    )
}
