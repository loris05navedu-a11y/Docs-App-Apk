package com.docssuite.texteditor

import androidx.compose.ui.text.style.TextAlign
import com.docssuite.core.AppFonts
import com.docssuite.core.DocType
import com.docssuite.core.DocumentStorage
import com.docssuite.core.fontLabelAt
import com.docssuite.fileformats.TextDocument
import com.docssuite.fileformats.TextParagraph
import com.docssuite.fileformats.TextRun

/**
 * Traduction entre le modèle de l'éditeur (un texte plat plus un style par
 * caractère) et le modèle pivot des fichiers (des paragraphes de fragments).
 */

private fun fontIndexOf(label: String): Int =
    AppFonts.indexOfFirst { it.label.equals(label, ignoreCase = true) }.takeIf { it >= 0 } ?: 0

internal fun alignToInt(align: TextAlign): Int = when (align) {
    TextAlign.Center -> 1
    TextAlign.End, TextAlign.Right -> 2
    TextAlign.Justify -> 3
    else -> 0
}

internal fun alignFromInt(align: Int): TextAlign = when (align) {
    1 -> TextAlign.Center
    2 -> TextAlign.End
    3 -> TextAlign.Justify
    else -> TextAlign.Start
}

/** Découpe le texte en paragraphes, chacun en fragments de style homogène. */
fun buildTextDocument(
    title: String,
    text: String,
    styles: List<CharStyle>,
    align: TextAlign
): TextDocument {
    val paragraphs = ArrayList<TextParagraph>()
    var lineStart = 0
    val alignValue = alignToInt(align)

    fun emit(from: Int, to: Int) {
        val runs = ArrayList<TextRun>()
        var index = from
        while (index < to) {
            val style = styles.getOrElse(index) { CharStyle() }
            var end = index + 1
            while (end < to && styles.getOrElse(end) { CharStyle() } == style) end++
            runs.add(
                TextRun(
                    text = text.substring(index, end),
                    bold = style.bold,
                    italic = style.italic,
                    underline = style.underline,
                    strike = style.strike,
                    size = style.size,
                    fontName = fontLabelAt(style.font),
                    color = style.color,
                    highlight = style.highlight
                )
            )
            index = end
        }
        paragraphs.add(TextParagraph(runs, alignValue))
    }

    text.forEachIndexed { index, character ->
        if (character == '\n') {
            emit(lineStart, index)
            lineStart = index + 1
        }
    }
    emit(lineStart, text.length)

    return TextDocument(title.ifBlank { "Document" }, paragraphs)
}

/** Résultat d'un import, prêt à remplacer l'état de l'éditeur. */
class DecodedText(
    val text: String,
    val styles: List<CharStyle>,
    val align: TextAlign
)

fun decodeTextDocument(document: TextDocument): DecodedText {
    val text = StringBuilder()
    val styles = ArrayList<CharStyle>()

    document.paragraphs.forEachIndexed { index, paragraph ->
        if (index > 0) {
            text.append('\n')
            styles.add(styles.lastOrNull() ?: CharStyle())
        }
        paragraph.runs.forEach { run ->
            val style = CharStyle(
                bold = run.bold,
                italic = run.italic,
                underline = run.underline,
                strike = run.strike,
                size = run.size.coerceIn(6, 200),
                font = fontIndexOf(run.fontName),
                color = run.color,
                highlight = run.highlight
            )
            text.append(run.text)
            repeat(run.text.length) { styles.add(style) }
        }
    }

    // L'éditeur n'a qu'un alignement pour tout le document : on retient celui
    // du premier paragraphe qui porte du texte.
    val align = document.paragraphs.firstOrNull { it.plainText.isNotBlank() }?.align ?: 0
    return DecodedText(text.toString(), styles, alignFromInt(align))
}

/**
 * Enregistre un document importé et renvoie son identifiant, pour que l'écran
 * d'accueil puisse ouvrir l'éditeur directement dessus.
 */
fun saveImportedTextDocument(
    storage: DocumentStorage,
    document: TextDocument,
    name: String
): String {
    val decoded = decodeTextDocument(document)
    val id = storage.newId()
    storage.save(id, name, DocType.TEXT, stylesToJson(decoded.text, decoded.styles))
    return id
}
