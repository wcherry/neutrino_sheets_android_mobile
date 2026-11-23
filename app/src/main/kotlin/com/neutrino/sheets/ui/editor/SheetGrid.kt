package com.neutrino.sheets.ui.editor

import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import com.neutrino.sheets.core.editor.FrozenPanes
import com.neutrino.sheets.core.editor.GridGeometry
import com.neutrino.sheets.core.workbook.CellAddress
import com.neutrino.sheets.core.workbook.CellFormatter
import com.neutrino.sheets.core.workbook.CellRange
import com.neutrino.sheets.core.workbook.CellStyle
import com.neutrino.sheets.core.workbook.FormulaReferences
import com.neutrino.sheets.core.workbook.SavedCell
import com.neutrino.sheets.core.workbook.SheetData
import com.neutrino.sheets.core.workbook.SheetSelection
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Scroll position (content pixels at the current zoom, past the frozen panes) and zoom. */
@Stable
class GridState {
    var scrollX by mutableFloatStateOf(0f)
    var scrollY by mutableFloatStateOf(0f)
    var zoom by mutableFloatStateOf(1f)
    /** While the fill handle is dragged: where the fill would land. */
    var fillPreview by mutableStateOf<CellRange?>(null)

    companion object {
        const val MIN_ZOOM = 0.5f
        const val MAX_ZOOM = 3f
    }
}

/** What a tap on the grid landed on. */
sealed class GridHit {
    data class Cell(val address: CellAddress) : GridHit()
    data class ColumnHeader(val column: Int) : GridHit()
    data class RowHeader(val row: Int) : GridHit()
    object Corner : GridHit()
}

/**
 * The spreadsheet, drawn: a canvas over the visible cells only, in four regions when panes are
 * frozen (corner, top band, left band, body), each clipped and offset so one drawing routine serves
 * all of them. Cell backgrounds are white and text black whatever the theme — a cell's colours are
 * the workbook's, as on the web.
 */
@Composable
fun SheetGrid(
    sheet: SheetData,
    geometry: GridGeometry,
    selection: SheetSelection?,
    frozen: FrozenPanes,
    gridlines: Boolean,
    editable: Boolean,
    filteredColumns: Set<Int>,
    state: GridState,
    onTap: (GridHit) -> Unit,
    onDoubleTap: (CellAddress) -> Unit,
    onExtend: (CellAddress, CellAddress) -> Unit,
    onFill: (CellRange) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current.density
    val slop = LocalViewConfiguration.current.touchSlop
    val scope = rememberCoroutineScope()
    val primary = MaterialTheme.colorScheme.primary
    val headerBackground = MaterialTheme.colorScheme.surfaceContainer
    val headerText = MaterialTheme.colorScheme.onSurfaceVariant
    val headerSelected = MaterialTheme.colorScheme.primaryContainer
    val painter = remember { GridPainter() }
    var flingX by remember { mutableStateOf<Job?>(null) }
    var flingY by remember { mutableStateOf<Job?>(null) }
    var size by remember { mutableStateOf(androidx.compose.ui.geometry.Size.Zero) }

    fun scale(): Double = (density * state.zoom).toDouble()
    fun hw() = (GridGeometry.ROW_HEADER_WIDTH * scale()).toFloat()
    fun hh() = (GridGeometry.COLUMN_HEADER_HEIGHT * scale()).toFloat()
    fun fw() = (geometry.x(frozen.columns + 1) * scale()).toFloat()
    fun fh() = (geometry.y(frozen.rows + 1) * scale()).toFloat()
    fun maxScrollX() = maxOf(0f, (geometry.contentWidth * scale()).toFloat() - (size.width - hw()))
    fun maxScrollY() = maxOf(0f, (geometry.contentHeight * scale()).toFloat() - (size.height - hh()))
    fun clamp() {
        state.scrollX = state.scrollX.coerceIn(0f, maxScrollX())
        state.scrollY = state.scrollY.coerceIn(0f, maxScrollY())
    }

    /** A screen point in unzoomed content units, through whichever pane holds it. */
    fun content(p: Offset): Pair<Double, Double> {
        val s = scale()
        val cx = (if (p.x < hw() + fw()) (p.x - hw()) else (p.x - hw() + state.scrollX)) / s
        val cy = (if (p.y < hh() + fh()) (p.y - hh()) else (p.y - hh() + state.scrollY)) / s
        return cx to cy
    }

    fun hit(p: Offset): GridHit {
        val (cx, cy) = content(p)
        return when {
            p.x < hw() && p.y < hh() -> GridHit.Corner
            p.y < hh() -> GridHit.ColumnHeader(geometry.column(cx))
            p.x < hw() -> GridHit.RowHeader(geometry.row(cy))
            else -> GridHit.Cell(geometry.address(cx, cy))
        }
    }

    fun addressAt(p: Offset): CellAddress { val (cx, cy) = content(p); return geometry.address(maxOf(0.0, cx), maxOf(0.0, cy)) }

    /** The fill handle's centre on screen, or null when it is not in view. */
    fun handleCenter(): Offset? {
        val range = selection?.range ?: return null
        val r = geometry.rect(range, sheet.cells)
        val s = scale()
        val x = (if (range.end.column <= frozen.columns) hw() + r[2] * s else hw() + r[2] * s - state.scrollX).toFloat()
        val y = (if (range.end.row <= frozen.rows) hh() + r[3] * s else hh() + r[3] * s - state.scrollY).toFloat()
        return Offset(x, y)
    }

    Canvas(
        // Compose does not clip a canvas to its bounds: without this the white page paints over the bars above it.
        modifier.fillMaxSize().clipToBounds()
            // Outermost: pan with fling, and pinch to zoom about the fingers.
            .pointerInput(geometry, frozen) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (down.isConsumed) return@awaitEachGesture
                    flingX?.cancel(); flingY?.cancel()
                    val tracker = VelocityTracker()
                    tracker.addPosition(down.uptimeMillis, down.position)
                    var moved = 0f
                    var panning = false
                    var pinched = false
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.changes.any { it.isConsumed }) return@awaitEachGesture
                        if (event.changes.none { it.pressed }) break
                        val zoom = event.calculateZoom()
                        val pan = event.calculatePan()
                        if (!panning) {
                            moved += pan.getDistance()
                            if (moved > slop || zoom != 1f) panning = true
                        }
                        if (event.changes.count { it.pressed } > 1) pinched = true
                        if (panning) {
                            if (zoom != 1f) {
                                val centroid = event.calculateCentroid()
                                val old = state.zoom
                                val new = (old * zoom).coerceIn(GridState.MIN_ZOOM, GridState.MAX_ZOOM)
                                val ratio = new / old
                                // Headers scale too, so the point under the fingers stays put when
                                // (scroll + centroid) scales by the ratio.
                                state.scrollX = (state.scrollX + centroid.x) * ratio - centroid.x
                                state.scrollY = (state.scrollY + centroid.y) * ratio - centroid.y
                                state.zoom = new
                            }
                            state.scrollX -= pan.x
                            state.scrollY -= pan.y
                            clamp()
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                        event.changes.firstOrNull()?.let { tracker.addPosition(it.uptimeMillis, it.position) }
                    }
                    if (panning && !pinched) {
                        val v = tracker.calculateVelocity()
                        flingX = scope.launch {
                            AnimationState(state.scrollX, -v.x).animateDecay(exponentialDecay()) { state.scrollX = value.coerceIn(0f, maxScrollX()); if (value != state.scrollX) cancelAnimation() }
                        }
                        flingY = scope.launch {
                            AnimationState(state.scrollY, -v.y).animateDecay(exponentialDecay()) { state.scrollY = value.coerceIn(0f, maxScrollY()); if (value != state.scrollY) cancelAnimation() }
                        }
                    }
                }
            }
            .pointerInput(geometry, frozen) {
                detectTapGestures(
                    onTap = { onTap(hit(it)) },
                    onDoubleTap = { p -> (hit(p) as? GridHit.Cell)?.let { onDoubleTap(it.address) } },
                )
            }
            // Long-press, then drag: extend the selection from where the press landed.
            .pointerInput(geometry, frozen) {
                var anchor: CellAddress? = null
                detectDragGesturesAfterLongPress(
                    onDragStart = { p -> anchor = addressAt(p).also { onExtend(it, it) } },
                    onDrag = { change, _ -> change.consume(); anchor?.let { onExtend(it, addressAt(change.position)) } },
                    onDragEnd = { anchor = null },
                )
            }
            // Innermost, so it sees a touch first: dragging the fill handle fills.
            .pointerInput(geometry, frozen, selection, editable) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val handle = handleCenter()
                    val source = selection?.range
                    if (!editable || handle == null || source == null || (down.position - handle).getDistance() > 24 * density) return@awaitEachGesture
                    down.consume()
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        change.consume()
                        if (!change.pressed) break
                        state.fillPreview = fillTarget(source, addressAt(change.position))
                    }
                    state.fillPreview?.takeIf { it != source }?.let(onFill)
                    state.fillPreview = null
                }
            },
    ) {
        size = this.size
        clamp()
        painter.paint(this, sheet, geometry, selection, frozen, gridlines, editable, filteredColumns, state, scale(),
            hw(), hh(), fw(), fh(), primary, headerBackground, headerText, headerSelected)
    }
}

/** The fill reaches along whichever axis the pointer went further, from the source's edge. */
internal fun fillTarget(source: CellRange, to: CellAddress): CellRange {
    val down = to.row - source.end.row
    val up = source.start.row - to.row
    val right = to.column - source.end.column
    val left = source.start.column - to.column
    val vertical = maxOf(down, up)
    val horizontal = maxOf(right, left)
    if (vertical <= 0 && horizontal <= 0) return source
    return if (vertical >= horizontal) {
        if (down > 0) CellRange(source.start, CellAddress(source.end.column, to.row)) else CellRange(CellAddress(source.start.column, to.row), source.end)
    } else {
        if (right > 0) CellRange(source.start, CellAddress(to.column, source.end.row)) else CellRange(CellAddress(to.column, source.start.row), source.end)
    }
}

/** Parses a CSS colour (`#rgb`, `#rrggbb`, `rgb()`) as an Android colour int. */
internal fun cssColor(value: String?): Int? {
    val hex = com.neutrino.shared.core.ooxml.XlsxCodec.normalizeColor(value) ?: return null
    return android.graphics.Color.parseColor(hex)
}

private class GridPainter {
    private val fill = Paint()
    private val line = Paint().apply { style = Paint.Style.STROKE }
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val header = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    fun paint(
        scope: DrawScope, sheet: SheetData, g: GridGeometry, selection: SheetSelection?, frozen: FrozenPanes, gridlines: Boolean,
        editable: Boolean, filteredColumns: Set<Int>, state: GridState, s: Double,
        hw: Float, hh: Float, fw: Float, fh: Float, primary: Color, headerBg: Color, headerFg: Color, headerSel: Color,
    ) = scope.drawIntoCanvas { c ->
        val canvas = c.nativeCanvas
        val w = scope.size.width
        val h = scope.size.height
        fill.color = android.graphics.Color.WHITE
        canvas.drawRect(0f, 0f, w, h, fill)
        val bodyCols = g.visibleColumns((fw + state.scrollX) / s, (w - hw + state.scrollX) / s).let { maxOf(it.first, frozen.columns + 1)..it.last }
        val bodyRows = g.visibleRows((fh + state.scrollY) / s, (h - hh + state.scrollY) / s).let { maxOf(it.first, frozen.rows + 1)..it.last }
        val frozenCols = 1..frozen.columns
        val frozenRows = 1..frozen.rows

        fun region(left: Float, top: Float, right: Float, bottom: Float, dx: Float, dy: Float, cols: IntRange, rows: IntRange) {
            if (right <= left || bottom <= top || cols.isEmpty() || rows.isEmpty()) return
            canvas.save()
            canvas.clipRect(left, top, right, bottom)
            canvas.translate(dx, dy)
            cells(canvas, sheet, g, cols, rows, gridlines, s)
            selection(canvas, sheet, g, selection, state.fillPreview, primary, editable, s, cols, rows)
            canvas.restore()
        }
        region(hw + fw, hh + fh, w, h, hw - state.scrollX, hh - state.scrollY, bodyCols, bodyRows)
        region(hw + fw, hh, w, hh + fh, hw - state.scrollX, hh, bodyCols, frozenRows)
        region(hw, hh + fh, hw + fw, h, hw, hh - state.scrollY, frozenCols, bodyRows)
        region(hw, hh, hw + fw, hh + fh, hw, hh, frozenCols, frozenRows)

        // Frozen-pane dividers.
        line.color = 0xFF9CA3AF.toInt(); line.strokeWidth = 2f
        if (frozen.rows > 0) canvas.drawLine(hw, hh + fh, w, hh + fh, line)
        if (frozen.columns > 0) canvas.drawLine(hw + fw, hh, hw + fw, h, line)

        headers(canvas, g, selection, frozen, filteredColumns, state, s, hw, hh, fw, fh, w, h, bodyCols, bodyRows, headerBg, headerFg, headerSel)
    }

    private fun cells(canvas: android.graphics.Canvas, sheet: SheetData, g: GridGeometry, cols: IntRange, rows: IntRange, gridlines: Boolean, s: Double) {
        val left = g.x(cols.first) * s
        val right = g.x(cols.last + 1) * s
        val top = g.y(rows.first) * s
        val bottom = g.y(rows.last + 1) * s
        if (gridlines) {
            line.color = 0xFFE2E4E8.toInt(); line.strokeWidth = 1f
            for (cIdx in cols.first..cols.last + 1) { val x = (g.x(cIdx) * s).toFloat(); canvas.drawLine(x, top.toFloat(), x, bottom.toFloat(), line) }
            for (rIdx in rows.first..rows.last + 1) { val y = (g.y(rIdx) * s).toFloat(); canvas.drawLine(left.toFloat(), y, right.toFloat(), y, line) }
        }
        // Merges whose anchor is off-screen still have to be drawn: collect anchors of visible covered cells.
        val anchors = LinkedHashSet<String>()
        for (r in rows) for (cIdx in cols) {
            val id = CellAddress(cIdx, r).id
            val cell = sheet.cells[id] ?: continue
            anchors += cell.mergeAnchor ?: id
        }
        for (id in anchors) {
            val cell = sheet.cells[id] ?: continue
            val at = CellAddress.parse(id) ?: continue
            if (cell.isMergedAway) continue
            val rect = g.rect(at, cell.colSpan ?: 1, cell.rowSpan ?: 1).map { (it * s).toFloat() }
            val style = cell.cellStyle
            val merged = (cell.colSpan ?: 1) > 1 || (cell.rowSpan ?: 1) > 1
            val bg = cssColor(style?.backgroundColor)
            if (bg != null || merged) {
                fill.color = bg ?: android.graphics.Color.WHITE
                canvas.drawRect(rect[0] + 0.5f, rect[1] + 0.5f, rect[2] - 0.5f, rect[3] - 0.5f, fill)
            }
            borders(canvas, style, rect)
            text(canvas, sheet, cell, at, rect, s, g, cols)
        }
    }

    private fun borders(canvas: android.graphics.Canvas, style: CellStyle?, r: List<Float>) {
        style ?: return
        line.color = android.graphics.Color.BLACK
        fun weight(w: String?) = when (w) { "thin" -> 1.5f; "medium" -> 2.5f; "thick" -> 3.5f; else -> 0f }
        weight(style.border(CellStyle.Side.TOP)).takeIf { it > 0 }?.let { line.strokeWidth = it; canvas.drawLine(r[0], r[1], r[2], r[1], line) }
        weight(style.border(CellStyle.Side.BOTTOM)).takeIf { it > 0 }?.let { line.strokeWidth = it; canvas.drawLine(r[0], r[3], r[2], r[3], line) }
        weight(style.border(CellStyle.Side.LEFT)).takeIf { it > 0 }?.let { line.strokeWidth = it; canvas.drawLine(r[0], r[1], r[0], r[3], line) }
        weight(style.border(CellStyle.Side.RIGHT)).takeIf { it > 0 }?.let { line.strokeWidth = it; canvas.drawLine(r[2], r[1], r[2], r[3], line) }
    }

    private fun text(canvas: android.graphics.Canvas, sheet: SheetData, cell: SavedCell, at: CellAddress, r: List<Float>, s: Double, g: GridGeometry, cols: IntRange) {
        val raw = cell.raw
        val isPendingFormula = cell.value.isNullOrEmpty() && raw != null && FormulaReferences.isFormula(raw)
        val shown = if (isPendingFormula) raw!! else CellFormatter.display(cell.value ?: raw, cell.cellStyle)
        if (shown.isEmpty()) return
        val style = cell.cellStyle
        text.textSize = ((style?.pointSize?.toDouble() ?: GridGeometry.FONT_SIZE) * s).toFloat()
        text.typeface = Typeface.create(style?.fontFamily?.let { Typeface.create(it, Typeface.NORMAL) } ?: Typeface.DEFAULT,
            (if (style?.isBold == true) Typeface.BOLD else 0) or (if (style?.isItalic == true) Typeface.ITALIC else 0))
        text.isStrikeThruText = style?.isStrikethrough == true
        text.color = if (isPendingFormula) 0xFF6B7280.toInt() else cssColor(style?.color) ?: android.graphics.Color.BLACK
        val inset = (4 * s).toFloat()
        val numeric = CellFormatter.parseNumber(cell.value ?: "") != null && (cell.value ?: "").trim().let { it.isNotEmpty() && it.all { ch -> ch.isDigit() || ch in ".-+eE" } }
        val align = style?.textAlign ?: if (numeric) "right" else "left"
        val cellWidth = r[2] - r[0]
        if (style?.wrapMode == "wrap") {
            val layout = StaticLayout.Builder.obtain(shown, 0, shown.length, text, maxOf(1, (cellWidth - 2 * inset).toInt()))
                .setAlignment(when (align) { "center" -> Layout.Alignment.ALIGN_CENTER; "right" -> Layout.Alignment.ALIGN_OPPOSITE; else -> Layout.Alignment.ALIGN_NORMAL }).build()
            val top = when (style.verticalAlign) { "top" -> r[1] + inset; "bottom" -> r[3] - inset - layout.height; else -> (r[1] + r[3] - layout.height) / 2 }
            canvas.save(); canvas.clipRect(r[0], r[1], r[2], r[3]); canvas.translate(r[0] + inset, maxOf(r[1], top)); layout.draw(canvas); canvas.restore()
            return
        }
        val fm = text.fontMetrics
        val baseline = when (style?.verticalAlign) {
            "top" -> r[1] + inset - fm.ascent
            "bottom" -> r[3] - inset - fm.descent
            else -> (r[1] + r[3]) / 2 - (fm.ascent + fm.descent) / 2
        }
        // Overflow: left-aligned text runs on over empty neighbours, as in every spreadsheet; clip stops at the cell.
        var clipRight = r[2]
        if (align == "left" && style?.wrapMode != "clip") {
            var next = at.column + (cell.colSpan ?: 1)
            while (next <= cols.last + 20 && next <= g.columns) {
                val neighbour = sheet.cells[CellAddress(next, at.row).id]
                if (neighbour != null && !(neighbour.raw.isNullOrEmpty() && neighbour.value.isNullOrEmpty() && !neighbour.isMergedAway)) break
                clipRight = (g.x(next + 1) * s).toFloat()
                if (text.measureText(shown) + inset < clipRight - r[0]) break
                next++
            }
        }
        val width = text.measureText(shown)
        val x = when (align) { "center" -> (r[0] + r[2] - width) / 2; "right" -> r[2] - inset - width; else -> r[0] + inset }
        canvas.save()
        canvas.clipRect(r[0], r[1], clipRight, r[3])
        canvas.drawText(shown, x, baseline, text)
        canvas.restore()
    }

    private fun selection(canvas: android.graphics.Canvas, sheet: SheetData, g: GridGeometry, sel: SheetSelection?, preview: CellRange?, primary: Color, editable: Boolean, s: Double, cols: IntRange, rows: IntRange) {
        sel ?: return
        val r = g.rect(sel.range, sheet.cells).map { (it * s).toFloat() }
        if (!sel.isSingleCell) {
            fill.color = primary.copy(alpha = 0.12f).toArgb()
            canvas.drawRect(r[0], r[1], r[2], r[3], fill)
        }
        line.color = primary.toArgb(); line.strokeWidth = (2 * s).toFloat()
        canvas.drawRect(r[0], r[1], r[2], r[3], line)
        val active = sheet.cells[sel.active.id]
        val a = g.rect(sel.active, active?.colSpan ?: 1, active?.rowSpan ?: 1).map { (it * s).toFloat() }
        if (!sel.isSingleCell) { line.strokeWidth = (1 * s).toFloat(); canvas.drawRect(a[0], a[1], a[2], a[3], line) }
        preview?.let { p ->
            val pr = g.rect(p).map { (it * s).toFloat() }
            line.strokeWidth = (1.5 * s).toFloat()
            line.pathEffect = android.graphics.DashPathEffect(floatArrayOf(8f, 6f), 0f)
            canvas.drawRect(pr[0], pr[1], pr[2], pr[3], line)
            line.pathEffect = null
        }
        if (editable) {
            fill.color = primary.toArgb()
            val half = (4 * s).toFloat()
            canvas.drawRect(r[2] - half, r[3] - half, r[2] + half, r[3] + half, fill)
        }
    }

    private fun headers(
        canvas: android.graphics.Canvas, g: GridGeometry, sel: SheetSelection?, frozen: FrozenPanes, filtered: Set<Int>, state: GridState, s: Double,
        hw: Float, hh: Float, fw: Float, fh: Float, w: Float, h: Float, bodyCols: IntRange, bodyRows: IntRange, bg: Color, fg: Color, selected: Color,
    ) {
        val range = sel?.range
        header.textSize = (11 * s).toFloat()
        header.color = fg.toArgb()
        line.color = 0xFFD1D5DB.toInt(); line.strokeWidth = 1f
        fill.color = bg.toArgb()
        canvas.drawRect(0f, 0f, w, hh, fill)
        canvas.drawRect(0f, 0f, hw, h, fill)

        fun columnHeader(column: Int, x0: Float, x1: Float) {
            if (x1 <= x0) return
            if (range != null && column in range.columns) { fill.color = selected.toArgb(); canvas.drawRect(x0, 0f, x1, hh, fill) }
            val label = CellAddress.columnLetters(column) + if (column in filtered) " ▾" else ""
            canvas.drawText(label, (x0 + x1) / 2, hh / 2 - (header.ascent() + header.descent()) / 2, header)
            canvas.drawLine(x1, 0f, x1, hh, line)
        }
        fun rowHeader(row: Int, y0: Float, y1: Float) {
            if (y1 <= y0) return
            if (range != null && row in range.rows) { fill.color = selected.toArgb(); canvas.drawRect(0f, y0, hw, y1, fill) }
            canvas.drawText(row.toString(), hw / 2, (y0 + y1) / 2 - (header.ascent() + header.descent()) / 2, header)
            canvas.drawLine(0f, y1, hw, y1, line)
        }
        canvas.save(); canvas.clipRect(hw, 0f, hw + fw, hh)
        for (column in 1..frozen.columns) columnHeader(column, hw + (g.x(column) * s).toFloat(), hw + (g.x(column + 1) * s).toFloat())
        canvas.restore()
        canvas.save(); canvas.clipRect(hw + fw, 0f, w, hh)
        for (column in bodyCols) columnHeader(column, hw + (g.x(column) * s).toFloat() - state.scrollX, hw + (g.x(column + 1) * s).toFloat() - state.scrollX)
        canvas.restore()
        canvas.save(); canvas.clipRect(0f, hh, hw, hh + fh)
        for (row in 1..frozen.rows) rowHeader(row, hh + (g.y(row) * s).toFloat(), hh + (g.y(row + 1) * s).toFloat())
        canvas.restore()
        canvas.save(); canvas.clipRect(0f, hh + fh, hw, h)
        for (row in bodyRows) rowHeader(row, hh + (g.y(row) * s).toFloat() - state.scrollY, hh + (g.y(row + 1) * s).toFloat() - state.scrollY)
        canvas.restore()
        canvas.drawLine(0f, hh, w, hh, line)
        canvas.drawLine(hw, 0f, hw, h, line)
    }
}
