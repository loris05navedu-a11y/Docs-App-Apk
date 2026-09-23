package com.docssuite.texteditor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.docssuite.core.AppFonts
import com.docssuite.core.fontLabelAt
import com.docssuite.fileformats.DocBlock
import com.docssuite.fileformats.TableCell
import com.docssuite.fileformats.TableRow
import com.docssuite.fileformats.TextDocument
import com.docssuite.fileformats.TextParagraph
import com.docssuite.fileformats.TextRun
import com.docssuite.fileformats.TextTable
import org.json.JSONArray
import org.json.JSONObject

/**
 * Passage entre le modèle de l'éditeur et, d'un côté, le modèle pivot des
 * fichiers (Word, PDF…), de l'autre, l'enregistrement interne en JSON.
 */
object EditorIO {

    private fun fontIndexOf(label: String): Int =
        AppFonts.indexOfFirst { it.label.equals(label, ignoreCase = true) }.takeIf { it >= 0 } ?: 0

    // ------------------------------------------------------------ fichiers

    fun toTextDocument(doc: EditorDoc, title: String): TextDocument {
        val blocks = ArrayList<DocBlock>()
        doc.blocks.forEachIndexed { index, block ->
            when (block) {
                is EditorBlock.Text -> {
                    // Le paragraphe vide qui entoure un tableau n'existe que pour
                    // pouvoir écrire autour ; il n'a pas à passer dans le fichier.
                    val besideTable = doc.blocks.getOrNull(index - 1) is EditorBlock.Table ||
                        doc.blocks.getOrNull(index + 1) is EditorBlock.Table
                    if (block.field.text.isEmpty() && besideTable) return@forEachIndexed
                    blocks.addAll(toParagraphs(block.field))
                }
                is EditorBlock.Table -> blocks.add(
                    TextTable(
                        rows = block.table.rows.map { row ->
                            TableRow(row.map { cell ->
                                TableCell(
                                    paragraphs = toParagraphs(cell.field),
                                    fill = cell.fill,
                                    colSpan = cell.colSpan,
                                    mergedAbove = cell.mergedAbove
                                )
                            })
                        },
                        headerRow = block.table.headerRow
                    )
                )
            }
        }
        return TextDocument(title.ifBlank { "Document" }, blocks.ifEmpty { listOf(TextParagraph()) }, doc.lineSpacing)
    }

    fun toParagraphs(field: RichField): List<TextParagraph> {
        val text = field.text
        val out = ArrayList<TextParagraph>()
        var start = 0
        var paragraph = 0
        while (start <= text.length) {
            val end = text.indexOf('\n', start).let { if (it < 0) text.length else it }
            val runs = ArrayList<TextRun>()
            var i = start
            while (i < end) {
                val style = field.styles.getOrElse(i) { CharStyle() }
                var j = i + 1
                while (j < end && field.styles.getOrElse(j) { CharStyle() } == style) j++
                runs.add(
                    TextRun(
                        text = text.substring(i, j),
                        bold = style.bold,
                        italic = style.italic,
                        underline = style.underline,
                        strike = style.strike,
                        size = style.size,
                        fontName = fontLabelAt(style.font),
                        color = style.color,
                        highlight = style.highlight,
                        baseline = style.baseline
                    )
                )
                i = j
            }
            val para = field.paras.getOrElse(paragraph) { ParaStyle() }
            out.add(TextParagraph(runs, para.align, para.heading, para.indent))
            paragraph++
            if (end >= text.length) break
            start = end + 1
        }
        return out
    }

    fun fromTextDocument(document: TextDocument): EditorDoc {
        val blocks = ArrayList<EditorBlock>()
        val pending = ArrayList<TextParagraph>()
        fun flush() {
            if (pending.isEmpty()) return
            blocks.add(EditorBlock.Text(newBlockId(), fromParagraphs(pending)))
            pending.clear()
        }
        document.blocks.forEach { block ->
            when (block) {
                is TextParagraph -> pending.add(block)
                is TextTable -> {
                    flush()
                    val table = block.normalized()
                    blocks.add(
                        EditorBlock.Table(
                            newBlockId(),
                            EditorModel.repair(
                                TableData(
                                    rows = table.rows.map { row ->
                                        row.cells.map { cell ->
                                            CellField(
                                                field = fromParagraphs(cell.paragraphs),
                                                fill = cell.fill,
                                                colSpan = cell.colSpan,
                                                mergedAbove = cell.mergedAbove
                                            )
                                        }
                                    },
                                    headerRow = table.headerRow
                                )
                            )
                        )
                    )
                }
            }
        }
        flush()
        return EditorDoc(EditorModel.mergeAdjacentText(blocks), document.lineSpacing.coerceIn(100, 300))
    }

    fun fromParagraphs(paragraphs: List<TextParagraph>): RichField {
        val text = StringBuilder()
        val styles = ArrayList<CharStyle>()
        val paras = ArrayList<ParaStyle>()
        paragraphs.ifEmpty { listOf(TextParagraph()) }.forEachIndexed { index, paragraph ->
            if (index > 0) {
                text.append('\n')
                styles.add(styles.lastOrNull() ?: CharStyle())
            }
            paragraph.runs.forEach { run ->
                // Un saut de ligne à l'intérieur d'un paragraphe devient un
                // paragraphe à part entière dans l'éditeur.
                val style = CharStyle(
                    bold = run.bold,
                    italic = run.italic,
                    underline = run.underline,
                    strike = run.strike,
                    size = run.size.coerceIn(6, 200),
                    font = fontIndexOf(run.fontName),
                    color = run.color,
                    highlight = run.highlight,
                    baseline = run.baseline.coerceIn(-1, 1)
                )
                run.text.forEach { ch ->
                    if (ch == '\n') paras.add(ParaStyle(paragraph.align, paragraph.heading.coerceIn(0, 3), paragraph.indent.coerceIn(0, 8)))
                    text.append(ch)
                    styles.add(style)
                }
            }
            paras.add(ParaStyle(paragraph.align, paragraph.heading.coerceIn(0, 3), paragraph.indent.coerceIn(0, 8)))
        }
        return RichField(TextFieldValue(text.toString(), TextRange(0)), styles, paras).normalized()
    }

    // ------------------------------------------------------------ enregistrement

    fun toJson(doc: EditorDoc): String {
        val blocks = JSONArray()
        doc.blocks.forEach { block ->
            when (block) {
                is EditorBlock.Text -> blocks.put(fieldJson(block.field).put("t", "text"))
                is EditorBlock.Table -> blocks.put(JSONObject().apply {
                    put("t", "table")
                    put("header", block.table.headerRow)
                    put("rows", JSONArray().apply {
                        block.table.rows.forEach { row ->
                            put(JSONArray().apply {
                                row.forEach { cell ->
                                    put(fieldJson(cell.field).apply {
                                        if (cell.fill != 0L) put("fill", cell.fill)
                                        if (cell.colSpan > 1) put("span", cell.colSpan)
                                        if (cell.mergedAbove) put("up", true)
                                    })
                                }
                            })
                        }
                    })
                })
            }
        }
        return JSONObject().apply {
            put("v", 2)
            put("spacing", doc.lineSpacing)
            put("blocks", blocks)
        }.toString()
    }

    /**
     * Relit un document enregistré. Les documents d'avant les tableaux
     * (`{text, runs}`) s'ouvrent comme un unique bloc de texte.
     */
    fun fromJson(payload: String): EditorDoc {
        val root = JSONObject(payload)
        val array = root.optJSONArray("blocks")
            ?: return EditorDoc(listOf(EditorBlock.Text(newBlockId(), fieldFrom(root))))
        val blocks = ArrayList<EditorBlock>()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            when (o.optString("t")) {
                "table" -> {
                    val rowsJson = o.optJSONArray("rows") ?: continue
                    val rows = (0 until rowsJson.length()).mapNotNull { r ->
                        val cells = rowsJson.optJSONArray(r) ?: return@mapNotNull null
                        (0 until cells.length()).mapNotNull { c ->
                            val cell = cells.optJSONObject(c) ?: return@mapNotNull null
                            CellField(
                                field = fieldFrom(cell),
                                fill = cell.optLong("fill", 0L),
                                colSpan = cell.optInt("span", 1).coerceIn(1, 64),
                                mergedAbove = cell.optBoolean("up", false)
                            )
                        }.takeIf { it.isNotEmpty() }
                    }
                    if (rows.isNotEmpty()) {
                        blocks.add(EditorBlock.Table(newBlockId(), EditorModel.repair(TableData(rows, o.optBoolean("header")))))
                    }
                }
                else -> blocks.add(EditorBlock.Text(newBlockId(), fieldFrom(o)))
            }
        }
        return EditorDoc(EditorModel.mergeAdjacentText(blocks), root.optInt("spacing", 100).coerceIn(100, 300))
    }

    private fun fieldJson(field: RichField): JSONObject {
        val base = JSONObject(stylesToJson(field.text, field.styles))
        base.put("paras", JSONArray().apply {
            field.paras.forEach { para -> put(JSONArray().apply { put(para.align); put(para.heading); put(para.indent) }) }
        })
        return base
    }

    private fun fieldFrom(o: JSONObject): RichField {
        val (text, styles) = stylesFromJson(o.toString())
        val parasJson = o.optJSONArray("paras")
        val paras = if (parasJson == null) emptyList() else (0 until parasJson.length()).map { i ->
            val p = parasJson.optJSONArray(i)
            ParaStyle(
                align = p?.optInt(0, 0)?.coerceIn(0, 3) ?: 0,
                heading = p?.optInt(1, 0)?.coerceIn(0, 3) ?: 0,
                indent = p?.optInt(2, 0)?.coerceIn(0, 8) ?: 0
            )
        }
        return RichField(TextFieldValue(text, TextRange(0)), styles, paras).normalized()
    }
}
