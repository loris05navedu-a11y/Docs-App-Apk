package com.docssuite.ocr

/** Rectangle en pixels de l'image analysée. */
data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

data class OcrLine(val text: String, val box: Box)

/** Un bloc tel que le rend le moteur : en général un paragraphe, une légende, un titre. */
data class OcrBlock(val lines: List<OcrLine>, val box: Box)

data class OcrPage(val blocks: List<OcrBlock>, val width: Int, val height: Int)

/**
 * Du résultat brut du moteur à un texte qu'on peut éditer : ordre de
 * lecture (colonnes comprises), mots coupés en fin de ligne recollés,
 * paragraphes reformés au lieu d'un retour à la ligne par ligne de l'image.
 */
object TextLayout {

    fun documentText(pages: List<OcrPage>): String =
        pages.map(::pageText).filter { it.isNotBlank() }.joinToString("\n\n")

    fun pageText(page: OcrPage): String =
        readingOrder(page.blocks).map(::blockText).filter { it.isNotBlank() }.joinToString("\n\n")

    /**
     * Découpe récursive de la page :
     *
     * 1. on coupe horizontalement là où un bloc large (titre, paragraphe
     *    pleine largeur, pied de page) borde un espace vide ; un espace
     *    entre deux paragraphes d'une même colonne étroite n'est pas une
     *    coupure, sinon deux colonnes seraient lues en zigzag ;
     * 2. sinon, on coupe verticalement entre des colonnes, à condition que
     *    chacune contienne un vrai paragraphe (plusieurs lignes) : les
     *    libellés et valeurs d'un formulaire ne sont pas des colonnes ;
     * 3. sinon, on lit rangée par rangée, de gauche à droite.
     */
    fun readingOrder(blocks: List<OcrBlock>): List<OcrBlock> {
        if (blocks.size <= 1) return blocks
        horizontalSplit(blocks)?.let { groups -> return groups.flatMap(::readingOrder) }
        verticalSplit(blocks)?.let { groups -> return groups.flatMap(::readingOrder) }
        return rowByRow(blocks)
    }

    private fun horizontalSplit(blocks: List<OcrBlock>): List<List<OcrBlock>>? {
        val width = blocks.maxOf { it.box.right } - blocks.minOf { it.box.left }
        fun wide(block: OcrBlock) = block.box.width >= width * 0.6
        val sorted = blocks.sortedBy { it.box.top }
        val groups = ArrayList<MutableList<OcrBlock>>()
        var current = mutableListOf(sorted.first())
        var bottom = sorted.first().box.bottom
        for (block in sorted.drop(1)) {
            val gap = block.box.top >= bottom
            val lowest = current.maxByOrNull { it.box.bottom }!!
            if (gap && (wide(lowest) || wide(block))) {
                groups.add(current)
                current = mutableListOf(block)
                bottom = block.box.bottom
            } else {
                current.add(block)
                bottom = maxOf(bottom, block.box.bottom)
            }
        }
        groups.add(current)
        return groups.takeIf { it.size > 1 }
    }

    private fun verticalSplit(blocks: List<OcrBlock>): List<List<OcrBlock>>? {
        val sorted = blocks.sortedBy { it.box.left }
        val groups = ArrayList<MutableList<OcrBlock>>()
        var current = mutableListOf(sorted.first())
        var right = sorted.first().box.right
        for (block in sorted.drop(1)) {
            if (block.box.left >= right) {
                groups.add(current)
                current = mutableListOf(block)
                right = block.box.right
            } else {
                current.add(block)
                right = maxOf(right, block.box.right)
            }
        }
        groups.add(current)
        return groups.takeIf { g -> g.size > 1 && g.all { column -> column.any { it.lines.size >= 2 } } }
    }

    /** Rangées : un bloc rejoint la rangée si son milieu tombe dans sa hauteur. */
    private fun rowByRow(blocks: List<OcrBlock>): List<OcrBlock> {
        val rows = ArrayList<MutableList<OcrBlock>>()
        var top = 0
        var bottom = 0
        blocks.sortedBy { it.box.top }.forEach { block ->
            val middle = (block.box.top + block.box.bottom) / 2
            if (rows.isNotEmpty() && middle in top..bottom) {
                rows.last().add(block)
                bottom = maxOf(bottom, block.box.bottom)
            } else {
                rows.add(mutableListOf(block))
                top = block.box.top
                bottom = block.box.bottom
            }
        }
        return rows.flatMap { row -> row.sortedBy { it.box.left } }
    }

    /** Les lignes d'un bloc, recollées en paragraphes. */
    fun blockText(block: OcrBlock): String {
        val lines = block.lines.map { it.copy(text = it.text.trim()) }.filter { it.text.isNotEmpty() }
        if (lines.isEmpty()) return ""
        val out = StringBuilder(lines.first().text)
        for (i in 1 until lines.size) {
            val previous = lines[i - 1]
            val next = lines[i]
            val prevText = previous.text
            val nextText = next.text
            when {
                endsWithHyphenatedWord(prevText) && nextText.first().isLowerCase() -> {
                    out.setLength(out.length - 1) // « exem- » + « ple » → « exemple »
                    out.append(nextText)
                }
                startsNewLine(previous, next, block) -> out.append('\n').append(nextText)
                else -> out.append(' ').append(nextText)
            }
        }
        return out.toString()
    }

    private fun endsWithHyphenatedWord(text: String): Boolean =
        text.length >= 2 && text.last() in HYPHENS && text[text.length - 2].isLetter()

    /**
     * Une vraie fin de ligne : ponctuation finale, puce ou numéro au début
     * de la suivante, ou ligne nettement plus courte que le bloc (fin de
     * paragraphe, ligne d'adresse, élément de liste).
     */
    private fun startsNewLine(previous: OcrLine, next: OcrLine, block: OcrBlock): Boolean {
        val prev = previous.text
        if (prev.last() in ".!?:;»\"") return true
        if (next.text.first() in BULLETS || Regex("^\\d{1,3}[.)]\\s").containsMatchIn(next.text)) return true
        val used = previous.box.right - block.box.left
        return block.box.width > 0 && used < block.box.width * 0.7
    }

    private val HYPHENS = setOf('-', '‐', '¬')
    private val BULLETS = setOf('•', '·', '-', '–', '—', '*', '▪', '◦')
}
