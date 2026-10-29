package io.github.agitangst.ampulse.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.annotation.StringRes
import io.github.agitangst.ampulse.R
import io.github.agitangst.ampulse.domain.PressureState
import io.github.agitangst.ampulse.domain.Target
import io.github.agitangst.ampulse.domain.classify
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.NumberFormat
import java.time.LocalDateTime

/**
 * PDF отчёта врачу (этап 0.8, задача 3.1): A4 595×842 pt, чёрный текст на белом и один акцент `#0B6B63`
 * (docs/ui-guideline.md, «PDF отчёта»). Текст — `StaticLayout` (кириллица, перенос строк), таблицы переносятся на
 * следующие страницы строка за строкой с повтором шапки; внизу каждой страницы — номер и оговорка. Язык — языка
 * приложения: тексты берутся через [MedTexts] экрана.
 */
internal object ReportPdf {
    private const val PAGE_W = 595
    private const val PAGE_H = 842
    private const val MARGIN = 40f
    private const val TOP = 44f

    /** Ниже этой линии содержимое не идёт — там подвал страницы. */
    private const val BOTTOM = PAGE_H - 58f
    private const val FOOTER_LINE = PAGE_H - 44f
    private const val CONTENT_W = PAGE_W - 2 * MARGIN

    private val INK = 0xFF000000.toInt()
    private val GREY = 0xFF555555.toInt()
    private val RULE = 0xFFBDBDBD.toInt()
    private val ACCENT = 0xFF0B6B63.toInt()

    private const val CELL_PAD_X = 4f
    private const val CELL_PAD_Y = 3.5f

    /** Папка отчётов в кэше; `file_paths.xml` отдаёт её через FileProvider. */
    const val DIR = "reports"

    /**
     * Собирает PDF в `cacheDir/reports/ampulse-report-<дата>.pdf`; прежние отчёты удаляются. Тяжёлая работа — звать не
     * с главного потока. [version] — «0.8.0» (или с номером сборки) для шапки.
     */
    fun create(context: Context, model: ReportModel, texts: MedTexts, createdAt: LocalDateTime, version: String): File {
        val dir = File(context.cacheDir, DIR).apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "ampulse-report-${createdAt.toLocalDate()}.pdf")
        try {
            // Первый проход считает страницы, второй пишет «Стр. N из M».
            val pages = render(model, texts, createdAt, version, total = 0, out = null)
            FileOutputStream(file).use { render(model, texts, createdAt, version, total = pages, out = it) }
        } catch (e: Exception) {
            file.delete()
            throw e
        }
        return file
    }

    /** Рисует документ; [out] = `null` — пробный проход только ради числа страниц. */
    private fun render(
        model: ReportModel,
        texts: MedTexts,
        createdAt: LocalDateTime,
        version: String,
        total: Int,
        out: OutputStream?,
    ): Int {
        val doc = PdfDocument()
        try {
            val w = Writer(doc, texts, total)
            w.header(model, createdAt, version)
            if (model.doses) {
                w.adherence(model)
                w.skips(model)
                w.extras(model)
                w.prn(model)
            }
            model.pressure?.let { w.pressure(it) }
            w.finish()
            out?.let { doc.writeTo(it) }
            return w.pageCount
        } finally {
            doc.close()
        }
    }

    private class Col(val title: String, val width: Float, val align: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL)

    private class Cell(val text: String, val bold: Boolean = false, val color: Int = INK)

    private class Writer(private val doc: PdfDocument, private val t: MedTexts, private val total: Int) {
        var pageCount = 0
            private set
        private var page: PdfDocument.Page? = null
        private var canvas: Canvas? = null
        private var y = TOP
        private val paints = HashMap<Triple<Float, Boolean, Int>, TextPaint>()
        private val rule = Paint().apply {
            color = RULE
            strokeWidth = 0.6f
        }
        private val percent = NumberFormat.getPercentInstance(t.locale)

        // --- Страницы и текст ---

        private fun paint(size: Float, bold: Boolean = false, color: Int = INK): TextPaint =
            paints.getOrPut(Triple(size, bold, color)) {
                TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    textSize = size
                    this.color = color
                    typeface = if (bold) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.DEFAULT
                }
            }

        private fun layout(text: CharSequence, p: TextPaint, width: Float, align: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL) =
            StaticLayout.Builder.obtain(text, 0, text.length, p, width.toInt().coerceAtLeast(1))
                .setAlignment(align)
                .setLineSpacing(0f, 1.12f)
                .setIncludePad(false)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
                .build()

        private fun draw(l: StaticLayout, x: Float, top: Float) {
            val c = canvas ?: return
            c.save()
            c.translate(x, top)
            l.draw(c)
            c.restore()
        }

        private fun newPage() {
            closePage()
            pageCount++
            val p = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageCount).create())
            page = p
            canvas = p.canvas
            y = TOP
        }

        private fun closePage() {
            val p = page ?: return
            footer()
            doc.finishPage(p)
            page = null
            canvas = null
        }

        /** Подвал: линия, оговорка слева, «Стр. N из M» справа. */
        private fun footer() {
            val c = canvas ?: return
            c.drawLine(MARGIN, FOOTER_LINE, PAGE_W - MARGIN, FOOTER_LINE, rule)
            val small = paint(8f, color = GREY)
            val pageW = 90f
            draw(layout(t.string(R.string.report_pdf_footer), small, CONTENT_W - pageW - 8f), MARGIN, FOOTER_LINE + 6f)
            draw(
                layout(t.string(R.string.report_pdf_page, pageCount, total), small, pageW, Layout.Alignment.ALIGN_OPPOSITE),
                PAGE_W - MARGIN - pageW,
                FOOTER_LINE + 6f,
            )
        }

        /** Освобождает место высотой [h]: нет места — новая страница. `true`, если страница началась. */
        private fun ensure(h: Float): Boolean {
            if (page != null && y + h <= BOTTOM) return false
            newPage()
            return true
        }

        fun finish() = closePage()

        private fun block(text: CharSequence, p: TextPaint, after: Float = 0f, keepWith: Float = 0f) {
            val l = layout(text, p, CONTENT_W)
            ensure(l.height + keepWith)
            draw(l, MARGIN, y)
            y += l.height + after
        }

        private fun heading(text: String) {
            y += if (page == null) 0f else 14f
            block(text, paint(12.5f, bold = true, color = ACCENT), after = 5f, keepWith = 56f)
        }

        private fun note(text: String) = block(text, paint(8.5f, color = GREY), after = 4f)

        // --- Таблица ---

        private fun rowLayouts(cols: List<Col>, cells: List<Cell>, head: Boolean) = cells.mapIndexed { i, cell ->
            val p = if (head) paint(8.5f, bold = true, color = GREY) else paint(9f, bold = cell.bold, color = cell.color)
            layout(cell.text, p, cols[i].width - 2 * CELL_PAD_X, cols[i].align)
        }

        private fun rowHeight(layouts: List<StaticLayout>) = (layouts.maxOfOrNull { it.height } ?: 0) + 2 * CELL_PAD_Y

        private fun drawRow(cols: List<Col>, layouts: List<StaticLayout>, height: Float, head: Boolean) {
            var x = MARGIN
            layouts.forEachIndexed { i, l ->
                draw(l, x + CELL_PAD_X, y + CELL_PAD_Y)
                x += cols[i].width
            }
            y += height
            val c = canvas ?: return
            if (head) {
                val ink = Paint().apply {
                    color = INK
                    strokeWidth = 0.9f
                }
                c.drawLine(MARGIN, y, PAGE_W - MARGIN, y, ink)
            } else {
                c.drawLine(MARGIN, y, PAGE_W - MARGIN, y, rule)
            }
        }

        /** Таблица без заливок; строка не разрывается между страницами, шапка повторяется на каждой. */
        private fun table(cols: List<Col>, rows: List<List<Cell>>) {
            if (rows.isEmpty()) return
            val head = rowLayouts(cols, cols.map { Cell(it.title) }, head = true)
            val headH = rowHeight(head)
            var needHead = true
            for (row in rows) {
                val layouts = rowLayouts(cols, row, head = false)
                val h = rowHeight(layouts)
                if (ensure(h + if (needHead) headH else 0f)) needHead = true
                if (needHead) {
                    drawRow(cols, head, headH, head = true)
                    needHead = false
                }
                drawRow(cols, layouts, h, head = false)
            }
            y += 4f
        }

        // --- Разделы ---

        fun header(model: ReportModel, createdAt: LocalDateTime, version: String) {
            newPage()
            val verW = 150f
            val title = layout(t.string(R.string.report_pdf_title), paint(22f, bold = true, color = ACCENT), CONTENT_W - verW)
            val ver = layout(t.string(R.string.report_pdf_version, version), paint(9f, color = GREY), verW, Layout.Alignment.ALIGN_OPPOSITE)
            draw(title, MARGIN, y)
            draw(ver, PAGE_W - MARGIN - verW, y + title.height - ver.height - 3f)
            y += title.height + 6f
            block(t.string(R.string.report_pdf_profile, model.profileName), paint(12f, bold = true), after = 2f)
            val days = (model.to.toEpochDay() - model.from.toEpochDay() + 1).toInt()
            block(
                t.string(R.string.report_pdf_period, t.date(model.from), t.date(model.to), t.days(days)),
                paint(10f),
                after = 2f,
            )
            block(
                t.string(R.string.report_pdf_created, t.date(createdAt.toLocalDate()) + ", " + t.time(createdAt.toLocalTime())),
                paint(10f, color = GREY),
                after = 8f,
            )
            val c = canvas
            if (c != null) {
                val line = Paint().apply {
                    color = ACCENT
                    strokeWidth = 1.5f
                }
                c.drawLine(MARGIN, y, PAGE_W - MARGIN, y, line)
            }
            y += 4f
        }

        /** Соблюдение схемы: итог и таблица по лекарствам. */
        fun adherence(model: ReportModel) {
            heading(t.string(R.string.report_pdf_adherence_h))
            val percentOfTotal = model.percent
            if (percentOfTotal == null) {
                block(t.string(R.string.report_pdf_adherence_none), paint(10f), after = 4f)
            } else {
                block(
                    t.string(R.string.report_pdf_adherence_total, model.taken, model.planned, percentText(percentOfTotal)),
                    paint(11f, bold = true),
                    after = 3f,
                )
                note(t.string(R.string.report_pdf_adherence_note))
            }
            if (model.meds.isEmpty()) return
            val cols = listOf(
                Col(t.string(R.string.report_pdf_col_med), 128f),
                Col(t.string(R.string.report_pdf_col_plan), 122f),
                Col(t.string(R.string.report_pdf_col_taken), 58f, Layout.Alignment.ALIGN_OPPOSITE),
                Col(t.string(R.string.report_pdf_col_percent), 36f, Layout.Alignment.ALIGN_OPPOSITE),
                Col(t.string(R.string.report_pdf_col_skipped), 58f, Layout.Alignment.ALIGN_OPPOSITE),
                Col(t.string(R.string.report_pdf_col_missed), 58f, Layout.Alignment.ALIGN_OPPOSITE),
                Col(t.string(R.string.report_pdf_col_extra), 55f, Layout.Alignment.ALIGN_OPPOSITE),
            )
            val rows = model.meds.map { m ->
                val s = m.stats
                listOf(
                    Cell(todayMedName(s.medicine.name, s.medicine.strength), bold = true),
                    Cell(t.summary(m.plan, s.medicine.unit)),
                    Cell(if (s.planned == 0) DASH else t.string(R.string.report_of, s.taken, s.planned)),
                    Cell(s.percent?.let { percentText(it) } ?: DASH),
                    Cell(s.skipped.toString()),
                    Cell(s.missed.toString()),
                    Cell(s.extra.toString()),
                )
            }
            table(cols, rows)
        }

        /** Пропуски с отметкой: когда, что, причина. */
        fun skips(model: ReportModel) {
            if (model.meds.isEmpty()) return
            heading(t.string(R.string.report_pdf_skips_h))
            if (model.skips.isEmpty()) {
                block(t.string(R.string.report_pdf_skips_none), paint(10f), after = 4f)
                return
            }
            val cols = listOf(
                Col(t.string(R.string.report_pdf_col_when), 108f),
                Col(t.string(R.string.report_pdf_col_med), 170f),
                Col(t.string(R.string.report_pdf_col_reason), CONTENT_W - 278f),
            )
            table(
                cols,
                model.skips.map {
                    listOf(
                        Cell(whenText(it.at)),
                        Cell(todayMedName(it.medicine.name, it.medicine.strength), bold = true),
                        if (it.reason.isBlank()) Cell(t.string(R.string.report_pdf_reason_none), color = GREY) else Cell(it.reason),
                    )
                },
            )
        }

        /** Лишние приёмы: когда, что, сколько. */
        fun extras(model: ReportModel) {
            if (model.extras.isEmpty()) return
            heading(t.string(R.string.report_pdf_extra_h))
            val cols = listOf(
                Col(t.string(R.string.report_pdf_col_when), 108f),
                Col(t.string(R.string.report_pdf_col_med), 250f),
                Col(t.string(R.string.report_pdf_col_dose), CONTENT_W - 358f),
            )
            table(
                cols,
                model.extras.map {
                    listOf(
                        Cell(whenText(it.at)),
                        Cell(todayMedName(it.medicine.name, it.medicine.strength), bold = true),
                        Cell(t.dose(it.amount, it.medicine.unit)),
                    )
                },
            )
        }

        /** «По необходимости»: сколько раз за период. */
        fun prn(model: ReportModel) {
            if (model.prn.isEmpty()) return
            heading(t.string(R.string.report_pdf_prn_h))
            val cols = listOf(
                Col(t.string(R.string.report_pdf_col_med), 358f),
                Col(t.string(R.string.report_pdf_col_times), CONTENT_W - 358f, Layout.Alignment.ALIGN_OPPOSITE),
            )
            table(
                cols,
                model.prn.map { listOf(Cell(todayMedName(it.medicine.name, it.medicine.strength), bold = true), Cell(t.times(it.count))) },
            )
        }

        /** Давление: цель врача, средние утром и вечером, таблица по дням. Вне цели — «*» и жирным, не цветом. */
        fun pressure(p: ReportPressure) {
            heading(t.string(R.string.report_pdf_bp_h))
            block(t.string(R.string.report_pdf_bp_target, p.target.sys, p.target.dia), paint(10f), after = 3f)
            if (p.total == 0) {
                block(t.string(R.string.report_pdf_bp_none), paint(10f), after = 4f)
                return
            }
            p.morning?.let { block(averageLine(R.string.report_pdf_bp_morning, it), paint(10f), after = 2f) }
            p.evening?.let { block(averageLine(R.string.report_pdf_bp_evening, it), paint(10f), after = 2f) }
            block(t.string(R.string.report_pdf_bp_total, t.plural(R.plurals.report_pdf_bp_readings, p.total, p.total)), paint(10f, color = GREY), after = 6f)
            note(t.string(R.string.report_pdf_bp_table_note))
            val dayCol = (CONTENT_W - 105f) / 3
            val cols = listOf(
                Col(t.string(R.string.report_pdf_col_date), 105f),
                Col(t.string(R.string.report_pdf_col_morning), dayCol),
                Col(t.string(R.string.report_pdf_col_daytime), dayCol),
                Col(t.string(R.string.report_pdf_col_evening), dayCol),
            )
            table(
                cols,
                p.days.map { listOf(Cell(t.date(it.date)), bpCell(it.morning, p.target), bpCell(it.daytime, p.target), bpCell(it.evening, p.target)) },
            )
            note(t.string(R.string.report_pdf_bp_flag))
        }

        // --- Тексты ---

        private fun percentText(value: Int) = percent.format(value / 100.0)

        private fun whenText(at: LocalDateTime) = t.date(at.toLocalDate()) + ", " + t.time(at.toLocalTime())

        private fun averageLine(@StringRes label: Int, a: BpAverage): String {
            val value = t.string(R.string.report_pdf_bp_mm, a.sys, a.dia) +
                (a.pulse?.let { t.string(R.string.report_pdf_bp_pulse, it) } ?: "")
            return t.string(R.string.report_pdf_bp_avg, t.string(label), value) + " (" +
                t.plural(R.plurals.report_pdf_bp_readings, a.count, a.count) + ")"
        }

        /** «128/82 (70)*»: давление, пульс в скобках, «*» и жирный — вне цели врача. */
        private fun bpCell(a: BpAverage?, target: Target): Cell {
            if (a == null) return Cell(DASH, color = GREY)
            val flagged = classify(a.sys, a.dia, target) != PressureState.Within
            val text = "${a.sys}/${a.dia}" + (a.pulse?.let { " ($it)" } ?: "") + if (flagged) " *" else ""
            return Cell(text, bold = flagged)
        }
    }

    private const val DASH = "—"
}
