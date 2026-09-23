package com.docssuite.spreadsheet

import com.docssuite.fileformats.Csv
import com.docssuite.fileformats.Odf
import com.docssuite.fileformats.Workbook
import com.docssuite.fileformats.Xlsx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SheetBridgeTest {

    private val cells = mapOf(
        "A1" to "Poste", "B1" to "Montant",
        "A2" to "Loyer", "B2" to "750",
        "A3" to "Courses", "B3" to "312,5",
        "A4" to "Total", "B4" to "=SOMME(B2:B3)"
    )

    private val formats = mapOf(
        "A1" to CellFormat(bold = true, background = 0xFFE0E7FFL, align = 2),
        "B4" to CellFormat(bold = true, italic = true, color = 0xFF166534L)
    )

    @Test
    fun `un aller-retour par un xlsx conserve valeurs, formule et styles`() {
        val workbook = buildWorkbook("Budget", cells, formats, 4, 10)
        val restored = decodeWorkbook(Xlsx.read(Xlsx.write(workbook, ::sheetDisplayValue))).first()

        assertEquals("Poste", restored.cells["A1"])
        assertEquals("750", restored.cells["B2"])
        assertEquals("=SOMME(B2:B3)", restored.cells["B4"])

        assertEquals(true, restored.formats["A1"]?.bold)
        assertEquals(0xFFE0E7FFL, restored.formats["A1"]?.background)
        assertEquals(2, restored.formats["A1"]?.align)
        assertEquals(0xFF166534L, restored.formats["B4"]?.color)
        assertEquals(true, restored.formats["B4"]?.italic)
    }

    @Test
    fun `la valeur calculee de la formule part dans le fichier`() {
        val workbook = buildWorkbook("Budget", cells, formats, 4, 10)
        // La virgule décimale saisie est comprise à l'entrée (750 + 312,5) et
        // le résultat s'affiche avec un point, comme partout dans l'app.
        assertEquals("1062.5", sheetDisplayValue(workbook.sheets.first(), "B4"))

        val xml = com.docssuite.fileformats.unzip(Xlsx.write(workbook, ::sheetDisplayValue))
            .getValue("xl/worksheets/sheet1.xml")
            .toString(Charsets.UTF_8)
        assertTrue(xml, xml.contains("<f>SUM(B2:B3)</f>"))
        assertTrue(xml, xml.contains("<v>1062.5</v>"))
    }

    @Test
    fun `un csv se relit dans la grille`() {
        val workbook = buildWorkbook("Budget", cells, formats, 4, 10)
        val sheet = workbook.sheets.first()
        val restored = decodeWorkbook(Workbook("Budget", listOf(Csv.read(Csv.write(sheet) { sheetDisplayValue(sheet, it) })))).first()
        assertEquals("Loyer", restored.cells["A2"])
        // Le CSV ne transporte que le résultat, pas la formule.
        assertEquals("1062.5", restored.cells["B4"])
    }

    @Test
    fun `un ods conserve valeurs et formule`() {
        val workbook = buildWorkbook("Budget", cells, formats, 4, 10)
        val restored = decodeWorkbook(Odf.readSheet(Odf.writeSheet(workbook, ::sheetDisplayValue))).first()
        assertEquals("Courses", restored.cells["A3"])
        assertEquals("=SOMME(B2:B3)", restored.cells["B4"])
    }

    @Test
    fun `chaque feuille importee garde sa place et ses formules`() {
        val twoSheets = Workbook(
            "Multi",
            listOf(
                com.docssuite.fileformats.Sheet("Un", mapOf("A1" to "5"), rows = 3),
                com.docssuite.fileformats.Sheet("Deux", mapOf("A1" to "=Un!A1*2", "A2" to "=A1+1"), rows = 3)
            )
        )
        val restored = decodeWorkbook(twoSheets)
        assertEquals(listOf("Un", "Deux"), restored.map { it.name })
        assertEquals("=Un!A1*2", restored[1].cells["A1"])
        // Vu depuis la seconde feuille, le calcul va chercher la première.
        val engine = WorkbookOps.engineCells(restored, 1)
        assertEquals("10", com.docssuite.core.FormulaEngine.displayValue("A1", engine))
        assertEquals("11", com.docssuite.core.FormulaEngine.displayValue("A2", engine))
        // Et l'export écrit ces valeurs calculées.
        val display = workbookDisplay(restored)
        val book = buildWorkbook("Multi", restored)
        assertEquals("10", display(book.sheets[1], "A1"))
    }

    @Test
    fun `un xlsx a deux feuilles se relit avec ses deux feuilles`() {
        val sheets = listOf(
            SheetState("Ventes", mapOf("A1" to "3", "A2" to "4", "A3" to "=SOMME(A1:A2)")),
            SheetState("Synthèse 2024", mapOf("B1" to "=Ventes!A3*10"))
        )
        val bytes = Xlsx.write(buildWorkbook("Classeur", sheets), workbookDisplay(sheets)::invoke)
        val back = decodeWorkbook(Xlsx.read(bytes))
        assertEquals(listOf("Ventes", "Synthèse 2024"), back.map { it.name })
        assertEquals("=Ventes!A3*10", back[1].cells["B1"])
        assertEquals("70", com.docssuite.core.FormulaEngine.displayValue("B1", WorkbookOps.engineCells(back, 1)))
    }

    @Test
    fun `renommer une feuille reecrit les formules qui la visent`() {
        val sheets = listOf(
            SheetState("Ventes", mapOf("A1" to "2")),
            SheetState("Bilan", mapOf("A1" to "=Ventes!A1+VENTES!A1", "A2" to "=\"Ventes!A1\"", "A3" to "=MesVentes!A1"))
        )
        val renamed = WorkbookOps.rename(sheets, 0, "Ventes 2024")
        assertEquals("Ventes 2024", renamed[0].name)
        assertEquals("='Ventes 2024'!A1+'Ventes 2024'!A1", renamed[1].cells["A1"])
        // Un texte entre guillemets et une autre feuille au nom voisin restent tels quels.
        assertEquals("=\"Ventes!A1\"", renamed[1].cells["A2"])
        assertEquals("=MesVentes!A1", renamed[1].cells["A3"])
        val back = WorkbookOps.rename(renamed, 0, "Ventes")
        assertEquals("=Ventes!A1+Ventes!A1", back[1].cells["A1"])
    }

    @Test
    fun `les noms de feuille invalides sont refuses`() {
        val sheets = listOf(SheetState("Ventes"), SheetState("Bilan"))
        assertTrue(WorkbookOps.validName(sheets, 1, "ventes") != null)
        assertTrue(WorkbookOps.validName(sheets, 1, "") != null)
        assertTrue(WorkbookOps.validName(sheets, 1, "a/b") != null)
        assertEquals(null, WorkbookOps.validName(sheets, 1, "Bilan final"))
        assertEquals("Feuille3", WorkbookOps.uniqueName(sheets))
    }

    @Test
    fun `un classeur enregistre garde ses feuilles et les anciens s ouvrent encore`() {
        val sheets = listOf(
            SheetState("A", mapOf("A1" to "1"), freezeRow = true),
            SheetState("B", mapOf("B2" to "=A!A1"), freezeColumn = true)
        )
        val (back, active) = WorkbookOps.decode(WorkbookOps.encode(sheets, 1))
        assertEquals(1, active)
        assertEquals(sheets.map { it.name to it.cells }, back.map { it.name to it.cells })
        assertTrue(back[0].freezeRow)
        assertTrue(back[1].freezeColumn)

        val legacy = encodeSheet(mapOf("C3" to "ancien"), emptyMap(), 12, 40, emptyList())
        val (old, _) = WorkbookOps.decode(legacy)
        assertEquals("ancien", old.single().cells["C3"])
    }

    @Test
    fun `l export se limite a la zone remplie`() {
        // La grille affichée est bien plus grande que les données : sans cette
        // coupe, un PDF issu d'un classeur importé ferait des milliers de pages.
        val sheet = buildWorkbook("Budget", cells, formats, 700, 5000).sheets.first()
        assertEquals(2, sheet.columns)
        assertEquals(4, sheet.rows)

        val lines = Csv.write(sheet) { sheetDisplayValue(sheet, it) }.split("\r\n")
        assertEquals(4, lines.size)
        assertEquals("Poste;Montant", lines[0])
    }

    @Test
    fun `un classeur vide reste utilisable`() {
        val restored = decodeWorkbook(Workbook("Vide", emptyList())).first()
        assertTrue(restored.cells.isEmpty())
        assertTrue(restored.columns >= 12)
        assertTrue(restored.rows >= 40)
    }
}
