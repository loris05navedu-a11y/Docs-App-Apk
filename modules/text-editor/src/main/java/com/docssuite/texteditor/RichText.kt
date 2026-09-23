package com.docssuite.texteditor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.docssuite.core.fontFamilyAt
import org.json.JSONArray
import org.json.JSONObject

/**
 * Style d'un caractère. Le document garde un style par caractère : c'est ce qui
 * permet de mettre en gras / changer de police uniquement la portion sélectionnée.
 */
data class CharStyle(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strike: Boolean = false,
    val size: Int = 16,
    val font: Int = 0,
    val color: Long = 0xFF1A1A1AL,
    val highlight: Long = 0L,
    /** 1 = exposant, -1 = indice. */
    val baseline: Int = 0
) {
    fun toSpanStyle(): SpanStyle = SpanStyle(
        color = Color(color),
        fontSize = if (baseline != 0) (size * 0.7f).sp else size.sp,
        baselineShift = when (baseline) {
            1 -> BaselineShift.Superscript
            -1 -> BaselineShift.Subscript
            else -> null
        },
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
        fontFamily = fontFamilyAt(font),
        background = if (highlight == 0L) Color.Transparent else Color(highlight),
        textDecoration = when {
            underline && strike -> TextDecoration.combine(
                listOf(TextDecoration.Underline, TextDecoration.LineThrough)
            )
            underline -> TextDecoration.Underline
            strike -> TextDecoration.LineThrough
            else -> TextDecoration.None
        }
    )
}

fun buildAnnotated(
    text: String,
    styles: List<CharStyle>,
    paras: List<ParaStyle> = emptyList(),
    lineSpacing: Int = 100
): AnnotatedString {
    // Dans Compose, un saut de ligne à l'intérieur d'un style de paragraphe
    // ajoute une ligne vide : c'est la limite du style qui fait le retour à la
    // ligne. À l'affichage seulement, chaque saut interne devient donc un
    // caractère invisible de même longueur (les positions ne bougent pas).
    // Le dernier reste un vrai saut : c'est lui qui ouvre la ligne vide après
    // un Entrée en fin de texte.
    val display = CharArray(text.length) { i ->
        if (text[i] == '\n' && i != text.length - 1) '\u200B' else text[i]
    }
    val builder = AnnotatedString.Builder(String(display))
    var i = 0
    while (i < text.length) {
        val style = styles.getOrElse(i) { CharStyle() }
        var j = i + 1
        while (j < text.length && styles.getOrElse(j) { CharStyle() } == style) j++
        builder.addStyle(style.toSpanStyle(), i, j)
        i = j
    }
    // Un style par paragraphe, sur des plages qui se suivent sans se
    // chevaucher (Compose le refuse), saut de ligne compris.
    var start = 0
    var index = 0
    while (start < text.length) {
        val newline = text.indexOf('\n', start)
        val end = if (newline < 0) text.length else newline + 1
        builder.addStyle(paragraphStyle(paras.getOrElse(index) { ParaStyle() }, lineSpacing), start, end)
        start = end
        index++
    }
    return builder.toAnnotatedString()
}

private fun paragraphStyle(para: ParaStyle, lineSpacing: Int): ParagraphStyle {
    val indent = (para.indent * 24).sp
    return ParagraphStyle(
        textAlign = alignFromInt(para.align),
        textIndent = if (para.indent > 0) TextIndent(firstLine = indent, restLine = indent) else null,
        lineHeight = if (lineSpacing != 100) (1.2f * lineSpacing / 100f).em else androidx.compose.ui.unit.TextUnit.Unspecified
    )
}

/** Applique les styles au rendu du champ de saisie sans modifier les offsets. */
data class RichTextTransformation(
    val styles: List<CharStyle>,
    val paras: List<ParaStyle> = emptyList(),
    val lineSpacing: Int = 100
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText =
        TransformedText(buildAnnotated(text.text, styles, paras, lineSpacing), OffsetMapping.Identity)
}

/**
 * Recale la liste de styles après une modification du texte : on garde le préfixe
 * et le suffixe communs, et les caractères insérés prennent [newCharStyle].
 */
fun adjustStyles(
    old: String,
    new: String,
    styles: List<CharStyle>,
    newCharStyle: CharStyle
): List<CharStyle> {
    if (old == new) return styles
    val maxCommon = minOf(old.length, new.length)
    var prefix = 0
    while (prefix < maxCommon && old[prefix] == new[prefix]) prefix++
    var suffix = 0
    while (suffix < maxCommon - prefix &&
        old[old.length - 1 - suffix] == new[new.length - 1 - suffix]
    ) suffix++

    val removed = old.length - prefix - suffix
    val inserted = new.length - prefix - suffix

    val out = ArrayList<CharStyle>(new.length)
    for (i in 0 until prefix) out.add(styles.getOrElse(i) { CharStyle() })
    repeat(inserted) { out.add(newCharStyle) }
    for (i in (prefix + removed) until old.length) out.add(styles.getOrElse(i) { CharStyle() })
    return out
}

fun applyToRange(
    styles: List<CharStyle>,
    start: Int,
    end: Int,
    transform: (CharStyle) -> CharStyle
): List<CharStyle> {
    val out = ArrayList(styles)
    for (i in start until minOf(end, out.size)) {
        if (i >= 0) out[i] = transform(out[i])
    }
    return out
}

fun stylesToJson(text: String, styles: List<CharStyle>): String {
    val runs = JSONArray()
    var i = 0
    while (i < text.length) {
        val style = styles.getOrElse(i) { CharStyle() }
        var j = i + 1
        while (j < text.length && styles.getOrElse(j) { CharStyle() } == style) j++
        runs.put(JSONObject().apply {
            put("len", j - i)
            put("b", style.bold)
            put("i", style.italic)
            put("u", style.underline)
            put("s", style.strike)
            put("sz", style.size)
            put("f", style.font)
            put("c", style.color)
            put("h", style.highlight)
            if (style.baseline != 0) put("bl", style.baseline)
        })
        i = j
    }
    return JSONObject().apply {
        put("text", text)
        put("runs", runs)
    }.toString()
}

fun stylesFromJson(payload: String): Pair<String, List<CharStyle>> {
    val root = JSONObject(payload)
    val text = root.optString("text", "")
    val runs = root.optJSONArray("runs") ?: JSONArray()
    val styles = ArrayList<CharStyle>(text.length)
    for (r in 0 until runs.length()) {
        val o = runs.getJSONObject(r)
        val style = CharStyle(
            bold = o.optBoolean("b"),
            italic = o.optBoolean("i"),
            underline = o.optBoolean("u"),
            strike = o.optBoolean("s"),
            size = o.optInt("sz", 16),
            font = o.optInt("f", 0),
            color = o.optLong("c", 0xFF1A1A1AL),
            highlight = o.optLong("h", 0L),
            baseline = o.optInt("bl", 0).coerceIn(-1, 1)
        )
        repeat(o.optInt("len")) { styles.add(style) }
    }
    while (styles.size < text.length) styles.add(CharStyle())
    return text to styles.take(text.length)
}
