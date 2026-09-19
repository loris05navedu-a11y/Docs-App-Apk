package com.docssuite.fileformats

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.AbsoluteSizeSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import java.io.ByteArrayOutputStream
import kotlin.math.max

/**
 * Génération de PDF via `android.graphics.pdf`. Le texte est dessiné, donc il
 * reste vectoriel et sélectionnable — contrairement à une capture d'écran.
 */
object PdfExport {

    /** A4 à 72 points par pouce. */
    private const val A4_WIDTH = 595
    private const val A4_HEIGHT = 842
    private const val MARGIN = 48

    /** 16:9, la même proportion que le mode diaporama de l'app. */
    private const val SLIDE_WIDTH = 960
    private const val SLIDE_HEIGHT = 540

    fun fromTextDocument(document: TextDocument): ByteArray {
        val pdf = PdfDocument()
        val contentWidth = A4_WIDTH - 2 * MARGIN
        var page = pdf.startPage(pageInfo(pdf, A4_WIDTH, A4_HEIGHT, 1))
        var pageNumber = 1
        var cursor = MARGIN.toFloat()

        val paragraphs = document.paragraphs.ifEmpty { listOf(TextParagraph()) }
        paragraphs.forEach { paragraph ->
            val layout = layoutFor(paragraph, contentWidth)
            val height = layout.height.toFloat()

            if (cursor + height > A4_HEIGHT - MARGIN && cursor > MARGIN) {
                pdf.finishPage(page)
                pageNumber++
                page = pdf.startPage(pageInfo(pdf, A4_WIDTH, A4_HEIGHT, pageNumber))
                cursor = MARGIN.toFloat()
            }

            // Un paragraphe plus haut qu'une page se découpe ligne par ligne.
            if (height > A4_HEIGHT - 2 * MARGIN) {
                var line = 0
                while (line < layout.lineCount) {
                    val available = (A4_HEIGHT - MARGIN - cursor).toInt()
                    var last = line
                    while (last < layout.lineCount &&
                        layout.getLineBottom(last) - layout.getLineTop(line) <= available
                    ) last++
                    if (last == line) last = line + 1

                    val top = layout.getLineTop(line)
                    val bottom = layout.getLineBottom(last - 1)
                    page.canvas.save()
                    page.canvas.translate(MARGIN.toFloat(), cursor - top)
                    page.canvas.clipRect(0, top, contentWidth, bottom)
                    layout.draw(page.canvas)
                    page.canvas.restore()

                    cursor += (bottom - top).toFloat()
                    line = last
                    if (line < layout.lineCount) {
                        pdf.finishPage(page)
                        pageNumber++
                        page = pdf.startPage(pageInfo(pdf, A4_WIDTH, A4_HEIGHT, pageNumber))
                        cursor = MARGIN.toFloat()
                    }
                }
            } else {
                page.canvas.save()
                page.canvas.translate(MARGIN.toFloat(), cursor)
                layout.draw(page.canvas)
                page.canvas.restore()
                cursor += height
            }
            cursor += 6f
        }

        pdf.finishPage(page)
        return close(pdf)
    }

    private fun layoutFor(paragraph: TextParagraph, width: Int): StaticLayout {
        val builder = SpannableStringBuilder()
        var defaultSize = 16
        paragraph.runs.forEach { run ->
            val start = builder.length
            builder.append(run.text)
            val end = builder.length
            if (end == start) return@forEach
            defaultSize = run.size
            val flag = android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            builder.setSpan(AbsoluteSizeSpan(run.size), start, end, flag)
            builder.setSpan(ForegroundColorSpan(run.color.toInt()), start, end, flag)
            builder.setSpan(TypefaceSpan(androidTypefaceName(run.fontName)), start, end, flag)
            if (run.bold && run.italic) {
                builder.setSpan(StyleSpan(Typeface.BOLD_ITALIC), start, end, flag)
            } else if (run.bold) {
                builder.setSpan(StyleSpan(Typeface.BOLD), start, end, flag)
            } else if (run.italic) {
                builder.setSpan(StyleSpan(Typeface.ITALIC), start, end, flag)
            }
            if (run.underline) builder.setSpan(UnderlineSpan(), start, end, flag)
            if (run.strike) builder.setSpan(StrikethroughSpan(), start, end, flag)
            if (run.highlight != 0L) {
                builder.setSpan(BackgroundColorSpan(run.highlight.toInt()), start, end, flag)
            }
        }

        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = defaultSize.toFloat()
            color = Color.BLACK
        }
        val alignment = when (paragraph.align) {
            1 -> Layout.Alignment.ALIGN_CENTER
            2 -> Layout.Alignment.ALIGN_OPPOSITE
            else -> Layout.Alignment.ALIGN_NORMAL
        }
        return StaticLayout.Builder
            .obtain(builder, 0, builder.length, paint, width.coerceAtLeast(1))
            .setAlignment(alignment)
            .setLineSpacing(2f, 1f)
            .setIncludePad(false)
            .build()
    }

    private fun androidTypefaceName(appLabel: String) = when (appLabel) {
        "Serif", "Noto Serif", "Droid Serif" -> "serif"
        "Monospace", "Serif Monospace" -> "monospace"
        "Manuscrite", "Cursive système", "Décontractée" -> "cursive"
        else -> "sans-serif"
    }

    // ------------------------------------------------------------ tableur

    fun fromWorkbook(workbook: Workbook, display: (Sheet, String) -> String): ByteArray {
        val pdf = PdfDocument()
        var pageNumber = 0

        workbook.sheets.forEach { sheet ->
            val columnWidth = 78f
            val rowHeight = 22f
            val headerWidth = 34f
            // On pagine par blocs de colonnes puis de lignes pour qu'une feuille
            // large ne soit pas tronquée.
            val usable = A4_WIDTH - 2 * MARGIN - headerWidth
            val perPage = max(1, (usable / columnWidth).toInt())
            val rowsPerPage = max(1, ((A4_HEIGHT - 2 * MARGIN - rowHeight - 20) / rowHeight).toInt())

            var firstColumn = 0
            while (firstColumn < sheet.columns) {
                val lastColumn = minOf(firstColumn + perPage, sheet.columns)
                var firstRow = 0
                while (firstRow < sheet.rows) {
                    val lastRow = minOf(firstRow + rowsPerPage, sheet.rows)
                    pageNumber++
                    val page = pdf.startPage(pageInfo(pdf, A4_WIDTH, A4_HEIGHT, pageNumber))
                    drawSheetBlock(
                        page.canvas, sheet, display,
                        firstColumn, lastColumn, firstRow, lastRow,
                        columnWidth, rowHeight, headerWidth
                    )
                    pdf.finishPage(page)
                    firstRow = lastRow
                }
                firstColumn = lastColumn
            }
        }

        if (pageNumber == 0) pdf.finishPage(pdf.startPage(pageInfo(pdf, A4_WIDTH, A4_HEIGHT, 1)))
        return close(pdf)
    }

    private fun drawSheetBlock(
        canvas: Canvas,
        sheet: Sheet,
        display: (Sheet, String) -> String,
        firstColumn: Int,
        lastColumn: Int,
        firstRow: Int,
        lastRow: Int,
        columnWidth: Float,
        rowHeight: Float,
        headerWidth: Float
    ) {
        val grid = Paint().apply {
            color = Color.parseColor("#D0D5DD"); strokeWidth = 0.6f; style = Paint.Style.STROKE
        }
        val headerFill = Paint().apply { color = Color.parseColor("#F1F5F9") }
        val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 9f }

        val left = MARGIN.toFloat()
        val top = MARGIN.toFloat()
        val columns = lastColumn - firstColumn
        val rows = lastRow - firstRow
        val right = left + headerWidth + columns * columnWidth
        val bottom = top + (rows + 1) * rowHeight

        canvas.drawRect(left, top, right, top + rowHeight, headerFill)
        canvas.drawRect(left, top, left + headerWidth, bottom, headerFill)

        text.color = Color.parseColor("#475467")
        text.textAlign = Paint.Align.CENTER
        for (c in firstColumn until lastColumn) {
            val x = left + headerWidth + (c - firstColumn + 0.5f) * columnWidth
            canvas.drawText(CellRef.columnLabel(c), x, top + rowHeight * 0.68f, text)
        }
        for (r in firstRow until lastRow) {
            val y = top + (r - firstRow + 1.68f) * rowHeight
            canvas.drawText("${r + 1}", left + headerWidth / 2, y, text)
        }

        for (r in firstRow until lastRow) {
            for (c in firstColumn until lastColumn) {
                val ref = CellRef.key(r, c)
                val style = sheet.styles[ref]
                val cellLeft = left + headerWidth + (c - firstColumn) * columnWidth
                val cellTop = top + (r - firstRow + 1) * rowHeight

                if (style != null && style.background != 0L) {
                    canvas.drawRect(
                        cellLeft, cellTop, cellLeft + columnWidth, cellTop + rowHeight,
                        Paint().apply { color = style.background.toInt() }
                    )
                }

                val value = display(sheet, ref)
                if (value.isEmpty()) continue

                text.color = style?.color?.toInt() ?: Color.parseColor("#1A1A1A")
                text.typeface = Typeface.create(
                    Typeface.DEFAULT,
                    when {
                        style == null -> Typeface.NORMAL
                        style.bold && style.italic -> Typeface.BOLD_ITALIC
                        style.bold -> Typeface.BOLD
                        style.italic -> Typeface.ITALIC
                        else -> Typeface.NORMAL
                    }
                )
                // Alignement « auto » : les nombres à droite, le texte à gauche.
                val numeric = value.replace(',', '.').replace(" ", "").toDoubleOrNull() != null
                val align = when (style?.align ?: 0) {
                    1 -> Paint.Align.LEFT
                    2 -> Paint.Align.CENTER
                    3 -> Paint.Align.RIGHT
                    else -> if (numeric) Paint.Align.RIGHT else Paint.Align.LEFT
                }
                text.textAlign = align
                val x = when (align) {
                    Paint.Align.LEFT -> cellLeft + 4f
                    Paint.Align.CENTER -> cellLeft + columnWidth / 2
                    else -> cellLeft + columnWidth - 4f
                }
                val clipped = ellipsize(value, text, columnWidth - 8f)
                canvas.drawText(clipped, x, cellTop + rowHeight * 0.68f, text)
            }
        }
        text.typeface = Typeface.DEFAULT

        for (c in 0..columns) {
            val x = left + headerWidth + c * columnWidth
            canvas.drawLine(x, top, x, bottom, grid)
        }
        canvas.drawLine(left, top, left, bottom, grid)
        for (r in 0..rows + 1) {
            val y = top + r * rowHeight
            canvas.drawLine(left, y, right, y, grid)
        }
    }

    private fun ellipsize(text: String, paint: Paint, width: Float): String {
        if (paint.measureText(text) <= width) return text
        var end = text.length
        while (end > 1 && paint.measureText(text.substring(0, end) + "…") > width) end--
        return text.substring(0, end) + "…"
    }

    // ------------------------------------------------------------ diapositives

    fun fromDeck(deck: Deck): ByteArray {
        val pdf = PdfDocument()
        val slides = deck.slides.ifEmpty { listOf(SlideModel(title = deck.title)) }
        slides.forEachIndexed { index, slide ->
            val page = pdf.startPage(pageInfo(pdf, SLIDE_WIDTH, SLIDE_HEIGHT, index + 1))
            drawSlide(page.canvas, slide)
            pdf.finishPage(page)
        }
        return close(pdf)
    }

    private fun drawSlide(canvas: Canvas, slide: SlideModel) {
        canvas.drawColor(slide.background.toInt())
        val margin = 64
        val width = SLIDE_WIDTH - 2 * margin

        val alignment = when (slide.align) {
            0 -> Layout.Alignment.ALIGN_NORMAL
            2 -> Layout.Alignment.ALIGN_OPPOSITE
            else -> Layout.Alignment.ALIGN_CENTER
        }
        val typeface = Typeface.create(androidTypefaceName(slide.fontName), Typeface.NORMAL)

        val title = if (slide.title.isBlank()) null else StaticLayout.Builder
            .obtain(
                slide.title, 0, slide.title.length,
                TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    textSize = slide.titleSize.toFloat()
                    color = slide.textColor.toInt()
                    this.typeface = Typeface.create(typeface, Typeface.BOLD)
                },
                width
            )
            .setAlignment(alignment).setIncludePad(false).build()

        val content = if (slide.content.isBlank()) null else StaticLayout.Builder
            .obtain(
                slide.content, 0, slide.content.length,
                TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    textSize = slide.contentSize.toFloat()
                    color = slide.textColor.toInt()
                    this.typeface = typeface
                },
                width
            )
            .setAlignment(alignment).setLineSpacing(4f, 1f).setIncludePad(false).build()

        val gap = if (title != null && content != null) 24f else 0f
        val total = (title?.height ?: 0) + (content?.height ?: 0) + gap
        var y = max(margin.toFloat(), (SLIDE_HEIGHT - total) / 2f)

        title?.let {
            canvas.save(); canvas.translate(margin.toFloat(), y); it.draw(canvas); canvas.restore()
            y += it.height + gap
        }
        content?.let {
            canvas.save(); canvas.translate(margin.toFloat(), y); it.draw(canvas); canvas.restore()
        }
    }

    // ------------------------------------------------------------ utilitaires

    private fun pageInfo(pdf: PdfDocument, width: Int, height: Int, number: Int) =
        PdfDocument.PageInfo.Builder(width, height, number).create()

    private fun close(pdf: PdfDocument): ByteArray {
        val bytes = ByteArrayOutputStream()
        pdf.writeTo(bytes)
        pdf.close()
        return bytes.toByteArray()
    }
}
