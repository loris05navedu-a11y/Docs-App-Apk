package com.docssuite.pdftools

import java.io.ByteArrayOutputStream
import java.text.Normalizer
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Comment écrire le numéro de la page 3 d'un document de 12 pages. */
enum class PageNumberFormat(val sample: String) {
    PLAIN("3"),
    SLASH("3 / 12"),
    PAGE_OF("Page 3 sur 12"),
    DASHES("– 3 –");

    fun text(number: Int, total: Int): String = when (this) {
        PLAIN -> "$number"
        SLASH -> "$number / $total"
        PAGE_OF -> "Page $number sur $total"
        DASHES -> "– $number –"
    }
}

enum class PageNumberPosition(val label: String) {
    BOTTOM_CENTER("En bas, au centre"),
    BOTTOM_RIGHT("En bas, à droite"),
    TOP_RIGHT("En haut, à droite")
}

/**
 * Numérotation. [skipFirst] laisse la couverture sans numéro — elle compte
 * quand même, comme dans un traitement de texte : la page suivante porte le 2.
 */
data class PageNumbering(
    val format: PageNumberFormat = PageNumberFormat.PAGE_OF,
    val position: PageNumberPosition = PageNumberPosition.BOTTOM_CENTER,
    val skipFirst: Boolean = false,
    val startAt: Int = 1
)

/** Un texte en travers de chaque page, semi-transparent. */
data class Watermark(
    val text: String,
    /** 0 transparent … 1 opaque. */
    val opacity: Float = 0.18f,
    val color: Int = 0xFFDC2626.toInt()
)

/**
 * Tamponner un PDF : filigrane, numéros de page, ou les deux. Le contenu
 * d'origine n'est pas touché ; on ajoute un flux par page, par-dessus, et le
 * texte reste sélectionnable. Pages tournées : le tampon suit la page telle
 * qu'elle s'affiche.
 */
object PdfStamp {

    private const val FONT = "DocsStampF"
    private const val BOLD = "DocsStampB"
    private const val STATE = "DocsStampGS"

    /**
     * [password] ouvre un PDF protégé ; le résultat est alors protégé par le
     * même mot de passe : tamponner ne doit pas retirer une protection en douce.
     */
    fun apply(bytes: ByteArray, watermark: Watermark?, numbering: PageNumbering?, password: String? = null): ByteArray {
        require(watermark != null || numbering != null) { "Rien à ajouter" }
        val file = PdfFile.parse(bytes, password)
        val rewrite = PdfRewrite(file)
        val font = rewrite.add(standardFont("Helvetica"))
        val bold = rewrite.add(standardFont("Helvetica-Bold"))
        val state = watermark?.let {
            rewrite.add(PdfDict().apply {
                set("Type", PdfName("ExtGState"))
                set("ca", PdfNumber(n(it.opacity.coerceIn(0.02f, 1f))))
                set("CA", PdfNumber(n(it.opacity.coerceIn(0.02f, 1f))))
            })
        }
        val total = (numbering?.startAt ?: 1) + file.pages.size - 1

        file.pages.forEachIndexed { index, page ->
            val ref = page.ref ?: return@forEachIndexed
            val rotation = (file.resolve(page.attribute("Rotate")) as? PdfNumber)?.intValue ?: 0
            val geometry = PageGeometry.of(file, page, rotation)
            val number = numbering?.takeUnless { it.skipFirst && index == 0 }?.let {
                it.format.text(it.startAt + index, total) to it.position
            }
            val content = content(geometry, watermark, number)

            val dict = page.dict.copy()
            PdfFile.INHERITABLE.forEach { key ->
                if (dict[key] == null) page.inherited[key]?.let { dict[key] = it }
            }
            // Ressources propres à la page : on ajoute les nôtres sans toucher
            // à celles, peut-être partagées avec d'autres pages, d'origine.
            val resources = (file.resolve(dict["Resources"]) as? PdfDict)?.copy() ?: PdfDict()
            resources["Font"] = ((file.resolve(resources["Font"]) as? PdfDict)?.copy() ?: PdfDict()).apply {
                set(FONT, font)
                set(BOLD, bold)
            }
            if (state != null) {
                resources["ExtGState"] = ((file.resolve(resources["ExtGState"]) as? PdfDict)?.copy() ?: PdfDict()).apply {
                    set(STATE, state)
                }
            }
            dict["Resources"] = resources
            // Le contenu d'origine est encadré par q … Q : ce qu'il laisse
            // (repère déplacé, couleur…) ne déteint pas sur le tampon.
            val before = rewrite.add(PdfStream(PdfDict(), "q\n".toByteArray(Charsets.ISO_8859_1)))
            val after = rewrite.add(PdfStream(PdfDict(), "\nQ\n".toByteArray(Charsets.ISO_8859_1) + content))
            val original = when (val contents = dict["Contents"]) {
                is PdfArray -> contents.items
                null, PdfNull -> emptyList()
                else -> listOf(contents)
            }
            dict["Contents"] = PdfArray((listOf<PdfObject>(before) + original + after).toMutableList())
            rewrite.replace(ref.num, dict)
        }
        return rewrite.write(PdfLock.carry(file, password))
    }

    private fun standardFont(name: String) = PdfDict().apply {
        set("Type", PdfName("Font"))
        set("Subtype", PdfName("Type1"))
        set("BaseFont", PdfName(name))
        set("Encoding", PdfName("WinAnsiEncoding"))
    }

    internal fun content(geometry: PageGeometry, watermark: Watermark?, number: Pair<String, PageNumberPosition>?): ByteArray {
        val out = ByteArrayOutputStream()
        val m = geometry.matrix()
        val w = geometry.displayWidth
        val h = geometry.displayHeight
        out.ascii("q ${m.joinToString(" ") { n(it) }} cm\n")

        if (watermark != null && watermark.text.isNotBlank()) {
            val text = watermark.text.trim()
            val units = Helvetica.width(text, bold = true)
            // Aussi large que les trois quarts de la diagonale, sans écraser la page.
            val diagonal = hypot(w, h)
            val size = minOf(diagonal * 0.75f * 1000f / units, minOf(w, h) * 0.28f)
            val angle = atan2(h, w)
            val c = cos(angle)
            val s = sin(angle)
            val half = size * units / 1000f / 2f
            // Le milieu du texte au centre de la page ; 0,36 : mi-hauteur des capitales.
            val x = w / 2 - c * half + s * size * 0.36f
            val y = h / 2 - s * half - c * size * 0.36f
            out.ascii("q /$STATE gs ${rgb(watermark.color)} rg BT /$BOLD ${n(size)} Tf ")
            out.ascii("${n(c)} ${n(s)} ${n(-s)} ${n(c)} ${n(x)} ${n(y)} Tm (")
            out.write(OverlayWriter.escape(OverlayWriter.encode(text)))
            out.ascii(") Tj ET Q\n")
        }

        if (number != null) {
            val (text, position) = number
            // Taille proportionnée à la page : 10 pt sur un A4.
            val size = (minOf(w, h) / 595f * 10f).coerceIn(7f, 18f)
            val width = size * Helvetica.width(text, bold = false) / 1000f
            val margin = minOf(w, h) * 0.05f
            val x = when (position) {
                PageNumberPosition.BOTTOM_CENTER -> (w - width) / 2
                PageNumberPosition.BOTTOM_RIGHT, PageNumberPosition.TOP_RIGHT -> w - margin - width
            }
            val y = if (position == PageNumberPosition.TOP_RIGHT) h - margin * 0.8f - size * 0.72f else margin * 0.8f
            out.ascii("BT /$FONT ${n(size)} Tf 0.2 0.2 0.2 rg ${n(x)} ${n(y)} Td (")
            out.write(OverlayWriter.escape(OverlayWriter.encode(text)))
            out.ascii(") Tj ET\n")
        }
        out.ascii("Q\n")
        return out.toByteArray()
    }

    private fun rgb(color: Int) = listOf(16, 8, 0).joinToString(" ") { n(((color shr it) and 0xFF) / 255f) }

    private fun n(value: Float): String = String.format(Locale.US, "%.3f", value).trimEnd('0').trimEnd('.').let {
        if (it.isEmpty() || it == "-0") "0" else it
    }
}

/**
 * Largeurs des polices standard Helvetica et Helvetica-Bold (métriques
 * Adobe, millièmes de corps), pour centrer un texte sans embarquer de police.
 */
internal object Helvetica {

    // Caractères 32 à 126, dans l'ordre.
    private val regular = intArrayOf(
        278, 278, 355, 556, 556, 889, 667, 191, 333, 333, 389, 584, 278, 333, 278, 278,
        556, 556, 556, 556, 556, 556, 556, 556, 556, 556, 278, 278, 584, 584, 584, 556,
        1015, 667, 667, 722, 722, 667, 611, 778, 722, 278, 500, 667, 556, 833, 722, 778,
        667, 778, 722, 667, 611, 722, 667, 944, 667, 667, 611, 278, 278, 278, 469, 556,
        333, 556, 556, 500, 556, 556, 278, 556, 556, 222, 222, 500, 222, 833, 556, 556,
        556, 556, 333, 500, 278, 556, 500, 722, 500, 500, 500, 334, 260, 334, 584
    )
    private val bold = intArrayOf(
        278, 333, 474, 556, 556, 889, 722, 238, 333, 333, 389, 584, 278, 333, 278, 278,
        556, 556, 556, 556, 556, 556, 556, 556, 556, 556, 333, 333, 584, 584, 584, 611,
        975, 722, 722, 722, 722, 667, 611, 778, 722, 278, 556, 722, 611, 833, 722, 778,
        667, 778, 722, 667, 611, 722, 667, 944, 667, 667, 611, 333, 278, 333, 584, 556,
        333, 556, 611, 556, 611, 556, 333, 611, 611, 278, 278, 556, 278, 889, 611, 611,
        611, 611, 389, 556, 333, 611, 556, 778, 556, 556, 500, 389, 280, 389, 584
    )

    fun width(text: String, bold: Boolean): Float = text.sumOf { char(it, bold) }.toFloat()

    private fun char(c: Char, bold: Boolean): Int {
        val table = if (bold) this.bold else regular
        if (c.code in 32..126) return table[c.code - 32]
        return when (c) {
            'œ' -> 944
            'Œ', '—', '‰' -> 1000
            'æ' -> 889
            'Æ' -> 1000
            '’', '‘' -> if (bold) 278 else 222
            '«', '»', '€', '–' -> 556
            '°' -> 400
            ' ', ' ' -> 278
            else -> {
                // Une lettre accentuée a la largeur de sa lettre de base.
                val base = Normalizer.normalize(c.toString(), Normalizer.Form.NFD).firstOrNull() ?: c
                if (base != c && base.code in 32..126) table[base.code - 32] else 556
            }
        }
    }
}
