package com.neutrino.sheets

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.neutrino.sheets.core.model.SheetItem
import com.neutrino.sheets.core.model.SheetsSection

/** The bottom bar. Favorites, Shared and Trash are sections of Spreadsheets. */
enum class Tab(val label: String) { SHEETS("Spreadsheets"), RECENT("Recent"), OFFLINE("Offline"), SETTINGS("Settings") }

sealed class Route {
    data class Folder(val section: SheetsSection, val folderId: String, val title: String) : Route()
    data class Editor(val item: SheetItem) : Route()
    object Devices : Route()
    object AppLockSettings : Route()
    object Pairing : Route()
    object RecoveryKit : Route()
    object KeyCode : Route()
    object SetUpEncryption : Route()
    object Trash : Route()
}

/** Where the app is showing, for what arrives from outside the tabs — a link naming a spreadsheet. */
class AppRouter {
    var tab by mutableStateOf(Tab.SHEETS)
    private val stacks = Tab.entries.associateWith { mutableStateListOf<Route>() }

    fun stack(tab: Tab): List<Route> = stacks.getValue(tab)
    val current: Route? get() = stacks.getValue(tab).lastOrNull()

    fun push(route: Route, on: Tab = tab) {
        tab = on
        stacks.getValue(on).add(route)
    }

    fun pop(): Boolean {
        val stack = stacks.getValue(tab)
        if (stack.isEmpty()) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    fun popWhile(predicate: (Route) -> Boolean) {
        val stack = stacks.getValue(tab)
        while (stack.isNotEmpty() && predicate(stack.last())) stack.removeAt(stack.lastIndex)
    }

    fun select(tab: Tab) {
        if (this.tab == tab) stacks.getValue(tab).clear()
        this.tab = tab
    }

    fun open(item: SheetItem) {
        stacks.getValue(Tab.SHEETS).clear()
        push(Route.Editor(item), Tab.SHEETS)
    }

    var pendingLink by mutableStateOf<String?>(null)
    var linkError by mutableStateOf<String?>(null)
    var newSheetRequested by mutableStateOf(false)

    fun reset() {
        stacks.values.forEach { it.clear() }
        tab = Tab.SHEETS
        pendingLink = null
        linkError = null
        newSheetRequested = false
    }
}
