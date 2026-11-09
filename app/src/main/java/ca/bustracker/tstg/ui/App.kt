package ca.bustracker.tstg.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ca.bustracker.tstg.util.DebugExport
import java.io.File

/**
 * Root of the app. The screen is selected by the auth/session phase so that
 * the WebView (and its cookies) is only alive while a portal session exists.
 */
@Composable
fun BusTrackerApp(viewModel: AppViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var showSettings by rememberSaveable { mutableStateOf(false) }
    var confirmSignOut by rememberSaveable { mutableStateOf(false) }
    var portalTarget by rememberSaveable { mutableStateOf(PortalTarget.Transport) }

    // Snapshot the OkHttp cookie jar once per portal session. The cookies are
    // copied into the WebView so the map does not ask for a second login.
    val webCookies = remember(state.sessionGeneration) { viewModel.webCookies() }

    when (state.phase) {
        AppPhase.Booting -> BootScreen(onCancel = viewModel::cancelAutoLogin)

        AppPhase.Login -> LoginScreen(
            state = state,
            onLogin = viewModel::login,
            onUseSaved = viewModel::loginWithSaved,
            onCancel = viewModel::cancelAutoLogin,
            onClearError = viewModel::clearError,
        )

        AppPhase.Main -> MainScreen(
            state = state,
            cookies = webCookies,
            portalTarget = portalTarget,
            onSelectPortalTarget = { portalTarget = it },
            onRefresh = viewModel::refresh,
            onSelectStudent = viewModel::selectStudent,
            onOpenSettings = { showSettings = true },
            onSessionExpired = viewModel::handleSessionExpired,
            onClearMessage = viewModel::clearMessage,
        )
    }

    if (showSettings && state.phase == AppPhase.Main) {
        SettingsDialog(
            state = state,
            onDismiss = { showSettings = false },
            onAutoRefreshChange = viewModel::setAutoRefreshSeconds,
            onCompactChange = viewModel::setCompactPortal,
            onDefaultStudentChange = viewModel::setDefaultStudent,
            onCapturePage = {
                showSettings = false
                viewModel.captureDebugPage()
            },
            onOpenAlerts = {
                portalTarget = PortalTarget.Alerts
                showSettings = false
            },
            onRequestSignOut = {
                showSettings = false
                confirmSignOut = true
            },
        )
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Sign out?") },
            text = {
                Text(
                    "This removes your saved email and password from this phone " +
                        "and clears the portal session.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmSignOut = false
                        viewModel.signOut()
                    },
                ) { Text("Sign out") }
            },
            dismissButton = {
                TextButton(onClick = { confirmSignOut = false }) { Text("Cancel") }
            },
        )
    }

    // Capturing the page is deliberately a two-step action: the HTML contains
    // the child's name, stop, route, and possibly GPS data.
    if (state.debugFiles.isNotEmpty()) {
        DebugShareDialog(
            files = state.debugFiles,
            onShare = {
                runCatching { DebugExport.share(context, state.debugFiles) }
                viewModel.debugFilesShared()
            },
            onDismiss = viewModel::debugFilesShared,
        )
    }
}

@Composable
private fun DebugShareDialog(
    files: List<File>,
    onShare: () -> Unit,
    onDismiss: () -> Unit,
) {
    val names = files.joinToString(", ") { it.name }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Share debug pages?") },
        text = {
            Text(
                "These files ($names) are copies of your logged-in portal pages. " +
                    "They contain your child's personal information. Only share them with " +
                    "someone you trust, such as the person helping tune this app.",
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        confirmButton = { TextButton(onClick = onShare) { Text("Share") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Don't share") } },
    )
}
