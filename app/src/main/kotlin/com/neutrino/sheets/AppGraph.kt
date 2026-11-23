package com.neutrino.sheets

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.neutrino.sheets.core.api.SheetsApi
import com.neutrino.sheets.core.editor.SheetPasteboard
import com.neutrino.sheets.core.service.DeviceSessionService
import com.neutrino.sheets.core.service.OfflineCopies
import com.neutrino.sheets.core.service.SheetContentService
import com.neutrino.sheets.core.service.SheetsDriveService
import com.neutrino.sheets.core.workbook.SheetClipboard
import com.neutrino.shared.android.NeutrinoAccount
import com.neutrino.shared.android.net.NetworkMonitor
import com.neutrino.shared.core.api.NeutrinoApps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** Every long-lived object, built once per process. Main-thread confined. */
class AppGraph(context: Context) {
    val context: Context = context.applicationContext

    val account = NeutrinoAccount(this.context, NeutrinoApps.sheets, "NeutrinoSheets-Android/${BuildConfig.VERSION_NAME}") { http, auth -> SheetsApi(http, auth) }
    val api: SheetsApi get() = account.api
    val prefs = SheetsPrefs(account.settings.prefs)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val network = NetworkMonitor(this.context)
    val isOnline: StateFlow<Boolean> = network.isOnline

    val offline = OfflineCopies(File(this.context.noBackupFilesDir, "offline-sheets"))
    val content = SheetContentService(account.api, { account.keys.keyring() }, account.keyCheck, offline)
    val drive = SheetsDriveService(account.api, content, offline, scope) { isOnline.value }
    val sessions = DeviceSessionService(account.api, scope) { account.auth.config.headerSafeDeviceName(account.settings.deviceName) }
    val router = AppRouter()
    val pasteboard = SystemPasteboard(this.context)

    init {
        account.onSignOut {
            drive.reset()
            offline.removeAll()
            pasteboard.forget()
            File(this.context.cacheDir, "exports").deleteRecursively()
            router.reset()
        }
        network.start()
    }
}

/**
 * The system clipboard carries values as tab-separated text for other apps; the copied cells
 * themselves — styles, formulas relative to their cells — stay in the process, and are used only
 * while the system clipboard still holds the text this app put there.
 */
class SystemPasteboard(context: Context) : SheetPasteboard {
    private val clipboard = context.getSystemService(ClipboardManager::class.java)
    private var payload: SheetClipboard? = null
    private var payloadText: String? = null

    override fun write(clipboard: SheetClipboard?, text: String) {
        payload = clipboard
        payloadText = text
        this.clipboard.setPrimaryClip(ClipData.newPlainText("Cells", text))
    }

    override fun readPayload(): SheetClipboard? = payload?.takeIf { readText() == payloadText }

    override fun readText(): String? = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()

    override val hasContent: Boolean get() = payload != null || clipboard.hasPrimaryClip()

    fun forget() { payload = null; payloadText = null }
}
