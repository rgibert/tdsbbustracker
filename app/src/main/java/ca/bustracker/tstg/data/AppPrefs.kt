package ca.bustracker.tstg.data

import android.content.Context
import ca.bustracker.tstg.util.Constants

class AppPrefs(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(Constants.PREF_FILE, Context.MODE_PRIVATE)

    var autoRefreshSeconds: Int
        get() = prefs.getInt(KEY_AUTO_REFRESH, 60).coerceIn(15, 600)
        set(value) = prefs.edit()
            .putInt(KEY_AUTO_REFRESH, value.coerceIn(15, 600))
            .apply()

    var compactPortal: Boolean
        get() = prefs.getBoolean(KEY_COMPACT, true)
        set(value) = prefs.edit().putBoolean(KEY_COMPACT, value).apply()

    /** Portal id of the student picked in the app, or null for the portal default. */
    var selectedStudentId: String?
        get() = prefs.getString(KEY_STUDENT, null)?.takeIf { it.isNotBlank() }
        set(value) = prefs.edit().putString(KEY_STUDENT, value).apply()

    var themeMode: ThemeMode
        get() = ThemeMode.fromKey(prefs.getString(KEY_THEME, null))
        set(value) = prefs.edit().putString(KEY_THEME, value.key).apply()

    private companion object {
        const val KEY_AUTO_REFRESH = "auto_refresh_seconds"
        const val KEY_COMPACT = "compact_portal"
        const val KEY_STUDENT = "selected_student_id"
        const val KEY_THEME = "theme_mode"
    }
}

/** Light or dark appearance; [System] follows the phone's dark theme setting. */
enum class ThemeMode(val key: String, val label: String) {
    System("system", "System"),
    Light("light", "Light"),
    Dark("dark", "Dark"),
    ;

    companion object {
        fun fromKey(key: String?): ThemeMode = entries.firstOrNull { it.key == key } ?: System
    }
}
