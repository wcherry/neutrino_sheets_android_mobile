package com.neutrino.sheets.ui.settings

import android.net.Uri
import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import com.neutrino.sheets.AppTheme
import com.neutrino.sheets.BuildConfig
import com.neutrino.sheets.SheetsPrefs
import com.neutrino.sheets.ui.formatBytes
import com.neutrino.sheets.Route
import com.neutrino.sheets.graph
import com.neutrino.sheets.ui.ActionRow
import com.neutrino.sheets.ui.ChoiceRow
import com.neutrino.sheets.ui.SheetsStyle
import com.neutrino.sheets.ui.ErrorText
import com.neutrino.sheets.ui.FormRow
import com.neutrino.sheets.ui.FormSection
import com.neutrino.sheets.ui.PageBar
import com.neutrino.sheets.ui.SwitchRow
import com.neutrino.sheets.ui.ValueRow
import com.neutrino.sheets.ui.formatDateTime
import com.neutrino.shared.android.security.AppLock
import com.neutrino.shared.android.ui.ConfirmDialog
import com.neutrino.shared.android.ui.NeutrinoBrand
import com.neutrino.shared.android.ui.TextInputDialog
import com.neutrino.shared.android.ui.openUrl
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val graph = context.graph
    val prefs = graph.prefs
    val router = graph.router
    val theme by prefs.theme.collectAsState()
    val autoSave by prefs.autoSaveSeconds.collectAsState()
    val gridlines by prefs.showGridlines.collectAsState()
    val offlineCopies by graph.offline.entries.collectAsState()
    val appLock by graph.account.settings.appLock.collectAsState()
    val keyring by graph.account.keyringVersion.collectAsState()
    var hasKey by remember { mutableStateOf(graph.account.keys.hasKey) }
    var canSetUp by remember { mutableStateOf(false) }
    var confirmRemoveKey by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }
    var editingDeviceName by remember { mutableStateOf(false) }

    LaunchedEffect(keyring) {
        hasKey = graph.account.keys.hasKey
        canSetUp = !hasKey && graph.account.keys.canProvision()
    }

    Column(Modifier.fillMaxSize()) {
        PageBar("Settings", null)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            FormSection("Appearance") {
                ChoiceRow("Theme", theme, AppTheme.entries, { it.title }) { prefs.setTheme(it) }
                SwitchRow("Gridlines", gridlines) { prefs.setShowGridlines(it) }
            }
            FormSection("Editing", "Changes save themselves after this long without another edit.") {
                ChoiceRow("Auto-Save", autoSave, SheetsPrefs.AUTO_SAVE_OPTIONS, SheetsPrefs::autoSaveLabel) { prefs.setAutoSaveSeconds(it) }
            }
            FormSection("Offline", "The last copy of each spreadsheet you open is kept here, encrypted, and opens read-only without a connection.") {
                ValueRow("Stored", "${offlineCopies.size} · ${formatBytes(graph.offline.bytesOnDisk)}")
                ActionRow("Remove Offline Copies", enabled = offlineCopies.isNotEmpty()) { graph.offline.removeAll() }
            }
            FormSection("Encryption", if (hasKey) "Spreadsheets are decrypted on this phone, and encrypted before they're uploaded. The key never leaves the device."
                else "Your spreadsheets are end-to-end encrypted. To open them here, bring your key from a device that has it.") {
                if (hasKey) {
                    FormRow {
                        Icon(Icons.Filled.VerifiedUser, null, Modifier.size(20.dp), tint = NeutrinoBrand.success)
                        Spacer(Modifier.width(12.dp))
                        Text("This phone has your encryption key")
                    }
                    ActionRow("Remove Key From This Phone", color = MaterialTheme.colorScheme.error) { confirmRemoveKey = true }
                } else {
                    FormRow {
                        Icon(Icons.Filled.Warning, null, Modifier.size(20.dp), tint = SheetsStyle.warning)
                        Spacer(Modifier.width(12.dp))
                        Text("No encryption key on this phone", color = SheetsStyle.warning)
                    }
                    if (canSetUp) ActionRow("Set Up Encryption", Icons.Filled.Key) { router.push(Route.SetUpEncryption) }
                    ActionRow("Pair With Another Device", Icons.Filled.QrCodeScanner) { router.push(Route.Pairing) }
                    ActionRow("Restore From Recovery Kit", Icons.AutoMirrored.Filled.MenuBook) { router.push(Route.RecoveryKit) }
                    ActionRow("Scan Key Code", Icons.Filled.Password) { router.push(Route.KeyCode) }
                }
            }
            FormSection("Security") {
                ValueRow("App Lock", if (appLock) "On" else "Off", Icons.Filled.Lock, onClick = { router.push(Route.AppLockSettings) })
                ValueRow("Registered Devices", "", Icons.Filled.Devices, onClick = { router.push(Route.Devices) })
            }
            FormSection("Account") {
                ValueRow("Signed in as", graph.account.auth.email ?: "—")
                ValueRow("Server", graph.account.settings.serverUrl.removePrefix("https://"))
                ValueRow("Device name", graph.account.settings.deviceName, onClick = { editingDeviceName = true })
                ActionRow("Reset Settings to Defaults") { prefs.resetToDefaults() }
                ActionRow("Sign Out", Icons.AutoMirrored.Filled.Logout, MaterialTheme.colorScheme.error) { confirmSignOut = true }
            }
            FormSection("About") {
                ValueRow("Version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                ActionRow("Report a Bug", Icons.Filled.BugReport) { openUrl(context, BugReport.url(graph.account.settings.serverUrl)) }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
    if (confirmRemoveKey) ConfirmDialog("Remove Key?", "Your spreadsheets stay encrypted on the server. You'll need to bring your key back to read them on this phone.",
        "Remove", destructive = true, onDismiss = { confirmRemoveKey = false }) {
        graph.account.keys.remove()
        graph.account.keyringChanged()
    }
    if (confirmSignOut) ConfirmDialog("Sign Out?", "Offline copies on this phone are removed. Your key stays, so signing back in doesn't need it again.",
        "Sign Out", destructive = true, onDismiss = { confirmSignOut = false }) { graph.account.signOut() }
    if (editingDeviceName) TextInputDialog("Device name", graph.account.settings.deviceName, "Shown in your account's device list", "Save",
        onDismiss = { editingDeviceName = false }) {
        graph.account.settings.customDeviceName = it
        editingDeviceName = false
    }
}

/** Locks Sheets with a fingerprint, face or the device PIN on return; the recents card is blanked while on. */
@Composable
fun AppLockScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val graph = context.graph
    val scope = rememberCoroutineScope()
    val on by graph.account.settings.appLock.collectAsState()
    val available = AppLock.isAvailable(context)
    Column(Modifier.fillMaxSize()) {
        PageBar("App Lock", onBack)
        FormSection(footer = if (available) "Asks for your fingerprint, face or PIN when you come back to Sheets after ${AppLock.GRACE_MILLIS / 1000} seconds away. Your spreadsheets are hidden from the recents screen immediately."
            else "Set up a screen lock in Android Settings to use App Lock.") {
            SwitchRow("Require Unlock", on, enabled = available) { enable ->
                scope.launch {
                    if (AppLock.authenticate(context as FragmentActivity, if (enable) "Turn on app lock" else "Turn off app lock")) graph.account.settings.setAppLock(enable)
                }
            }
        }
    }
}

/** The devices registered against the account; revoking one signs it out. */
@Composable
fun DevicesScreen(onBack: () -> Unit) {
    val graph = LocalContext.current.graph
    val sessions by graph.sessions.sessions.collectAsState()
    val loading by graph.sessions.isLoading.collectAsState()
    val error by graph.sessions.error.collectAsState()
    var revoking by remember { mutableStateOf<com.neutrino.sheets.core.model.DeviceSession?>(null) }
    LaunchedEffect(Unit) { graph.sessions.load() }
    Column(Modifier.fillMaxSize()) {
        PageBar("Registered Devices", onBack)
        Column(Modifier.verticalScroll(rememberScrollState())) {
            FormSection("Devices", "This phone registered as “${graph.account.settings.deviceName}” when you signed in. Revoking signs that device out.") {
                if (sessions.isEmpty() && !loading) Text("No registered devices found.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
                sessions.forEach { s ->
                    FormRow {
                        Column(Modifier.weight(1f)) {
                            Text(s.deviceName?.takeIf { it.isNotBlank() } ?: s.userAgent ?: "Unknown device")
                            Text((if (s.isCurrentDevice) "This device · " else "") + (s.lastUsedAt?.let { "Last used ${formatDateTime(it)}" } ?: "Registered ${formatDateTime(s.createdAt)}"),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { revoking = s }) { Icon(Icons.AutoMirrored.Filled.Logout, "Revoke", tint = MaterialTheme.colorScheme.error) }
                    }
                }
            }
            ErrorText(error)
        }
    }
    revoking?.let { s ->
        ConfirmDialog("Revoke Device?", if (s.isCurrentDevice) "This is the phone you're using: revoking it signs you out here." else "That device will be signed out.", "Revoke",
            destructive = true, onDismiss = { revoking = null }) { graph.sessions.revoke(s.id) }
    }
}

/**
 * The GitHub issue form behind "Report a Bug", prefilled with what this build knows about itself —
 * in fields the user reads and can edit before submitting, since an issue is public. The server's
 * *kind*, never its address.
 */
object BugReport {
    const val REPOSITORY = "wcherry/neutrino_sheets_android_mobile"

    fun url(server: String): String {
        val host = server.lowercase()
        val kind = when {
            host.contains("getneutrino.app") -> "getneutrino.app (hosted)"
            host.contains("localhost") || host.contains("127.0.0.1") || host.contains("10.0.2.2") -> "localhost / development"
            else -> "Self-hosted"
        }
        return Uri.parse("https://github.com/$REPOSITORY/issues/new").buildUpon()
            .appendQueryParameter("template", "1-bug-report.yml")
            .appendQueryParameter("version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            .appendQueryParameter("android", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            .appendQueryParameter("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            .appendQueryParameter("install", if (BuildConfig.DEBUG) "Built from source" else "Release build")
            .appendQueryParameter("server", kind)
            .build().toString()
    }
}
