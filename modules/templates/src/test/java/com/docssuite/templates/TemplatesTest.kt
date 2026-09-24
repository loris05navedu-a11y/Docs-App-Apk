package com.docssuite.templates

import com.docssuite.core.FormulaEngine
import com.docssuite.fileformats.Docx
import com.docssuite.fileformats.FileFormat
import com.docssuite.fileformats.FileFormats
import com.docssuite.fileformats.TextTable
import com.docssuite.fileformats.Xlsx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TemplatesTest {

    private val today = Calendar.getInstance().apply { set(2026, Calendar.SEPTEMBER, 24, 10, 0) }.time

    private fun text(id: String, values: Map<String, String> = emptyMap()) =
        Templates.byId(id)!!.build(values, today) as Built.Text

    private fun sheet(id: String, values: Map<String, String> = emptyMap()) =
        Templates.byId(id)!!.build(values, today) as Built.Sheet

    private fun Built.Sheet.show(ref: String, extra: Map<String, String> = emptyMap()): String {
        val cells = workbook.sheets[0].cells + extra
        // Les milliers sont séparés par une espace insécable, comme en français.
        return FormulaEngine.displayValue(ref, cells).replace('\u202F', ' ').replace('\u00A0', ' ')
    }

    @Test
    fun `chaque modele se cree meme sans rien remplir`() {
        assertEquals(8, Templates.all.size)
        Templates.all.forEach { template ->
            val built = template.build(emptyMap(), today)
            assertTrue(built.name, built.name.isNotBlank())
            when (built) {
                is Built.Text -> assertTrue(template.id, built.document.plainText.length > 100)
                is Built.Sheet -> {
                    val cells = built.workbook.sheets[0].cells
                    // Aucune formule en erreur dans un modèle vierge.
                    cells.keys.forEach { ref ->
                        val shown = FormulaEngine.displayValue(ref, cells)
                        assertFalse("${template.id} $ref : $shown", shown.startsWith("#"))
                    }
                }
            }
        }
    }

    @Test
    fun `un champ vide devient un repere entre crochets, un champ rempli est repris`() {
        val letter = text("motivation", mapOf("nom" to "Léa Martin", "poste" to "Assistante de gestion", "lieu" to "Lyon"))
        val plain = letter.document.plainText
        assertTrue(plain.contains("Léa Martin"))
        assertTrue(plain.contains("Objet : candidature au poste de Assistante de gestion"))
        assertTrue(plain.contains("Lyon, le 24 septembre 2026"))
        assertTrue(plain.contains("[Entreprise]"))
        assertFalse(plain.contains("réf."))
        assertEquals("Lettre de motivation – [Entreprise]", letter.name)
    }

    @Test
    fun `le cv met les dates en valeur et liste les competences`() {
        val cv = text(
            "cv",
            mapOf(
                "nom" to "Léa Martin",
                "experiences" to "2022-2024 – Vendeuse – Boulangerie Martin\n\nStage chez Orange",
                "competences" to "Excel\nAccueil client"
            )
        )
        val paragraphs = cv.document.paragraphs
        val dated = paragraphs.first { it.plainText.startsWith("2022-2024") }
        assertTrue(dated.runs[0].bold)
        assertEquals("Vendeuse – Boulangerie Martin", dated.runs[1].text)
        assertTrue(paragraphs.any { it.plainText == "Stage chez Orange" })
        assertTrue(paragraphs.any { it.plainText == "•  Excel" })
        assertTrue(paragraphs.any { it.heading == 1 && it.plainText == "Compétences" })
        // Le document s'exporte en Word et se relit.
        val back = Docx.read(FileFormats.exportText(FileFormat.DOCX, cv.document))
        assertTrue(back.plainText.contains("Boulangerie Martin"))
    }

    @Test
    fun `la facture calcule lignes, tva et total ttc`() {
        val invoice = sheet("facture", mapOf("client" to "SARL Dupont", "numero" to "F-042"))
        assertEquals("Facture F-042 SARL Dupont", invoice.name)
        val first = Templates.FIRST_LINE
        // La ligne d'exemple : 1 × 100.
        assertEquals("100,00", invoice.show("D$first"))
        assertEquals("120,00 €", invoice.show(Templates.totalRef("TTC")))

        val lines = mapOf(
            "B${first + 1}" to "3", "C${first + 1}" to "19,90",
            "B${first + 2}" to "2,5", "C${first + 2}" to "1200"
        )
        assertEquals("59,70", invoice.show("D${first + 1}", lines))
        assertEquals("3 000,00", invoice.show("D${first + 2}", lines))
        assertEquals("", invoice.show("D${first + 3}", lines))
        assertEquals("3 159,70 €", invoice.show(Templates.totalRef("HT"), lines))
        assertEquals("631,94 €", invoice.show(Templates.totalRef("TVA"), lines))
        assertEquals("3 791,64 €", invoice.show(Templates.totalRef("TTC"), lines))

        val plain = invoice.workbook.sheets[0].cells.values.joinToString("\n")
        assertTrue(plain.contains("Échéance : 24/10/2026"))
        assertTrue(plain.contains("40 €"))
        assertFalse(plain.contains("293 B"))
    }

    @Test
    fun `sans tva la mention legale apparait et le ttc egale le ht`() {
        val invoice = sheet("facture", mapOf("tva" to "0"))
        assertEquals("100,00 €", invoice.show(Templates.totalRef("TTC")))
        assertTrue(invoice.workbook.sheets[0].cells.values.any { it.contains("art. 293 B du CGI") })
    }

    @Test
    fun `le devis a sa validite et le bon pour accord`() {
        val quote = sheet("devis")
        val cells = quote.workbook.sheets[0].cells.values
        assertTrue(cells.contains("DEVIS"))
        assertTrue(cells.contains("Valable jusqu'au 24/10/2026"))
        assertTrue(cells.any { it.startsWith("Bon pour accord") })
        assertEquals("120,00 €", quote.show(Templates.totalRef("TTC")))
    }

    @Test
    fun `le budget calcule totaux, ecarts et reste a vivre`() {
        val budget = sheet("budget")
        assertEquals("Budget Septembre 2026", budget.name)
        val cells = budget.workbook.sheets[0].cells
        fun row(label: String) = cells.entries.first { it.value == label }.key.drop(1).toInt()
        val input = mapOf(
            "B${row("Salaire")}" to "1800", "C${row("Salaire")}" to "1800",
            "C${row("Aides (CAF, APL…)")}" to "150",
            "B${row("Loyer ou crédit")}" to "650", "C${row("Loyer ou crédit")}" to "650",
            "B${row("Courses")}" to "300", "C${row("Courses")}" to "342,50"
        )
        val rest = row("Reste à vivre")
        assertEquals("1 800,00 €", budget.show("B${row("Total revenus")}", input))
        assertEquals("1 950,00 €", budget.show("C${row("Total revenus")}", input))
        assertEquals("992,50 €", budget.show("C${row("Total dépenses")}", input))
        assertEquals("42.5", budget.show("D${row("Courses")}", input))
        assertEquals("", budget.show("D${row("Santé")}", input))
        assertEquals("850,00 €", budget.show("B$rest", input))
        assertEquals("957,50 €", budget.show("C$rest", input))
        assertEquals("51 %", budget.show("C${rest + 1}", input))
    }

    @Test
    fun `le compte rendu reprend l ordre du jour et le tableau des actions`() {
        val cr = text("compte-rendu", mapOf("objet" to "Lancement du site", "ordre" to "Budget\nPlanning"))
        val headings = cr.document.paragraphs.filter { it.heading == 2 }.map { it.plainText }
        assertEquals(listOf("1. Budget", "2. Planning"), headings)
        val tables = cr.document.blocks.filterIsInstance<TextTable>()
        assertEquals(listOf("Date", "Lieu", "Rédigé par"), tables[0].rows[0].cells.map { it.plainText })
        assertEquals("24 septembre 2026", tables[0].rows[1].cells[0].plainText)
        assertEquals(5, tables[1].rows.size)
        assertEquals("CR Lancement du site 24/09/2026", cr.name)
    }

    @Test
    fun `la facture s exporte en excel avec ses formules`() {
        val invoice = sheet("facture")
        val back = Xlsx.read(FileFormats.exportSheet(FileFormat.XLSX, invoice.workbook) { s, ref -> FormulaEngine.displayValue(ref, s.cells) })
        val cells = back.sheets[0].cells
        assertTrue(cells.values.any { it.contains("SOMMEPROD") || it.contains("SUMPRODUCT") })
    }
}
