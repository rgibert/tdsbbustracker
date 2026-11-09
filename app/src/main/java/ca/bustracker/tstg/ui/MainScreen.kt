package ca.bustracker.tstg.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ca.bustracker.tstg.data.WebCookie
import ca.bustracker.tstg.util.Constants
import kotlinx.coroutines.delay

enum class PortalTarget {
    Transport,
    Alerts,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    state: AppUiState,
    cookies: List<WebCookie>,
    portalTarget: PortalTarget,
    onSelectPortalTarget: (PortalTarget) -> Unit,
    onRefresh: (Boolean) -> Unit,
    onSelectStudent: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onSessionExpired: () -> Unit,
    onClearMessage: () -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val currentOnRefresh by rememberUpdatedState(onRefresh)

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        onClearMessage()
    }

    // Quietly re-read the portal in the background. This moves the bus on the
    // map and keeps the ETA card from going stale.
    LaunchedEffect(state.autoRefreshSeconds) {
        while (true) {
            delay(state.autoRefreshSeconds * 1000L)
            currentOnRefresh(true)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Bus Tracker",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = if (portalTarget == PortalTarget.Alerts) {
                                "Alerts"
                            } else {
                                "Live bus map"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    if (state.busy) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            modifier = Modifier
                                .padding(horizontal = 14.dp)
                                .size(20.dp),
                        )
                    } else {
                        IconButton(onClick = { onRefresh(false) }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                        }
                    }
                    IconButton(
                        onClick = {
                            onSelectPortalTarget(
                                if (portalTarget == PortalTarget.Transport) {
                                    PortalTarget.Alerts
                                } else {
                                    PortalTarget.Transport
                                },
                            )
                        },
                    ) {
                        Icon(
                            imageVector = if (portalTarget == PortalTarget.Transport) {
                                Icons.Filled.Notifications
                            } else {
                                Icons.Filled.Map
                            },
                            contentDescription = if (portalTarget == PortalTarget.Transport) {
                                "Show alerts"
                            } else {
                                "Show live map"
                            },
                        )
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { innerPadding ->
        // Only pad the top: the map runs to the bottom edge of the screen,
        // behind the navigation bar, and keeps its own controls clear of it.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding()),
        ) {
            SummaryCard(
                child = state.child,
                lastUpdatedMillis = state.lastUpdatedMillis,
                busy = state.busy,
                onRefresh = { onRefresh(false) },
                selectedStudentId = state.selectedStudentId,
                onSelectStudent = onSelectStudent,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = if (portalTarget == PortalTarget.Alerts) {
                        Icons.Filled.Notifications
                    } else {
                        Icons.Filled.DirectionsBus
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (portalTarget == PortalTarget.Alerts) {
                        "TSTG alerts"
                    } else {
                        "Live bus map"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "Refresh ${state.autoRefreshSeconds}s",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (portalTarget == PortalTarget.Alerts) {
                PortalWebView(
                    url = Constants.ALERTS_URL,
                    compact = state.compactPortal,
                    sessionGeneration = state.sessionGeneration,
                    refreshTick = state.portalRefreshTick,
                    cookies = cookies,
                    onSessionExpired = onSessionExpired,
                    studentId = state.selectedStudentId,
                    hideStudentSelector = (state.child?.students?.size ?: 0) > 1,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .navigationBarsPadding(),
                )
            } else {
                // Just the map: bus and stop positions come from the same
                // portal refresh that fills the summary card.
                BusMap(
                    child = state.child,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
            }
        }
    }
}
