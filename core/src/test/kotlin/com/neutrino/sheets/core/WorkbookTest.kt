package com.neutrino.sheets.core

import com.neutrino.sheets.core.workbook.Axis
import com.neutrino.sheets.core.workbook.CellAddress
import com.neutrino.sheets.core.workbook.CellFormatter
import com.neutrino.sheets.core.workbook.CellRange
import com.neutrino.sheets.core.workbook.CellStyle
import com.neutrino.sheets.core.workbook.CellStylePatch
import com.neutrino.sheets.core.workbook.ColumnFilters
import com.neutrino.sheets.core.workbook.FillEngine
import com.neutrino.sheets.core.workbook.FindReplace
import com.neutrino.sheets.core.workbook.FormulaReferences
import com.neutrino.sheets.core.workbook.MergeEngine
import com.neutrino.sheets.core.workbook.NumberFormats
import com.neutrino.sheets.core.workbook.Operation
import com.neutrino.sheets.core.workbook.RangeSummary
import com.neutrino.sheets.core.workbook.SavedCell
import com.neutrino.sheets.core.workbook.SheetClipboard
import com.neutrino.sheets.core.workbook.SheetData
import com.neutrino.sheets.core.workbook.SheetFile
import com.neutrino.sheets.core.workbook.SheetTabs
import com.neutrino.sheets.core.workbook.SortEngine
import com.neutrino.sheets.core.workbook.StructuralShift
import com.neutrino.sheets.core.workbook.TableStyle
import com.neutrino.sheets.core.workbook.TableStyles
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun cells(vararg entries: Pair<String, String>) = entries.associate { (id, raw) -> id to SavedCell(id, raw, raw) }
private fun a(id: String) = CellAddress.parse(id)!!
private fun r(text: String) = CellRange.parse(text)!!

class ModelTest {
    @Test
    fun formulasFollowInsertedAndDeletedRows() {
        val ins = Operation.INSERT; val del = Operation.DELETE; val row = Axis.ROW; val col = Axis.COLUMN
        assertEquals("=SUM(B3:B4)", StructuralShift.shiftFormula("=SUM(B2:B3)", row, ins, 1))
        assertEquals("=SUM(B2:B4)", StructuralShift.shiftFormula("=SUM(B2:B3)", row, ins, 3))
        assertEquals("=SUM(B2:B3)", StructuralShift.shiftFormula("=SUM(B2:B3)", row, ins, 4))
        assertEquals("=\$C\$2+LOG10(4)", StructuralShift.shiftFormula("=\$B\$2+LOG10(4)", col, ins, 2))
        assertEquals("=A1+#REF!", StructuralShift.shiftFormula("=A1+B2", row, del, 2))
        assertEquals("=B3:A1", StructuralShift.shiftFormula("=B4:A1", row, del, 2))
        assertEquals("=\"B2\"&B3", StructuralShift.shiftFormula("=\"B2\"&B2", row, ins, 1))
        assertEquals("=Data!A3+A2", StructuralShift.shiftFormula("=Data!A2+A2", row, ins, 1, target = "data", formulaOnTarget = false))
        assertEquals("='My Data'!A3", StructuralShift.shiftFormula("='My Data'!A2", row, ins, 2, target = "My Data", formulaOnTarget = false))
        assertEquals("=Q1_total+A2", StructuralShift.shiftFormula("=Q1_total+A1", row, ins, 1))
        // The web's structuralShift.test.ts cases, one for one.
        val c = Axis.COLUMN
        val web = listOf(
            listOf("=A1+C1", c, ins, 2, "=A1+D1"), listOf("=B2+B5", row, ins, 3, "=B2+B6"), listOf("=B2+B5", row, del, 3, "=B2+B4"),
            listOf("=\$C\$1+\$C1+C\$1", c, ins, 1, "=\$D\$1+\$D1+D\$1"), listOf("=SUM(B1:D1)", c, ins, 3, "=SUM(B1:E1)"),
            listOf("=SUM(B1:D1)", c, ins, 2, "=SUM(C1:E1)"), listOf("=SUM(B1:D1)", c, ins, 5, "=SUM(B1:D1)"),
            listOf("=SUM(B1:D1)", c, del, 3, "=SUM(B1:C1)"), listOf("=SUM(B1:B1)", c, del, 2, "=SUM(#REF!)"), listOf("=A1+B1", c, del, 2, "=A1+#REF!"),
            listOf("=SUM(D1:B1)", c, ins, 3, "=SUM(E1:B1)"), listOf("=IF(C1>0,\"C1 ok\",LOG10(C1))", c, ins, 1, "=IF(D1>0,\"C1 ok\",LOG10(D1))"),
            listOf("=Beta!C1+'Net Worth'!C1:D1+C1", c, ins, 1, "=Beta!C1+'Net Worth'!C1:D1+D1"),
        )
        for (case in web) assertEquals(case[0] as String, case[4] as String, StructuralShift.shiftFormula(case[0] as String, case[1] as Axis, case[2] as Operation, case[3] as Int))
        assertEquals("='Sheet 1'!D1", StructuralShift.shiftFormula("='Sheet 1'!C1", c, ins, 1, "sheet 1", true))
        assertEquals("='Sheet 1'!D1:E1+C1", StructuralShift.shiftFormula("='Sheet 1'!C1:D1+C1", c, ins, 1, "Sheet 1", false))
        assertEquals("=#REF!+1", StructuralShift.shiftFormula("='Sheet 1'!C1+1", c, del, 3, "Sheet 1", false))
    }

    @Test
    fun aFormulaWithNoCachedValueSurvivesARoundTrip() {
        val sheet = SheetData(name = "Sheet1", cells = mapOf("B4" to SavedCell("B4", raw = "=SUM(B2:B3)"), "B2" to SavedCell("B2", raw = "1200", value = "1200")))
        val back = SheetFile.decode(SheetFile(listOf(sheet)).encoded())
        assertEquals("=SUM(B2:B3)", back.sheets[0].cells["B4"]?.raw)
        val before = SheetFile.empty.encoded()
        val kept = SheetFile.decode(SheetFile(listOf(sheet)).encoded(keeping = before))
        assertEquals("=SUM(B2:B3)", kept.sheets[0].cells["B4"]?.raw)
    }

    @Test
    fun addressesAndRanges() {
        assertEquals(CellAddress(28, 10), a("AB10"))
        assertNull(CellAddress.parse("a1"))
        assertNull(CellAddress.parse("A0"))
        assertEquals("AB10", CellAddress(28, 10).id)
        assertEquals("B2:D5", CellRange(a("D5"), a("B2")).address)
        assertEquals(12L, r("B2:D5").cellCount)
    }

    @Test
    fun aWorkbookKeepsWhatItDoesNotModelThroughAnXlsxRoundTrip() {
        val json = Json.parseToJsonElement("""{"sheets":[{"name":"Data","color":"#22c55e","colWidths":{"0":120},"cells":{
            "A1":{"id":"A1","raw":"Total","value":"Total","cellStyle":{"fontWeight":"bold","backgroundColor":"#dbeafe"}},
            "B1":{"id":"B1","raw":"=SUM(B2:B3)","value":"30"}},
            "charts":[{"id":"c1","type":"bar","range":"A1:B3"}],
            "conditionalFormats":[{"id":"r1","range":"B2:B3","rule":{"kind":"progressBar","color":"#34c759","minValue":0,"maxValue":100}}]}]}""") as JsonObject
        val file = SheetFile.from(json)
        assertEquals("bold", file.sheets[0].cells["A1"]!!.cellStyle!!.fontWeight)
        val back = SheetFile.decode(file.encoded())
        assertEquals(file.sheets[0].cells, back.sheets[0].cells)
        assertEquals(120.0, back.sheets[0].width(1))
        assertTrue("charts" in back.sheets[0].extra)
        assertTrue("conditionalFormats" in back.sheets[0].extra)
        assertEquals(SheetFile.empty, SheetFile.decode(ByteArray(0)))
    }
}

class FormatterTest {
    @Test
    fun numbersCurrencyAndPercent() {
        assertEquals("1,234.57", CellFormatter.display("1234.567", CellStyle(numberFormat = "number", decimalPlaces = 2)))
        assertEquals("$1,234.50", CellFormatter.display("1234.5", CellStyle(numberFormat = "currency")))
        assertEquals("-$3.00", CellFormatter.display("-3", CellStyle(numberFormat = "currency")))
        assertEquals("12.5%", CellFormatter.display("0.125", CellStyle(numberFormat = "percent", decimalPlaces = 1)))
        assertEquals("abc", CellFormatter.display("abc", CellStyle(numberFormat = "number")))
        assertEquals("1,235", CellFormatter.applyCustomFormat("1234.5", "#,##0"))
    }

    @Test
    fun datesFromSerialsAndText() {
        assertEquals("1/1/2025", CellFormatter.display("45658", CellStyle(numberFormat = "date")))
        assertEquals("11/1/2025", CellFormatter.display("2025-11-01", CellStyle(numberFormat = "date")))
        assertEquals("Sat, 1 Nov 2025", CellFormatter.applyCustomFormat("2025-11-01T13:30:00Z", "ddd, D mmm yyyy"))
        assertEquals("1:30 PM", CellFormatter.applyCustomFormat("2025-11-01T13:30:00Z", "h:mm AM/PM"))
        assertEquals("Due 11/01", CellFormatter.applyCustomFormat("2025-11-01", "\"Due \"MM/DD"))
        assertEquals(45658.0, CellFormatter.excelSerial(CellFormatter.dateFromExcelSerial(45658.0)), 0.0)
        assertEquals("1:30:00 PM", NumberFormats.preview("h:mm:ss AM/PM", NumberFormats.Category.DATETIME))
    }

    @Test
    fun parseNumberReadsTheLeadingNumber() {
        assertEquals(12.5, CellFormatter.parseNumber(" 12.5kg ")!!, 0.0)
        assertEquals(1e3, CellFormatter.parseNumber("1e3")!!, 0.0)
        assertNull(CellFormatter.parseNumber("kg"))
    }

    @Test
    fun aPatchSetsClearsAndLeavesFields() {
        val styled = CellStylePatch.bold(true).applied(CellStyle(color = "#ff0000"))
        assertEquals(CellStyle(color = "#ff0000", fontWeight = "bold"), styled)
        assertNull(CellStylePatch.clearFormatting.applied(styled))
        val bordered = CellStylePatch.allBorders("thin").applied(CellStyle(borderTop = "thick"))!!
        assertEquals("thin", bordered.border(CellStyle.Side.TOP))
    }
}

class EngineTest {
    @Test
    fun formulasReanchorWhereTheyLand() {
        assertEquals("=A2+\$B\$1+C2", FormulaReferences.shift("=A1+\$B\$1+C1", a("D1"), a("D2")))
        assertEquals("=Sheet2!B1*2", FormulaReferences.shift("=Sheet2!A1*2", a("A1"), a("B1")))
        assertEquals("=#REF!", FormulaReferences.shift("=A1", a("A2"), a("A1")))
    }

    @Test
    fun fillFollowsSeries() {
        fun fill(vararg values: String, count: Int): List<String?> {
            val source = values.mapIndexed { i, v -> "A${i + 1}" to v }.toTypedArray()
            val changes = FillEngine.fill(CellRange(a("A1"), a("A${values.size}")), CellRange(a("A1"), a("A${values.size + count}")), cells(*source))
            return (values.size + 1..values.size + count).map { changes["A$it"]?.raw }
        }
        assertEquals(listOf("3", "4", "5"), fill("1", "2", count = 3))
        assertEquals(listOf("7", "7"), fill("7", count = 2))
        assertEquals(listOf("Mar", "Apr"), fill("Jan", "Feb", count = 2))
        assertEquals(listOf("Item 3", "Item 4"), fill("Item 1", "Item 2", count = 2))
        assertEquals(listOf("2025-01-03"), fill("2025-01-01", "2025-01-02", count = 1))
        val formula = FillEngine.fill(r("B1"), r("B1:B2"), mapOf("B1" to SavedCell("B1", "=A1*2", "4")))
        assertEquals("=A2*2", formula["B2"]!!.raw)
    }

    @Test
    fun mergeAndUnmerge() {
        val merged = MergeEngine.merge(r("A1:B2"), cells("A1" to "x"))
        assertEquals(2, merged["A1"]!!.colSpan)
        assertEquals("A1", merged["B2"]!!.mergeAnchor)
        val all = merged.mapValues { it.value!! }
        assertEquals(r("A1:B2"), MergeEngine.mergeOrigin(a("A1"), all))
        val unmerged = MergeEngine.unmerge(r("A1:B2"), all)
        assertNull(unmerged["A1"]!!.colSpan)
        assertNull(unmerged["B2"])
    }

    @Test
    fun sortIsNumericStableAndBlankLast() {
        val cells = cells("A1" to "10", "B1" to "x", "A2" to "", "A3" to "9", "B3" to "y", "A4" to "apple")
        val sorted = (SortEngine.sortRows(r("A1:B4"), 1, true, cells) as SortEngine.Outcome.Sorted).changes
        assertEquals("9", sorted["A1"]!!.raw)
        assertEquals("y", sorted["B1"]!!.raw)
        assertEquals("10", sorted["A2"]!!.raw)
        assertEquals("apple", sorted["A3"]!!.raw)
        val refused = SortEngine.sortRows(r("A1:B2"), 1, true, mapOf("A1" to SavedCell("A1", colSpan = 2)))
        assertEquals(SortEngine.Outcome.Refused(SortEngine.Refusal.CONTAINS_MERGED_CELLS), refused)
    }

    @Test
    fun filtersFindAndSummary() {
        val c = cells("A1" to "red", "A2" to "blue", "A3" to "red", "B1" to "2", "B2" to "x", "B3" to "4")
        assertEquals(setOf(2), ColumnFilters().with(1, setOf("red")).hiddenRows(c, 3))
        // Blank sorts last, as everywhere else in the app.
        assertEquals(listOf("blue", "red", ""), ColumnFilters.values(1, c, 3))
        assertEquals(listOf(a("A1"), a("A3")), FindReplace.matches(c, "RED", FindReplace.Options()))
        assertEquals("green", FindReplace.replaced(c["A1"]!!, "red", "green", FindReplace.Options(), true)!!.raw)
        val summary = RangeSummary.of(r("B1:B3"), c)
        assertEquals(3, summary.count)
        assertEquals(6.0, summary.sum, 0.0)
        assertEquals("Sum 6  ·  Avg 3  ·  Count 3", summary.statusText)
    }

    @Test
    fun insertingAndDeletingMovesCellsMergesSizesAndRules() {
        val sheet = SheetData(
            cells = mapOf("A1" to SavedCell("A1", "x", rowSpan = 2), "A2" to SavedCell("A2", mergeAnchor = "A1"), "A3" to SavedCell("A3", "y", "y")),
            rowHeights = mapOf("2" to 30.0),
            extra = mapOf("conditionalFormats" to Json.parseToJsonElement("""[{"id":"r","range":"A3:A4","rule":{}}]""")),
        )
        val inserted = StructuralShift.apply(sheet, Axis.ROW, Operation.INSERT, 2)
        assertEquals(3, inserted.cells["A1"]!!.rowSpan)
        assertEquals("y", inserted.cells["A4"]!!.raw)
        assertEquals(30.0, inserted.rowHeights!!["3"])
        assertTrue(inserted.extra["conditionalFormats"].toString().contains("A4:A5"))
        val deleted = StructuralShift.apply(sheet, Axis.ROW, Operation.DELETE, 2)
        assertNull(deleted.cells["A1"]!!.rowSpan)
        assertEquals("y", deleted.cells["A2"]!!.raw)
    }

    @Test
    fun tableStylesBandAndHead() {
        val style = TableStyles.style("blue-banded") as TableStyle.Regular
        val patches = TableStyles.patches(style, r("A1:B3"))
        assertEquals("bold", patches["A1"]!!.applied(null)!!.fontWeight)
        assertEquals("#ffffff", patches["A2"]!!.applied(null)!!.backgroundColor)
        assertEquals("#dbeafe", patches["A3"]!!.applied(null)!!.backgroundColor)
        assertEquals(85, TableStyles.all.size)
    }

    @Test
    fun clipboardTilesAndReanchors() {
        val clip = SheetClipboard.of(r("A1:A2"), mapOf("A1" to SavedCell("A1", "1", "1"), "A2" to SavedCell("A2", "=A1+1", "2")), false)
        val pasted = clip.changes(a("C1"), r("C1:C4"), emptyMap())
        assertEquals("=C3+1", pasted["C4"]!!.raw)
        assertEquals("1\t\n2\t", SheetClipboard.fromTabSeparated("1\t\n2\n")!!.tabSeparatedText)
        assertEquals("1\n2", clip.tabSeparatedText)
    }

    @Test
    fun tabs() {
        val sheets = listOf(SheetData(name = "Sheet1"))
        val added = SheetTabs.add(sheets)
        assertEquals("Sheet 2", added.sheets[1].name)
        assertEquals("Sheet1 (copy)", SheetTabs.duplicate(added.sheets, 0).sheets[1].name)
        assertNull(SheetTabs.delete(sheets, 0, 0))
        assertEquals(0, SheetTabs.move(added.sheets, 1, -1, 1)!!.activeIndex)
    }
}
