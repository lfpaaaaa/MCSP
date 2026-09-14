package au.edu.unimelb.campuscompanion.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Nfc
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import au.edu.unimelb.campuscompanion.ui.components.GroupUpdateRow
import au.edu.unimelb.campuscompanion.ui.components.QuickActionChip
import au.edu.unimelb.campuscompanion.ui.components.SectionHeader
import au.edu.unimelb.campuscompanion.ui.model.MockCampusData

@Composable
fun GroupsScreen(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "Course groups",
                style = MaterialTheme.typography.headlineSmall
            )
            Text(
                text = "Join course teams, follow messages, and keep shared files close.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = {},
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Outlined.QrCodeScanner, contentDescription = null)
                Text(
                    text = "Scan QR",
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
            OutlinedButton(
                onClick = {},
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Outlined.Add, contentDescription = null)
                Text(
                    text = "Create",
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            QuickActionChip(
                label = "Find group",
                icon = Icons.Outlined.Search
            )
            QuickActionChip(
                label = "NFC join",
                icon = Icons.Outlined.Nfc
            )
        }

        SectionHeader(title = "Active groups")
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            MockCampusData.groups.forEach { group ->
                GroupUpdateRow(group = group)
            }
        }
    }
}
