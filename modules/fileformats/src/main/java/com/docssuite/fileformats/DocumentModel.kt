package com.docssuite.fileformats

/**
 * Modèle pivot des conversions.
 *
 * Chaque format (docx, odt, rtf, pdf…) sait seulement lire vers ce modèle ou
 * l'écrire. On évite ainsi d'écrire un convertisseur par couple de formats :
 * N lecteurs + N écrivains suffisent pour N × N conversions.
 */

// ---------------------------------------------------------------- texte

data class TextRun(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strike: Boolean = false,
    /** Taille en points. */
    val size: Int = 16,
    val fontName: String = "",
    /** ARGB. */
    val color: Long = 0xFF1A1A1AL,
    /** ARGB, 0 = pas de surlignage. */
    val highlight: Long = 0L
)

/** 0 = gauche, 1 = centre, 2 = droite, 3 = justifié. */
data class TextParagraph(
    val runs: List<TextRun> = emptyList(),
    val align: Int = 0
) {
    val plainText: String get() = runs.joinToString("") { it.text }
}

data class TextDocument(
    val title: String = "Document",
    val paragraphs: List<TextParagraph> = emptyList()
) {
    val plainText: String get() = paragraphs.joinToString("\n") { it.plainText }
}

// ---------------------------------------------------------------- tableur

data class CellStyle(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val color: Long = 0xFF1A1A1AL,
    /** ARGB, 0 = pas de remplissage. */
    val background: Long = 0L,
    /** 0 = auto, 1 = gauche, 2 = centre, 3 = droite. */
    val align: Int = 0
) {
    val isDefault: Boolean
        get() = !bold && !italic && color == 0xFF1A1A1AL && background == 0L && align == 0
}

/**
 * Une feuille. [cells] est indexée par référence A1 ; une valeur commençant par
 * `=` est une formule, exactement comme dans l'éditeur.
 */
data class Sheet(
    val name: String = "Feuille1",
    val cells: Map<String, String> = emptyMap(),
    val styles: Map<String, CellStyle> = emptyMap(),
    val columns: Int = 12,
    val rows: Int = 40
)

data class Workbook(
    val title: String = "Classeur",
    val sheets: List<Sheet> = listOf(Sheet())
)

// ---------------------------------------------------------------- présentation

data class SlideModel(
    val title: String = "",
    val content: String = "",
    val background: Long = 0xFF1E293BL,
    val textColor: Long = 0xFFFFFFFFL,
    val titleSize: Int = 32,
    val contentSize: Int = 20,
    /** 0 = gauche, 1 = centre, 2 = droite. */
    val align: Int = 1,
    val fontName: String = "",
    /** Commentaire du présentateur, jamais projeté à l'écran. */
    val notes: String = ""
)

data class Deck(
    val title: String = "Présentation",
    val slides: List<SlideModel> = emptyList()
)

// ---------------------------------------------------------------- couleurs

/** `AARRGGBB` → `RRGGBB`, la forme attendue par OOXML et ODF. */
fun Long.toHexRgb(): String = String.format("%06X", this.toInt() and 0xFFFFFF)

/** `RRGGBB` ou `AARRGGBB` → ARGB opaque. */
fun hexToArgb(hex: String?, fallback: Long): Long {
    val clean = hex?.trim()?.removePrefix("#") ?: return fallback
    return when (clean.length) {
        6 -> runCatching { 0xFF000000L or clean.toLong(16) }.getOrDefault(fallback)
        8 -> runCatching { clean.toLong(16) }.getOrDefault(fallback)
        else -> fallback
    }
}
