package ca.bustracker.tstg.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private val refreshChoices = listOf(
    30 to "30 s",
    60 to "1 min",
    120 to "2 min",
    300 to "5 min",
)

@Composable
fun SettingsDialog(
    state: AppUiState,
    onDismiss: () -> Unit,
    onAutoRefreshChange: (Int) -> Unit,
    onCompactChange: (Boolean) -> Unit,
    onCapturePage: () -> Unit,
    onOpenAlerts: () -> Unit,
    onRequestSignOut: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .fillMaxWidth(),
            ) {
                Text(
                    text = "Refresh summary every",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    refreshChoices.forEach { (seconds, label) ->
                        FilterChip(
                            selected = state.autoRefreshSeconds == seconds,
                            onClick = { onAutoRefreshChange(seconds) },
                            label = { Text(label) },
                        )
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Compact portal",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = "Hides the portal's header, footer, and menus so " +
                                "the live map gets more room.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        checked = state.compactPortal,
                        onCheckedChange = onCompactChange,
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                TextButton(
                    onClick = onOpenAlerts,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Open TSTG alerts page")
                }

                TextButton(
                    onClick = onCapturePage,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Save logged-in pages for tuning")
                }
                Text(
                    text = "Creates HTML copies of the student and live-map pages so the " +
                        "parser can be adjusted. The files contain your child's information; " +
                        "share them only with someone you trust.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                Text(
                    text = "Account",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = state.savedEmail ?: "Credentials are not saved on this phone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                TextButton(
                    onClick = onRequestSignOut,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Sign out and forget saved password")
                }

                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Bus Tracker is an unofficial helper and is not affiliated with " +
                        "TSTG or BusPlanner. Your password is encrypted with an " +
                        "Android Keystore key and is only sent to tstg.mybusplanner.ca.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        },
    )
}
