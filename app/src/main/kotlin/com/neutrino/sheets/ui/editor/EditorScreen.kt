package com.neutrino.sheets.ui.editor

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatAlignLeft
import androidx.compose.material.icons.automirrored.filled.FormatAlignRight
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.filled.WrapText
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BorderAll
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.FormatAlignCenter
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatColorFill
import androidx.compose.material.icons.filled.FormatColorText
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatStrikethrough
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.neutrino.sheets.core.editor.GridGeometry
import com.neutrino.sheets.core.editor.SheetEditor
import com.neutrino.sheets.core.model.SheetItem
import com.neutrino.sheets.core.workbook.CellAddress
import com.neutrino.sheets.core.workbook.CellRange
import com.neutrino.sheets.core.workbook.CellStylePatch
import com.neutrino.sheets.core.workbook.MergeEngine
import com.neutrino.sheets.core.workbook.SheetSelection
import com.neutrino.sheets.graph
import com.neutrino.sheets.ui.PageBar
import com.neutrino.sheets.ui.SheetsStyle
import com.neutrino.sheets.ui.Unavailable
import com.neutrino.sheets.ui.formatDateTime
import kotlinx.coroutines.launch

/**
 * One workbook: a formula bar over the grid, a format bar and the sheet tabs under it. Every edit
 * goes through [SheetEditor], which records it for undo and autosaves it under the content version
 * the workbook was loaded at. Offline, the device's last copy opens read-only.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EditorScreen(item: SheetItem, onBack: () -> Unit) {
    val graph = LocalContext.current.graph
    val scope = rememberCoroutineScope()
    val autoSave by graph.prefs.autoSaveSeconds.collectAsState()
    val gridlines by graph.prefs.showGridlines.collectAsState()
    val editor = remember(item.id) {
        SheetEditor(item, graph.content, graph.scope, graph.pasteboard, autosaveDelayMs = autoSave * 1000L, onSaved = { r ->
            graph.drive.spreadsheetWasSaved(item.id, r.sizeBytes, r.updatedAt, r.contentVersion)
        })
    }
    @Suppress("UNUSED_VARIABLE") val revision by editor.revision.collectAsState()
    val loading by editor.isLoading.collectAsState()
    val loadError by editor.loadError.collectAsState()
    val saving by editor.isSaving.collectAsState()
    val unsaved by editor.hasUnsavedChanges.collectAsState()
    val saveError by editor.saveError.collectAsState()
    val conflict by editor.conflict.collectAsState()
    val offlineAt by editor.offlineCopyAt.collectAsState()
    val notice by editor.notice.collectAsState()
    val filters by editor.columnFilters.collectAsState()
    val selection by editor.selection.collectAsState()
    val activeIndex by editor.activeSheetIndex.collectAsState()
    val gridState = remember(item.id) { GridState() }
    val focus = LocalFocusManager.current
    val formulaFocus = remember { FocusRequester() }
    var editing by remember { mutableStateOf(false) }
    var entry by remember { mutableStateOf(TextFieldValue("")) }
    var numericKeys by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<EditorDialog?>(null) }
    var finding by remember { mutableStateOf(false) }

    LaunchedEffect(item.id) { editor.load() }
    // Leaving: what is unsaved is saved on the app's scope, which outlives this screen.
    DisposableEffect(Unit) { onDispose { editor.cancelAutosave(); graph.scope.launch { editor.save() } } }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) graph.scope.launch { editor.save() } }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    // The formula bar shows the selected cell unless something is being typed into it.
    LaunchedEffect(selection, revision, editing) {
        if (!editing) entry = TextFieldValue(editor.selectedCell?.editableText ?: "")
    }

    fun commit(move: Int = 1) {
        val at = selection?.active ?: return
        val current = editor.selectedCell?.editableText ?: ""
        if (entry.text != current) editor.commitEdit(entry.text, at)
        editing = false
        focus.clearFocus()
        if (move != 0) editor.select(SheetSelection(at.offset(rows = move)))
    }

    fun startEditing(at: CellAddress) {
        if (!editor.isEditable) return
        editor.select(SheetSelection(at))
        val text = editor.selectedCell?.editableText ?: ""
        entry = TextFieldValue(text, TextRange(text.length))
        editing = true
        formulaFocus.requestFocus()
    }

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))) {
        PageBar(editor.title, {
            if (editing) commit(0)
            onBack()
        }) {
            IconButton(onClick = { editor.undo() }, enabled = editor.canUndo && editor.isEditable) { Icon(Icons.AutoMirrored.Filled.Undo, editor.undoName?.let { "Undo $it" } ?: "Undo") }
            IconButton(onClick = { editor.redo() }, enabled = editor.canRedo && editor.isEditable) { Icon(Icons.AutoMirrored.Filled.Redo, editor.redoName?.let { "Redo $it" } ?: "Redo") }
            MoreMenu(editor, onFind = { finding = true }, onDialog = { dialog = it })
        }

        when {
            loading && editor.file.value == null -> Box(Modifier.fillMaxSize()) { CircularProgressIndicator(Modifier.align(Alignment.Center)) }
            loadError != null -> Unavailable(Icons.Filled.Warning, "Couldn’t open this spreadsheet", loadError!!, "Retry") { scope.launch { editor.load() } }
            else -> {
                val sheet = editor.activeSheet ?: return@Column
                offlineAt?.let { at ->
                    Banner("Offline copy from ${formatDateTime(at)} — read only.", SheetsStyle.warning.copy(alpha = 0.15f), "Reload") { scope.launch { editor.reload() } }
                }
                if (offlineAt == null && !editor.isEditable) Banner("Read only — you can view this spreadsheet but not edit it.", MaterialTheme.colorScheme.surfaceContainerHigh)
                conflict?.let {
                    Banner("This spreadsheet changed on another device since it was opened.", SheetsStyle.warning.copy(alpha = 0.15f), "Use Theirs", { scope.launch { editor.reload() } }, "Keep Mine") { editor.keepLocalCopy() }
                }
                if (finding) FindBar(editor) { finding = false }

                FormulaBar(
                    address = selection?.address ?: "",
                    value = entry,
                    editable = editor.isEditable,
                    editing = editing,
                    numericKeys = numericKeys,
                    focusRequester = formulaFocus,
                    onFocus = { focused -> if (focused && editor.isEditable) editing = true },
                    onChange = { entry = it },
                    onCommit = { commit() },
                    onCancel = { editing = false; focus.clearFocus(); entry = TextFieldValue(editor.selectedCell?.editableText ?: "") },
                    onToggleKeys = { numericKeys = !numericKeys },
                )

                val geometry = remember(revision, activeIndex, filters) { GridGeometry(sheet, editor.filteredRows) }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    SheetGrid(
                        sheet = sheet, geometry = geometry, selection = selection, frozen = editor.frozen, gridlines = gridlines,
                        editable = editor.isEditable, filteredColumns = filters.allowed.keys, state = gridState,
                        onTap = { hit ->
                            if (editing) commit(0)
                            when (hit) {
                                is GridHit.Cell -> {
                                    val origin = MergeEngine.mergeOrigin(hit.address, sheet.cells)
                                        ?: sheet.cells[hit.address.id]?.mergeAnchor?.let(CellAddress::parse)?.let { MergeEngine.mergeOrigin(it, sheet.cells) }
                                    editor.select(if (origin != null) SheetSelection(origin.start, origin.end) else SheetSelection(hit.address))
                                }
                                is GridHit.ColumnHeader -> editor.select(SheetSelection(CellAddress(hit.column, 1), CellAddress(hit.column, geometry.rows)))
                                is GridHit.RowHeader -> editor.select(SheetSelection(CellAddress(1, hit.row), CellAddress(geometry.columns, hit.row)))
                                GridHit.Corner -> editor.select(SheetSelection(CellAddress(1, 1), CellAddress(geometry.columns, geometry.rows)))
                            }
                        },
                        onDoubleTap = { startEditing(it) },
                        onExtend = { a, b -> if (editing) commit(0); editor.select(SheetSelection(a, b)) },
                        onFill = { editor.fill(it) },
                    )
                }
                if (editor.isEditable) FormatBar(editor, editor.selectionStyle, editor.isSelectionMerged) { dialog = it }
                TabBar(editor, activeIndex, editor.sheetNames, editor.file.value?.sheets?.map { it.color }.orEmpty()) { dialog = it }
                StatusLine(editor.summary?.statusText, when {
                    saving -> "Saving…"
                    saveError != null -> "Save failed: $saveError"
                    conflict != null -> "Not saved — changed elsewhere"
                    unsaved -> "Edited"
                    offlineAt != null -> "Offline"
                    else -> "Saved"
                }, saveError != null || conflict != null, notice)
            }
        }
    }

    dialog?.let { EditorDialogs(it, editor) { dialog = null } }
}

@Composable
private fun Banner(message: String, color: Color, action: String? = null, onAction: () -> Unit = {}, second: String? = null, onSecond: () -> Unit = {}) {
    Surface(color = color) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(message, Modifier.weight(1f).padding(vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
            if (action != null) TextButton(onClick = onAction) { Text(action) }
            if (second != null) TextButton(onClick = onSecond) { Text(second) }
        }
    }
}

@Composable
private fun FormulaBar(
    address: String, value: TextFieldValue, editable: Boolean, editing: Boolean, numericKeys: Boolean, focusRequester: FocusRequester,
    onFocus: (Boolean) -> Unit, onChange: (TextFieldValue) -> Unit, onCommit: () -> Unit, onCancel: () -> Unit, onToggleKeys: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow).padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(address, Modifier.width(76.dp).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, fontFamily = FontFamily.Monospace)
        Text("fx", Modifier.padding(horizontal = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Medium)
        BasicTextField(
            value = value, onValueChange = onChange, readOnly = !editable, singleLine = true,
            textStyle = TextStyle(fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(keyboardType = if (numericKeys) KeyboardType.Decimal else KeyboardType.Text, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onCommit() }),
            modifier = Modifier.weight(1f).focusRequester(focusRequester).onFocusChanged { onFocus(it.isFocused) }.padding(vertical = 6.dp),
        )
        if (editing) {
            TextButton(onClick = onToggleKeys) { Text(if (numericKeys) "ABC" else "123") }
            IconButton(onClick = onCancel, Modifier.size(36.dp)) { Icon(Icons.Filled.Close, "Cancel") }
            IconButton(onClick = onCommit, Modifier.size(36.dp)) { Icon(Icons.Filled.Check, "Enter") }
        }
    }
    HorizontalDivider()
}

/** One-tap formatting over the selection; "Aa" opens the full format sheet. */
@Composable
// The style and merge state come in as parameters: read off the editor here, Compose would skip this bar
// when the selection moves, because the editor itself never changes.
private fun FormatBar(editor: SheetEditor, style: com.neutrino.sheets.core.workbook.CellStyle?, merged: Boolean, onDialog: (EditorDialog) -> Unit) {
    Surface(tonalElevation = 2.dp) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Toggle(Icons.Filled.FormatBold, "Bold", style?.isBold == true) { editor.applyStyle(CellStylePatch.bold(style?.isBold != true), "Bold") }
            Toggle(Icons.Filled.FormatItalic, "Italic", style?.isItalic == true) { editor.applyStyle(CellStylePatch.italic(style?.isItalic != true), "Italic") }
            Toggle(Icons.Filled.FormatStrikethrough, "Strikethrough", style?.isStrikethrough == true) { editor.applyStyle(CellStylePatch.strikethrough(style?.isStrikethrough != true), "Strikethrough") }
            IconButton(onClick = { onDialog(EditorDialog.TextColor) }) { Icon(Icons.Filled.FormatColorText, "Text Colour") }
            IconButton(onClick = { onDialog(EditorDialog.FillColor) }) { Icon(Icons.Filled.FormatColorFill, "Fill Colour") }
            Toggle(Icons.AutoMirrored.Filled.FormatAlignLeft, "Align Left", style?.textAlign == "left") { editor.applyStyle(CellStylePatch.textAlign("left"), "Align Left") }
            Toggle(Icons.Filled.FormatAlignCenter, "Align Centre", style?.textAlign == "center") { editor.applyStyle(CellStylePatch.textAlign("center"), "Align Centre") }
            Toggle(Icons.AutoMirrored.Filled.FormatAlignRight, "Align Right", style?.textAlign == "right") { editor.applyStyle(CellStylePatch.textAlign("right"), "Align Right") }
            Toggle(Icons.AutoMirrored.Filled.WrapText, "Wrap", style?.wrapMode == "wrap") { editor.applyStyle(CellStylePatch.wrapMode(if (style?.wrapMode == "wrap") null else "wrap"), "Wrap") }
            for ((format, label) in listOf("currency" to "$", "percent" to "%", "number" to "#")) {
                TextButton(onClick = {
                    val on = style?.numberFormat == format && style.customFormat == null
                    editor.applyStyle(CellStylePatch(numberFormat = com.neutrino.sheets.core.workbook.StyleUpdate.of(if (on) null else format),
                        customFormat = com.neutrino.sheets.core.workbook.StyleUpdate.Clear), "Number Format")
                }) { Text(label, fontWeight = if (style?.numberFormat == format) FontWeight.Bold else null) }
            }
            IconButton(onClick = { onDialog(EditorDialog.Borders) }) { Icon(Icons.Filled.BorderAll, "Borders") }
            TextButton(onClick = { editor.toggleMerge() }) { Text(if (merged) "Unmerge" else "Merge") }
            IconButton(onClick = { onDialog(EditorDialog.TableStyles) }) { Icon(Icons.Filled.TableChart, "Table Style") }
            TextButton(onClick = { onDialog(EditorDialog.Format) }) { Text("Aa", fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun Toggle(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, on: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, Modifier.background(if (on) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent, RoundedCornerShape(8.dp))) {
        Icon(icon, label)
    }
}

/** The tabs, the active one marked; a long press offers the tab's commands. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TabBar(editor: SheetEditor, active: Int, names: List<String>, colors: List<String?>, onDialog: (EditorDialog) -> Unit) {
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        names.forEachIndexed { i, name ->
            var menu by remember { mutableStateOf(false) }
            Box {
                val color = cssColor(colors.getOrNull(i))?.let { Color(it) }
                Column(Modifier.combinedClickable(onClick = { editor.selectSheet(i) }, onLongClick = { editor.selectSheet(i); menu = true })
                    .background(if (i == active) MaterialTheme.colorScheme.surface else Color.Transparent).padding(horizontal = 14.dp, vertical = 8.dp)) {
                    Text(name, fontWeight = if (i == active) FontWeight.SemiBold else null, style = MaterialTheme.typography.bodyMedium)
                    if (color != null) Box(Modifier.padding(top = 2.dp).height(3.dp).width(24.dp).background(color))
                }
                DropdownMenu(menu, { menu = false }) {
                    fun act(f: () -> Unit) { menu = false; f() }
                    val editable = editor.isEditable
                    DropdownMenuItem(text = { Text("Rename…") }, enabled = editable, onClick = { act { onDialog(EditorDialog.RenameSheet(i)) } })
                    DropdownMenuItem(text = { Text("Colour…") }, enabled = editable, onClick = { act { onDialog(EditorDialog.SheetColor(i)) } })
                    DropdownMenuItem(text = { Text("Duplicate") }, enabled = editable, onClick = { act { editor.duplicateSheet(i) } })
                    DropdownMenuItem(text = { Text("Move Left") }, enabled = editable && i > 0, onClick = { act { editor.moveSheet(i, -1) } })
                    DropdownMenuItem(text = { Text("Move Right") }, enabled = editable && i < names.size - 1, onClick = { act { editor.moveSheet(i, 1) } })
                    DropdownMenuItem(text = { Text("Delete", color = MaterialTheme.colorScheme.error) }, enabled = editable && editor.canDeleteSheet,
                        onClick = { act { onDialog(EditorDialog.DeleteSheet(i)) } })
                }
            }
        }
        if (editor.isEditable) IconButton(onClick = { editor.addSheet() }) { Icon(Icons.Filled.Add, "Add Sheet") }
    }
}

@Composable
private fun StatusLine(summary: String?, status: String, failed: Boolean, notice: String?) {
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(notice ?: summary ?: "", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.width(8.dp))
        Text(status, style = MaterialTheme.typography.labelSmall, color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

/** Everything that is not one tap: clipboard, structure, sort, filter, freeze, find. */
@Composable
private fun MoreMenu(editor: SheetEditor, onFind: () -> Unit, onDialog: (EditorDialog) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, "More") }
        DropdownMenu(open, { open = false }) {
            fun act(f: () -> Unit) { open = false; f() }
            val editable = editor.isEditable
            val sel = editor.selection.value
            val context = LocalContext.current
            DropdownMenuItem(text = { Text("Find & Replace") }, leadingIcon = { Icon(Icons.Filled.Search, null) }, onClick = { act(onFind) })
            DropdownMenuItem(text = { Text("Send a Copy…") }, leadingIcon = { Icon(Icons.Filled.Share, null) }, onClick = { act { sendCopy(context, editor) } })
            DropdownMenuItem(text = { Text("Copy") }, leadingIcon = { Icon(Icons.Filled.ContentCopy, null) }, onClick = { act { editor.copySelection() } })
            DropdownMenuItem(text = { Text("Cut") }, leadingIcon = { Icon(Icons.Filled.ContentCut, null) }, enabled = editable, onClick = { act { editor.copySelection(cut = true) } })
            DropdownMenuItem(text = { Text("Paste") }, leadingIcon = { Icon(Icons.Filled.ContentPaste, null) }, enabled = editor.canPaste, onClick = { act { editor.paste() } })
            DropdownMenuItem(text = { Text("Clear Contents") }, enabled = editable, onClick = { act { editor.clearSelection() } })
            DropdownMenuItem(text = { Text("Clear Formatting") }, enabled = editable, onClick = { act { editor.clearFormatting() } })
            HorizontalDivider()
            DropdownMenuItem(text = { Text("Insert Row Above") }, enabled = editable, onClick = { act { editor.insertRows(true) } })
            DropdownMenuItem(text = { Text("Insert Row Below") }, enabled = editable, onClick = { act { editor.insertRows(false) } })
            DropdownMenuItem(text = { Text("Insert Column Left") }, enabled = editable, onClick = { act { editor.insertColumns(true) } })
            DropdownMenuItem(text = { Text("Insert Column Right") }, enabled = editable, onClick = { act { editor.insertColumns(false) } })
            DropdownMenuItem(text = { Text("Delete Rows") }, enabled = editable, onClick = { act { editor.deleteRows() } })
            DropdownMenuItem(text = { Text("Delete Columns") }, enabled = editable, onClick = { act { editor.deleteColumns() } })
            DropdownMenuItem(text = { Text("Resize Columns…") }, enabled = editable, onClick = { act { onDialog(EditorDialog.Resize(columns = true)) } })
            DropdownMenuItem(text = { Text("Resize Rows…") }, enabled = editable, onClick = { act { onDialog(EditorDialog.Resize(columns = false)) } })
            DropdownMenuItem(text = { Text("Hide Rows") }, enabled = editable, onClick = { act { editor.hideRows() } })
            DropdownMenuItem(text = { Text("Hide Columns") }, enabled = editable, onClick = { act { editor.hideColumns() } })
            if (editor.hasHiddenRowsOrColumns) DropdownMenuItem(text = { Text("Show All Rows & Columns") }, enabled = editable, onClick = { act { editor.showEverything() } })
            HorizontalDivider()
            val column = sel?.active?.column ?: 1
            DropdownMenuItem(text = { Text("Sort A → Z") }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.Sort, null) }, enabled = editable, onClick = { act { editor.sort(column, true) } })
            DropdownMenuItem(text = { Text("Sort Z → A") }, enabled = editable, onClick = { act { editor.sort(column, false) } })
            DropdownMenuItem(text = { Text("Filter Column ${CellAddress.columnLetters(column)}…") }, leadingIcon = { Icon(Icons.Filled.FilterList, null) },
                onClick = { act { onDialog(EditorDialog.Filter(column)) } })
            if (!editor.columnFilters.value.isEmpty) DropdownMenuItem(text = { Text("Clear All Filters") }, onClick = { act { editor.clearAllFilters() } })
            HorizontalDivider()
            DropdownMenuItem(text = { Text("Freeze Above & Left of Selection") }, onClick = { act { editor.freezeToSelection() } })
            if (!editor.frozen.isEmpty) DropdownMenuItem(text = { Text("Unfreeze Panes") }, onClick = { act { editor.unfreeze() } })
        }
    }
}


/** The decrypted .xlsx, written to the share cache (cleared at sign-out) and handed to the system share sheet. */
private fun sendCopy(context: android.content.Context, editor: SheetEditor) {
    val bytes = editor.exportedBytes() ?: return
    val dir = java.io.File(context.cacheDir, "exports").apply { mkdirs() }
    val name = editor.title.replace(Regex("[/\\\\:*?\"<>|]"), "_").ifBlank { "Spreadsheet" }
    val file = java.io.File(dir, if (name.endsWith(".xlsx", ignoreCase = true)) name else "$name.xlsx")
    file.writeBytes(bytes)
    val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType(com.neutrino.shared.core.ooxml.XlsxCodec.MIME_TYPE)
        .putExtra(android.content.Intent.EXTRA_STREAM, uri).addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(android.content.Intent.createChooser(send, "Send a Copy"))
}
