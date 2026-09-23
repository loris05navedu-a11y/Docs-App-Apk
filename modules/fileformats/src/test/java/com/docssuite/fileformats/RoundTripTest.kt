package com.docssuite.fileformats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RoundTripTest {

    private val document = TextDocument(
        title = "Rapport",
        blocks = listOf(
            TextParagraph(
                listOf(
                    TextRun("Titre ", bold = true, size = 24, color = 0xFF1D4ED8L),
                    TextRun("secondaire", italic = true, size = 24)
                ),
                align = 1
            ),
            TextParagraph(
                listOf(
                    TextRun("Texte normal avec « accents » & <balises>."),
                    TextRun(" souligné", underline = true),
                    TextRun(" barré", strike = true),
                    TextRun(" surligné", highlight = 0xFFFFFF00L)
                )
            ),
            TextParagraph(listOf(TextRun("À droite")), align = 2)
        )
    )

    private val workbook = Workbook(
        title = "Budget",
        sheets = listOf(
            Sheet(
                name = "Janvier",
                cells = mapOf(
                    "A1" to "Poste", "B1" to "Montant",
                    "A2" to "Loyer", "B2" to "750",
                    "A3" to "Courses", "B3" to "312.5",
                    "B4" to "=SOMME(B2:B3)"
                ),
                styles = mapOf(
                    "A1" to CellStyle(bold = true, background = 0xFFE0E7FFL, align = 2),
                    "B1" to CellStyle(bold = true, background = 0xFFE0E7FFL, align = 2),
                    "B4" to CellStyle(bold = true, color = 0xFF166534L)
                ),
                columns = 4,
                rows = 8
            )
        )
    )

    private val deck = Deck(
        title = "Bilan",
        slides = listOf(
            SlideModel(
                title = "Introduction",
                content = "Première ligne\nDeuxième ligne",
                background = 0xFF1E293BL,
                textColor = 0xFFFFFFFFL,
                titleSize = 40,
                contentSize = 20
            ),
            SlideModel(title = "Conclusion", content = "Merci !", background = 0xFF7F1D1DL)
        )
    )

    private val evaluator = Xlsx.Evaluator { _, ref -> if (ref == "B4") "1062.5" else null }

    // ------------------------------------------------------------ docx

    @Test
    fun `un docx se relit avec son texte et sa mise en forme`() {
        val restored = Docx.read(Docx.write(document))

        assertEquals(document.paragraphs.size, restored.paragraphs.size)
        assertEquals("Titre secondaire", restored.paragraphs[0].plainText)
        assertEquals(1, restored.paragraphs[0].align)
        assertEquals(2, restored.paragraphs[2].align)

        val title = restored.paragraphs[0].runs.first()
        assertTrue(title.bold)
        assertEquals(24, title.size)
        assertEquals(0xFF1D4ED8L, title.color)
        assertTrue(restored.paragraphs[0].runs[1].italic)

        val body = restored.paragraphs[1]
        assertTrue(body.plainText.contains("« accents » & <balises>"))
        assertTrue(body.runs.any { it.underline })
        assertTrue(body.runs.any { it.strike })
        assertEquals(0xFFFFFF00L, body.runs.first { it.text.contains("surligné") }.highlight)
    }

    @Test
    fun `un docx contient les parties exigees par le format`() {
        val parts = unzip(Docx.write(document))
        assertTrue(parts.containsKey("[Content_Types].xml"))
        assertTrue(parts.containsKey("_rels/.rels"))
        assertTrue(parts.containsKey("word/document.xml"))
        assertTrue(parts.containsKey("word/_rels/document.xml.rels"))
    }

    // ------------------------------------------------------------ xlsx

    @Test
    fun `un xlsx se relit avec ses valeurs, formules et styles`() {
        val restored = Xlsx.read(Xlsx.write(workbook, evaluator))
        val sheet = restored.sheets.single()

        assertEquals("Janvier", sheet.name)
        assertEquals("Poste", sheet.cells["A1"])
        assertEquals("750", sheet.cells["B2"])
        assertEquals("312.5", sheet.cells["B3"])
        // La formule part en anglais dans le fichier et revient en français.
        assertEquals("=SOMME(B2:B3)", sheet.cells["B4"])

        assertEquals(true, sheet.styles["A1"]?.bold)
        assertEquals(0xFFE0E7FFL, sheet.styles["A1"]?.background)
        assertEquals(2, sheet.styles["A1"]?.align)
        assertEquals(0xFF166534L, sheet.styles["B4"]?.color)
    }

    @Test
    fun `un xlsx ecrit la formule en anglais et met sa valeur en cache`() {
        val sheetXml = unzip(Xlsx.write(workbook, evaluator))["xl/worksheets/sheet1.xml"]!!
            .toString(Charsets.UTF_8)
        assertTrue(sheetXml.contains("<f>SUM(B2:B3)</f>"))
        assertTrue(sheetXml.contains("<v>1062.5</v>"))
    }

    @Test
    fun `les deux premiers remplissages xlsx restent ceux imposes par Excel`() {
        val styles = unzip(Xlsx.write(workbook, evaluator))["xl/styles.xml"]!!.toString(Charsets.UTF_8)
        val none = styles.indexOf("patternType=\"none\"")
        val gray = styles.indexOf("patternType=\"gray125\"")
        val solid = styles.indexOf("patternType=\"solid\"")
        assertTrue(none in 0 until gray)
        assertTrue(gray < solid)
    }

    @Test
    fun `un texte a zeros de tete reste du texte`() {
        val sheet = Sheet(cells = mapOf("A1" to "01234"))
        val restored = Xlsx.read(Xlsx.write(Workbook(sheets = listOf(sheet))))
        assertEquals("01234", restored.sheets.single().cells["A1"])
    }

    // ------------------------------------------------------------ pptx

    @Test
    fun `un pptx se relit avec ses diapositives dans l ordre`() {
        val restored = Pptx.read(Pptx.write(deck))
        assertEquals(2, restored.slides.size)
        assertEquals("Introduction", restored.slides[0].title)
        assertEquals("Première ligne\nDeuxième ligne", restored.slides[0].content)
        assertEquals(0xFF1E293BL, restored.slides[0].background)
        assertEquals("Conclusion", restored.slides[1].title)
        assertEquals(0xFF7F1D1DL, restored.slides[1].background)
    }

    @Test
    fun `un pptx contient la chaine masque, disposition et theme`() {
        val parts = unzip(Pptx.write(deck))
        listOf(
            "ppt/presentation.xml",
            "ppt/_rels/presentation.xml.rels",
            "ppt/slideMasters/slideMaster1.xml",
            "ppt/slideLayouts/slideLayout1.xml",
            "ppt/theme/theme1.xml",
            "ppt/slides/slide1.xml",
            "ppt/slides/_rels/slide1.xml.rels"
        ).forEach { assertTrue("$it manquant", parts.containsKey(it)) }
    }

    @Test
    fun `l ordre des diapositives ne suit pas l ordre alphabetique des fichiers`() {
        val long = Deck("Long", (1..12).map { SlideModel(title = "Diapo $it") })
        val restored = Pptx.read(Pptx.write(long))
        assertEquals((1..12).map { "Diapo $it" }, restored.slides.map { it.title })
    }

    // ------------------------------------------------------------ OpenDocument

    @Test
    fun `un odt se relit avec son texte`() {
        val restored = Odf.readText(Odf.writeText(document))
        assertEquals("Titre secondaire", restored.paragraphs[0].plainText)
        assertTrue(restored.paragraphs[1].plainText.contains("« accents » & <balises>"))
    }

    @Test
    fun `un odt declare son type mimetype en premier et non compresse`() {
        val bytes = Odf.writeText(document)
        // En-tête local (30 o) + nom de l'entrée, puis le type en clair : tout
        // tient dans les premiers octets, preuve que rien n'a été compressé.
        val head = bytes.copyOfRange(0, 128).toString(Charsets.ISO_8859_1)
        assertTrue(head.contains("mimetype"))
        assertTrue(head.contains(Odf.MIME_TEXT))
    }

    @Test
    fun `un ods se relit avec ses valeurs et formules`() {
        val restored = Odf.readSheet(Odf.writeSheet(workbook) { _, ref ->
            workbook.sheets[0].cells[ref]?.takeUnless { it.startsWith("=") } ?: ""
        })
        val sheet = restored.sheets.single()
        assertEquals("Loyer", sheet.cells["A2"])
        assertEquals("=SOMME(B2:B3)", sheet.cells["B4"])
    }

    @Test
    fun `un odp se relit avec ses diapositives`() {
        val restored = Odf.readDeck(Odf.writeDeck(deck))
        assertEquals(2, restored.slides.size)
        assertEquals("Introduction", restored.slides[0].title)
        assertTrue(restored.slides[0].content.contains("Deuxième ligne"))
    }

    @Test
    fun `le remplissage vide d un ods ne gonfle pas la feuille`() {
        // LibreOffice termine chaque ligne par une plage vide qui va jusqu'au
        // bout de la feuille : elle ne doit créer ni cellule ni colonne.
        val content = XML_DECL +
            "<office:document-content" +
            " xmlns:office=\"urn:oasis:names:tc:opendocument:xmlns:office:1.0\"" +
            " xmlns:table=\"urn:oasis:names:tc:opendocument:xmlns:table:1.0\"" +
            " xmlns:text=\"urn:oasis:names:tc:opendocument:xmlns:text:1.0\">" +
            "<office:body><office:spreadsheet><table:table table:name=\"Feuille1\">" +
            "<table:table-row>" +
            "<table:table-cell office:value-type=\"string\"><text:p>Nom</text:p></table:table-cell>" +
            "<table:table-cell table:number-columns-repeated=\"16383\"/>" +
            "</table:table-row>" +
            "<table:table-row table:number-rows-repeated=\"1048575\">" +
            "<table:table-cell table:number-columns-repeated=\"16384\"/>" +
            "</table:table-row>" +
            "</table:table></office:spreadsheet></office:body></office:document-content>"
        val archive = ZipBuilder()
            .add("content.xml", content)
            .addStoredFirst("mimetype", Odf.MIME_SHEET)
            .build()

        val sheet = Odf.readSheet(archive).sheets.single()
        assertEquals(1, sheet.cells.size)
        assertEquals("Nom", sheet.cells["A1"])
        assertEquals(12, sheet.columns)
        assertEquals(40, sheet.rows)
    }

    // ------------------------------------------------------------ texte

    @Test
    fun `un csv protege les separateurs et les guillemets`() {
        val sheet = Sheet(
            cells = mapOf("A1" to "Dupont; Jean", "B1" to "Il a dit \"oui\"", "A2" to "42"),
            columns = 2,
            rows = 2
        )
        val csv = Csv.write(sheet) { sheet.cells[it].orEmpty() }
        val restored = Csv.read(csv)
        assertEquals("Dupont; Jean", restored.cells["A1"])
        assertEquals("Il a dit \"oui\"", restored.cells["B1"])
        assertEquals("42", restored.cells["A2"])
    }

    @Test
    fun `un csv a virgules est reconnu`() {
        val restored = Csv.read("nom,age\nAlice,30\nBob,41")
        assertEquals("nom", restored.cells["A1"])
        assertEquals("41", restored.cells["B3"])
    }

    @Test
    fun `un rtf se relit avec ses accents`() {
        val restored = Rtf.read(Rtf.write(document))
        val text = restored.paragraphs.joinToString("\n") { it.plainText }
        assertTrue(text.contains("Titre"))
        assertTrue(text.contains("accents"))
        assertTrue(text.contains("À droite"))
    }

    @Test
    fun `le markdown conserve gras et italique`() {
        val restored = Markdown.read(Markdown.write(document))
        val runs = restored.paragraphs.flatMap { it.runs }
        assertTrue(runs.any { it.bold && it.text.contains("Titre") })
        assertTrue(runs.any { it.italic && it.text.contains("secondaire") })
    }

    @Test
    fun `le html echappe le balisage puis le retrouve`() {
        val restored = Html.read(Html.write(document))
        assertTrue(restored.plainText.contains("<balises>"))
    }

    // ------------------------------------------------------------ formules

    @Test
    fun `les noms de fonctions sont traduits mais pas les references`() {
        assertEquals("SUM(A1:A5)", formulaToEnglish("SOMME(A1:A5)"))
        assertEquals("AVERAGE(B1:B9)/2", formulaToEnglish("MOYENNE(B1:B9)/2"))
        assertEquals("SOMME(SI(A1;B1;C1))", formulaToFrench("SUM(IF(A1;B1;C1))"))
        // E1 est une cellule, pas la constante E ; MIN n'a pas de traduction.
        assertEquals("MIN(E1:E9)", formulaToEnglish("MIN(E1:E9)"))
    }

    @Test
    fun `les references de cellules se convertissent dans les deux sens`() {
        assertEquals("A1", CellRef.key(0, 0))
        assertEquals("AA10", CellRef.key(9, 26))
        assertEquals(0 to 0, CellRef.parse("A1"))
        assertEquals(9 to 26, CellRef.parse("AA10"))
        assertEquals(6 to 1, CellRef.parse("\$B\$7"))
        assertNull(CellRef.parse("42"))
        assertNull(CellRef.parse(""))
    }

    // ------------------------------------------------------------ détection

    @Test
    fun `chaque format produit est reconnu a sa signature`() {
        assertEquals(FileFormat.DOCX, FileFormats.detect("x.bin", Docx.write(document)))
        assertEquals(FileFormat.XLSX, FileFormats.detect("x.bin", Xlsx.write(workbook)))
        assertEquals(FileFormat.PPTX, FileFormats.detect("x.bin", Pptx.write(deck)))
        assertEquals(FileFormat.ODT, FileFormats.detect("x.bin", Odf.writeText(document)))
        assertEquals(FileFormat.ODS, FileFormats.detect("x.bin", Odf.writeSheet(workbook) { _, _ -> "" }))
        assertEquals(FileFormat.ODP, FileFormats.detect("x.bin", Odf.writeDeck(deck)))
        if (nativePdfAvailable) {
            assertEquals(
                FileFormat.PDF,
                FileFormats.detect("x.bin", PdfExport.fromTextDocument(document))
            )
        }
        assertEquals(
            FileFormat.RTF,
            FileFormats.detect(null, Rtf.write(document).toByteArray(Charsets.UTF_8))
        )
    }

    @Test
    fun `l extension sert de recours quand le contenu ne dit rien`() {
        assertEquals(FileFormat.CSV, FileFormats.detect("data.csv", "a;b".toByteArray()))
        assertEquals(FileFormat.TXT, FileFormats.detect("notes.txt", "bonjour".toByteArray()))
        assertEquals(FileFormat.MD, FileFormats.detect("lisezmoi.md", "# Titre".toByteArray()))
        assertNull(FileFormats.detect("inconnu.zzz", byteArrayOf(1, 2, 3)))
    }

    @Test
    fun `un fichier illisible remonte une erreur de format, pas un plantage`() {
        assertThrows(FormatException::class.java) {
            FileFormats.import("x.zzz", byteArrayOf(9, 9, 9))
        }
        assertThrows(FormatException::class.java) {
            Docx.read(ZipBuilder().add("autre.xml", "<a/>").build())
        }
    }

    // ------------------------------------------------------------ import global

    @Test
    fun `l import route chaque format vers la bonne famille`() {
        assertTrue(FileFormats.import("a.docx", Docx.write(document)) is Imported.AsText)
        assertTrue(FileFormats.import("a.xlsx", Xlsx.write(workbook)) is Imported.AsSheet)
        assertTrue(FileFormats.import("a.pptx", Pptx.write(deck)) is Imported.AsDeck)
        if (nativePdfAvailable) {
            assertTrue(
                FileFormats.import("a.pdf", PdfExport.fromTextDocument(document)) is Imported.AsPdf
            )
        }
        assertEquals(
            "Rapport final",
            FileFormats.import("Rapport final.docx", Docx.write(document)).suggestedName
        )
    }

    @Test
    fun `chaque format exportable produit un fichier non vide`() {
        FileFormat.exportTargets(DocKind.TEXT)
            .filter { nativePdfAvailable || it != FileFormat.PDF }
            .forEach {
            assertTrue(it.label, FileFormats.exportText(it, document).isNotEmpty())
        }
        FileFormat.exportTargets(DocKind.SHEET)
            .filter { nativePdfAvailable || it != FileFormat.PDF }
            .forEach {
            val bytes = FileFormats.exportSheet(it, workbook) { sheet, ref ->
                sheet.cells[ref].orEmpty().removePrefix("=")
            }
            assertTrue(it.label, bytes.isNotEmpty())
        }
        FileFormat.exportTargets(DocKind.DECK)
            .filter { nativePdfAvailable || it != FileFormat.PDF }
            .forEach {
            assertTrue(it.label, FileFormats.exportDeck(it, deck).isNotEmpty())
        }
    }

    // ------------------------------------------------------------ PDF

    @Test
    fun `un pdf exporte contient le texte du document`() {
        assumeTrue(NATIVE_PDF_MESSAGE, nativePdfAvailable)
        val bytes = PdfExport.fromTextDocument(document)
        assertTrue(PdfText.looksLikePdf(bytes))
        val extracted = PdfText.extract(bytes)
        assertTrue("texte absent : $extracted", extracted.contains("Titre"))
    }

    @Test
    fun `un pdf de classeur et de diapositives se genere sans erreur`() {
        assumeTrue(NATIVE_PDF_MESSAGE, nativePdfAvailable)
        assertTrue(
            PdfExport.fromWorkbook(workbook) { sheet, ref -> sheet.cells[ref].orEmpty() }
                .isNotEmpty()
        )
        assertTrue(PdfExport.fromDeck(deck).isNotEmpty())
    }

    @Test
    fun `un document vide s exporte quand meme`() {
        val empty = TextDocument("Vide", emptyList())
        if (nativePdfAvailable) assertTrue(PdfExport.fromTextDocument(empty).isNotEmpty())
        assertNotNull(Docx.read(Docx.write(empty)))
        assertTrue(Pptx.write(Deck("Vide", emptyList())).isNotEmpty())
        assertTrue(Xlsx.write(Workbook("Vide", emptyList())).isNotEmpty())
    }

    // ------------------------------------------------------------ binaires hérités

    @Test
    fun `un fichier qui n est pas du OLE2 est refuse proprement`() {
        assertTrue(!Cfb.looksLikeCfb(byteArrayOf(1, 2, 3)))
        assertThrows(FormatException::class.java) { Cfb.open(ByteArray(1024)) }
        assertThrows(FormatException::class.java) { XlsLegacy.read(ByteArray(1024)) }
        assertThrows(FormatException::class.java) { DocLegacy.read(ByteArray(1024)) }
        assertThrows(FormatException::class.java) { PptLegacy.read(ByteArray(1024)) }
    }

    @Test
    fun `les polices se traduisent vers des noms connus des suites bureautiques`() {
        assertEquals("Times New Roman", officeFontName("Serif"))
        assertEquals("Courier New", officeFontName("Monospace"))
        assertEquals("Calibri", officeFontName(null))
        assertEquals("Serif", appFontLabel("Times New Roman"))
        assertEquals("Monospace", appFontLabel("Consolas"))
        assertEquals("Défaut", appFontLabel(""))
    }

    @Test
    fun `l echappement xml retire les caracteres interdits`() {
        assertEquals("a&amp;b&lt;c&gt;d", xmlEscape("a&b<c>d"))
        assertEquals("ok", xmlEscape("o\u0000k\u0001"))
        assertEquals("a\tb\n", xmlEscape("a\tb\n"))
    }

    internal companion object {
        const val NATIVE_PDF_MESSAGE =
            "android.graphics.pdf.PdfDocument s'appuie sur du code natif absent de la JVM de test"

        /**
         * Robolectric ne fournit pas le moteur PDF natif : le document se crée
         * mais naît fermé. On vérifie donc ici ce qui est vérifiable hors
         * appareil, et le rendu PDF se teste sur un vrai téléphone.
         */
        val nativePdfAvailable: Boolean = runCatching {
            val pdf = android.graphics.pdf.PdfDocument()
            val page = pdf.startPage(
                android.graphics.pdf.PdfDocument.PageInfo.Builder(10, 10, 1).create()
            )
            pdf.finishPage(page)
            pdf.close()
            true
        }.getOrDefault(false)
    }
}
