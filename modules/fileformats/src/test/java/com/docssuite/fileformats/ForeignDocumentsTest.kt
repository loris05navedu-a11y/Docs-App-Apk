package com.docssuite.fileformats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Fichiers produits par d'autres logiciels (voir `src/test/corpus`). Le même
 * rapport, avec deux tableaux fusionnés, passe par Word, LibreOffice, RTF,
 * HTML et Word 97 : chaque lecteur doit en rendre la même structure.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ForeignDocumentsTest {

    private fun corpus(name: String): ByteArray = File("src/test/resources/corpus/$name").readBytes()

    private fun document(name: String): TextDocument =
        (FileFormats.import(name, corpus(name)) as Imported.AsText).document

    private fun cellText(cell: TableCell) = cell.plainText.trim()

    private val report = listOf(
        "rapport-word.docx", "rapport-libreoffice.docx", "rapport-libreoffice.odt",
        "rapport.rtf", "rapport.html", "rapport-word97.doc"
    )

    @Test
    fun `le tableau du rapport garde sa forme quel que soit le logiciel qui l a ecrit`() {
        report.forEach { name ->
            val tables = document(name).blocks.filterIsInstance<TextTable>()
            assertEquals("$name : deux tableaux", 2, tables.size)

            val figures = tables[0]
            assertEquals("$name : trois colonnes", 3, figures.columnCount)
            assertEquals(4, figures.rows.size)
            assertEquals(listOf("Région", "T1", "T2"), figures.rows[0].cells.map(::cellText))
            assertEquals(listOf("Nord", "120", "135"), figures.rows[1].cells.map(::cellText))

            // « Total » occupe deux colonnes.
            val total = figures.rows[3].cells
            assertEquals("$name : fusion horizontale", 2, total.size)
            assertEquals(2, total[0].colSpan)
            assertEquals("Total", cellText(total[0]))
            assertEquals("454", cellText(total[1]))

            // « Équipe » occupe deux lignes.
            val team = tables[1]
            assertEquals("Équipe", cellText(team.rows[0].cells[0]))
            assertTrue("$name : fusion verticale", team.rows[1].cells[0].mergedAbove)
            assertEquals("Bruno", cellText(team.rows[1].cells[1]))
            assertEquals("Chloé", cellText(team.rows[2].cells[1]))
        }
    }

    @Test
    fun `chaque ligne d un tableau importe couvre toutes les colonnes`() {
        (report + "word-particularites.docx").forEach { name ->
            document(name).blocks.filterIsInstance<TextTable>().forEach { table ->
                table.rows.forEach { row ->
                    assertEquals("$name : ligne incomplète", table.columnCount, row.cells.sumOf { it.colSpan })
                }
            }
        }
    }

    @Test
    fun `la mise en forme d un run ne deborde pas sur le suivant`() {
        // Le gras de « tableau » s'arrêtait tant qu'aucun run ne le désactivait.
        val paragraphs = document("rapport-word.docx").blocks.filterIsInstance<TextParagraph>()
        val intro = paragraphs.first { it.plainText.startsWith("Ce document") }
        assertTrue(intro.runs.first { it.text == "tableau" }.bold)
        assertFalse(intro.runs.first { it.text.contains("et des") }.bold)
        val table = document("rapport-word.docx").blocks.filterIsInstance<TextTable>().first()
        assertFalse(table.rows[1].cells[0].paragraphs.first().runs.first().bold)
    }

    @Test
    fun `titres, listes et alignement sont retrouves`() {
        listOf("rapport-word.docx", "rapport-libreoffice.odt", "rapport.rtf", "rapport.html").forEach { name ->
            val paragraphs = document(name).blocks.filterIsInstance<TextParagraph>()
            assertEquals("$name : titre", 1, paragraphs.first { it.plainText == "Rapport trimestriel" }.heading)
            assertEquals(2, paragraphs.first { it.plainText == "Chiffres" }.heading)
            assertTrue("$name : puce", paragraphs.any { it.plainText == "• Premier point" })
            assertTrue("$name : numéro", paragraphs.any { it.plainText == "2. Étape deux" })
            assertEquals(1, paragraphs.first { it.plainText == "Fin du rapport" }.align)
        }
    }

    @Test
    fun `les tournures de Word sont lues sans doublon ni perte`() {
        val document = document("word-particularites.docx")
        val text = document.plainText
        // Le BOM en tête du XML faisait échouer toute l'ouverture.
        assertTrue(text.contains("Paragraphe normal"))
        // Zone de texte : présente dans les deux rendus, lue une seule fois.
        assertEquals(1, Regex("Texte de la zone").findAll(text).count())
        assertTrue(text.contains("Avant la zone après la zone"))
        assertTrue(text.contains("Dans un contrôle de contenu"))
        // Suivi des modifications : l'ajout est gardé, la suppression non.
        assertTrue(text.contains("inséré"))
        assertFalse(text.contains("supprimé"))
        // Le code du champ n'est pas du texte.
        assertFalse(text.contains("PAGE"))

        val runs = document.blocks.filterIsInstance<TextParagraph>().first { it.plainText == "X2 et H2O" }.runs
        assertEquals(1, runs.first { it.text == "2" }.baseline)
        assertEquals(-1, runs.last { it.text == "2" }.baseline)

        val table = document.blocks.filterIsInstance<TextTable>().single()
        assertEquals(3, table.columnCount)
        assertEquals(2, table.rows[0].cells[1].colSpan)
        assertEquals("1\ndeuxième ligne", table.rows[1].cells[0].plainText)
        assertTrue(table.rows[1].cells[1].plainText.contains("imbriqué | x"))
        assertEquals("court", table.rows[2].cells[0].plainText)
    }

    @Test
    fun `les textes Windows sont decodes sans caracteres casses`() {
        val expected = "Élève, café, où, « guillemets » — 25 €"
        assertEquals(expected, document("windows-ansi.txt").paragraphs.first().plainText)
        assertEquals(expected, document("windows-utf16.txt").paragraphs.first().plainText)
        val sheet = (FileFormats.import("excel-francais.csv", corpus("excel-francais.csv")) as Imported.AsSheet)
            .workbook.sheets.first()
        assertEquals("Café", sheet.cells["A2"])
    }

    @Test
    fun `les images d un document ne sont pas decompressees pour le lire`() {
        val parts = unzip(corpus("word-particularites.docx"))
        assertFalse(parts.keys.any { it.startsWith("word/media/") })
        assertTrue(parts.containsKey("word/document.xml"))
    }

    @Test
    fun `un tableau survit a l aller-retour dans chaque format d enregistrement`() {
        val original = document("rapport-word.docx")
        listOf(FileFormat.DOCX, FileFormat.ODT, FileFormat.RTF, FileFormat.HTML, FileFormat.MD).forEach { format ->
            val bytes = FileFormats.exportText(format, original)
            val back = (FileFormats.import("retour.${format.extension}", bytes) as Imported.AsText).document
            val tables = back.blocks.filterIsInstance<TextTable>()
            assertEquals("${format.label} : tableaux", 2, tables.size)
            assertEquals("${format.label} : colonnes", 3, tables[0].columnCount)
            assertEquals("Total", cellText(tables[0].rows[3].cells[0]))
            if (format != FileFormat.MD) {
                // Markdown ne sait pas fusionner des cellules.
                assertEquals("${format.label} : fusion horizontale", 2, tables[0].rows[3].cells[0].colSpan)
                assertTrue("${format.label} : fusion verticale", tables[1].rows[1].cells[0].mergedAbove)
            }
        }
    }

    @Test
    fun `l export pdf dessine les documents a tableaux`() {
        assumeTrue(RoundTripTest.NATIVE_PDF_MESSAGE, RoundTripTest.nativePdfAvailable)
        val pdf = FileFormats.exportText(FileFormat.PDF, document("rapport-word.docx"))
        assertTrue(String(pdf, 0, 5, Charsets.ISO_8859_1).startsWith("%PDF"))
    }

    @Test
    fun `un tableau declare avec des fusions absurdes reste affichable`() {
        val html = """
            <table><tr><td colspan="999">A</td></tr><tr><td rowspan="50000">B</td><td>C</td></tr>
            <tr><td>D</td></tr></table><p>après</p>
        """.trimIndent()
        val document = Html.read(html)
        val table = document.blocks.filterIsInstance<TextTable>().single()
        table.rows.forEach { row -> assertEquals(table.columnCount, row.cells.sumOf { it.colSpan }) }
        assertTrue(table.columnCount <= 64)
        assertEquals("après", (document.blocks.last() as TextParagraph).plainText)
    }

    // ------------------------------------------------------------ classeurs et présentations

    private fun workbook(name: String): Workbook =
        (FileFormats.import(name, corpus(name)) as Imported.AsSheet).workbook

    @Test
    fun `une formule partagee d Excel est recopiee et decalee`() {
        val sheet = workbook("formules-partagees.xlsx").sheets.first()
        assertEquals("=A1+B1", sheet.cells["C1"])
        assertEquals("=A2+B2", sheet.cells["C2"])
        // Ligne 3 : cellules sans adresse, placées à la suite.
        assertEquals("3", sheet.cells["A3"])
        assertEquals("=A3+B3", sheet.cells["C3"])
        assertEquals("Riche texte", sheet.cells["A4"])
        assertEquals("0.3", sheet.cells["B4"])
        assertFalse(sheet.cells["C4"]!!.contains("_xlfn"))
    }

    @Test
    fun `une cellule mise en forme au bout de la feuille ne la rend pas geante`() {
        val sheet = workbook("formules-partagees.xlsx").sheets.first()
        assertTrue("${sheet.columns} × ${sheet.rows}", sheet.columns <= 12 && sheet.rows <= 40)
    }

    @Test
    fun `les dates Excel s affichent en dates`() {
        assertEquals("15/03/2024", workbook("classeur-excel.xlsx").sheets.first().cells["B7"])
    }

    @Test
    fun `un classeur LibreOffice garde ses lignes, formules et feuilles`() {
        val book = workbook("classeur-libreoffice.ods")
        val sales = book.sheets[0]
        assertEquals("=SOMME(D2:D4)", sales.cells["D5"])
        // Des lignes vides répétées séparent A7 de F40 : elles comptent.
        assertEquals("loin", sales.cells["F40"])
        assertEquals("15/03/2024", sales.cells["B7"])
        assertEquals("=Ventes!D5", book.sheets[1].cells["B1"])
        assertEquals("Synthèse 2024", book.sheets[1].name)
    }

    @Test
    fun `une presentation garde toutes ses diapositives, ses notes et son fond clair`() {
        listOf("presentation-powerpoint.pptx", "presentation-libreoffice.odp").forEach { name ->
            val deck = (FileFormats.import(name, corpus(name)) as Imported.AsDeck).deck
            assertEquals("$name : diapositives", 4, deck.slides.size)
            assertEquals("Présentation annuelle", deck.slides[0].title)
            assertEquals("Ordre du jour", deck.slides[1].title)
            assertEquals("Bilan\nPerspectives", deck.slides[1].content)
            assertEquals("$name : notes", "Parler lentement", deck.slides[1].notes)
            assertEquals("", deck.slides[0].notes)
            assertTrue(deck.slides[2].content.contains("L1C1 | L1C2"))
            deck.slides.forEach { slide ->
                assertEquals("$name : fond", 0xFFFFFFFFL, slide.background)
                assertEquals("$name : texte lisible", readableOn(0xFFFFFFFFL), slide.textColor)
            }
        }
    }
}
