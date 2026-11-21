package com.neutrino.sheets.core.workbook

/** A cell's position, 1-based. Orders row first, as a reading eye does. */
data class CellAddress(val column: Int, val row: Int) : Comparable<CellAddress> {
    val id: String get() = "${columnLetters(column)}$row"

    override fun toString() = id

    /** Moved and clamped to the sheet. */
    fun offset(columns: Int = 0, rows: Int = 0) = CellAddress((column + columns).coerceIn(1, MAX_COLUMN), (row + rows).coerceIn(1, MAX_ROW))

    override fun compareTo(other: CellAddress) = if (row != other.row) row.compareTo(other.row) else column.compareTo(other.column)

    companion object {
        const val MAX_ROW = 65536
        const val MAX_COLUMN = 65536

        /** `B3` → (2, 3); null for anything that is not an A1 address. */
        fun parse(id: String): CellAddress? {
            var i = 0
            while (i < id.length && id[i] in 'A'..'Z') i++
            if (i == 0 || i == id.length) return null
            val digits = id.substring(i)
            if (!digits.all { it.isDigit() }) return null
            val row = digits.toIntOrNull()?.takeIf { it >= 1 } ?: return null
            val column = columnIndex(id.substring(0, i)) ?: return null
            return CellAddress(column, row)
        }

        fun columnLetters(index: Int): String {
            if (index < 1) return ""
            var remaining = index
            val out = StringBuilder()
            while (remaining > 0) {
                remaining--
                out.insert(0, 'A' + remaining % 26)
                remaining /= 26
            }
            return out.toString()
        }

        fun columnIndex(letters: String): Int? {
            if (letters.isEmpty() || letters.length > 4) return null
            var index = 0
            for (c in letters) {
                if (c !in 'A'..'Z') return null
                index = index * 26 + (c - 'A' + 1)
            }
            return index.takeIf { it in 1..MAX_COLUMN }
        }
    }
}

/** A rectangle of cells, normalised so `start` is top-left. */
class CellRange(a: CellAddress, b: CellAddress) {
    val start = CellAddress(minOf(a.column, b.column), minOf(a.row, b.row))
    val end = CellAddress(maxOf(a.column, b.column), maxOf(a.row, b.row))

    val isSingleCell get() = start == end
    val address get() = if (isSingleCell) start.id else "${start.id}:${end.id}"
    val columns get() = start.column..end.column
    val rows get() = start.row..end.row
    val columnCount get() = end.column - start.column + 1
    val rowCount get() = end.row - start.row + 1
    val cellCount get() = columnCount.toLong() * rowCount

    operator fun contains(a: CellAddress) = a.column in columns && a.row in rows

    /** Every address, row by row. */
    val addresses: List<CellAddress> get() = rows.flatMap { r -> columns.map { c -> CellAddress(c, r) } }

    fun union(other: CellRange) = CellRange(
        CellAddress(minOf(start.column, other.start.column), minOf(start.row, other.start.row)),
        CellAddress(maxOf(end.column, other.end.column), maxOf(end.row, other.end.row)),
    )

    override fun equals(other: Any?) = other is CellRange && start == other.start && end == other.end
    override fun hashCode() = start.hashCode() * 31 + end.hashCode()
    override fun toString() = address

    companion object {
        /** `A1:B3` or `C2`. */
        fun parse(text: String): CellRange? {
            val parts = text.split(":")
            return when (parts.size) {
                1 -> CellAddress.parse(parts[0])?.let { CellRange(it, it) }
                2 -> {
                    val a = CellAddress.parse(parts[0]) ?: return null
                    val b = CellAddress.parse(parts[1]) ?: return null
                    CellRange(a, b)
                }
                else -> null
            }
        }
    }
}

/** Where the selection started and where its moving corner is. */
data class SheetSelection(val anchor: CellAddress, val active: CellAddress = anchor) {
    val range get() = CellRange(anchor, active)
    val isSingleCell get() = anchor == active
    val address get() = range.address
}

/** Changes to a sheet's cells by id; null removes the cell. */
typealias CellChanges = Map<String, SavedCell?>
