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
    val highlight: Long = 0L,
    /** 1 = exposant, -1 = indice, 0 = ligne de base. */
    val baseline: Int = 0
)

/** Ce qui compose le corps d'un document : un paragraphe ou un tableau. */
sealed interface DocBlock

/**
 * [align] : 0 = gauche, 1 = centre, 2 = droite, 3 = justifié.
 * [heading] : 0 pour le corps de texte, 1 à 3 pour les niveaux de titre.
 * [indent] : niveau de retrait, un cran valant 1,27 cm comme dans Word.
 */
data class TextParagraph(
    val runs: List<TextRun> = emptyList(),
    val align: Int = 0,
    val heading: Int = 0,
    val indent: Int = 0
) : DocBlock {
    val plainText: String get() = runs.joinToString("") { it.text }
}

/**
 * Une cellule de tableau. Une fusion horizontale s'écrit par [colSpan] ; une
 * fusion verticale, par une cellule vide marquée [mergedAbove] sous celle
 * qu'elle prolonge. La grille reste ainsi rectangulaire, ce qui garde simples
 * l'affichage et les insertions de lignes.
 */
data class TableCell(
    val paragraphs: List<TextParagraph> = listOf(TextParagraph()),
    /** ARGB, 0 = pas de fond. */
    val fill: Long = 0L,
    val colSpan: Int = 1,
    val mergedAbove: Boolean = false
) {
    val plainText: String get() = paragraphs.joinToString("\n") { it.plainText }
}

data class TableRow(val cells: List<TableCell>)

data class TextTable(
    val rows: List<TableRow>,
    /** La première ligne est un en-tête, répété en haut de page par Word. */
    val headerRow: Boolean = false
) : DocBlock {
    val columnCount: Int get() = rows.maxOfOrNull { row -> row.cells.sumOf { it.colSpan } } ?: 0

    /**
     * Grille rectangulaire : chaque ligne couvre exactement [columnCount]
     * colonnes. Les fichiers reçus ont souvent des lignes courtes ; les
     * compléter ici évite que l'affichage ne se décale.
     */
    fun normalized(): TextTable {
        val width = columnCount.coerceAtLeast(1)
        val fixed = rows.map { row ->
            val cells = ArrayList<TableCell>()
            var used = 0
            for (cell in row.cells) {
                if (used >= width) break
                val span = cell.colSpan.coerceIn(1, width - used)
                cells.add(if (span == cell.colSpan) cell else cell.copy(colSpan = span))
                used += span
            }
            while (used < width) {
                cells.add(TableCell()); used++
            }
            TableRow(cells)
        }
        // Une cellule « fusionnée avec celle du dessus » n'a pas de sens en
        // première ligne.
        val first = fixed.firstOrNull()?.let { row ->
            TableRow(row.cells.map { if (it.mergedAbove) it.copy(mergedAbove = false) else it })
        }
        return copy(rows = if (first == null) fixed else listOf(first) + fixed.drop(1))
    }

    /** Une ligne de texte par rangée, cellules séparées par des tabulations. */
    val plainRows: List<String>
        get() = rows.map { row -> row.cells.joinToString("\t") { it.plainText.replace('\n', ' ') } }
}

data class TextDocument(
    val title: String = "Document",
    val blocks: List<DocBlock> = emptyList(),
    /** Interligne en pourcentage : 100 = simple, 150 = une ligne et demie. */
    val lineSpacing: Int = 100
) {
    /**
     * Le document vu comme une suite de paragraphes, pour les formats qui
     * ignorent les tableaux : chaque rangée devient une ligne tabulée.
     */
    val paragraphs: List<TextParagraph>
        get() = blocks.flatMap { block ->
            when (block) {
                is TextParagraph -> listOf(block)
                is TextTable -> block.plainRows.map { TextParagraph(listOf(TextRun(it))) }
            }
        }

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
    val notes: String = "",
    /** Mise en page : voir [SlideLayout]. */
    val layout: Int = SlideLayout.TITLE_AND_CONTENT,
    /** Colonne de droite de la mise en page à deux colonnes. */
    val secondContent: String = "",
    /** Chaque ligne du contenu est une puce. */
    val bullets: Boolean = false
)

object SlideLayout {
    const val TITLE_AND_CONTENT = 0
    /** Titre centré seul : page de titre ou de section. */
    const val SECTION = 1
    const val TWO_COLUMNS = 2
}

data class Deck(
    val title: String = "Présentation",
    val slides: List<SlideModel> = emptyList(),
    /** Texte de pied de page, répété sur chaque diapositive. */
    val footer: String = "",
    /** Numéro de diapositive affiché en bas à droite. */
    val slideNumbers: Boolean = false
)

// ---------------------------------------------------------------- couleurs

/** `AARRGGBB` → `RRGGBB`, la forme attendue par OOXML et ODF. */
fun Long.toHexRgb(): String = String.format("%06X", this.toInt() and 0xFFFFFF)

/** Couleur de texte lisible sur [background] : presque noir sur fond clair, blanc sinon. */
fun readableOn(background: Long): Long {
    val r = (background shr 16) and 0xFF
    val g = (background shr 8) and 0xFF
    val b = background and 0xFF
    val luminance = 0.2126 * r + 0.7152 * g + 0.0722 * b
    return if (luminance > 150) 0xFF111827L else 0xFFFFFFFFL
}

/** `RRGGBB` ou `AARRGGBB` → ARGB opaque. */
fun hexToArgb(hex: String?, fallback: Long): Long {
    val clean = hex?.trim()?.removePrefix("#") ?: return fallback
    return when (clean.length) {
        6 -> runCatching { 0xFF000000L or clean.toLong(16) }.getOrDefault(fallback)
        8 -> runCatching { clean.toLong(16) }.getOrDefault(fallback)
        else -> fallback
    }
}
