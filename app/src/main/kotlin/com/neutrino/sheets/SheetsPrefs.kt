package com.neutrino.sheets

import android.content.SharedPreferences
import com.neutrino.sheets.core.model.SheetSortOrder
import com.neutrino.sheets.core.model.SheetsSection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class AppTheme(val title: String) { SYSTEM("System"), LIGHT("Light"), DARK("Dark") }

/** The user's preferences, in the account's own `nsheet.settings` file under `nsheet.` keys — the iOS app's. */
class SheetsPrefs(private val prefs: SharedPreferences) {

    private val _theme = MutableStateFlow(AppTheme.entries.firstOrNull { it.name == prefs.getString(THEME, null) } ?: AppTheme.SYSTEM)
    val theme: StateFlow<AppTheme> = _theme
    fun setTheme(v: AppTheme) { prefs.edit().putString(THEME, v.name).apply(); _theme.value = v }

    private val _autoSave = MutableStateFlow(prefs.getInt(AUTO_SAVE, DEFAULT_AUTO_SAVE))
    /** Seconds of quiet before an autosave. */
    val autoSaveSeconds: StateFlow<Int> = _autoSave
    fun setAutoSaveSeconds(v: Int) { prefs.edit().putInt(AUTO_SAVE, v).apply(); _autoSave.value = v }

    private val _gridlines = MutableStateFlow(prefs.getBoolean(GRIDLINES, true))
    val showGridlines: StateFlow<Boolean> = _gridlines
    fun setShowGridlines(v: Boolean) { prefs.edit().putBoolean(GRIDLINES, v).apply(); _gridlines.value = v }

    private val _sortOrder = MutableStateFlow(SheetSortOrder.entries.firstOrNull { it.name == prefs.getString(SORT, null) })
    val sortOrder: StateFlow<SheetSortOrder?> = _sortOrder
    fun setSortOrder(v: SheetSortOrder?) { prefs.edit().putString(SORT, v?.name).apply(); _sortOrder.value = v }
    fun sortOrder(section: SheetsSection) = _sortOrder.value ?: section.defaultSortOrder

    fun resetToDefaults() {
        setTheme(AppTheme.SYSTEM); setAutoSaveSeconds(DEFAULT_AUTO_SAVE); setShowGridlines(true); setSortOrder(null)
    }

    companion object {
        val AUTO_SAVE_OPTIONS = listOf(1, 3, 5, 10, 30)
        const val DEFAULT_AUTO_SAVE = 3
        fun autoSaveLabel(seconds: Int) = if (seconds == 1) "Every second" else "Every $seconds seconds"

        private const val THEME = "nsheet.theme"
        private const val AUTO_SAVE = "nsheet.auto_save_seconds"
        private const val GRIDLINES = "nsheet.gridlines"
        private const val SORT = "nsheet.sort_order"
    }
}
