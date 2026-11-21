package com.neutrino.sheets.core.workbook

import com.neutrino.shared.core.ooxml.XlsxCodec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/**
 * The workbook the editor holds — the web's `types.ts`, as iOS models it. Every type keeps the keys
 * it does not model in [extra] and writes them back: a phone will always understand less of the
 * format than the web, and a decoder that kept only what it renders would drop charts and
 * conditional formats on the first mobile save, with nothing failing anywhere.
 */
data class CellStyle(
    val fontFamily: String? = null,
    /** A CSS length as the web writes it ("14px"), or points ("11pt") as a workbook reads back. */
    val fontSize: String? = null,
    val fontWeight: String? = null,
    val fontStyle: String? = null,
    val textDecoration: String? = null,
    val color: String? = null,
    val backgroundColor: String? = null,
    val textAlign: String? = null,
    val verticalAlign: String? = null,
    val wrapMode: String? = null,
    val borderStyle: String? = null,
    val borderTop: String? = null,
    val borderRight: String? = null,
    val borderBottom: String? = null,
    val borderLeft: String? = null,
    val numberFormat: String? = null,
    val decimalPlaces: Int? = null,
    val customFormat: String? = null,
    val extra: Map<String, JsonElement> = emptyMap(),
) {
    val isBold get() = fontWeight == "bold"
    val isItalic get() = fontStyle == "italic"
    val isStrikethrough get() = textDecoration == "line-through"
    val isEmpty get() = this == CellStyle()

    /** [fontSize] as a number, its unit dropped: the web renders CSS pixels 1:1 as points. */
    val pointSize: Float?
        get() = fontSize?.let { s -> s.takeWhile { it.isDigit() || it == '.' }.toFloatOrNull()?.takeIf { it > 0 } }

    enum class Side { TOP, RIGHT, BOTTOM, LEFT }

    /** One side's weight, falling back to [borderStyle] as the web's CSS does; "none" is no border. */
    fun border(side: Side): String? {
        val specific = when (side) { Side.TOP -> borderTop; Side.RIGHT -> borderRight; Side.BOTTOM -> borderBottom; Side.LEFT -> borderLeft }
        return (specific ?: borderStyle)?.takeIf { it != "none" }
    }

    fun toJson(): JsonObject = JsonObject(buildMap {
        putAll(extra)
        fun s(k: String, v: String?) { if (v != null) put(k, JsonPrimitive(v)) }
        s("fontFamily", fontFamily); s("fontSize", fontSize); s("fontWeight", fontWeight); s("fontStyle", fontStyle)
        s("textDecoration", textDecoration); s("color", color); s("backgroundColor", backgroundColor)
        s("textAlign", textAlign); s("verticalAlign", verticalAlign); s("wrapMode", wrapMode)
        s("borderStyle", borderStyle); s("borderTop", borderTop); s("borderRight", borderRight); s("borderBottom", borderBottom); s("borderLeft", borderLeft)
        s("numberFormat", numberFormat); decimalPlaces?.let { put("decimalPlaces", JsonPrimitive(it)) }; s("customFormat", customFormat)
    })

    companion object {
        private val known = setOf("fontFamily", "fontSize", "fontWeight", "fontStyle", "textDecoration", "color", "backgroundColor",
            "textAlign", "verticalAlign", "wrapMode", "borderStyle", "borderTop", "borderRight", "borderBottom", "borderLeft",
            "numberFormat", "decimalPlaces", "customFormat")

        fun from(json: JsonObject): CellStyle = CellStyle(
            json.str("fontFamily"), json.str("fontSize"), json.str("fontWeight"), json.str("fontStyle"), json.str("textDecoration"),
            json.str("color"), json.str("backgroundColor"), json.str("textAlign"), json.str("verticalAlign"), json.str("wrapMode"),
            json.str("borderStyle"), json.str("borderTop"), json.str("borderRight"), json.str("borderBottom"), json.str("borderLeft"),
            json.str("numberFormat"), json.int("decimalPlaces"), json.str("customFormat"),
            json.filterKeys { it !in known },
        )
    }
}

/** One persisted cell. `raw` is what was typed (a literal, or a formula starting `=`); `value` is what the web computed. */
data class SavedCell(
    val id: String,
    val raw: String? = null,
    val value: String? = null,
    val cellStyle: CellStyle? = null,
    val colSpan: Int? = null,
    val rowSpan: Int? = null,
    /** The anchor's id when this cell is covered by another's merge. */
    val mergeAnchor: String? = null,
    val extra: Map<String, JsonElement> = emptyMap(),
) {
    val isMergedAway get() = mergeAnchor != null

    /** Neither entry nor display value nor style nor merge: the web's `isPopulatedCell`, negated. */
    val isEmpty get() = raw.isNullOrEmpty() && value.isNullOrEmpty() && cellStyle == null && colSpan == null && rowSpan == null && mergeAnchor == null

    /** What the formula bar shows. */
    val editableText get() = raw ?: value ?: ""

    fun toJson(): JsonObject = JsonObject(buildMap {
        putAll(extra)
        put("id", JsonPrimitive(id))
        raw?.let { put("raw", JsonPrimitive(it)) }
        value?.let { put("value", JsonPrimitive(it)) }
        cellStyle?.let { put("cellStyle", it.toJson()) }
        colSpan?.let { put("colSpan", JsonPrimitive(it)) }
        rowSpan?.let { put("rowSpan", JsonPrimitive(it)) }
        mergeAnchor?.let { put("mergeAnchor", JsonPrimitive(it)) }
    })

    companion object {
        private val known = setOf("id", "raw", "value", "cellStyle", "colSpan", "rowSpan", "mergeAnchor")

        fun from(key: String, json: JsonObject) = SavedCell(
            id = json.str("id") ?: key, raw = json.str("raw"), value = json.str("value"),
            cellStyle = (json["cellStyle"] as? JsonObject)?.let(CellStyle::from)?.takeIf { !it.isEmpty },
            colSpan = json.int("colSpan"), rowSpan = json.int("rowSpan"), mergeAnchor = json.str("mergeAnchor"),
            extra = json.filterKeys { it !in known },
        )
    }
}

/** A range a table style was applied to, 1-based and inclusive. */
data class TableRegion(val id: String, val styleId: String, val minR: Int, val maxR: Int, val minC: Int, val maxC: Int, val extra: Map<String, JsonElement> = emptyMap()) {
    constructor(id: String, styleId: String, range: CellRange) : this(id, styleId, range.start.row, range.end.row, range.start.column, range.end.column)

    val range get() = CellRange(CellAddress(minC, minR), CellAddress(maxC, maxR))
    val isValid get() = minR in 1..maxR && minC in 1..maxC

    fun overlaps(other: CellRange) = !(maxC < other.start.column || other.end.column < minC || maxR < other.start.row || other.end.row < minR)

    fun toJson() = JsonObject(extra + mapOf("id" to JsonPrimitive(id), "styleId" to JsonPrimitive(styleId),
        "minR" to JsonPrimitive(minR), "maxR" to JsonPrimitive(maxR), "minC" to JsonPrimitive(minC), "maxC" to JsonPrimitive(maxC)))

    companion object {
        fun makeId() = "table-${java.util.UUID.randomUUID().toString().lowercase()}"

        fun from(json: JsonObject): TableRegion? {
            val id = json.str("id") ?: return null
            val style = json.str("styleId") ?: return null
            return TableRegion(id, style, json.int("minR") ?: return null, json.int("maxR") ?: return null,
                json.int("minC") ?: return null, json.int("maxC") ?: return null,
                json.filterKeys { it !in setOf("id", "styleId", "minR", "maxR", "minC", "maxC") })
        }
    }
}

fun List<TableRegion>.removingOverlaps(range: CellRange) = filter { !it.overlaps(range) }
fun List<TableRegion>.registering(region: TableRegion) = removingOverlaps(region.range) + region

/** One tab. Widths and heights are pixels keyed by the **0-based** index as a string, as the web stores them. */
data class SheetData(
    val name: String? = null,
    val color: String? = null,
    val cells: Map<String, SavedCell> = emptyMap(),
    val colWidths: Map<String, Double>? = null,
    val rowHeights: Map<String, Double>? = null,
    val tables: List<TableRegion>? = null,
    /** Charts, conditional formats, CF variables, and whatever a later web release adds. */
    val extra: Map<String, JsonElement> = emptyMap(),
) {
    fun width(column: Int): Double? = colWidths?.get((column - 1).toString())
    fun height(row: Int): Double? = rowHeights?.get((row - 1).toString())

    /** The bottom-right extent of the populated cells (1-based), merges at full span; null when empty. */
    val usedBounds: Pair<Int, Int>?
        get() {
            var maxRow = 0
            var maxColumn = 0
            for ((key, cell) in cells) {
                if (cell.isEmpty) continue
                val a = CellAddress.parse(key) ?: continue
                maxRow = maxOf(maxRow, a.row + maxOf(0, (cell.rowSpan ?: 1) - 1))
                maxColumn = maxOf(maxColumn, a.column + maxOf(0, (cell.colSpan ?: 1) - 1))
            }
            return if (maxRow > 0 && maxColumn > 0) maxRow to maxColumn else null
        }

    fun toJson(): JsonObject = JsonObject(buildMap {
        putAll(extra)
        name?.let { put("name", JsonPrimitive(it)) }
        color?.let { put("color", JsonPrimitive(it)) }
        put("cells", JsonObject(cells.mapValues { it.value.toJson() }))
        colWidths?.takeIf { it.isNotEmpty() }?.let { m -> put("colWidths", JsonObject(m.mapValues { XlsxCodec.jsonNumber(it.value) })) }
        rowHeights?.takeIf { it.isNotEmpty() }?.let { m -> put("rowHeights", JsonObject(m.mapValues { XlsxCodec.jsonNumber(it.value) })) }
        tables?.takeIf { it.isNotEmpty() }?.let { put("tables", JsonArray(it.map(TableRegion::toJson))) }
    })

    companion object {
        private val known = setOf("name", "color", "cells", "colWidths", "rowHeights", "tables")

        fun from(json: JsonObject): SheetData {
            val tablesJson = json["tables"] as? JsonArray
            val tables = tablesJson?.map { (it as? JsonObject)?.let(TableRegion::from) }
            // A `tables` array this app cannot read is carried opaquely rather than dropped.
            val typedTables = tables?.takeIf { list -> list.all { it != null } }?.filterNotNull()
            val extra = json.filterKeys { it !in known || (it == "tables" && typedTables == null && tablesJson != null) }
            return SheetData(
                name = json.str("name"), color = json.str("color"),
                cells = (json["cells"] as? JsonObject)?.mapNotNull { (k, v) -> (v as? JsonObject)?.let { k to SavedCell.from(k, it) } }?.toMap().orEmpty(),
                colWidths = (json["colWidths"] as? JsonObject)?.numbers(),
                rowHeights = (json["rowHeights"] as? JsonObject)?.numbers(),
                tables = typedTables, extra = extra,
            )
        }

        private fun JsonObject.numbers(): Map<String, Double> =
            mapNotNull { (k, v) -> (v as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.let { k to it } }.toMap()
    }
}

/** A whole workbook: the `{"sheets":[…]}` envelope the `.xlsx` codec speaks. */
data class SheetFile(val sheets: List<SheetData>, val extra: Map<String, JsonElement> = emptyMap()) {
    fun toJson() = JsonObject(extra + ("sheets" to JsonArray(sheets.map(SheetData::toJson))))

    /** The `.xlsx` bytes to store; `keeping` is the package this replaces, whose foreign parts are carried. */
    fun encoded(keeping: ByteArray? = null): ByteArray = XlsxCodec.encode(toJson(), keeping)

    companion object {
        /** One tab, named as the web's editor names a new one. */
        val empty get() = SheetFile(listOf(SheetData(name = "Sheet1")))

        fun from(json: JsonObject) = SheetFile(
            (json["sheets"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(SheetData::from) }.orEmpty(),
            json.filterKeys { it != "sheets" },
        )

        /**
         * A stored body. Not a package at all — a spreadsheet created a moment ago, whose Drive row
         * has no content yet — decodes to an empty workbook whose first save writes a real one.
         */
        fun decode(bytes: ByteArray): SheetFile {
            if (bytes.isEmpty()) return empty
            val file = runCatching { from(XlsxCodec.decode(bytes)) }.getOrNull()
            return file?.takeIf { it.sheets.isNotEmpty() } ?: empty
        }
    }
}

internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
internal fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString && it != JsonNull }?.let { it.intOrNull ?: it.doubleOrNull?.toInt() }
