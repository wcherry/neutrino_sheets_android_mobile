package com.neutrino.sheets.core.editor

import com.neutrino.sheets.core.workbook.CellAddress
import com.neutrino.sheets.core.workbook.CellRange
import com.neutrino.sheets.core.workbook.SavedCell
import com.neutrino.sheets.core.workbook.SheetData

/**
 * Where every row and column sits, in unzoomed pixels — the iOS `GridGeometry`, and the web's
 * `CELL_W`/`CELL_H`/`ROW_HDR_W`/`COL_HDR_H`, so a sheet is laid out the same everywhere. Prefix sums
 * make position → index a binary search; a hidden row (height 0, or filtered out) simply has no
 * extent, so hit testing, drawing and selection never treat it specially.
 */
class GridGeometry(sheet: SheetData?, hiddenRows: Set<Int> = emptySet(), minimumColumns: Int = 26, minimumRows: Int = 100) {
    val columns: Int
    val rows: Int
    private val columnOffsets: DoubleArray
    private val rowOffsets: DoubleArray

    init {
        val used = sheet?.usedBounds
        columns = maxOf(minimumColumns, (used?.second ?: 0) + 5).coerceAtMost(CellAddress.MAX_COLUMN)
        rows = maxOf(minimumRows, (used?.first ?: 0) + 20).coerceAtMost(CellAddress.MAX_ROW)
        columnOffsets = DoubleArray(columns + 1)
        for (i in 0 until columns) columnOffsets[i + 1] = columnOffsets[i] + maxOf(0.0, sheet?.width(i + 1) ?: COLUMN_WIDTH)
        rowOffsets = DoubleArray(rows + 1)
        for (i in 0 until rows) {
            val h = if ((i + 1) in hiddenRows) 0.0 else sheet?.height(i + 1) ?: ROW_HEIGHT
            rowOffsets[i + 1] = rowOffsets[i] + maxOf(0.0, h)
        }
    }

    val contentWidth get() = columnOffsets[columns]
    val contentHeight get() = rowOffsets[rows]

    fun x(column: Int) = columnOffsets[(column - 1).coerceIn(0, columns)]
    fun y(row: Int) = rowOffsets[(row - 1).coerceIn(0, rows)]
    fun width(column: Int) = if (column in 1..columns) columnOffsets[column] - columnOffsets[column - 1] else COLUMN_WIDTH
    fun height(row: Int) = if (row in 1..rows) rowOffsets[row] - rowOffsets[row - 1] else ROW_HEIGHT

    /** The column whose span holds `x`; past the end, the last one. Zero-width columns are skipped. */
    fun column(x: Double) = index(x, columnOffsets, columns)
    fun row(y: Double) = index(y, rowOffsets, rows)

    fun address(x: Double, y: Double) = CellAddress(column(x), row(y))

    /** Left, top, right, bottom of a cell or a merge anchored at it. */
    fun rect(at: CellAddress, colSpan: Int = 1, rowSpan: Int = 1): DoubleArray {
        val right = minOf(at.column + colSpan - 1, columns)
        val bottom = minOf(at.row + rowSpan - 1, rows)
        return doubleArrayOf(x(at.column), y(at.row), columnOffsets[right.coerceIn(0, columns)], rowOffsets[bottom.coerceIn(0, rows)])
    }

    /** A range's rectangle, grown to take in any merge it cuts through the edge of. */
    fun rect(range: CellRange, cells: Map<String, SavedCell>? = null): DoubleArray {
        var r = range
        if (cells != null && range.cellCount <= MERGE_SCAN_LIMIT) {
            for (a in range.addresses) {
                val c = cells[a.id] ?: continue
                val anchor = c.mergeAnchor?.let(CellAddress::parse)?.let { cells[it.id]?.let { ac -> it to ac } } ?: (a to c)
                val span = CellRange(anchor.first, anchor.first.offset((anchor.second.colSpan ?: 1) - 1, (anchor.second.rowSpan ?: 1) - 1))
                if ((anchor.second.colSpan ?: 1) > 1 || (anchor.second.rowSpan ?: 1) > 1) r = r.union(span)
            }
        }
        return doubleArrayOf(x(r.start.column), y(r.start.row), columnOffsets[minOf(r.end.column, columns)], rowOffsets[minOf(r.end.row, rows)])
    }

    /** First and last visible column and row for a viewport in content coordinates. */
    fun visibleColumns(left: Double, right: Double) = column(left)..column(right)
    fun visibleRows(top: Double, bottom: Double) = row(top)..row(bottom)

    private fun index(position: Double, offsets: DoubleArray, extent: Int): Int {
        if (position <= 0) return firstVisible(offsets, extent)
        if (position >= offsets[extent]) return extent
        var lo = 0
        var hi = extent
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (offsets[mid + 1] <= position) lo = mid + 1 else hi = mid
        }
        return lo + 1
    }

    private fun firstVisible(offsets: DoubleArray, extent: Int): Int {
        for (i in 0 until extent) if (offsets[i + 1] > offsets[i]) return i + 1
        return 1
    }

    companion object {
        const val COLUMN_WIDTH = 100.0
        const val ROW_HEIGHT = 28.0
        const val ROW_HEADER_WIDTH = 50.0
        const val COLUMN_HEADER_HEIGHT = 26.0
        const val FONT_SIZE = 13.0
        const val MERGE_SCAN_LIMIT = 1024L
    }
}
