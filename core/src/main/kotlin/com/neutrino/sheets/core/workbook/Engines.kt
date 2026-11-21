package com.neutrino.sheets.core.workbook

import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/**
 * Relative references in a formula, so copying `=A1+B1` down a row gives `=A2+B2`. A formula is
 * encoded against its own cell (`[0][-1]`) and decoded against the destination; `$`-anchored parts
 * stay put and a reference pushed off the sheet becomes `#REF!`. Sheet prefixes (`Sheet2!`) are
 * masked first so their names are not read as references.
 */
object FormulaReferences {
    fun isFormula(text: String) = text.trim().startsWith("=")

    private val sheetPrefix = Regex("('[^']+'!|[A-Za-z_][A-Za-z0-9_]*!)")
    private val reference = Regex("(\\$?)([A-Za-z]+)(\\$?)([0-9]+)")
    private val token = Regex("\\[(\\$[A-Z]+|-?[0-9]+)\\]\\[(\\$[0-9]+|-?[0-9]+)\\]")
    private val placeholder = Regex("\u0000([0-9]+)\u0000")

    fun encode(raw: String, at: CellAddress): String {
        if (!raw.startsWith("=")) return raw
        val prefixes = ArrayList<String>()
        val protected = sheetPrefix.replace(raw) { m -> prefixes += m.value; "\u0000${prefixes.size - 1}\u0000" }
        val encoded = reference.replace(protected) { m ->
            val (dollarColumn, rawLetters, dollarRow, digits) = m.destructured
            val letters = rawLetters.uppercase()
            val column = if (dollarColumn.isEmpty()) "[${(CellAddress.columnIndex(letters) ?: 0) - at.column}]" else "[\$$letters]"
            val row = if (dollarRow.isEmpty()) "[${(digits.toIntOrNull() ?: 0) - at.row}]" else "[\$$digits]"
            column + row
        }
        return placeholder.replace(encoded) { m -> prefixes.getOrNull(m.groupValues[1].toInt()) ?: "" }
    }

    fun decode(encoded: String, at: CellAddress): String {
        if (!encoded.startsWith("=")) return encoded
        return token.replace(encoded) { m ->
            val (columnToken, rowToken) = m.destructured
            val column = if (columnToken.startsWith("$")) columnToken else {
                val c = at.column + (columnToken.toIntOrNull() ?: 0)
                if (c < 1) return@replace "#REF!"
                CellAddress.columnLetters(c)
            }
            val row = if (rowToken.startsWith("$")) rowToken else {
                val r = at.row + (rowToken.toIntOrNull() ?: 0)
                if (r < 1) return@replace "#REF!"
                r.toString()
            }
            column + row
        }
    }

    fun shift(raw: String, from: CellAddress, to: CellAddress): String = if (!raw.startsWith("=")) raw else decode(encode(raw, from), to)
}

/** The fill handle: extends a selection by series (numbers, dates, month and day names, `Item 1`) or by repeating it. */
object FillEngine {

    fun fill(source: CellRange, target: CellRange, cells: Map<String, SavedCell>): CellChanges {
        val vertical = target.columns == source.columns && target.rows != source.rows
        val horizontal = target.rows == source.rows && target.columns != source.columns
        if (vertical == horizontal) return emptyMap()
        val changes = LinkedHashMap<String, SavedCell?>()
        if (vertical) {
            for (column in source.columns) {
                val ascending = source.rows.map { CellAddress(column, it) }
                if (target.end.row > source.end.row) changes += extend(ascending, ((source.end.row + 1)..target.end.row).map { CellAddress(column, it) }, cells)
                if (target.start.row < source.start.row) changes += extend(ascending.reversed(), (target.start.row until source.start.row).reversed().map { CellAddress(column, it) }, cells)
            }
        } else {
            for (row in source.rows) {
                val ascending = source.columns.map { CellAddress(it, row) }
                if (target.end.column > source.end.column) changes += extend(ascending, ((source.end.column + 1)..target.end.column).map { CellAddress(it, row) }, cells)
                if (target.start.column < source.start.column) changes += extend(ascending, (target.start.column until source.start.column).reversed().map { CellAddress(it, row) }, cells)
            }
        }
        return changes
    }

    private fun extend(sources: List<CellAddress>, destinations: List<CellAddress>, cells: Map<String, SavedCell>): CellChanges {
        if (sources.isEmpty() || destinations.isEmpty()) return emptyMap()
        val sourceCells = sources.map { cells[it.id] }
        val texts = sourceCells.map { it?.editableText ?: "" }
        val series = series(texts)
        val out = LinkedHashMap<String, SavedCell?>()
        destinations.forEachIndexed { offset, destination ->
            val index = offset % sources.size
            val template = sourceCells[index]
            val existing = cells[destination.id]
            if (template == null || template.isEmpty) {
                out[destination.id] = null
                return@forEachIndexed
            }
            var cell = SavedCell(destination.id, cellStyle = template.cellStyle, colSpan = existing?.colSpan, rowSpan = existing?.rowSpan, mergeAnchor = existing?.mergeAnchor)
            val raw = template.raw
            cell = if (raw != null && FormulaReferences.isFormula(raw)) {
                cell.copy(raw = FormulaReferences.shift(raw, sources[index], destination), value = null)
            } else {
                val text = text(series, sources.size + offset, texts, index).ifEmpty { null }
                cell.copy(raw = text, value = text)
            }
            out[destination.id] = if (cell.isEmpty) null else cell
        }
        return out
    }

    sealed class Series {
        data class Linear(val intercept: Double, val slope: Double, val decimals: Int) : Series()
        data class Numbered(val prefix: String, val intercept: Double, val slope: Double, val width: Int) : Series()
        data class Names(val list: NameList, val start: Int, val step: Int, val casing: Casing) : Series()
        data class Dates(val start: Double, val step: Double, val style: DateStyle) : Series()
        object Repeating : Series()
    }

    enum class Casing { CANONICAL, UPPER, LOWER }

    enum class DateStyle(pattern: String) {
        ISO("yyyy-MM-dd"), AMERICAN("MM/dd/yyyy");

        val formatter: DateTimeFormatter = DateTimeFormatter.ofPattern(pattern, Locale.US)

        companion object {
            fun detect(value: String): DateStyle? {
                val dashed = value.split("-")
                if (dashed.size == 3 && dashed[0].length == 4 && dashed.all { p -> p.isNotEmpty() && p.all(Char::isDigit) }) return ISO
                val slashed = value.split("/")
                if (slashed.size == 3 && slashed.all { p -> p.isNotEmpty() && p.all(Char::isDigit) }) return AMERICAN
                return null
            }
        }
    }

    enum class NameList(val entries: List<String>) {
        MONTHS_LONG(listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")),
        MONTHS_SHORT(listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")),
        WEEKDAYS_LONG(listOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")),
        WEEKDAYS_SHORT(listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"));

        fun index(value: String): Int? = entries.indexOfFirst { it.equals(value, ignoreCase = true) }.takeIf { it >= 0 }
    }

    fun series(values: List<String>): Series {
        val trimmed = values.map { it.trim() }
        if (trimmed.none { it.isNotEmpty() }) return Series.Repeating
        return linear(trimmed) ?: dates(trimmed) ?: names(trimmed) ?: numbered(trimmed) ?: Series.Repeating
    }

    fun text(series: Series, position: Int, source: List<String>, index: Int): String = when (series) {
        is Series.Linear -> number(series.intercept + series.slope * position, series.decimals)
        is Series.Numbered -> {
            val value = Math.round(series.intercept + series.slope * position).toInt()
            val digits = abs(value).toString()
            series.prefix + (if (value < 0) "-" else "") + digits.padStart(series.width, '0')
        }
        is Series.Names -> {
            val n = series.list.entries.size
            val raw = (series.start + series.step * position) % n
            val name = series.list.entries[if (raw < 0) raw + n else raw]
            when (series.casing) { Casing.CANONICAL -> name; Casing.UPPER -> name.uppercase(); Casing.LOWER -> name.lowercase() }
        }
        is Series.Dates -> series.style.formatter.format(LocalDateTime.ofInstant(CellFormatter.dateFromExcelSerial(series.start + series.step * position), ZoneOffset.UTC))
        Series.Repeating -> source.getOrElse(index) { "" }
    }

    private fun linear(values: List<String>): Series? {
        val numbers = ArrayList<Double>()
        var decimals = 0
        for (v in values) {
            if (!isPlainNumber(v)) return null
            numbers += v.toDoubleOrNull() ?: return null
            val dot = v.indexOf('.')
            if (dot >= 0) decimals = maxOf(decimals, v.substring(dot + 1).takeWhile(Char::isDigit).length)
        }
        val (intercept, slope) = fit(numbers)
        return Series.Linear(intercept, slope, decimals)
    }

    private fun dates(values: List<String>): Series? {
        val serials = ArrayList<Double>()
        var style: DateStyle? = null
        for (v in values) {
            val matched = DateStyle.detect(v) ?: return null
            val serial = CellFormatter.serialFromDateString(v) ?: return null
            if (serial != Math.rint(serial)) return null
            if (style != null && style != matched) return null
            style = matched
            serials += serial
        }
        val s = style ?: return null
        if (serials.size == 1) return Series.Dates(serials[0], 1.0, s)
        val (intercept, slope) = fit(serials)
        if (slope != Math.rint(slope)) return null
        return Series.Dates(intercept, slope, s)
    }

    private fun names(values: List<String>): Series? {
        for (list in NameList.entries) {
            val indices = values.map { list.index(it) ?: return@map null }
            if (indices.any { it == null }) continue
            val idx = indices.filterNotNull()
            val n = list.entries.size
            var step = 1
            if (idx.size > 1) {
                val deltas = idx.zipWithNext { a, b -> (b - a + n) % n }
                if (deltas.any { it != deltas[0] }) continue
                step = if (deltas[0] > n / 2) deltas[0] - n else deltas[0]
            }
            val first = values[0]
            val casing = when {
                first == first.uppercase() && first != first.lowercase() -> Casing.UPPER
                first == first.lowercase() -> Casing.LOWER
                else -> Casing.CANONICAL
            }
            return Series.Names(list, idx[0], step, casing)
        }
        return null
    }

    private fun numbered(values: List<String>): Series? {
        var prefix: String? = null
        val numbers = ArrayList<Double>()
        var width = 0
        for (v in values) {
            val digits = v.takeLastWhile(Char::isDigit)
            if (digits.isEmpty()) return null
            val head = v.dropLast(digits.length)
            if (head.isEmpty()) return null
            if (prefix != null && prefix != head) return null
            prefix = head
            width = maxOf(width, if (digits.first() == '0') digits.length else 0)
            numbers += digits.toDouble()
        }
        val p = prefix ?: return null
        val (intercept, slope) = if (numbers.size > 1) fit(numbers) else numbers[0] to 1.0
        return Series.Numbered(p, intercept, slope, width)
    }

    /** Least squares over index → value: a single value has slope 0 (it repeats). */
    private fun fit(values: List<Double>): Pair<Double, Double> {
        if (values.size < 2) return (values.firstOrNull() ?: 0.0) to 0.0
        val n = values.size.toDouble()
        val meanX = (n - 1) / 2
        val meanY = values.sum() / n
        var covariance = 0.0
        var variance = 0.0
        values.forEachIndexed { i, v ->
            val dx = i - meanX
            covariance += dx * (v - meanY)
            variance += dx * dx
        }
        val slope = if (variance == 0.0) 0.0 else covariance / variance
        return (meanY - slope * meanX) to slope
    }

    private fun isPlainNumber(value: String): Boolean {
        if (value.isEmpty()) return false
        val body = if (value.startsWith("+") || value.startsWith("-")) value.drop(1) else value
        val parts = body.split(".")
        if (parts.size > 2 || body.none(Char::isDigit)) return false
        return parts.all { p -> p.all(Char::isDigit) }
    }

    private fun number(value: Double, decimals: Int): String = String.format(Locale.ROOT, "%.${decimals}f", if (value == 0.0) 0.0 else value)
}

/** Merging and unmerging: an anchor carries the spans and every covered cell points back at it. */
object MergeEngine {
    fun mergeOrigin(at: CellAddress, cells: Map<String, SavedCell>): CellRange? {
        val cell = cells[at.id] ?: return null
        val columns = cell.colSpan ?: 1
        val rows = cell.rowSpan ?: 1
        if (columns <= 1 && rows <= 1) return null
        return CellRange(at, at.offset(columns - 1, rows - 1))
    }

    fun merge(range: CellRange, cells: Map<String, SavedCell>): CellChanges {
        if (range.cellCount <= 1) return emptyMap()
        val anchorId = range.start.id
        val out = LinkedHashMap<String, SavedCell?>()
        out[anchorId] = (cells[anchorId] ?: SavedCell(anchorId)).copy(colSpan = range.columnCount, rowSpan = range.rowCount, mergeAnchor = null)
        for (a in range.addresses) {
            if (a.id == anchorId) continue
            out[a.id] = (cells[a.id] ?: SavedCell(a.id)).copy(mergeAnchor = anchorId, colSpan = null, rowSpan = null)
        }
        return out
    }

    fun unmerge(range: CellRange, cells: Map<String, SavedCell>): CellChanges {
        val anchorId = range.start.id
        val out = LinkedHashMap<String, SavedCell?>()
        cells[anchorId]?.let { out[anchorId] = it.copy(colSpan = null, rowSpan = null).takeIf { c -> !c.isEmpty } }
        for (a in range.addresses) {
            if (a.id == anchorId) continue
            val cell = cells[a.id] ?: continue
            out[a.id] = cell.copy(mergeAnchor = null).takeIf { !it.isEmpty }
        }
        return out
    }
}

/** Sorting rows by one column: numbers numerically, text case- and accent-insensitively, blanks last, stable. */
object SortEngine {
    private val collator = java.text.Collator.getInstance(Locale.US).apply { strength = java.text.Collator.PRIMARY }

    fun compare(a: String, b: String, ascending: Boolean): Int {
        if (a.isEmpty() && b.isNotEmpty()) return 1
        if (b.isEmpty() && a.isNotEmpty()) return -1
        val left = CellFormatter.parseNumber(a)
        val right = CellFormatter.parseNumber(b)
        val result = if (left != null && right != null) left.compareTo(right).coerceIn(-1, 1) else collator.compare(a, b).coerceIn(-1, 1)
        return if (ascending || result == 0) result else -result
    }

    enum class Refusal { CONTAINS_MERGED_CELLS, NOTHING_TO_SORT }

    sealed class Outcome {
        data class Sorted(val changes: CellChanges) : Outcome()
        data class Refused(val reason: Refusal) : Outcome()
    }

    fun sortRows(range: CellRange, column: Int, ascending: Boolean, cells: Map<String, SavedCell>): Outcome {
        if (range.rowCount <= 1 || column !in range.columns) return Outcome.Refused(Refusal.NOTHING_TO_SORT)
        for (a in range.addresses) {
            val c = cells[a.id] ?: continue
            if ((c.colSpan ?: 1) > 1 || (c.rowSpan ?: 1) > 1 || c.mergeAnchor != null) return Outcome.Refused(Refusal.CONTAINS_MERGED_CELLS)
        }
        val rows = range.rows.map { row ->
            val occupants = range.columns.mapNotNull { c -> cells[CellAddress(c, row).id]?.let { c to it } }.toMap()
            val key = occupants[column]?.let { it.value ?: it.raw } ?: ""
            key to occupants
        }
        val order = rows.withIndex().sortedWith { l, r -> compare(l.value.first, r.value.first, ascending).takeIf { it != 0 } ?: l.index.compareTo(r.index) }
        if (order.map { it.index } == order.indices.toList()) return Outcome.Sorted(emptyMap())
        val out = LinkedHashMap<String, SavedCell?>()
        order.forEachIndexed { position, entry ->
            val row = range.start.row + position
            for (c in range.columns) {
                val id = CellAddress(c, row).id
                val cell = entry.value.second[c]
                if (cell == null) {
                    if (cells[id] != null) out[id] = null
                    continue
                }
                val moved = cell.copy(id = id)
                if (cells[id] != moved) out[id] = moved
            }
        }
        return Outcome.Sorted(out)
    }
}

/** Per-column filters: the values a column may show, by `raw`. Session state, like the web's. */
data class ColumnFilters(val allowed: Map<Int, Set<String>> = emptyMap()) {
    val isEmpty get() = allowed.isEmpty()
    fun isFiltered(column: Int) = column in allowed
    fun with(column: Int, values: Set<String>?) = ColumnFilters(if (values == null) allowed - column else allowed + (column to values))

    fun hiddenRows(cells: Map<String, SavedCell>, rows: Int): Set<Int> {
        if (allowed.isEmpty() || rows <= 0) return emptySet()
        return (1..rows).filter { row -> allowed.any { (column, values) -> (cells[CellAddress(column, row).id]?.raw ?: "") !in values } }.toSet()
    }

    companion object {
        /** Every distinct value in a column, blank included, in sort order. */
        fun values(column: Int, cells: Map<String, SavedCell>, rows: Int): List<String> {
            val values = hashSetOf("")
            for (row in 1..maxOf(0, rows)) values += cells[CellAddress(column, row).id]?.raw ?: ""
            return values.sortedWith { a, b -> SortEngine.compare(a, b, true) }
        }
    }
}

/** Find and replace over what was typed (`raw`), which is what the formula bar shows. */
object FindReplace {
    data class Options(val matchCase: Boolean = false, val entireCell: Boolean = false)

    fun matches(cells: Map<String, SavedCell>, query: String, options: Options): List<CellAddress> {
        if (query.isEmpty()) return emptyList()
        return cells.mapNotNull { (id, cell) -> CellAddress.parse(id)?.takeIf { matches(cell.raw ?: "", query, options) } }.sorted()
    }

    fun matches(text: String, query: String, options: Options): Boolean {
        if (query.isEmpty()) return false
        return if (options.entireCell) text.equals(query, ignoreCase = !options.matchCase) else text.contains(query, ignoreCase = !options.matchCase)
    }

    fun replacing(text: String, query: String, replacement: String, options: Options, all: Boolean): String {
        if (query.isEmpty()) return text
        val out = StringBuilder()
        var from = 0
        while (true) {
            val i = text.indexOf(query, from, ignoreCase = !options.matchCase)
            if (i < 0) break
            out.append(text, from, i).append(replacement)
            from = i + query.length
            if (!all) break
        }
        return out.append(text.substring(from)).toString()
    }

    fun replaced(cell: SavedCell, query: String, replacement: String, options: Options, all: Boolean): SavedCell? {
        val raw = replacing(cell.raw ?: "", query, replacement, options, all)
        val updated = cell.copy(raw = raw.ifEmpty { null }, value = if (raw.isEmpty() || FormulaReferences.isFormula(raw)) null else raw)
        return updated.takeIf { !it.isEmpty }
    }
}

/** The status bar under a multi-cell selection: sum, average, count. */
data class RangeSummary(val count: Int, val numericCount: Int, val sum: Double) {
    val average get() = if (numericCount > 0) sum / numericCount else null
    val isEmpty get() = count == 0

    val statusText: String
        get() {
            val parts = ArrayList<String>()
            if (numericCount > 0) {
                parts += "Sum ${format(sum)}"
                average?.let { parts += "Avg ${format(it)}" }
            }
            parts += "Count $count"
            return parts.joinToString("  ·  ")
        }

    private fun format(v: Double) = CellFormatter.decimal(v, if (v == Math.rint(v)) 0 else 2, true)

    companion object {
        fun of(range: CellRange, cells: Map<String, SavedCell>): RangeSummary {
            var count = 0
            var numeric = 0
            var sum = 0.0
            fun accumulate(c: SavedCell) {
                if (c.isMergedAway) return
                val text = c.value ?: c.raw ?: ""
                if (text.isEmpty()) return
                count++
                CellFormatter.parseNumber(text)?.let { numeric++; sum += it }
            }
            // Walk whichever is smaller: a selected column is 65,536 cells over a sheet of a hundred.
            if (range.cellCount <= cells.size) range.addresses.forEach { a -> cells[a.id]?.let(::accumulate) }
            else cells.forEach { (id, c) -> if (CellAddress.parse(id)?.let { it in range } == true) accumulate(c) }
            return RangeSummary(count, numeric, sum)
        }
    }
}

/** Adding, renaming, colouring, duplicating, deleting and moving tabs — pure, so undo is a swap of lists. */
object SheetTabs {
    data class Outcome(val sheets: List<SheetData>, val activeIndex: Int)

    val colors = listOf("Red" to "#ef4444", "Orange" to "#f97316", "Amber" to "#f59e0b", "Green" to "#22c55e", "Teal" to "#14b8a6",
        "Blue" to "#3b82f6", "Indigo" to "#6366f1", "Purple" to "#a855f7", "Pink" to "#ec4899", "Slate" to "#64748b")

    fun nextName(sheets: List<SheetData>): String {
        val taken = sheets.mapNotNull { it.name }.toSet()
        var n = sheets.size + 1
        while ("Sheet $n" in taken) n++
        return "Sheet $n"
    }

    fun copyName(sheet: SheetData, index: Int, sheets: List<SheetData>): String {
        val base = sheet.name ?: "Sheet ${index + 1}"
        val taken = sheets.mapNotNull { it.name }.toSet()
        val first = "$base (copy)"
        if (first !in taken) return first
        var n = 2
        while ("$first $n" in taken) n++
        return "$first $n"
    }

    fun add(sheets: List<SheetData>) = (sheets + SheetData(name = nextName(sheets))).let { Outcome(it, it.size - 1) }

    fun rename(sheets: List<SheetData>, index: Int, name: String): List<SheetData> {
        val trimmed = name.trim()
        if (index !in sheets.indices || trimmed.isEmpty()) return sheets
        return sheets.toMutableList().also { it[index] = it[index].copy(name = trimmed) }
    }

    fun setColor(sheets: List<SheetData>, index: Int, color: String?): List<SheetData> =
        if (index !in sheets.indices) sheets else sheets.toMutableList().also { it[index] = it[index].copy(color = color) }

    fun duplicate(sheets: List<SheetData>, index: Int): Outcome {
        if (index !in sheets.indices) return Outcome(sheets, index)
        val copy = sheets[index].copy(name = copyName(sheets[index], index, sheets))
        return Outcome(sheets.toMutableList().also { it.add(index + 1, copy) }, index + 1)
    }

    fun delete(sheets: List<SheetData>, index: Int, activeIndex: Int): Outcome? {
        if (sheets.size <= 1 || index !in sheets.indices) return null
        val out = sheets.toMutableList().also { it.removeAt(index) }
        val active = when {
            activeIndex == index -> maxOf(0, index - 1)
            activeIndex > index -> activeIndex - 1
            else -> activeIndex
        }
        return Outcome(out, minOf(active, out.size - 1))
    }

    fun move(sheets: List<SheetData>, index: Int, offset: Int, activeIndex: Int): Outcome? {
        val target = index + offset
        if (index !in sheets.indices || target !in sheets.indices || offset == 0) return null
        val out = sheets.toMutableList()
        out[index] = sheets[target]
        out[target] = sheets[index]
        val active = when (activeIndex) { index -> target; target -> index; else -> activeIndex }
        return Outcome(out, active)
    }
}
