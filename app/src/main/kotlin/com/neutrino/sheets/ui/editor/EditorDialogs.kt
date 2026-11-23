package com.neutrino.sheets.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.neutrino.sheets.core.editor.GridGeometry
import com.neutrino.sheets.core.editor.SheetEditor
import com.neutrino.sheets.core.workbook.CellAddress
import com.neutrino.sheets.core.workbook.CellStyle
import com.neutrino.sheets.core.workbook.CellStylePatch
import com.neutrino.sheets.core.workbook.FindReplace
import com.neutrino.sheets.core.workbook.NumberFormats
import com.neutrino.sheets.core.workbook.SheetSelection
import com.neutrino.sheets.core.workbook.SheetTabs
import com.neutrino.sheets.core.workbook.TableStyle
import com.neutrino.sheets.core.workbook.TableStyles
import com.neutrino.shared.android.ui.ConfirmDialog
import com.neutrino.shared.android.ui.TextInputDialog

sealed class EditorDialog {
    object TextColor : EditorDialog()
    object FillColor : EditorDialog()
    object Borders : EditorDialog()
    object Format : EditorDialog()
    object TableStyles : EditorDialog()
    data class Filter(val column: Int) : EditorDialog()
    data class Resize(val columns: Boolean) : EditorDialog()
    data class RenameSheet(val index: Int) : EditorDialog()
    data class SheetColor(val index: Int) : EditorDialog()
    data class DeleteSheet(val index: Int) : EditorDialog()
}

/** The web's colour picker palette, row by row. */
private val PALETTE = listOf(
    "#000000", "#434343", "#666666", "#999999", "#cccccc", "#efefef", "#ffffff",
    "#ef4444", "#f97316", "#f59e0b", "#22c55e", "#14b8a6", "#3b82f6", "#6366f1", "#a855f7", "#ec4899",
    "#fee2e2", "#ffedd5", "#fef3c7", "#dcfce7", "#ccfbf1", "#dbeafe", "#e0e7ff", "#ede9fe", "#fce7f3",
    "#b91c1c", "#c2410c", "#b45309", "#15803d", "#0f766e", "#1e40af", "#4338ca", "#6d28d9", "#be185d",
)

@Composable
fun EditorDialogs(dialog: EditorDialog, editor: SheetEditor, onDismiss: () -> Unit) {
    when (dialog) {
        EditorDialog.TextColor -> ColorDialog("Text Colour", editor.selectionStyle?.color, onDismiss) { editor.applyStyle(CellStylePatch.textColor(it), "Text Colour"); onDismiss() }
        EditorDialog.FillColor -> ColorDialog("Fill Colour", editor.selectionStyle?.backgroundColor, onDismiss) { editor.applyStyle(CellStylePatch.fillColor(it), "Fill Colour"); onDismiss() }
        EditorDialog.Borders -> BordersDialog(editor, onDismiss)
        EditorDialog.Format -> FormatDialog(editor, onDismiss)
        EditorDialog.TableStyles -> TableStyleDialog(editor, onDismiss)
        is EditorDialog.Filter -> FilterDialog(editor, dialog.column, onDismiss)
        is EditorDialog.Resize -> ResizeDialog(editor, dialog.columns, onDismiss)
        is EditorDialog.RenameSheet -> TextInputDialog("Rename Sheet", editor.sheetNames.getOrElse(dialog.index) { "" }, "Name", "Rename", onDismiss = onDismiss) {
            editor.renameSheet(dialog.index, it); onDismiss()
        }
        is EditorDialog.SheetColor -> ColorDialog("Tab Colour", editor.file.value?.sheets?.getOrNull(dialog.index)?.color, onDismiss, SheetTabs.colors.map { it.second }) {
            editor.setSheetColor(dialog.index, it); onDismiss()
        }
        is EditorDialog.DeleteSheet -> ConfirmDialog("Delete “${editor.sheetNames.getOrElse(dialog.index) { "" }}”?", "The sheet and everything on it is removed. Undo brings it back.",
            "Delete", destructive = true, onDismiss = onDismiss) { editor.deleteSheet(dialog.index) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorDialog(title: String, current: String?, onDismiss: () -> Unit, colors: List<String> = PALETTE, onPick: (String?) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                colors.forEach { hex ->
                    val selected = com.neutrino.shared.core.ooxml.XlsxCodec.normalizeColor(current) == hex
                    Box(Modifier.size(32.dp).background(Color(cssColor(hex)!!), CircleShape)
                        .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, CircleShape)
                        .clickable { onPick(hex) })
                }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(null) }) { Text("None") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun BordersDialog(editor: SheetEditor, onDismiss: () -> Unit) {
    var weight by remember { mutableStateOf("thin") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Borders") },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("thin", "medium", "thick").forEach { w -> FilterChip(weight == w, { weight = w }, label = { Text(w.replaceFirstChar(Char::uppercase)) }) }
                }
                fun act(name: String, patch: CellStylePatch) { editor.applyStyle(patch, name); onDismiss() }
                TextButton(onClick = { act("Borders", CellStylePatch.allBorders(weight)) }) { Text("All sides") }
                TextButton(onClick = { act("Top Border", CellStylePatch.border(CellStyle.Side.TOP, weight)) }) { Text("Top") }
                TextButton(onClick = { act("Bottom Border", CellStylePatch.border(CellStyle.Side.BOTTOM, weight)) }) { Text("Bottom") }
                TextButton(onClick = { act("Left Border", CellStylePatch.border(CellStyle.Side.LEFT, weight)) }) { Text("Left") }
                TextButton(onClick = { act("Right Border", CellStylePatch.border(CellStyle.Side.RIGHT, weight)) }) { Text("Right") }
                TextButton(onClick = { act("No Borders", CellStylePatch.allBorders(null)) }) { Text("None") }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Font, size, vertical alignment, wrap, and number formats with live previews. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FormatDialog(editor: SheetEditor, onDismiss: () -> Unit) {
    val style = editor.selectionStyle
    var category by remember { mutableStateOf(NumberFormats.Category.of(style?.numberFormat)) }
    var custom by remember { mutableStateOf(style?.customFormat ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Format") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Font", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(null to "Default", "Georgia" to "Serif", "Courier New" to "Mono").forEach { (family, label) ->
                        FilterChip(style?.fontFamily == family, { editor.applyStyle(CellStylePatch.fontFamily(family), "Font") }, label = { Text(label) })
                    }
                }
                Text("Size", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(null, 10, 12, 14, 18, 24).forEach { size ->
                        FilterChip(style?.pointSize?.toInt() == size, { editor.applyStyle(CellStylePatch.fontSize(size), "Font Size") }, label = { Text(size?.toString() ?: "Default") })
                    }
                }
                Text("Vertical", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("top" to "Top", "middle" to "Middle", "bottom" to "Bottom").forEach { (v, label) ->
                        FilterChip(style?.verticalAlign == v, { editor.applyStyle(CellStylePatch.verticalAlign(v), "Vertical Alignment") }, label = { Text(label) })
                    }
                }
                Text("Text", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(null to "Overflow", "wrap" to "Wrap", "clip" to "Clip").forEach { (v, label) ->
                        FilterChip(style?.wrapMode == v, { editor.applyStyle(CellStylePatch.wrapMode(v), "Text Wrapping") }, label = { Text(label) })
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text("Number Format", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    NumberFormats.Category.entries.forEach { c -> FilterChip(category == c, { category = c }, label = { Text(c.title) }) }
                }
                NumberFormats.presets[category].orEmpty().forEach { preset ->
                    Row(Modifier.fillMaxWidth().clickable { editor.applyStyle(NumberFormats.patch(preset.customFormat, category), "Number Format"); custom = preset.customFormat }.padding(vertical = 8.dp)) {
                        Text(preset.label, Modifier.weight(1f), fontWeight = if (style?.customFormat == preset.customFormat) FontWeight.Bold else null)
                        Text(preset.customFormat, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                }
                OutlinedTextField(custom, { custom = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Custom pattern") },
                    supportingText = { Text("Preview: ${NumberFormats.preview(custom, category)}") })
                Row {
                    TextButton(onClick = { editor.applyStyle(NumberFormats.patch(custom, category), "Number Format") }, enabled = custom.isNotBlank()) { Text("Apply Pattern") }
                    TextButton(onClick = { editor.applyStyle(CellStylePatch(numberFormat = com.neutrino.sheets.core.workbook.StyleUpdate.Clear, decimalPlaces = com.neutrino.sheets.core.workbook.StyleUpdate.Clear,
                        customFormat = com.neutrino.sheets.core.workbook.StyleUpdate.Clear), "Plain Number"); custom = "" }) { Text("Plain") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun TableStyleDialog(editor: SheetEditor, onDismiss: () -> Unit) {
    var variant by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Table Style") },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("" to "Grid", "-horizontal" to "Lines", "-no-border" to "None").forEach { (v, label) -> FilterChip(variant == v, { variant = v }, label = { Text(label) }) }
                }
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(TableStyles.all.filter { it is TableStyle.Blank || (it.id.endsWith(variant) && (variant.isNotEmpty() || (!it.id.endsWith("-horizontal") && !it.id.endsWith("-no-border")))) }) { style ->
                        Row(Modifier.fillMaxWidth().clickable { editor.applyTableStyle(style); onDismiss() }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (style is TableStyle.Regular) {
                                Column(Modifier.size(44.dp, 30.dp).border(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                                    Box(Modifier.fillMaxWidth().weight(1f).background(Color(cssColor(style.headerBackground)!!)))
                                    Box(Modifier.fillMaxWidth().weight(1f).background(Color(cssColor(style.bandColorA)!!)))
                                    Box(Modifier.fillMaxWidth().weight(1f).background(Color(cssColor(style.bandColorB)!!)))
                                }
                            } else Box(Modifier.size(44.dp, 30.dp).border(1.dp, MaterialTheme.colorScheme.outlineVariant))
                            Text(style.name, Modifier.padding(start = 12.dp))
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun FilterDialog(editor: SheetEditor, column: Int, onDismiss: () -> Unit) {
    val values = remember { editor.filterValues(column) }
    val current = editor.columnFilters.value.allowed[column]
    var chosen by remember { mutableStateOf(current ?: values.toSet()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Filter Column ${CellAddress.columnLetters(column)}") },
        text = {
            Column {
                Row {
                    TextButton(onClick = { chosen = values.toSet() }) { Text("Select All") }
                    TextButton(onClick = { chosen = emptySet() }) { Text("Clear") }
                }
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(values) { v ->
                        Row(Modifier.fillMaxWidth().clickable { chosen = if (v in chosen) chosen - v else chosen + v }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(v in chosen, { chosen = if (it) chosen + v else chosen - v })
                            Text(v.ifEmpty { "(Blanks)" })
                        }
                    }
                }
                Text("Filters hide rows on this device only; nothing is saved.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { editor.setFilter(if (chosen.size == values.size) null else chosen, column); onDismiss() }) { Text("Apply") } },
        dismissButton = { TextButton(onClick = { editor.setFilter(null, column); onDismiss() }) { Text("Remove Filter") } },
    )
}

@Composable
private fun ResizeDialog(editor: SheetEditor, columns: Boolean, onDismiss: () -> Unit) {
    val range = editor.selection.value?.range ?: return onDismiss()
    val sheet = editor.activeSheet
    val current = if (columns) sheet?.width(range.start.column) ?: GridGeometry.COLUMN_WIDTH else sheet?.height(range.start.row) ?: GridGeometry.ROW_HEIGHT
    var text by remember { mutableStateOf(current.toInt().toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (columns) "Column Width" else "Row Height") },
        text = {
            OutlinedTextField(text, { text = it.filter(Char::isDigit) }, singleLine = true, label = { Text("Pixels") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        },
        confirmButton = {
            TextButton(onClick = {
                val v = text.toDoubleOrNull() ?: return@TextButton
                if (columns) editor.resizeColumns(range.columns, v) else editor.resizeRows(range.rows, v)
                onDismiss()
            }) { Text("Set") }
        },
        dismissButton = {
            TextButton(onClick = {
                if (columns) editor.resizeColumns(range.columns, null, "Default Width") else editor.resizeRows(range.rows, null, "Default Height")
                onDismiss()
            }) { Text("Default") }
        },
    )
}

/** Find over what was typed, stepping through matches; replace one or all. */
@Composable
fun FindBar(editor: SheetEditor, onClose: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var replacement by remember { mutableStateOf("") }
    var matchCase by remember { mutableStateOf(false) }
    var entire by remember { mutableStateOf(false) }
    var index by remember { mutableStateOf(0) }
    val options = FindReplace.Options(matchCase, entire)
    val matches = editor.matches(query, options)
    fun go(i: Int) {
        if (matches.isEmpty()) return
        index = ((i % matches.size) + matches.size) % matches.size
        editor.select(SheetSelection(matches[index]))
    }
    Surface(tonalElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(query, { query = it; index = 0 }, Modifier.weight(1f), singleLine = true, placeholder = { Text("Find") },
                    supportingText = { if (query.isNotEmpty()) Text(if (matches.isEmpty()) "No matches" else "${index + 1} of ${matches.size}") })
                IconButton(onClick = { go(index - 1) }, enabled = matches.isNotEmpty()) { Icon(Icons.Filled.KeyboardArrowUp, "Previous") }
                IconButton(onClick = { go(index + 1) }, enabled = matches.isNotEmpty()) { Icon(Icons.Filled.KeyboardArrowDown, "Next") }
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Close") }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(matchCase, { matchCase = !matchCase }, label = { Text("Match case") })
                FilterChip(entire, { entire = !entire }, label = { Text("Entire cell") })
            }
            if (editor.isEditable) Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(replacement, { replacement = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("Replace with") })
                TextButton(onClick = { matches.getOrNull(index)?.let { editor.replace(it, query, replacement, options) } }, enabled = matches.isNotEmpty()) { Text("Replace") }
                TextButton(onClick = { editor.replaceAll(query, replacement, options) }, enabled = matches.isNotEmpty()) { Text("All") }
            }
        }
    }
}
