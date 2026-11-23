package com.neutrino.sheets.core.workbook

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

enum class Axis { ROW, COLUMN }
enum class Operation { INSERT, DELETE }

/**
 * Inserting and deleting rows and columns: cells move, merges grow, shrink or dissolve, widths and
 * heights follow their row or column, table regions shift and are restyled, and conditional-format
 * ranges move with the cells they colour. One index at a time; several are applied as a fold.
 */
object StructuralShift {

    fun shiftedId(id: String, axis: Axis, operation: Operation, index: Int): String? {
        val a = CellAddress.parse(id) ?: return id
        val coordinate = if (axis == Axis.ROW) a.row else a.column
        val moved = when (operation) {
            Operation.INSERT -> if (coordinate >= index) coordinate + 1 else coordinate
            Operation.DELETE -> when {
                coordinate == index -> return null
                coordinate > index -> coordinate - 1
                else -> coordinate
            }
        }
        return if (axis == Axis.ROW) CellAddress(a.column, moved).id else CellAddress(moved, a.row).id
    }

    fun shift(cells: Map<String, SavedCell>, axis: Axis, operation: Operation, index: Int): Map<String, SavedCell> {
        fun span(c: SavedCell) = if (axis == Axis.ROW) c.rowSpan else c.colSpan
        fun otherSpan(c: SavedCell) = if (axis == Axis.ROW) c.colSpan else c.rowSpan
        data class Anchor(val newId: String?, val newSpan: Int?)

        val anchors = HashMap<String, Anchor>()
        for ((id, cell) in cells) {
            val current = span(cell)?.takeIf { it > 1 } ?: continue
            val a = CellAddress.parse(id) ?: continue
            val start = if (axis == Axis.ROW) a.row else a.column
            val end = start + current - 1
            var newSpan: Int? = current
            if (start < index && index <= end) {
                newSpan = if (operation == Operation.INSERT) current + 1 else current - 1
                if (newSpan <= 1) newSpan = null
            }
            anchors[id] = Anchor(shiftedId(id, axis, operation, index), newSpan)
        }

        val shifted = HashMap<String, SavedCell>(cells.size)
        for ((id, cell) in cells) {
            val newId = shiftedId(id, axis, operation, index) ?: continue
            var moved = cell.copy(id = newId)
            anchors[id]?.let { moved = if (axis == Axis.ROW) moved.copy(rowSpan = it.newSpan) else moved.copy(colSpan = it.newSpan) }
            shifted[newId] = moved
        }

        val result = LinkedHashMap<String, SavedCell>(shifted.size)
        for ((newId, cell) in shifted) {
            val oldAnchor = cell.mergeAnchor
            if (oldAnchor == null) {
                result[newId] = cell
                continue
            }
            val info = anchors[oldAnchor]
            val (newAnchor, stillMerged) = if (info != null) {
                info.newId to ((info.newSpan ?: 1) > 1 || ((cells[oldAnchor]?.let(::otherSpan)) ?: 1) > 1)
            } else {
                val moved = shiftedId(oldAnchor, axis, operation, index)
                moved to (moved != null)
            }
            result[newId] = cell.copy(mergeAnchor = if (stillMerged) newAnchor else null)
        }
        return result
    }

    fun shift(range: CellRange, axis: Axis, operation: Operation, index: Int): CellRange? {
        var low = if (axis == Axis.ROW) range.start.row else range.start.column
        var high = if (axis == Axis.ROW) range.end.row else range.end.column
        when (operation) {
            Operation.INSERT -> if (index <= low) { low++; high++ } else if (index <= high) high++
            Operation.DELETE -> if (index < low) { low--; high-- } else if (index <= high) { high--; if (high < low) return null }
        }
        return if (axis == Axis.ROW) CellRange(CellAddress(range.start.column, low), CellAddress(range.end.column, high))
        else CellRange(CellAddress(low, range.start.row), CellAddress(high, range.end.row))
    }

    fun shiftSizes(sizes: Map<String, Double>, operation: Operation, index: Int): Map<String, Double> {
        val zeroBased = index - 1
        val out = LinkedHashMap<String, Double>()
        for ((key, value) in sizes) {
            val n = key.toIntOrNull()
            if (n == null) { out[key] = value; continue }
            when (operation) {
                Operation.INSERT -> out[(if (n >= zeroBased) n + 1 else n).toString()] = value
                Operation.DELETE -> if (n != zeroBased) out[(if (n > zeroBased) n - 1 else n).toString()] = value
            }
        }
        return out
    }

    /** A rule whose whole range was deleted goes with it; a range that cannot be read is left alone. */
    fun shiftConditionalFormats(value: JsonElement, axis: Axis, operation: Operation, index: Int): JsonElement {
        val rules = value as? JsonArray ?: return value
        return JsonArray(rules.mapNotNull { rule ->
            val fields = rule as? JsonObject ?: return@mapNotNull rule
            val range = (fields["range"] as? JsonPrimitive)?.content?.let(CellRange::parse) ?: return@mapNotNull rule
            val moved = shift(range, axis, operation, index) ?: return@mapNotNull null
            // A formula rule's own references move with the sheet, as the web's shiftCFRules does.
            val ruleSpec = fields["rule"] as? JsonObject
            val formula = (ruleSpec?.get("formula") as? JsonPrimitive)?.content
            val rule = if (ruleSpec != null && (ruleSpec["kind"] as? JsonPrimitive)?.content == "formula" && formula != null)
                mapOf("rule" to JsonObject(ruleSpec + ("formula" to JsonPrimitive(shiftFormula(formula, axis, operation, index))))) else emptyMap()
            JsonObject(fields + ("range" to JsonPrimitive(moved.address)) + rule)
        })
    }

    // A string literal, which is skipped, or a reference: an optional sheet prefix, then a cell or
    // range. The lookarounds keep names that merely contain a reference (`LOG10(`, `Q1_total`) out.
    // The web's FORMULA_REFERENCE, character for character.
    private val FORMULA_REFERENCE = Regex(
        "\"[^\"]*\"|(?<![A-Za-z0-9_\$.'])(?:('[^']+'|[A-Za-z_][A-Za-z0-9_]*)!)?(\\$?)([A-Za-z]+)(\\$?)(\\d+)(?::(\\$?)([A-Za-z]+)(\\$?)(\\d+))?(?![A-Za-z0-9_(!])",
    )

    /**
     * The web's `shiftFormula`: a reference moves with its cell, a range grows or shrinks around the
     * edit, a reference to a deleted cell becomes `#REF!`. `$` references move too — `$` pins a
     * reference against copying, not against the sheet changing under it. References with no sheet
     * prefix belong to the formula's own sheet, so they move only when that sheet is `target`.
     */
    fun shiftFormula(formula: String, axis: Axis, operation: Operation, index: Int, target: String? = null, formulaOnTarget: Boolean = true): String {
        val wanted = target?.lowercase()
        return FORMULA_REFERENCE.replace(formula) { m ->
            val g = m.groups
            val col1 = g[3]?.value ?: return@replace m.value // string literal
            val prefix = g[1]?.value
            val onTarget = if (prefix == null) formulaOnTarget else prefix.removePrefix("'").removeSuffix("'").lowercase() == wanted
            if (!onTarget) return@replace m.value
            val col2 = g[7]?.value
            val c1 = CellAddress.columnIndex(col1.uppercase()) ?: return@replace m.value; val r1 = g[5]!!.value.toInt()
            val c2 = if (col2 == null) c1 else CellAddress.columnIndex(col2.uppercase()) ?: return@replace m.value
            val r2 = g[9]?.value?.toInt() ?: r1
            val bounds = CellRange(CellAddress(minOf(c1, c2), minOf(r1, r2)), CellAddress(maxOf(c1, c2), maxOf(r1, r2)))
            val moved = shift(bounds, axis, operation, index) ?: return@replace "#REF!"
            if (moved == bounds) return@replace m.value
            // A range may be written back to front (B3:A1); it stays that way round.
            val d = { i: Int -> g[i]?.value ?: "" }
            val start = d(2) + CellAddress.columnLetters(if (c1 <= c2) moved.start.column else moved.end.column) + d(4) + (if (r1 <= r2) moved.start.row else moved.end.row)
            val sheet = if (prefix == null) "" else "$prefix!"
            if (col2 == null) "$sheet$start"
            else "$sheet$start:" + d(6) + CellAddress.columnLetters(if (c1 <= c2) moved.end.column else moved.start.column) + d(8) + (if (r1 <= r2) moved.end.row else moved.start.row)
        }
    }

    /** A formula rewritten by a shift keeps its cached value — its references still name the same cells — unless one was deleted. */
    private fun SavedCell.shiftedFormula(axis: Axis, operation: Operation, index: Int, target: String?, onTarget: Boolean): SavedCell {
        val raw = raw ?: return this
        if (!FormulaReferences.isFormula(raw)) return this
        val moved = shiftFormula(raw, axis, operation, index, target, onTarget)
        if (moved == raw) return this
        return copy(raw = moved, value = if ("#REF!" in moved && "#REF!" !in raw) "#REF!" else value)
    }

    /** The same edit as another tab sees it: only references naming `target` move. Null when nothing did. */
    fun shiftReferencesFromOtherSheet(sheet: SheetData, target: String, axis: Axis, operation: Operation, index: Int): SheetData? {
        var changed = false
        val cells = sheet.cells.mapValues { (_, c) -> c.shiftedFormula(axis, operation, index, target, false).also { if (it !== c) changed = true } }
        return if (changed) sheet.copy(cells = cells) else null
    }

    fun apply(sheet: SheetData, axis: Axis, operation: Operation, index: Int): SheetData {
        val cells = shift(sheet.cells, axis, operation, index).mapValues { (_, c) -> c.shiftedFormula(axis, operation, index, sheet.name, true) }.toMutableMap()
        val regions = ArrayList<TableRegion>()
        for (region in sheet.tables.orEmpty()) {
            if (!region.isValid) continue
            val moved = shift(region.range, axis, operation, index) ?: continue
            regions += region.copy(minR = moved.start.row, maxR = moved.end.row, minC = moved.start.column, maxC = moved.end.column)
            // A table restyles itself over its new extent, so a row inserted inside it is banded.
            val style = TableStyles.style(region.styleId) as? TableStyle.Regular ?: continue
            for ((id, patch) in TableStyles.patches(style, moved)) {
                val cell = cells[id] ?: SavedCell(id)
                cells[id] = cell.copy(cellStyle = patch.applied(cell.cellStyle))
            }
        }
        val extra = sheet.extra["conditionalFormats"]?.let { sheet.extra + ("conditionalFormats" to shiftConditionalFormats(it, axis, operation, index)) } ?: sheet.extra
        return sheet.copy(
            cells = cells,
            tables = regions.ifEmpty { null },
            colWidths = if (axis == Axis.COLUMN) sheet.colWidths?.let { shiftSizes(it, operation, index) } else sheet.colWidths,
            rowHeights = if (axis == Axis.ROW) sheet.rowHeights?.let { shiftSizes(it, operation, index) } else sheet.rowHeights,
            extra = extra,
        )
    }

    fun apply(sheet: SheetData, axis: Axis, operation: Operation, indices: List<Int>) = indices.fold(sheet) { s, i -> apply(s, axis, operation, i) }
}

/** The table style gallery — the web's 84 styles plus Blank, by the same ids, so a styled range reads the same everywhere. */
sealed class TableStyle(val id: String, val name: String) {
    class Blank : TableStyle("blank", "Blank")

    class Regular(
        id: String, name: String,
        val headerBackground: String, val headerColor: String, val bandColorA: String, val bandColorB: String,
        val border: Borders, val headerRow: Boolean, val headerColumn: Boolean, val totalRow: Boolean,
    ) : TableStyle(id, name)

    data class Borders(
        val borderStyle: StyleUpdate<String>, val borderTop: StyleUpdate<String>, val borderRight: StyleUpdate<String>,
        val borderBottom: StyleUpdate<String>, val borderLeft: StyleUpdate<String>,
    ) {
        fun applied(patch: CellStylePatch) = patch.copy(borderStyle = borderStyle, borderTop = borderTop, borderRight = borderRight, borderBottom = borderBottom, borderLeft = borderLeft)
    }
}

object TableStyles {
    private val palette = listOf(
        Triple("Blue", "#1e40af", "#dbeafe"), Triple("Sky", "#0369a1", "#e0f2fe"), Triple("Cyan", "#0e7490", "#cffafe"),
        Triple("Teal", "#0f766e", "#ccfbf1"), Triple("Emerald", "#047857", "#d1fae5"), Triple("Green", "#15803d", "#dcfce7"),
        Triple("Lime", "#4d7c0f", "#ecfccb"), Triple("Amber", "#b45309", "#fef3c7"), Triple("Orange", "#c2410c", "#ffedd5"),
        Triple("Red", "#b91c1c", "#fee2e2"), Triple("Rose", "#be123c", "#ffe4e6"), Triple("Purple", "#6d28d9", "#ede9fe"),
        Triple("Indigo", "#4338ca", "#e0e7ff"), Triple("Slate", "#334155", "#f1f5f9"),
    )

    private data class Layout(val slug: String, val label: String, val headerRow: Boolean, val headerColumn: Boolean, val totalRow: Boolean)
    private val layouts = listOf(Layout("banded", "Banded", true, false, false), Layout("header-totals", "Header & Totals", true, true, true))

    private val S = StyleUpdate.Set("none")
    val uniform = TableStyle.Borders(StyleUpdate.Set("thin"), StyleUpdate.Clear, StyleUpdate.Clear, StyleUpdate.Clear, StyleUpdate.Clear)
    val none = TableStyle.Borders(S, S, S, S, S)
    val horizontal = TableStyle.Borders(S, StyleUpdate.Set("thin"), S, StyleUpdate.Set("thin"), S)
    private val variants = listOf(Triple("-no-border", " — No Border", none), Triple("-horizontal", " — Horizontal Lines", horizontal), Triple("", "", uniform))

    val all: List<TableStyle> = palette.flatMap { (hue, header, tint) ->
        layouts.flatMap { l ->
            variants.map { (idSuffix, nameSuffix, borders) ->
                TableStyle.Regular("${hue.lowercase()}-${l.slug}$idSuffix", "$hue ${l.label}$nameSuffix", header, "#ffffff", "#ffffff", tint,
                    borders, l.headerRow, l.headerColumn, l.totalRow)
            }
        }
    } + TableStyle.Blank()

    fun style(id: String) = all.firstOrNull { it.id == id }

    fun patches(style: TableStyle.Regular, range: CellRange): Map<String, CellStylePatch> {
        val header = style.border.applied(CellStylePatch(fontWeight = StyleUpdate.Set("bold"), color = StyleUpdate.Set(style.headerColor), backgroundColor = StyleUpdate.Set(style.headerBackground)))
        val headerOffset = if (style.headerRow) 1 else 0
        return range.addresses.associate { a ->
            val bodyRow = a.row - range.start.row - headerOffset
            val bandA = ((bodyRow % 2) + 2) % 2 == 0
            var patch = style.border.applied(CellStylePatch(fontWeight = StyleUpdate.Set("normal"), color = StyleUpdate.Clear,
                backgroundColor = StyleUpdate.Set(if (bandA) style.bandColorA else style.bandColorB)))
            if (style.headerColumn && a.column == range.start.column) patch = header
            if (style.headerRow && a.row == range.start.row) patch = header
            if (style.totalRow && a.row == range.end.row && range.end.row > range.start.row) patch = header
            a.id to patch
        }
    }
}

/**
 * A copied range: entries relative to its top-left, formulas encoded relative to their own cells so
 * they re-anchor where they land. Pasting into a selection that is a whole multiple of the copy
 * tiles it; anything else pastes at its natural size.
 */
data class SheetClipboard(val isCut: Boolean, val rows: Int, val columns: Int, val entries: List<Entry>) {
    data class Entry(val relativeRow: Int, val relativeColumn: Int, val raw: String?, val value: String?, val cellStyle: CellStyle?)

    fun target(origin: CellAddress, selection: CellRange): CellRange {
        val natural = CellRange(origin, origin.offset(maxOf(0, columns - 1), maxOf(0, rows - 1)))
        if (rows <= 0 || columns <= 0 || selection.cellCount <= 1 || selection.rowCount % rows != 0 || selection.columnCount % columns != 0) return natural
        return selection
    }

    fun changes(origin: CellAddress, selection: CellRange, existing: Map<String, SavedCell>): CellChanges {
        if (rows <= 0 || columns <= 0) return emptyMap()
        val target = target(origin, selection)
        val byPosition = entries.associateBy { it.relativeRow to it.relativeColumn }
        val out = LinkedHashMap<String, SavedCell?>()
        for (a in target.addresses) {
            val entry = byPosition[(a.row - target.start.row) % rows to (a.column - target.start.column) % columns] ?: continue
            val old = existing[a.id]
            var cell = SavedCell(a.id, cellStyle = entry.cellStyle, colSpan = old?.colSpan, rowSpan = old?.rowSpan, mergeAnchor = old?.mergeAnchor)
            cell = if (entry.raw != null && FormulaReferences.isFormula(entry.raw)) cell.copy(raw = FormulaReferences.decode(entry.raw, a), value = null)
            else cell.copy(raw = entry.raw, value = entry.value ?: entry.raw)
            out[a.id] = cell.takeIf { !it.isEmpty }
        }
        return out
    }

    /** What another app gets: values in a tab-separated grid. */
    val tabSeparatedText: String
        get() {
            val grid = Array(rows) { Array(columns) { "" } }
            for (e in entries) if (e.relativeRow < rows && e.relativeColumn < columns) grid[e.relativeRow][e.relativeColumn] = e.value ?: e.raw ?: ""
            return grid.joinToString("\n") { it.joinToString("\t") }
        }

    companion object {
        fun of(range: CellRange, cells: Map<String, SavedCell>, isCut: Boolean) = SheetClipboard(isCut, range.rowCount, range.columnCount,
            range.addresses.map { a ->
                val c = cells[a.id]
                Entry(a.row - range.start.row, a.column - range.start.column, c?.raw?.let { FormulaReferences.encode(it, a) }, c?.value, c?.cellStyle)
            })

        /** Text from another app: rows by line, columns by tab. */
        fun fromTabSeparated(text: String): SheetClipboard? {
            val lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n")
            val rows = if (lines.lastOrNull()?.isEmpty() == true) lines.dropLast(1) else lines
            if (rows.isEmpty()) return null
            val entries = ArrayList<Entry>()
            var columns = 0
            rows.forEachIndexed { r, line ->
                val fields = line.split("\t")
                columns = maxOf(columns, fields.size)
                fields.forEachIndexed { c, f -> entries += Entry(r, c, f.ifEmpty { null }, f.ifEmpty { null }, null) }
            }
            if (columns == 0) return null
            return SheetClipboard(false, rows.size, columns, entries)
        }
    }
}
