package com.neutrino.sheets.core.editor

import com.neutrino.sheets.core.api.SheetsApi
import com.neutrino.sheets.core.model.SheetFileInfo
import com.neutrino.sheets.core.model.SheetItem
import com.neutrino.sheets.core.service.SheetContentService
import com.neutrino.sheets.core.workbook.Axis
import com.neutrino.sheets.core.workbook.CellAddress
import com.neutrino.sheets.core.workbook.CellChanges
import com.neutrino.sheets.core.workbook.CellRange
import com.neutrino.sheets.core.workbook.CellStyle
import com.neutrino.sheets.core.workbook.CellStylePatch
import com.neutrino.sheets.core.workbook.ColumnFilters
import com.neutrino.sheets.core.workbook.FillEngine
import com.neutrino.sheets.core.workbook.FindReplace
import com.neutrino.sheets.core.workbook.FormulaReferences
import com.neutrino.sheets.core.workbook.MergeEngine
import com.neutrino.sheets.core.workbook.Operation
import com.neutrino.sheets.core.workbook.RangeSummary
import com.neutrino.sheets.core.workbook.SavedCell
import com.neutrino.sheets.core.workbook.SheetClipboard
import com.neutrino.sheets.core.workbook.SheetData
import com.neutrino.sheets.core.workbook.SheetFile
import com.neutrino.sheets.core.workbook.SheetSelection
import com.neutrino.sheets.core.workbook.SheetTabs
import com.neutrino.sheets.core.workbook.SortEngine
import com.neutrino.sheets.core.workbook.StructuralShift
import com.neutrino.sheets.core.workbook.TableRegion
import com.neutrino.sheets.core.workbook.TableStyle
import com.neutrino.sheets.core.workbook.TableStyles
import com.neutrino.sheets.core.workbook.registering
import com.neutrino.sheets.core.workbook.removingOverlaps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant

/** Where copied cells live between copy and paste: the app's in-process payload, plus plain text for other apps. */
interface SheetPasteboard {
    fun write(clipboard: SheetClipboard?, text: String)
    /** The payload, when the system clipboard still holds what this app put there. */
    fun readPayload(): SheetClipboard?
    fun readText(): String?
    val hasContent: Boolean
}

/** Frozen rows and columns above and left of the selection when frozen. Per tab, per session. */
data class FrozenPanes(val rows: Int = 0, val columns: Int = 0) {
    val isEmpty get() = rows == 0 && columns == 0
    val description get() = listOfNotNull(
        rows.takeIf { it > 0 }?.let { if (it == 1) "1 row" else "$it rows" },
        columns.takeIf { it > 0 }?.let { if (it == 1) "1 column" else "$it columns" },
    ).joinToString(" and ")
}

/**
 * One open workbook — the iOS `SheetEditorModel`. Every edit goes through two funnels: [apply] for
 * cell changes (recorded as before/after maps, so undo is a write) and [applyWorkbook] for whole-tab
 * changes (structure, tabs). Saves are serialised and guarded by the content version the workbook
 * was loaded at, so a save never lands over a change made elsewhere unseen. Main-thread confined.
 */
class SheetEditor(
    private val item: SheetItem,
    private val content: SheetContentService,
    private val scope: CoroutineScope,
    private val pasteboard: SheetPasteboard,
    private val onSaved: (SheetContentService.SaveResult) -> Unit = {},
    /** Quiet time before an autosave; 0 saves only when asked. */
    private val autosaveDelayMs: Long = 3_000,
) {
    data class Conflict(val serverVersion: Long?)

    /** Bumped on every change, so a screen can observe one value instead of every flow. */
    val revision = MutableStateFlow(0)
    val file = MutableStateFlow<SheetFile?>(null)
    val info = MutableStateFlow<SheetFileInfo?>(null)
    val activeSheetIndex = MutableStateFlow(0)
    val selection = MutableStateFlow<SheetSelection?>(null)
    val isLoading = MutableStateFlow(false)
    val isSaving = MutableStateFlow(false)
    val hasUnsavedChanges = MutableStateFlow(false)
    val loadError = MutableStateFlow<String?>(null)
    val saveError = MutableStateFlow<String?>(null)
    val offlineCopyAt = MutableStateFlow<Instant?>(null)
    val conflict = MutableStateFlow<Conflict?>(null)
    val notice = MutableStateFlow<String?>(null)
    val columnFilters = MutableStateFlow(ColumnFilters())
    private val frozenByTab = MutableStateFlow<Map<Int, FrozenPanes>>(emptyMap())
    val frozenPanes: StateFlow<Map<Int, FrozenPanes>> = frozenByTab

    private var dek: ByteArray? = null
    private var contentVersion: Long? = null
    private var sourcePackage: ByteArray? = null
    private val history = EditHistory()
    private var cutSource: Pair<Int, CellRange>? = null
    private val saveLock = Mutex()
    private var autosave: Job? = null
    private var noticeJob: Job? = null

    val title get() = info.value?.name?.let(com.neutrino.shared.core.ooxml.XlsxCodec::strippingExtension) ?: item.displayName
    val isEditable get() = info.value?.isEditable == true && offlineCopyAt.value == null
    val sheetNames get() = file.value?.sheets?.mapIndexed { i, s -> s.name ?: "Sheet ${i + 1}" }.orEmpty()
    val activeSheet: SheetData? get() = file.value?.sheets?.getOrNull(activeSheetIndex.value)
    val selectedCell: SavedCell? get() = selection.value?.let { activeSheet?.cells?.get(it.active.id) }
    val selectionStyle: CellStyle? get() = selectedCell?.cellStyle
    val frozen get() = frozenByTab.value[activeSheetIndex.value] ?: FrozenPanes()
    val canUndo get() = history.canUndo
    val canRedo get() = history.canRedo
    val undoName get() = history.undoName
    val redoName get() = history.redoName
    val canPaste get() = isEditable && pasteboard.hasContent
    val canDeleteSheet get() = (file.value?.sheets?.size ?: 0) > 1

    val summary: RangeSummary?
        get() {
            val sel = selection.value?.takeIf { !it.isSingleCell } ?: return null
            val sheet = activeSheet ?: return null
            return RangeSummary.of(sel.range, sheet.cells).takeIf { !it.isEmpty }
        }

    val isSelectionMerged: Boolean
        get() {
            val sel = selection.value ?: return false
            return MergeEngine.mergeOrigin(sel.range.start, activeSheet?.cells.orEmpty()) != null
        }

    // ── Load ────────────────────────────────────────────────────────────────────────────────────

    suspend fun load() {
        if (isLoading.value || file.value != null) return
        isLoading.value = true
        loadError.value = null
        try {
            val loaded = content.load(item.id)
            dek = loaded.dek
            contentVersion = loaded.info.contentVersion
            sourcePackage = loaded.sourcePackage
            info.value = loaded.info
            offlineCopyAt.value = loaded.offlineCopyAt
            activeSheetIndex.value = 0
            selection.value = SheetSelection(CellAddress(1, 1))
            history.clear()
            cutSource = null
            file.value = loaded.file
            bump()
            if (loaded.needsInitialEncryption) queueSave()
        } catch (e: Exception) {
            loadError.value = e.message ?: "Couldn’t open this spreadsheet."
        } finally {
            isLoading.value = false
        }
    }

    suspend fun reload() {
        conflict.value = null
        file.value = null
        hasUnsavedChanges.value = false
        history.clear()
        bump()
        load()
    }

    // ── Editing cells ───────────────────────────────────────────────────────────────────────────

    /** A typed entry. Formulas keep no value here — the web computes it when it next opens the sheet. */
    fun commitEdit(text: String, at: CellAddress) {
        val sheet = activeSheet ?: return
        val cell = (sheet.cells[at.id] ?: SavedCell(at.id)).copy(raw = text.ifEmpty { null }, value = if (text.isEmpty() || FormulaReferences.isFormula(text)) null else text)
        apply(mapOf(at.id to cell.takeIf { !it.isEmpty }), "Typing")
        if (FormulaReferences.isFormula(text)) show("Formulas calculate when the sheet is opened on the web")
    }

    fun clearSelection() {
        val sheet = activeSheet ?: return
        val sel = selection.value ?: return
        val changes = LinkedHashMap<String, SavedCell?>()
        for (a in sel.range.addresses) {
            val cell = sheet.cells[a.id] ?: continue
            if (cell.raw.isNullOrEmpty() && cell.value.isNullOrEmpty()) continue
            changes[a.id] = cell.copy(raw = null, value = null).takeIf { !it.isEmpty }
        }
        apply(changes, "Clear")
    }

    fun applyStyle(patch: CellStylePatch, name: String) {
        val sheet = activeSheet ?: return
        val sel = selection.value ?: return
        if (!isEditable || patch.isEmpty) return
        apply(sel.range.addresses.associate { a ->
            val cell = sheet.cells[a.id] ?: SavedCell(a.id)
            a.id to cell.copy(cellStyle = patch.applied(cell.cellStyle)).takeIf { !it.isEmpty }
        }, name)
    }

    private fun applyStyles(patches: Map<String, CellStylePatch>, name: String, tables: List<TableRegion>?) {
        val sheet = activeSheet ?: return
        apply(mapOf(activeSheetIndex.value to patches.mapValues { (id, patch) ->
            val cell = sheet.cells[id] ?: SavedCell(id)
            cell.copy(cellStyle = patch.applied(cell.cellStyle)).takeIf { !it.isEmpty }
        }), name, tables = tables?.let { mapOf(activeSheetIndex.value to it) }.orEmpty())
    }

    fun clearFormatting() = applyStyle(CellStylePatch.clearFormatting, "Clear Formatting")

    fun toggleMerge() {
        val sheet = activeSheet ?: return
        val sel = selection.value ?: return
        if (!isEditable) return
        val origin = MergeEngine.mergeOrigin(sel.range.start, sheet.cells)
        if (origin != null) apply(MergeEngine.unmerge(origin, sheet.cells), "Unmerge", SheetSelection(origin.start, origin.end))
        else if (sel.range.cellCount > 1) apply(MergeEngine.merge(sel.range, sheet.cells), "Merge", SheetSelection(sel.range.start))
    }

    fun applyTableStyle(style: TableStyle) {
        val sheet = activeSheet ?: return
        val range = selection.value?.range ?: return
        if (!isEditable) return
        val existing = sheet.tables.orEmpty()
        when (style) {
            is TableStyle.Blank -> applyStyles(range.addresses.associate { it.id to CellStylePatch.clearFormatting }, "Clear Table Style", existing.removingOverlaps(range))
            is TableStyle.Regular -> applyStyles(TableStyles.patches(style, range), "Table Style", existing.registering(TableRegion(TableRegion.makeId(), style.id, range)))
        }
    }

    fun fill(target: CellRange) {
        val sheet = activeSheet ?: return
        val source = selection.value?.range ?: return
        if (!isEditable) return
        val changes = FillEngine.fill(source, target, sheet.cells)
        if (changes.isNotEmpty()) apply(changes, "Fill", SheetSelection(target.start, target.end))
    }

    // ── Clipboard ───────────────────────────────────────────────────────────────────────────────

    fun copySelection(cut: Boolean = false) {
        val sheet = activeSheet ?: return
        val range = selection.value?.range ?: return
        if (cut && !isEditable) return
        val clipboard = SheetClipboard.of(range, sheet.cells, cut)
        pasteboard.write(clipboard, clipboard.tabSeparatedText)
        cutSource = if (cut) activeSheetIndex.value to range else null
        show(if (cut) "Cut ${range.address}" else "Copied ${range.address}")
    }

    fun paste() {
        val sel = selection.value ?: return
        val sheet = activeSheet ?: return
        if (!isEditable) return
        val clipboard = pasteboard.readPayload() ?: pasteboard.readText()?.takeIf { it.isNotEmpty() }?.let(SheetClipboard::fromTabSeparated)
        if (clipboard == null) { show("Nothing to paste"); return }
        val target = clipboard.target(sel.range.start, sel.range)
        val destination = clipboard.changes(sel.range.start, sel.range, sheet.cells)
        if (destination.isEmpty()) return
        val bySheet = HashMap<Int, CellChanges>()
        bySheet[activeSheetIndex.value] = destination
        val cut = cutSource
        if (clipboard.isCut && cut != null) {
            val (sheetIndex, range) = cut
            val cleared = range.addresses.filter { !(sheetIndex == activeSheetIndex.value && it.id in destination) }.associate { it.id to null as SavedCell? }
            bySheet[sheetIndex] = cleared + (bySheet[sheetIndex] ?: emptyMap())
        }
        apply(bySheet, "Paste", selectionAfter = SheetSelection(target.start, target.end))
        if (clipboard.isCut) {
            cutSource = null
            pasteboard.write(null, clipboard.tabSeparatedText)
        }
        if (destination.values.any { it?.raw?.let(FormulaReferences::isFormula) == true }) show("Pasted formulas recalculate when the sheet is opened on the web")
    }

    // ── Rows, columns, tabs ─────────────────────────────────────────────────────────────────────

    fun insertRows(before: Boolean) {
        val r = selection.value?.range ?: return
        val index = if (before) r.start.row else r.end.row + 1
        shift(Axis.ROW, Operation.INSERT, List(r.rowCount) { index }, if (r.rowCount > 1) "Insert Rows" else "Insert Row",
            SheetSelection(CellAddress(r.start.column, index), CellAddress(r.end.column, index + r.rowCount - 1)))
    }

    fun insertColumns(before: Boolean) {
        val r = selection.value?.range ?: return
        val index = if (before) r.start.column else r.end.column + 1
        shift(Axis.COLUMN, Operation.INSERT, List(r.columnCount) { index }, if (r.columnCount > 1) "Insert Columns" else "Insert Column",
            SheetSelection(CellAddress(index, r.start.row), CellAddress(index + r.columnCount - 1, r.end.row)))
    }

    fun deleteRows() {
        val r = selection.value?.range ?: return
        shift(Axis.ROW, Operation.DELETE, r.rows.reversed().toList(), if (r.rowCount > 1) "Delete Rows" else "Delete Row", SheetSelection(r.start))
    }

    fun deleteColumns() {
        val r = selection.value?.range ?: return
        shift(Axis.COLUMN, Operation.DELETE, r.columns.reversed().toList(), if (r.columnCount > 1) "Delete Columns" else "Delete Column", SheetSelection(r.start))
    }

    private fun shift(axis: Axis, operation: Operation, indices: List<Int>, name: String, after: SheetSelection?) {
        if (indices.isEmpty()) return
        val sheets = file.value?.sheets ?: return
        val active = activeSheetIndex.value
        val target = sheets.getOrNull(active) ?: return
        // Other tabs' references to this sheet move with it, as on the web.
        val targetName = target.name ?: "Sheet ${active + 1}"
        val next = sheets.mapIndexed { i, sheet ->
            if (i == active) StructuralShift.apply(sheet, axis, operation, indices)
            else indices.fold(sheet) { s, index -> StructuralShift.shiftReferencesFromOtherSheet(s, targetName, axis, operation, index) ?: s }
        }
        applyWorkbook(next, name, selectionAfter = after)
    }

    fun resizeColumns(columns: IntRange, width: Double?, name: String = "Resize Column") = editActiveSheet(name) { sheet ->
        val widths = sheet.colWidths.orEmpty().toMutableMap()
        for (c in columns) if (width != null) widths[(c - 1).toString()] = maxOf(0.0, width) else widths.remove((c - 1).toString())
        sheet.copy(colWidths = widths.ifEmpty { null })
    }

    fun resizeRows(rows: IntRange, height: Double?, name: String = "Resize Row") = editActiveSheet(name) { sheet ->
        val heights = sheet.rowHeights.orEmpty().toMutableMap()
        for (r in rows) if (height != null) heights[(r - 1).toString()] = maxOf(0.0, height) else heights.remove((r - 1).toString())
        sheet.copy(rowHeights = heights.ifEmpty { null })
    }

    /** Hiding is a size of zero — the web's own representation, so a hidden row stays hidden everywhere. */
    fun hideRows() { val r = selection.value?.range ?: return; resizeRows(r.rows, 0.0, if (r.rowCount > 1) "Hide Rows" else "Hide Row") }
    fun hideColumns() { val r = selection.value?.range ?: return; resizeColumns(r.columns, 0.0, if (r.columnCount > 1) "Hide Columns" else "Hide Column") }

    val hasHiddenRowsOrColumns get() = activeSheet?.let { s -> s.rowHeights.orEmpty().values.any { it == 0.0 } || s.colWidths.orEmpty().values.any { it == 0.0 } } == true

    fun showEverything() = editActiveSheet("Show All") { s ->
        s.copy(rowHeights = s.rowHeights.orEmpty().filterValues { it != 0.0 }.ifEmpty { null }, colWidths = s.colWidths.orEmpty().filterValues { it != 0.0 }.ifEmpty { null })
    }

    fun sort(column: Int, ascending: Boolean) {
        val sheet = activeSheet ?: return
        val sel = selection.value ?: return
        if (!isEditable) return
        val range = if (sel.range.columnCount > 1 && sel.range.rowCount > 1) sel.range else {
            val (rows, columns) = sheet.usedBounds ?: return
            CellRange(CellAddress(1, 1), CellAddress(maxOf(columns, column), rows))
        }
        when (val outcome = SortEngine.sortRows(range, column, ascending, sheet.cells)) {
            is SortEngine.Outcome.Sorted -> if (outcome.changes.isNotEmpty()) apply(outcome.changes, "Sort")
            is SortEngine.Outcome.Refused -> if (outcome.reason == SortEngine.Refusal.CONTAINS_MERGED_CELLS) show("Cannot sort a range that contains merged cells")
        }
    }

    val usedRowCount get() = activeSheet?.usedBounds?.first ?: 0
    val filteredRows: Set<Int> get() = activeSheet?.let { columnFilters.value.hiddenRows(it.cells, usedRowCount) }.orEmpty()
    fun filterValues(column: Int) = activeSheet?.let { ColumnFilters.values(column, it.cells, usedRowCount) }.orEmpty()
    fun setFilter(values: Set<String>?, column: Int) { columnFilters.value = columnFilters.value.with(column, values); bump() }
    fun clearAllFilters() { columnFilters.value = ColumnFilters(); bump() }

    fun matches(query: String, options: FindReplace.Options) = activeSheet?.let { FindReplace.matches(it.cells, query, options) }.orEmpty()

    fun replace(at: CellAddress, query: String, replacement: String, options: FindReplace.Options) {
        val cell = activeSheet?.cells?.get(at.id) ?: return
        if (!isEditable) return
        apply(mapOf(at.id to FindReplace.replaced(cell, query, replacement, options, false)), "Replace", SheetSelection(at))
        if (cell.raw?.let(FormulaReferences::isFormula) == true) show("Replaced formulas recalculate when the sheet is opened on the web")
    }

    fun replaceAll(query: String, replacement: String, options: FindReplace.Options): Int {
        val sheet = activeSheet ?: return 0
        if (!isEditable) return 0
        val changes = FindReplace.matches(sheet.cells, query, options).mapNotNull { a ->
            sheet.cells[a.id]?.let { a.id to FindReplace.replaced(it, query, replacement, options, true) }
        }.toMap()
        if (changes.isEmpty()) return 0
        apply(changes, "Replace All")
        return changes.size
    }

    fun freezeToSelection() {
        val active = selection.value?.active ?: return
        val panes = FrozenPanes(active.row - 1, active.column - 1)
        frozenByTab.value = if (panes.isEmpty) frozenByTab.value - activeSheetIndex.value else frozenByTab.value + (activeSheetIndex.value to panes)
        bump()
        show(if (panes.isEmpty) "Panes unfrozen" else "Froze ${panes.description}")
    }

    fun unfreeze() { frozenByTab.value = frozenByTab.value - activeSheetIndex.value; bump() }

    fun addSheet() {
        val f = file.value ?: return
        val o = SheetTabs.add(f.sheets)
        applyWorkbook(o.sheets, "Add Sheet", o.activeIndex, SheetSelection(CellAddress(1, 1)))
    }

    fun renameSheet(index: Int, name: String) { file.value?.let { applyWorkbook(SheetTabs.rename(it.sheets, index, name), "Rename Sheet") } }
    fun setSheetColor(index: Int, color: String?) { file.value?.let { applyWorkbook(SheetTabs.setColor(it.sheets, index, color), "Sheet Colour") } }

    fun duplicateSheet(index: Int) {
        val f = file.value ?: return
        val o = SheetTabs.duplicate(f.sheets, index)
        frozenByTab.value = emptyMap()
        applyWorkbook(o.sheets, "Duplicate Sheet", o.activeIndex, SheetSelection(CellAddress(1, 1)))
    }

    fun deleteSheet(index: Int) {
        val f = file.value ?: return
        val o = SheetTabs.delete(f.sheets, index, activeSheetIndex.value) ?: return
        frozenByTab.value = emptyMap()
        applyWorkbook(o.sheets, "Delete Sheet", o.activeIndex, SheetSelection(CellAddress(1, 1)))
    }

    fun moveSheet(index: Int, offset: Int) {
        val f = file.value ?: return
        val o = SheetTabs.move(f.sheets, index, offset, activeSheetIndex.value) ?: return
        frozenByTab.value = emptyMap()
        applyWorkbook(o.sheets, "Move Sheet", o.activeIndex)
    }

    fun selectSheet(index: Int) {
        if (file.value?.sheets?.indices?.contains(index) != true || index == activeSheetIndex.value) return
        activeSheetIndex.value = index
        selection.value = SheetSelection(CellAddress(1, 1))
        columnFilters.value = ColumnFilters()
        bump()
    }

    fun select(selection: SheetSelection) {
        this.selection.value = selection
        bump()
    }

    // ── Undo ────────────────────────────────────────────────────────────────────────────────────

    fun undo() { history.undo()?.let { restore(it, true) } }
    fun redo() { history.redo()?.let { restore(it, false) } }

    private fun restore(edit: WorkbookEdit, undoing: Boolean) {
        when (val c = edit.change) {
            is WorkbookEdit.Change.Cells -> write(if (undoing) c.before else c.after, if (undoing) c.tablesBefore else c.tablesAfter)
            is WorkbookEdit.Change.Workbook -> file.value = file.value?.copy(sheets = if (undoing) c.before else c.after)
        }
        val count = file.value?.sheets?.size ?: 1
        activeSheetIndex.value = (if (undoing) edit.sheetBefore else edit.sheetAfter).coerceIn(0, maxOf(0, count - 1))
        selection.value = if (undoing) edit.selectionBefore else edit.selectionAfter
        changed()
    }

    // ── The two funnels ─────────────────────────────────────────────────────────────────────────

    private fun apply(changes: CellChanges, name: String, selectionAfter: SheetSelection? = null) =
        apply(mapOf(activeSheetIndex.value to changes), name, selectionAfter = selectionAfter)

    private fun apply(changes: Map<Int, CellChanges>, name: String, tables: Map<Int, List<TableRegion>> = emptyMap(), selectionAfter: SheetSelection? = null) {
        if (!isEditable) return
        val f = file.value ?: return
        val sheets = f.sheets.toMutableList()
        val before = HashMap<Int, CellChanges>()
        val after = HashMap<Int, CellChanges>()
        for ((index, sheetChanges) in changes) {
            if (index !in sheets.indices) continue
            val cells = sheets[index].cells.toMutableMap()
            val b = LinkedHashMap<String, SavedCell?>()
            val a = LinkedHashMap<String, SavedCell?>()
            for ((id, newCell) in sheetChanges) {
                val old = cells[id]
                if (old == newCell) continue
                b[id] = old
                a[id] = newCell
                if (newCell != null) cells[id] = newCell else cells.remove(id)
            }
            if (a.isEmpty()) continue
            sheets[index] = sheets[index].copy(cells = cells)
            before[index] = b
            after[index] = a
        }
        val tablesBefore = HashMap<Int, List<TableRegion>>()
        val tablesAfter = HashMap<Int, List<TableRegion>>()
        for ((index, regions) in tables) {
            if (index !in sheets.indices) continue
            val current = sheets[index].tables.orEmpty()
            if (current == regions) continue
            tablesBefore[index] = current
            tablesAfter[index] = regions
            sheets[index] = sheets[index].copy(tables = regions.ifEmpty { null })
        }
        if (after.isEmpty() && tablesAfter.isEmpty()) return
        file.value = f.copy(sheets = sheets)
        history.record(WorkbookEdit(WorkbookEdit.Change.Cells(before, after, tablesBefore, tablesAfter), activeSheetIndex.value, activeSheetIndex.value,
            selection.value, selectionAfter ?: selection.value, name))
        if (selectionAfter != null) selection.value = selectionAfter
        changed()
    }

    private fun applyWorkbook(sheets: List<SheetData>, name: String, activeIndexAfter: Int? = null, selectionAfter: SheetSelection? = null) {
        if (!isEditable) return
        val f = file.value ?: return
        if (f.sheets == sheets) return
        val sheetAfter = (activeIndexAfter ?: activeSheetIndex.value).coerceIn(0, maxOf(0, sheets.size - 1))
        history.record(WorkbookEdit(WorkbookEdit.Change.Workbook(f.sheets, sheets), activeSheetIndex.value, sheetAfter, selection.value, selectionAfter ?: selection.value, name))
        file.value = f.copy(sheets = sheets)
        activeSheetIndex.value = sheetAfter
        if (selectionAfter != null) selection.value = selectionAfter
        changed()
    }

    private fun editActiveSheet(name: String, selectionAfter: SheetSelection? = null, transform: (SheetData) -> SheetData?) {
        val f = file.value ?: return
        val sheet = activeSheet ?: return
        if (!isEditable) return
        val updated = transform(sheet) ?: return
        applyWorkbook(f.sheets.toMutableList().also { it[activeSheetIndex.value] = updated }, name, selectionAfter = selectionAfter)
    }

    private fun write(changes: Map<Int, CellChanges>, tables: Map<Int, List<TableRegion>>) {
        val f = file.value ?: return
        val sheets = f.sheets.toMutableList()
        for ((index, sheetChanges) in changes) {
            if (index !in sheets.indices) continue
            val cells = sheets[index].cells.toMutableMap()
            for ((id, cell) in sheetChanges) if (cell != null) cells[id] = cell else cells.remove(id)
            sheets[index] = sheets[index].copy(cells = cells)
        }
        for ((index, regions) in tables) if (index in sheets.indices) sheets[index] = sheets[index].copy(tables = regions.ifEmpty { null })
        file.value = f.copy(sheets = sheets)
    }

    private fun changed() {
        hasUnsavedChanges.value = true
        bump()
        if (autosaveDelayMs > 0) {
            autosave?.cancel()
            autosave = scope.launch {
                delay(autosaveDelayMs)
                saveIfNeeded()
            }
        }
    }

    private fun bump() { revision.value++ }

    private fun show(message: String) {
        notice.value = message
        noticeJob?.cancel()
        noticeJob = scope.launch {
            delay(4_000)
            notice.value = null
        }
    }

    // ── Saving ──────────────────────────────────────────────────────────────────────────────────

    fun saveIfNeeded() {
        if (hasUnsavedChanges.value && conflict.value == null) queueSave()
    }

    private fun queueSave() { scope.launch { save() } }

    /** Saves now, serialised behind any save already running; awaitable for leaving the editor. */
    suspend fun save() = saveLock.withLock {
        val f = file.value ?: return@withLock
        val key = dek ?: return@withLock
        if (!isEditable || conflict.value != null) return@withLock
        if (!hasUnsavedChanges.value && contentVersion != null) return@withLock
        isSaving.value = true
        saveError.value = null
        hasUnsavedChanges.value = false
        try {
            val name = info.value?.name ?: item.name
            val result = content.save(f, item.copy(name = name), key, contentVersion, sourcePackage)
            contentVersion = result.contentVersion ?: contentVersion
            onSaved(result)
        } catch (e: SheetsApi.ContentVersionConflict) {
            hasUnsavedChanges.value = true
            conflict.value = Conflict(e.current)
        } catch (e: kotlinx.coroutines.CancellationException) {
            hasUnsavedChanges.value = true
            throw e
        } catch (e: Exception) {
            hasUnsavedChanges.value = true
            saveError.value = e.message ?: "Save failed"
        } finally {
            isSaving.value = false
            bump()
        }
    }

    /** Keeps this copy: the guard rearmed against the version the user chose to overwrite. */
    fun keepLocalCopy() {
        contentVersion = conflict.value?.serverVersion
        conflict.value = null
        queueSave()
    }

    /** The workbook as it stands, as the .xlsx a save would write — for sending a copy to another app. */
    fun exportedBytes(): ByteArray? = file.value?.encoded(sourcePackage)

    /** For a test or a caller with its own clock: no autosave pending. */
    fun cancelAutosave() { autosave?.cancel() }
}

/** One undoable step: cell before/after maps, or the whole list of tabs. */
class WorkbookEdit(
    val change: Change, val sheetBefore: Int, val sheetAfter: Int,
    val selectionBefore: SheetSelection?, val selectionAfter: SheetSelection?, val name: String,
) {
    sealed class Change {
        class Cells(val before: Map<Int, CellChanges>, val after: Map<Int, CellChanges>, val tablesBefore: Map<Int, List<TableRegion>>, val tablesAfter: Map<Int, List<TableRegion>>) : Change()
        class Workbook(val before: List<SheetData>, val after: List<SheetData>) : Change()
    }
}

class EditHistory {
    private val undoStack = ArrayDeque<WorkbookEdit>()
    private val redoStack = ArrayDeque<WorkbookEdit>()
    val canUndo get() = undoStack.isNotEmpty()
    val canRedo get() = redoStack.isNotEmpty()
    val undoName get() = undoStack.lastOrNull()?.name
    val redoName get() = redoStack.lastOrNull()?.name

    fun record(edit: WorkbookEdit) {
        undoStack.addLast(edit)
        if (undoStack.size > LIMIT) undoStack.removeFirst()
        redoStack.clear()
    }

    fun undo() = undoStack.removeLastOrNull()?.also { redoStack.addLast(it) }
    fun redo() = redoStack.removeLastOrNull()?.also { undoStack.addLast(it) }
    fun clear() { undoStack.clear(); redoStack.clear() }

    companion object { const val LIMIT = 100 }
}
