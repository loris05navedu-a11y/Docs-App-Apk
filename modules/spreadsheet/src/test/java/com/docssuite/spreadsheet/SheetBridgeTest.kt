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
        val restored = decodeWorkbook(
            Xlsx.read(Xlsx.write(workbook, ::sheetDisplayValue)),
            "Budget"
        )

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
        val restored = decodeWorkbook(
            Workbook("Budget", listOf(Csv.read(Csv.write(sheet) { sheetDisplayValue(sheet, it) }))),
            "Budget"
        )
        assertEquals("Loyer", restored.cells["A2"])
        // Le CSV ne transporte que le résultat, pas la formule.
        assertEquals("1062.5", restored.cells["B4"])
    }

    @Test
    fun `un ods conserve valeurs et formule`() {
        val workbook = buildWorkbook("Budget", cells, formats, 4, 10)
        val restored = decodeWorkbook(
            Odf.readSheet(Odf.writeSheet(workbook, ::sheetDisplayValue)),
            "Budget"
        )
        assertEquals("Courses", restored.cells["A3"])
        assertEquals("=SOMME(B2:B3)", restored.cells["B4"])
    }

    @Test
    fun `les feuilles supplementaires sont recopiees sous la premiere`() {
        val twoSheets = Workbook(
            "Multi",
            listOf(
                com.docssuite.fileformats.Sheet("Un", mapOf("A1" to "premier"), rows = 3),
                com.docssuite.fileformats.Sheet("Deux", mapOf("A1" to "second"), rows = 3)
            )
        )
        val restored = decodeWorkbook(twoSheets, "Multi")
        assertEquals("premier", restored.cells["A1"])
        assertTrue(restored.cells.values.contains("Deux"))
        assertTrue(restored.cells.values.contains("second"))
    }

    @Test
    fun `un classeur vide reste utilisable`() {
        val restored = decodeWorkbook(Workbook("Vide", emptyList()), "Vide")
        assertTrue(restored.cells.isEmpty())
        assertTrue(restored.columns >= 4)
        assertTrue(restored.rows >= 20)
    }
}
