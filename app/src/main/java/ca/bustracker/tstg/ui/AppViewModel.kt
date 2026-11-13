package ca.bustracker.tstg.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ca.bustracker.tstg.data.AppPrefs
import ca.bustracker.tstg.data.BusPlannerClient
import ca.bustracker.tstg.data.BusPlannerParser
import ca.bustracker.tstg.data.ChildInfo
import ca.bustracker.tstg.data.Credentials
import ca.bustracker.tstg.data.LoginException
import ca.bustracker.tstg.data.PortalException
import ca.bustracker.tstg.data.PortalPage
import ca.bustracker.tstg.data.SecureStore
import ca.bustracker.tstg.data.SessionExpiredException
import ca.bustracker.tstg.data.ThemeMode
import ca.bustracker.tstg.util.DebugExport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

enum class AppPhase {
    Booting,
    Login,
    Main,
}

data class AppUiState(
    val phase: AppPhase = AppPhase.Booting,
    val savedEmail: String? = null,
    val child: ChildInfo? = null,
    val lastUpdatedMillis: Long? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val sessionGeneration: Int = 0,
    val autoRefreshSeconds: Int = 60,
    val compactPortal: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.System,
    val hasSavedCredentials: Boolean = false,
    /** Student picked in the app; the summary and the live map follow it. */
    val selectedStudentId: String? = null,
    val debugFiles: List<File> = emptyList(),
    /** Bumped whenever the native refresh runs, so the WebView can refresh too. */
    val portalRefreshTick: Int = 0,
)

class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = AppPrefs(application)
    private val secureStore = SecureStore(application)
    private val client = BusPlannerClient()

    private var loginJob: Job? = null
    private var refreshJob: Job? = null
    private var reloginInProgress = false

    private val _state = MutableStateFlow(
        AppUiState(
            autoRefreshSeconds = prefs.autoRefreshSeconds,
            compactPortal = prefs.compactPortal,
            themeMode = prefs.themeMode,
            selectedStudentId = prefs.selectedStudentId,
            savedEmail = secureStore.load()?.email,
            hasSavedCredentials = secureStore.hasCredentials(),
        ),
    )
    val state = _state.asStateFlow()

    init {
        bootstrap()
    }

    private fun bootstrap() {
        val credentials = secureStore.load()
        if (credentials == null) {
            _state.update { it.copy(phase = AppPhase.Login) }
            return
        }
        startLogin(
            email = credentials.email,
            password = credentials.password,
            remember = true,
            auto = true,
        )
    }

    fun login(email: String, password: String, remember: Boolean) {
        startLogin(email.trim(), password, remember, auto = false)
    }

    private fun startLogin(
        email: String,
        password: String,
        remember: Boolean,
        auto: Boolean,
    ) {
        loginJob?.cancel()
        reloginInProgress = auto
        loginJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    phase = if (auto) AppPhase.Booting else AppPhase.Login,
                    busy = true,
                    error = null,
                )
            }
            runCatching {
                val pages = withContext(Dispatchers.IO) {
                    client.login(email, password, remember)
                    fetchPages(prefs.selectedStudentId)
                }
                val info = withContext(Dispatchers.Default) {
                    runCatching { BusPlannerParser.parsePages(pages) }
                        .getOrDefault(ChildInfo())
                }
                val saved = runCatching {
                    withContext(Dispatchers.IO) {
                        if (remember) {
                            secureStore.save(Credentials(email, password))
                        } else {
                            secureStore.clear()
                        }
                        remember && secureStore.hasCredentials()
                    }
                }.onFailure { error ->
                    if (error is CancellationException) throw error
                }.getOrDefault(false)
                info to saved
            }.onSuccess { (info, saved) ->
                _state.update {
                    it.copy(
                        phase = AppPhase.Main,
                        savedEmail = if (saved) email else null,
                        hasSavedCredentials = saved,
                        child = info,
                        lastUpdatedMillis = System.currentTimeMillis(),
                        busy = false,
                        error = null,
                        sessionGeneration = it.sessionGeneration + 1,
                    )
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                // A stored password that no longer works should not cause a
                // login loop on every launch. Keep the email for convenience.
                if (auto && error is LoginException) {
                    secureStore.clear()
                }
                _state.update {
                    it.copy(
                        phase = AppPhase.Login,
                        savedEmail = if (auto) email else it.savedEmail,
                        hasSavedCredentials = secureStore.hasCredentials(),
                        busy = false,
                        error = friendlyError(error),
                    )
                }
            }
            reloginInProgress = false
        }
    }

    /**
     * Retries the one-tap sign-in after a non-credential failure (for example
     * a dropped connection), without making the user retype the password.
     */
    fun loginWithSaved() {
        val credentials = secureStore.load()
        if (credentials == null) {
            _state.update {
                it.copy(
                    hasSavedCredentials = false,
                    error = "Saved credentials are no longer available. Enter your password again.",
                )
            }
            return
        }
        startLogin(
            email = credentials.email,
            password = credentials.password,
            remember = true,
            auto = true,
        )
    }

    /**
     * Refreshes the native summary card without disturbing the WebView.
     * If the portal session has expired, it transparently signs in again.
     */
    fun refresh(silent: Boolean = false) {
        if (_state.value.phase != AppPhase.Main) return
        if (refreshJob?.isActive == true) return

        launchRefresh(silent)
    }

    /**
     * Shows [studentId] in the summary card and the live map, and remembers
     * the choice for next time.
     */
    fun selectStudent(studentId: String) {
        if (studentId == _state.value.selectedStudentId &&
            studentId == _state.value.child?.selectedStudentId
        ) {
            return
        }
        prefs.selectedStudentId = studentId
        _state.update { it.copy(selectedStudentId = studentId) }
        if (_state.value.phase != AppPhase.Main) return
        // A refresh for the previous student may still be running.
        refreshJob?.cancel()
        launchRefresh(silent = false)
    }

    private fun fetchPages(studentId: String?): List<PortalPage> {
        val child = client.fetchChildPage(studentId)
        // The live page is optional: the summary still works if TSTG has
        // temporarily disabled Where's My Bus.
        val live = runCatching { client.fetchLivePage(studentId) }
            .onFailure { if (it is CancellationException) throw it }
            .getOrNull()
        return listOfNotNull(child, live)
    }

    private fun launchRefresh(silent: Boolean) {
        refreshJob = viewModelScope.launch {
            // Bump the tick even if the native fetch fails: the WebView can
            // still refresh its own copy of the map.
            _state.update {
                it.copy(
                    busy = if (silent) it.busy else true,
                    portalRefreshTick = it.portalRefreshTick + 1,
                )
            }
            runCatching {
                val pages = withContext(Dispatchers.IO) {
                    fetchPages(_state.value.selectedStudentId)
                }
                val info = withContext(Dispatchers.Default) {
                    runCatching { BusPlannerParser.parsePages(pages) }
                        .getOrDefault(ChildInfo())
                }
                _state.update {
                    it.copy(
                        child = info,
                        lastUpdatedMillis = System.currentTimeMillis(),
                        busy = false,
                        error = null,
                    )
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                _state.update { it.copy(busy = false) }
                if (error is SessionExpiredException) {
                    relogin()
                } else if (!silent) {
                    _state.update { it.copy(message = friendlyError(error)) }
                }
            }
        }
    }

    fun handleSessionExpired() {
        if (_state.value.phase != AppPhase.Main) return
        relogin()
    }

    private fun relogin() {
        if (reloginInProgress) return
        val credentials = secureStore.load()
        if (credentials == null) {
            _state.update {
                it.copy(
                    phase = AppPhase.Login,
                    busy = false,
                    error = "Your portal session expired. Please sign in again.",
                )
            }
            return
        }
        startLogin(
            email = credentials.email,
            password = credentials.password,
            remember = true,
            auto = true,
        )
    }

    fun cancelAutoLogin() {
        loginJob?.cancel()
        reloginInProgress = false
        _state.update { it.copy(phase = AppPhase.Login, busy = false) }
    }

    fun signOut() {
        loginJob?.cancel()
        refreshJob?.cancel()
        reloginInProgress = false
        client.clearSession()
        secureStore.clear()
        prefs.selectedStudentId = null
        _state.value = AppUiState(
            phase = AppPhase.Login,
            autoRefreshSeconds = prefs.autoRefreshSeconds,
            compactPortal = prefs.compactPortal,
            themeMode = prefs.themeMode,
        )
    }

    fun setAutoRefreshSeconds(seconds: Int) {
        val safe = seconds.coerceIn(15, 600)
        prefs.autoRefreshSeconds = safe
        _state.update { it.copy(autoRefreshSeconds = safe) }
    }

    fun setCompactPortal(compact: Boolean) {
        prefs.compactPortal = compact
        _state.update { it.copy(compactPortal = compact) }
    }

    fun setThemeMode(mode: ThemeMode) {
        prefs.themeMode = mode
        _state.update { it.copy(themeMode = mode) }
    }

    fun captureDebugPage() {
        if (_state.value.phase != AppPhase.Main) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            runCatching {
                val pages = withContext(Dispatchers.IO) {
                    val studentId = _state.value.selectedStudentId
                    val child = runCatching { client.fetchChildPage(studentId) }
                        .onFailure { if (it is CancellationException) throw it }
                        .getOrNull()
                    val live = runCatching { client.fetchLivePage(studentId) }
                        .onFailure { if (it is CancellationException) throw it }
                        .getOrNull()
                    listOfNotNull(child, live).ifEmpty {
                        throw IOException(
                            "Couldn't capture the portal page. Check your connection and try again.",
                        )
                    }
                }
                val files = withContext(Dispatchers.IO) {
                    DebugExport.writePages(getApplication(), pages)
                }
                _state.update { it.copy(busy = false, debugFiles = files) }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                _state.update { it.copy(busy = false, message = friendlyError(error)) }
            }
        }
    }

    fun debugFilesShared() {
        _state.update { it.copy(debugFiles = emptyList()) }
    }

    fun clearMessage() {
        _state.update { it.copy(message = null) }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    fun webCookies() = client.cookieSnapshot()

    private fun friendlyError(error: Throwable): String = when (error) {
        is LoginException -> error.message ?: "Sign-in failed. Check your email and password."
        is SessionExpiredException -> "Your portal session expired. Please sign in again."
        is PortalException -> error.message ?: "The portal returned an unexpected response."
        is IOException -> "Couldn't reach the TSTG portal. Check your internet connection and try again."
        else -> "Something went wrong: ${error.message ?: error::class.simpleName}"
    }

    override fun onCleared() {
        loginJob?.cancel()
        refreshJob?.cancel()
        client.clearSession()
        super.onCleared()
    }
}
