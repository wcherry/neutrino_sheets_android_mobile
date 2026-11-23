package com.neutrino.sheets.ui.offline

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.neutrino.sheets.Route
import com.neutrino.sheets.graph
import com.neutrino.sheets.ui.FormRow
import com.neutrino.sheets.ui.FormSection
import com.neutrino.sheets.ui.PageBar
import com.neutrino.sheets.ui.formatBytes
import com.neutrino.sheets.ui.formatDateTime
import com.neutrino.shared.core.ooxml.XlsxCodec
import java.time.Instant

/**
 * The spreadsheets stored on this phone: the last copy of each one opened, encrypted. Without a
 * connection they open read-only — there is no offline editing to queue, exactly as on iOS.
 */
@Composable
fun OfflineScreen() {
    val graph = LocalContext.current.graph
    val entries by graph.offline.entries.collectAsState()
    val online by graph.isOnline.collectAsState()
    Column(Modifier.fillMaxSize()) {
        PageBar("Offline", null)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            FormSection("Stored on This Phone", "${formatBytes(graph.offline.bytesOnDisk)} used. Copies are encrypted and need your key to open. " +
                if (online) "They refresh each time you open a spreadsheet." else "You're offline: they open read-only.") {
                if (entries.isEmpty()) Text("Spreadsheets you open are kept here for when you're offline.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
                entries.forEach { e ->
                    FormRow(onClick = { graph.router.push(Route.Editor(e.info.asItem())) }) {
                        Column(Modifier.weight(1f)) {
                            Text(XlsxCodec.strippingExtension(e.name), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("Saved ${formatDateTime(Instant.ofEpochMilli(e.storedAt))}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { graph.offline.remove(e.id) }) { Icon(Icons.Filled.Delete, "Remove") }
                    }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}
