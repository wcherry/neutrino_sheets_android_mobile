package com.neutrino.sheets.core.workbook

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * What a cell shows: its value through its number format — the iOS `CellFormatter`, itself the web's
 * `formatCellValue`/`applyCustomFormat`. US conventions throughout, because that is what the web
 * renders and a sheet must read the same on every device. All dates are UTC.
 */
object CellFormatter {

    fun display(value: String?, style: CellStyle?): String {
        if (value.isNullOrEmpty()) return ""
        style?.customFormat?.takeIf { it.isNotEmpty() }?.let { return applyCustomFormat(value, it) }
        val format = style?.numberFormat ?: return value
        return when (format) {
            "currency", "percent", "number" -> {
                val number = parseNumber(value) ?: return value
                val decimals = style.decimalPlaces ?: if (format == "currency") 2 else 0
                when (format) {
                    "currency" -> currency(number, decimals)
                    "percent" -> percent(number, decimals)
                    else -> decimal(number, decimals, true)
                }
            }
            "date", "time", "datetime" -> {
                val date = parseCellDate(value) ?: return value
                when (format) {
                    "date" -> dateFormat.format(date)
                    "time" -> timeFormat.format(date)
                    else -> dateTimeFormat.format(date)
                }
            }
            else -> value
        }
    }

    /** The leading number in `value`, as `parseFloat` reads it; null when there is none. */
    fun parseNumber(value: String): Double? {
        val t = value.trim()
        if (t.isEmpty()) return null
        var end = 0
        var seenDigit = false
        var seenDot = false
        var seenExponent = false
        var i = 0
        while (i < t.length) {
            val c = t[i]
            when {
                c.isDigit() -> seenDigit = true
                c == '.' && !seenDot && !seenExponent -> seenDot = true
                (c == 'e' || c == 'E') && seenDigit && !seenExponent -> {
                    seenExponent = true
                    if (i + 1 < t.length && (t[i + 1] == '+' || t[i + 1] == '-')) i++
                }
                (c == '-' || c == '+') && i == 0 -> Unit
                else -> break
            }
            i++
            end = i
        }
        if (!seenDigit) return null
        return t.substring(0, end).toDoubleOrNull() ?: t.substring(0, end).trimEnd('e', 'E', '+', '-').toDoubleOrNull()
    }

    private fun pattern(decimals: Int, grouping: Boolean, prefix: String = "", suffix: String = ""): DecimalFormat {
        val frac = if (decimals > 0) "." + "0".repeat(decimals) else ""
        return DecimalFormat("$prefix${if (grouping) "#,##0" else "0"}$frac$suffix", DecimalFormatSymbols(Locale.US)).apply { roundingMode = RoundingMode.HALF_UP }
    }

    private fun exact(value: Double) = BigDecimal(value.toString())

    fun decimal(value: Double, decimals: Int, grouping: Boolean): String = pattern(decimals, grouping).format(exact(value))

    fun currency(value: Double, decimals: Int): String {
        val body = pattern(decimals, true).format(exact(kotlin.math.abs(value)))
        val negative = value < 0 && body.any { it in '1'..'9' }
        return (if (negative) "-$" else "$") + body
    }

    fun percent(value: Double, decimals: Int): String = pattern(decimals, true).format(exact(value).multiply(BigDecimal(100))) + "%"

    // ── Dates ───────────────────────────────────────────────────────────────────────────────────

    private const val EXCEL_EPOCH = -2_208_988_800L

    /** Excel's serial day number (with its 1900 leap-year bug) as an instant. */
    fun dateFromExcelSerial(serial: Double): Instant {
        val adjusted = if (serial > 60) serial - 1 else serial
        return Instant.ofEpochSecond(Math.round(EXCEL_EPOCH + (adjusted - 1) * 86_400))
    }

    fun excelSerial(date: Instant): Double {
        val days = Math.round((date.epochSecond - EXCEL_EPOCH) / 86_400.0).toDouble()
        val adjusted = days + 1
        return if (adjusted > 59) adjusted + 1 else adjusted
    }

    /** A date, time or date-time string as a serial; null when it is none of them. */
    fun serialFromDateString(value: String): Double? {
        val text = value.trim()
        if (text.isEmpty()) return null
        var datePart = text
        var timePart: String? = null
        val split = splitPoint(text)
        if (split != null) {
            datePart = text.substring(0, split)
            timePart = text.substring(split + 1)
        } else {
            parseTimeParts(text)?.let { return fraction(it) }
        }
        val day = parseDateParts(datePart) ?: return null
        var serial = excelSerial(day.atStartOfDay().toInstant(ZoneOffset.UTC))
        if (timePart != null) serial += fraction(parseTimeParts(timePart) ?: return null)
        return serial
    }

    fun parseCellDate(value: String): Instant? {
        val text = value.trim()
        if (text.isEmpty()) return null
        if (isPlainNumber(text)) text.toDoubleOrNull()?.let { return dateFromExcelSerial(it) }
        serialFromDateString(text)?.let { return dateFromExcelSerial(it) }
        return runCatching { OffsetDateTime.parse(text).toInstant() }.getOrNull()
    }

    fun applyCustomFormat(value: String, pattern: String): String {
        if (isDateOrTimePattern(pattern)) {
            val date = parseCellDate(value) ?: return value
            return formatDate(date, pattern)
        }
        val number = parseNumber(value) ?: return value
        val decimals = decimalPlaces(pattern)
        return when {
            '%' in pattern -> percent(number, decimals)
            '$' in pattern -> currency(number, decimals)
            else -> decimal(number, decimals, ',' in pattern)
        }
    }

    fun isDateOrTimePattern(pattern: String): Boolean {
        val bare = maskLiterals(pattern, ArrayList())
        return bare.any { it in "dmyDMY" } || 'h' in bare.lowercase() || 's' in bare.lowercase()
    }

    fun formatDate(date: Instant, pattern: String): String {
        val literals = ArrayList<String>()
        val masked = maskLiterals(pattern, literals)
        val tokens = tokenize(masked)
        val hasMeridiem = tokens.any { it.first.lowercase() == "am/pm" || it.first.lowercase() == "a/p" }
        val minuteTokens = HashSet<Int>()
        tokens.forEachIndexed { i, (text, _) ->
            val lower = text.lowercase()
            if (lower != "m" && lower != "mm") return@forEachIndexed
            val previous = tokens.getOrNull(i - 1)?.first?.lowercase()
            val next = tokens.getOrNull(i + 1)?.first?.lowercase()
            if (previous == "h" || previous == "hh" || next == "s" || next == "ss") minuteTokens += i
        }
        val dt = LocalDateTime.ofInstant(date, ZoneOffset.UTC)
        val out = StringBuilder()
        var cursor = 0
        tokens.forEachIndexed { i, (text, range) ->
            out.append(masked, cursor, range.first)
            out.append(substitute(text, i in minuteTokens, hasMeridiem, dt))
            cursor = range.last + 1
        }
        out.append(masked.substring(cursor))
        return restoreLiterals(out.toString(), literals)
    }

    private fun substitute(token: String, isMinute: Boolean, hasMeridiem: Boolean, dt: LocalDateTime): String {
        val hour24 = dt.hour
        val hour = if (hasMeridiem) (if (hour24 % 12 == 0) 12 else hour24 % 12) else hour24
        val weekday = dt.dayOfWeek.value % 7 // Sunday = 0
        return when (token.lowercase()) {
            "mmmm" -> MONTHS[dt.monthValue - 1]
            "mmm" -> MONTHS_SHORT[dt.monthValue - 1]
            "mm" -> pad(if (isMinute) dt.minute else dt.monthValue)
            "m" -> (if (isMinute) dt.minute else dt.monthValue).toString()
            "dddd" -> DAYS[weekday]
            "ddd" -> DAYS_SHORT[weekday]
            "dd" -> pad(dt.dayOfMonth)
            "d" -> dt.dayOfMonth.toString()
            "yyyy" -> dt.year.toString()
            "yy" -> dt.year.toString().takeLast(2)
            "hh" -> pad(hour)
            "h" -> hour.toString()
            "ss" -> pad(dt.second)
            "s" -> dt.second.toString()
            "am/pm" -> { val lower = token == token.lowercase(); if (hour24 < 12) (if (lower) "am" else "AM") else (if (lower) "pm" else "PM") }
            "a/p" -> { val lower = token == token.lowercase(); if (hour24 < 12) (if (lower) "a" else "A") else (if (lower) "p" else "P") }
            else -> token
        }
    }

    private val CANDIDATES = listOf("mmmm", "mmm", "dddd", "ddd", "am/pm", "a/p", "yyyy", "yy", "mm", "dd", "hh", "ss", "m", "d", "h", "s")

    private fun tokenize(pattern: String): List<Pair<String, IntRange>> {
        val out = ArrayList<Pair<String, IntRange>>()
        var i = 0
        outer@ while (i < pattern.length) {
            for (candidate in CANDIDATES) {
                val end = i + candidate.length
                if (end <= pattern.length && pattern.substring(i, end).lowercase() == candidate) {
                    out += pattern.substring(i, end) to (i until end)
                    i = end
                    continue@outer
                }
            }
            i++
        }
        return out
    }

    private fun decimalPlaces(pattern: String): Int {
        val dot = pattern.indexOf('.')
        if (dot < 0) return 0
        return pattern.substring(dot + 1).takeWhile { it == '0' || it == '#' }.length
    }

    /** Quoted literals out of the pattern and into `literals`, leaving `\u0000n\u0000` markers. */
    private fun maskLiterals(pattern: String, literals: MutableList<String>): String {
        val out = StringBuilder()
        var inLiteral = false
        val current = StringBuilder()
        for (c in pattern) {
            if (c == '"') {
                if (inLiteral) {
                    out.append('\u0000').append(literals.size).append('\u0000')
                    literals += current.toString()
                    current.clear()
                }
                inLiteral = !inLiteral
            } else if (inLiteral) current.append(c) else out.append(c)
        }
        if (inLiteral) out.append(current)
        return out.toString()
    }

    private fun restoreLiterals(text: String, literals: List<String>): String =
        Regex("\u0000(\\d+)\u0000").replace(text) { m -> literals.getOrNull(m.groupValues[1].toInt()) ?: m.value }

    /** The last space or `T` after which the rest is a time. */
    private fun splitPoint(text: String): Int? {
        for (i in text.indices.reversed()) {
            if ((text[i] == ' ' || text[i] == 'T') && i + 1 < text.length && parseTimeParts(text.substring(i + 1)) != null) return i
        }
        return null
    }

    private fun parseDateParts(text: String): LocalDate? {
        val iso = text.split("-")
        val us = text.split("/")
        val (year, month, day) = when {
            iso.size == 3 && iso[0].length == 4 -> Triple(iso[0].toIntOrNull() ?: return null, iso[1].toIntOrNull() ?: return null, iso[2].toIntOrNull() ?: return null)
            us.size == 3 -> {
                var y = us[2].toIntOrNull() ?: return null
                if (y < 100) y += if (y < 50) 2000 else 1900
                Triple(y, us[0].toIntOrNull() ?: return null, us[1].toIntOrNull() ?: return null)
            }
            else -> return null
        }
        if (month !in 1..12 || day !in 1..31) return null
        return runCatching { LocalDate.of(year, month, day) }.getOrNull()
    }

    private fun parseTimeParts(text: String): Triple<Int, Int, Int>? {
        var body = text.trim()
        var meridiem: String? = null
        for (suffix in listOf("am", "pm")) {
            if (body.lowercase().endsWith(suffix)) {
                meridiem = suffix
                body = body.dropLast(2).trim()
                break
            }
        }
        val parts = body.split(":")
        if (parts.size !in 2..3) return null
        var hour = parts[0].toIntOrNull() ?: return null
        if (parts[1].length != 2) return null
        val minute = parts[1].toIntOrNull() ?: return null
        val second = if (parts.size == 3) parts[2].toIntOrNull() ?: return null else 0
        if (minute > 59 || second > 59 || hour < 0) return null
        if (meridiem != null) {
            if (hour !in 1..12) return null
            hour = if (meridiem == "am") hour % 12 else hour % 12 + 12
        } else if (hour > 23) return null
        return Triple(hour, minute, second)
    }

    private fun fraction(t: Triple<Int, Int, Int>) = (t.first * 3600 + t.second * 60 + t.third) / 86_400.0

    private fun isPlainNumber(text: String) =
        text.isNotEmpty() && text.all { it.isDigit() || it == '.' } && text.count { it == '.' } <= 1 && text.first() != '.'

    private fun pad(v: Int) = if (v < 10) "0$v" else v.toString()

    private val dateFormat = DateTimeFormatter.ofPattern("M/d/yyyy", Locale.US).withZone(ZoneOffset.UTC)
    private val timeFormat = DateTimeFormatter.ofPattern("h:mm:ss a", Locale.US).withZone(ZoneOffset.UTC)
    private val dateTimeFormat = DateTimeFormatter.ofPattern("M/d/yyyy, h:mm:ss a", Locale.US).withZone(ZoneOffset.UTC)

    private val MONTHS = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")
    private val MONTHS_SHORT = MONTHS.map { it.take(3) }
    private val DAYS = listOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")
    private val DAYS_SHORT = DAYS.map { it.take(3) }
}

/** The format sheet's categories and presets — the web's, value for value. */
object NumberFormats {
    enum class Category(val title: String, val sample: String) {
        NUMBER("Number", "1234.5678"), CURRENCY("Currency", "1234.5678"), PERCENT("Percent", "1234.5678"), DATETIME("Date & Time", "2025-11-01T13:30:00Z");

        companion object {
            fun of(numberFormat: String?) = when (numberFormat) {
                "date", "time", "datetime" -> DATETIME
                "currency" -> CURRENCY
                "percent" -> PERCENT
                else -> NUMBER
            }
        }
    }

    data class Preset(val label: String, val customFormat: String, val numberFormat: String, val decimalPlaces: Int?)

    val presets: Map<Category, List<Preset>> = mapOf(
        Category.NUMBER to listOf(
            Preset("1,234", "#,##0", "number", 0), Preset("1,234.0", "#,##0.0", "number", 1), Preset("1,234.00", "#,##0.00", "number", 2),
            Preset("1,234.000", "#,##0.000", "number", 3), Preset("1,234.0000", "#,##0.0000", "number", 4),
            Preset("1234", "0", "number", 0), Preset("1234.00", "0.00", "number", 2),
        ),
        Category.CURRENCY to listOf(
            Preset("$1,234.00", "\"$\"#,##0.00", "currency", 2), Preset("$1,234", "\"$\"#,##0", "currency", 0),
            Preset("$1,234.0", "\"$\"#,##0.0", "currency", 1), Preset("$1,234.000", "\"$\"#,##0.000", "currency", 3),
            Preset("$1,234.0000", "\"$\"#,##0.0000", "currency", 4),
        ),
        Category.PERCENT to listOf(
            Preset("75%", "0%", "percent", 0), Preset("75.0%", "0.0%", "percent", 1), Preset("75.00%", "0.00%", "percent", 2), Preset("75.000%", "0.000%", "percent", 3),
        ),
        Category.DATETIME to listOf(
            Preset("11/1/2025", "M/D/yyyy", "date", null), Preset("11/01/2025", "MM/DD/yyyy", "date", null),
            Preset("Nov 2025", "mmm yyyy", "date", null), Preset("November 1, 2025", "mmmm D, yyyy", "date", null),
            Preset("1-Nov-25", "D-mmm-yy", "date", null), Preset("Sat, 1 Nov 2025", "ddd, D mmm yyyy", "date", null),
            Preset("Saturday, 1 Nov 2025", "dddd, D mmm yyyy", "date", null), Preset("1:30 PM", "h:mm AM/PM", "time", null),
            Preset("1:30:00 PM", "h:mm:ss AM/PM", "time", null), Preset("13:30", "hh:mm", "time", null), Preset("13:30:00", "hh:mm:ss", "time", null),
            Preset("11/1/2025 1:30 PM", "M/D/yyyy h:mm AM/PM", "datetime", null), Preset("11/1/2025 13:30", "M/D/yyyy hh:mm", "datetime", null),
            Preset("November 1, 2025 1:30 PM", "mmmm D, yyyy h:mm AM/PM", "datetime", null),
        ),
    )

    val allPresets get() = Category.entries.flatMap { presets[it].orEmpty() }

    /** A pattern as a style patch: a preset's structured format when it is one, the category otherwise. */
    fun patch(pattern: String, category: Category): CellStylePatch {
        val match = allPresets.firstOrNull { it.customFormat == pattern }
        return CellStylePatch(
            numberFormat = StyleUpdate.Set(match?.numberFormat ?: category.name.lowercase()),
            decimalPlaces = StyleUpdate.of(match?.decimalPlaces),
            customFormat = StyleUpdate.of(pattern.ifEmpty { null }),
        )
    }

    fun preview(pattern: String, category: Category) = if (pattern.isEmpty()) category.sample else CellFormatter.applyCustomFormat(category.sample, pattern)
}

/** A change to one style field: leave it, set it, or remove it. */
sealed class StyleUpdate<out T> {
    object Unchanged : StyleUpdate<Nothing>()
    data class Set<T>(val value: T) : StyleUpdate<T>()
    object Clear : StyleUpdate<Nothing>()

    fun resolve(current: @UnsafeVariance T?): T? = when (this) {
        Unchanged -> current
        is Set -> value
        Clear -> null
    }

    companion object {
        fun <T> of(value: T?): StyleUpdate<T> = if (value != null) Set(value) else Clear
    }
}

/** One formatting command, applied the same way to every cell in a selection. */
data class CellStylePatch(
    val fontFamily: StyleUpdate<String> = StyleUpdate.Unchanged,
    val fontSize: StyleUpdate<String> = StyleUpdate.Unchanged,
    val fontWeight: StyleUpdate<String> = StyleUpdate.Unchanged,
    val fontStyle: StyleUpdate<String> = StyleUpdate.Unchanged,
    val textDecoration: StyleUpdate<String> = StyleUpdate.Unchanged,
    val color: StyleUpdate<String> = StyleUpdate.Unchanged,
    val backgroundColor: StyleUpdate<String> = StyleUpdate.Unchanged,
    val textAlign: StyleUpdate<String> = StyleUpdate.Unchanged,
    val verticalAlign: StyleUpdate<String> = StyleUpdate.Unchanged,
    val wrapMode: StyleUpdate<String> = StyleUpdate.Unchanged,
    val borderStyle: StyleUpdate<String> = StyleUpdate.Unchanged,
    val borderTop: StyleUpdate<String> = StyleUpdate.Unchanged,
    val borderRight: StyleUpdate<String> = StyleUpdate.Unchanged,
    val borderBottom: StyleUpdate<String> = StyleUpdate.Unchanged,
    val borderLeft: StyleUpdate<String> = StyleUpdate.Unchanged,
    val numberFormat: StyleUpdate<String> = StyleUpdate.Unchanged,
    val decimalPlaces: StyleUpdate<Int> = StyleUpdate.Unchanged,
    val customFormat: StyleUpdate<String> = StyleUpdate.Unchanged,
) {
    val isEmpty get() = this == CellStylePatch()

    /** The style after this patch; null when nothing is left, so an unformatted cell stores no style. */
    fun applied(style: CellStyle?): CellStyle? {
        val s = style ?: CellStyle()
        val out = s.copy(
            fontFamily = fontFamily.resolve(s.fontFamily), fontSize = fontSize.resolve(s.fontSize), fontWeight = fontWeight.resolve(s.fontWeight),
            fontStyle = fontStyle.resolve(s.fontStyle), textDecoration = textDecoration.resolve(s.textDecoration), color = color.resolve(s.color),
            backgroundColor = backgroundColor.resolve(s.backgroundColor), textAlign = textAlign.resolve(s.textAlign),
            verticalAlign = verticalAlign.resolve(s.verticalAlign), wrapMode = wrapMode.resolve(s.wrapMode),
            borderStyle = borderStyle.resolve(s.borderStyle), borderTop = borderTop.resolve(s.borderTop), borderRight = borderRight.resolve(s.borderRight),
            borderBottom = borderBottom.resolve(s.borderBottom), borderLeft = borderLeft.resolve(s.borderLeft),
            numberFormat = numberFormat.resolve(s.numberFormat), decimalPlaces = decimalPlaces.resolve(s.decimalPlaces), customFormat = customFormat.resolve(s.customFormat),
        )
        return if (out.isEmpty) null else out
    }

    companion object {
        fun bold(on: Boolean) = CellStylePatch(fontWeight = StyleUpdate.Set(if (on) "bold" else "normal"))
        fun italic(on: Boolean) = CellStylePatch(fontStyle = StyleUpdate.Set(if (on) "italic" else "normal"))
        fun strikethrough(on: Boolean) = CellStylePatch(textDecoration = StyleUpdate.Set(if (on) "line-through" else "none"))
        fun fontFamily(family: String?) = CellStylePatch(fontFamily = StyleUpdate.of(family))
        fun fontSize(points: Int?) = CellStylePatch(fontSize = StyleUpdate.of(points?.let { "${it}pt" }))
        fun textColor(css: String?) = CellStylePatch(color = StyleUpdate.of(css))
        fun fillColor(css: String?) = CellStylePatch(backgroundColor = StyleUpdate.of(css))
        fun textAlign(v: String?) = CellStylePatch(textAlign = StyleUpdate.of(v))
        fun verticalAlign(v: String?) = CellStylePatch(verticalAlign = StyleUpdate.of(v))
        fun wrapMode(v: String?) = CellStylePatch(wrapMode = StyleUpdate.of(v))
        fun allBorders(weight: String?) = CellStylePatch(borderStyle = StyleUpdate.Set(weight ?: "none"),
            borderTop = StyleUpdate.Clear, borderRight = StyleUpdate.Clear, borderBottom = StyleUpdate.Clear, borderLeft = StyleUpdate.Clear)
        fun border(side: CellStyle.Side, weight: String?): CellStylePatch {
            val u = StyleUpdate.Set(weight ?: "none")
            return when (side) {
                CellStyle.Side.TOP -> CellStylePatch(borderTop = u)
                CellStyle.Side.RIGHT -> CellStylePatch(borderRight = u)
                CellStyle.Side.BOTTOM -> CellStylePatch(borderBottom = u)
                CellStyle.Side.LEFT -> CellStylePatch(borderLeft = u)
            }
        }
        fun numberFormat(v: String?) = CellStylePatch(numberFormat = StyleUpdate.of(v))
        fun decimalPlaces(places: Int) = CellStylePatch(decimalPlaces = StyleUpdate.Set(maxOf(0, places)))

        val clearFormatting = CellStylePatch(
            StyleUpdate.Clear, StyleUpdate.Clear, StyleUpdate.Clear, StyleUpdate.Clear, StyleUpdate.Clear, StyleUpdate.Clear, StyleUpdate.Clear,
            StyleUpdate.Clear, StyleUpdate.Clear, StyleUpdate.Clear, StyleUpdate.Clear, StyleUpdate.Clear, StyleUpdate.Clear, StyleUpdate.Clear,
            StyleUpdate.Clear, StyleUpdate.Clear, StyleUpdate.Clear, StyleUpdate.Clear,
        )
    }
}
