package com.docssuite.mailmerge

import com.docssuite.fileformats.Sheet
import com.docssuite.fileformats.TableCell
import com.docssuite.fileformats.TableRow
import com.docssuite.fileformats.TextDocument
import com.docssuite.fileformats.TextParagraph
import com.docssuite.fileformats.TextRun
import com.docssuite.fileformats.TextTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class MailMergeTest {

    private val today = Calendar.getInstance().apply { set(2026, Calendar.SEPTEMBER, 25, 9, 0) }.time

    private fun paragraph(vararg runs: TextRun) = TextParagraph(runs.toList())

    private fun model(vararg lines: String) = TextDocument(
        "Invitation",
        lines.map { paragraph(TextRun(it)) }
    )

    private fun sheet(vararg rows: List<String>): Sheet {
        val cells = HashMap<String, String>()
        rows.forEachIndexed { r, row ->
            row.forEachIndexed { c, value ->
                if (value.isNotEmpty()) cells["${('A' + c)}${r + 1}"] = value
            }
        }
        return Sheet("Destinataires", cells)
    }

    private val guests = sheet(
        listOf("Nom", "Prénom", "Ville"),
        listOf("Dupont", "Léa", "Lyon"),
        listOf("Martin", "Paul", "Brest")
    )

    // ------------------------------------------------------------ les données

    @Test
    fun `la premiere ligne donne les colonnes, les suivantes les destinataires`() {
        val recipients = MergeData.read(guests)
        assertEquals(listOf("Nom", "Prénom", "Ville"), recipients.headers)
        assertEquals(2, recipients.records.size)
        assertEquals("Léa", recipients.records[0].value("Prénom"))
        assertEquals("Brest", recipients.records[1].value("Ville"))
    }

    @Test
    fun `un champ retrouve sa colonne sans accent ni majuscule`() {
        val record = MergeData.read(guests).records[0]
        assertEquals("Léa", record.value("prenom"))
        assertEquals("Léa", record.value("PRÉNOM"))
        assertEquals("Léa", record.value("  Prénom  "))
    }

    @Test
    fun `une colonne absente et une case vide ne sont pas la meme chose`() {
        val withHole = sheet(
            listOf("Nom", "Ville"),
            listOf("Dupont", "")
        )
        val record = MergeData.read(withHole).records[0]
        assertEquals("", record.value("Ville"))
        assertTrue(record.has("Ville"))
        assertEquals(null, record.value("Téléphone"))
        assertFalse(record.has("Téléphone"))
    }

    @Test
    fun `les lignes vides ne sont pas des destinataires`() {
        val holes = sheet(
            listOf("Nom"),
            listOf("Dupont"),
            listOf(""),
            listOf("Martin")
        )
        val recipients = MergeData.read(holes)
        assertEquals(2, recipients.records.size)
        assertEquals("Martin", recipients.records[1].value("Nom"))
    }

    @Test
    fun `une colonne calculee arrive a sa valeur, pas a sa formule`() {
        val invoices = sheet(
            listOf("Client", "Quantité", "Prix", "Total"),
            listOf("Dupont", "3", "12", "=B2*C2")
        )
        assertEquals("36", MergeData.read(invoices).records[0].value("Total"))
    }

    @Test
    fun `un tableau qui ne commence pas en A1 se lit quand meme`() {
        val moved = Sheet("Feuille1", mapOf("C3" to "Nom", "D3" to "Ville", "C4" to "Dupont", "D4" to "Lyon"))
        val recipients = MergeData.read(moved)
        assertEquals(listOf("Nom", "Ville"), recipients.headers)
        assertEquals("Lyon", recipients.records[0].value("Ville"))
    }

    @Test
    fun `une colonne sans titre est ignoree`() {
        val recipients = MergeData.read(sheet(listOf("Nom", "", "Ville"), listOf("Dupont", "x", "Lyon")))
        assertEquals(listOf("Nom", "Ville"), recipients.headers)
    }

    // ------------------------------------------------------------ les champs

    @Test
    fun `les champs sont listes dans l'ordre, sans doublon`() {
        val document = model("Bonjour {{Prénom}} {{Nom}},", "À bientôt à {{Ville}}, {{Prénom}}.")
        assertEquals(listOf("Prénom", "Nom", "Ville"), MergeFields.of(document))
    }

    @Test
    fun `les champs des tableaux comptent aussi`() {
        val table = TextTable(
            listOf(
                TableRow(listOf(cell("Article"), cell("Prix"))),
                TableRow(listOf(cell("{{Article}}"), cell("{{Prix}}")))
            )
        )
        val document = TextDocument("Facture {{Numéro}}", listOf(table))
        assertEquals(listOf("Article", "Prix", "Numéro"), MergeFields.of(document))
    }

    @Test
    fun `les espaces dans les accolades sont tolerees`() {
        assertEquals(listOf("Nom"), MergeFields.of(model("Cher {{ Nom }},")))
    }

    // ------------------------------------------------------------ le plan

    @Test
    fun `le plan signale les champs sans colonne et les colonnes sans champ`() {
        val plan = MergePlan(listOf("Nom", "Téléphone"), listOf("Nom", "Ville"))
        assertEquals(listOf("Téléphone"), plan.unknown)
        assertEquals(listOf("Ville"), plan.unused)
        assertFalse(plan.ready)
    }

    @Test
    fun `la date n'a pas besoin d'une colonne`() {
        val plan = MergePlan(listOf("Nom", "date"), listOf("Nom"))
        assertTrue(plan.unknown.isEmpty())
        assertTrue(plan.ready)
    }

    // ------------------------------------------------------------ le courrier

    @Test
    fun `chaque destinataire recoit son courrier`() {
        val document = model("Bonjour {{Prénom}} {{Nom}},", "Rendez-vous à {{Ville}}.")
        val letters = MailMerge.run(document, MergeData.read(guests), MergeOptions(nameField = "Nom", today = today))
        assertEquals(2, letters.size)
        assertEquals("Invitation — Dupont", letters[0].name)
        assertEquals("Bonjour Léa Dupont,\nRendez-vous à Lyon.", letters[0].document.plainText)
        assertEquals("Bonjour Paul Martin,\nRendez-vous à Brest.", letters[1].document.plainText)
    }

    @Test
    fun `sans colonne de nom les courriers sont numerotes`() {
        val letters = MailMerge.run(model("Bonjour {{Nom}}"), MergeData.read(guests))
        assertEquals("Invitation — 1", letters[0].name)
        assertEquals("Invitation — 2", letters[1].name)
    }

    @Test
    fun `la mise en forme du champ est conservee`() {
        val document = TextDocument(
            "Invitation",
            listOf(paragraph(TextRun("Bonjour "), TextRun("{{Nom}}", bold = true), TextRun(", merci.")))
        )
        val runs = single(document).runs
        assertEquals(listOf("Bonjour ", "Dupont", ", merci."), runs.map { it.text })
        assertTrue(runs[1].bold)
        assertFalse(runs[0].bold)
    }

    @Test
    fun `un champ coupe en deux morceaux par l'editeur est reconnu`() {
        // Ce que devient « {{Nom}} » quand on repasse le curseur dessus :
        // les accolades se retrouvent dans deux fragments distincts.
        val document = TextDocument(
            "Invitation",
            listOf(paragraph(TextRun("Cher {{No"), TextRun("m}}, bonjour.")))
        )
        assertEquals("Cher Dupont, bonjour.", single(document).plainText)
    }

    @Test
    fun `un champ à cheval prend la mise en forme de son début`() {
        val document = TextDocument(
            "Invitation",
            listOf(paragraph(TextRun("{{No", italic = true), TextRun("m}}"), TextRun(" !")))
        )
        val runs = single(document).runs
        assertEquals("Dupont !", runs.joinToString("") { it.text })
        assertTrue(runs[0].italic)
    }

    @Test
    fun `une valeur sur plusieurs lignes coupe le paragraphe`() {
        val document = model("{{Adresse}}", "Fin.")
        val record = Record(mapOf("adresse" to "12 rue des Lilas\n69000 Lyon"))
        val letter = MailMerge.letter(document, record)
        assertEquals(
            listOf("12 rue des Lilas", "69000 Lyon", "Fin."),
            letter.document.paragraphs.map { it.plainText }
        )
    }

    @Test
    fun `une ligne qui n'avait qu'un champ vide disparait`() {
        val document = model("Dupont", "{{Complément}}", "69000 Lyon")
        val record = Record(mapOf("complement" to ""))
        val letter = MailMerge.letter(document, record)
        assertEquals(listOf("Dupont", "69000 Lyon"), letter.document.paragraphs.map { it.plainText })
    }

    @Test
    fun `une ligne vide du modele reste une ligne vide`() {
        val document = model("Dupont", "", "69000 Lyon")
        val letter = MailMerge.letter(document, Record(emptyMap()))
        assertEquals(listOf("Dupont", "", "69000 Lyon"), letter.document.paragraphs.map { it.plainText })
    }

    @Test
    fun `on peut demander de garder les lignes vidées`() {
        val document = model("Dupont", "{{Complément}}", "69000 Lyon")
        val record = Record(mapOf("complement" to ""))
        val letter = MailMerge.letter(document, record, MergeOptions(dropEmptied = false))
        assertEquals(listOf("Dupont", "", "69000 Lyon"), letter.document.paragraphs.map { it.plainText })
    }

    @Test
    fun `un champ que les donnees ignorent reste visible`() {
        val letter = MailMerge.letter(model("Tél : {{Téléphone}}"), MergeData.read(guests).records[0])
        assertEquals("Tél : {{Téléphone}}", letter.document.plainText)
    }

    @Test
    fun `la date du jour se remplit sans colonne`() {
        val letter = MailMerge.letter(model("Lyon, le {{date}}"), Record(emptyMap()), MergeOptions(today = today))
        assertEquals("Lyon, le 25 septembre 2026", letter.document.plainText)
    }

    @Test
    fun `une colonne date l'emporte sur la date du jour`() {
        val record = Record(mapOf("date" to "1er avril"))
        val letter = MailMerge.letter(model("Le {{date}}"), record, MergeOptions(today = today))
        assertEquals("Le 1er avril", letter.document.plainText)
    }

    @Test
    fun `le titre du document est personnalise aussi`() {
        val document = TextDocument("Facture {{Nom}}", listOf(paragraph(TextRun("Merci."))))
        val letter = MailMerge.letter(document, MergeData.read(guests).records[0])
        assertEquals("Facture Dupont", letter.document.title)
    }

    @Test
    fun `les champs d'un tableau sont remplaces et la grille garde sa forme`() {
        val table = TextTable(
            listOf(
                TableRow(listOf(cell("Client"), cell("Ville"))),
                TableRow(listOf(cell("{{Nom}}"), cell("{{Ville}}")))
            ),
            headerRow = true
        )
        val document = TextDocument("Liste", listOf(table))
        val letter = MailMerge.letter(document, MergeData.read(guests).records[0])
        val result = letter.document.blocks[0] as TextTable
        assertEquals(2, result.rows.size)
        assertEquals(listOf("Dupont", "Lyon"), result.rows[1].cells.map { it.plainText })
        assertTrue(result.headerRow)
    }

    @Test
    fun `une cellule dont le champ est vide reste une cellule`() {
        val table = TextTable(listOf(TableRow(listOf(cell("{{Vide}}"), cell("fin")))))
        val document = TextDocument("Liste", listOf(table))
        val letter = MailMerge.letter(document, Record(mapOf("vide" to "")))
        val result = letter.document.blocks[0] as TextTable
        assertEquals(2, result.rows[0].cells.size)
        assertEquals("", result.rows[0].cells[0].plainText)
    }

    @Test
    fun `tout dans un seul document garde chaque courrier, separes`() {
        val letters = MailMerge.run(model("Bonjour {{Nom}}."), MergeData.read(guests))
        val all = MailMerge.combine(letters, "Invitations")
        assertEquals("Invitations", all.title)
        val text = all.plainText
        assertTrue(text, text.contains("Bonjour Dupont."))
        assertTrue(text, text.contains("Bonjour Martin."))
        assertTrue(text, text.contains("· · ·"))
    }

    @Test
    fun `un nom de destinataire ne casse pas le nom du document`() {
        val record = Record(mapOf("nom" to "Dupont / Martin\nSARL"))
        val letter = MailMerge.letter(model("x"), record, MergeOptions(nameField = "Nom"))
        assertEquals("Invitation — Dupont - Martin SARL", letter.name)
    }

    @Test
    fun `un modele sans champ produit le meme texte pour tout le monde`() {
        val letters = MailMerge.run(model("Avis à tous."), MergeData.read(guests))
        assertEquals(2, letters.size)
        assertTrue(letters.all { it.document.plainText == "Avis à tous." })
    }

    private fun cell(text: String) = TableCell(listOf(TextParagraph(listOf(TextRun(text)))))

    private fun single(document: TextDocument): TextParagraph =
        MailMerge.letter(document, MergeData.read(guests).records[0]).document.paragraphs[0]
}
