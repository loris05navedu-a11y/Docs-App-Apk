package com.docssuite.texteditor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.docssuite.fileformats.Docx
import com.docssuite.fileformats.FileFormats
import com.docssuite.fileformats.Imported
import com.docssuite.fileformats.Odf
import com.docssuite.fileformats.Rtf
import com.docssuite.fileformats.TextTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class EditorModelTest {

    private fun field(text: String, caret: Int = text.length, end: Int = caret) =
        RichField.of(text).copy(value = TextFieldValue(text, TextRange(caret, end)))

    /** Simule une frappe : le texte change, les styles et paragraphes suivent. */
    private fun type(before: RichField, newText: String, caret: Int): RichField {
        val after = RichField(
            value = TextFieldValue(newText, TextRange(caret)),
            styles = adjustStyles(before.text, newText, before.styles, CharStyle()),
            paras = EditorModel.adjustParas(before.text, newText, before.paras)
        ).normalized()
        return EditorModel.continueList(before, after, CharStyle()) ?: after
    }

    // ------------------------------------------------------------ paragraphes

    @Test
    fun `un paragraphe garde son alignement quand on ecrit avant lui`() {
        var f = field("Titre\nCorps", caret = 0)
        f = EditorModel.setAlign(f, 1)
        assertEquals(listOf(1, 0), f.paras.map { it.align })
        val typed = type(f, "Le Titre\nCorps", 3)
        assertEquals(listOf(1, 0), typed.paras.map { it.align })
    }

    @Test
    fun `entree cree un paragraphe et retour arriere les refusionne`() {
        val f = EditorModel.setAlign(field("AB", caret = 1), 2)
        val split = type(f, "A\nB", 2)
        assertEquals(2, split.paras.size)
        assertEquals(2, split.paras[1].align)
        val joined = type(split, "AB", 1)
        assertEquals(1, joined.paras.size)
    }

    @Test
    fun `un titre ne se prolonge pas au paragraphe suivant`() {
        val f = EditorModel.setHeading(field("Chapitre"), 1)
        assertEquals(1, f.paras[0].heading)
        assertTrue(f.styles.all { it.bold && it.size == EditorModel.headingSize(1) })
        val next = type(f, "Chapitre\n", 9)
        assertEquals(listOf(1, 0), next.paras.map { it.heading })
    }

    @Test
    fun `le retrait se borne entre zero et huit`() {
        var f = field("x")
        repeat(12) { f = EditorModel.changeIndent(f, 1) }
        assertEquals(8, f.paras[0].indent)
        repeat(12) { f = EditorModel.changeIndent(f, -1) }
        assertEquals(0, f.paras[0].indent)
    }

    // ------------------------------------------------------------ listes

    @Test
    fun `les puces s ajoutent puis se retirent sur tous les paragraphes choisis`() {
        val f = field("un\ndeux\ntrois", caret = 0, end = 13)
        val bulleted = EditorModel.toggleBullets(f)
        assertEquals("• un\n• deux\n• trois", bulleted.text)
        assertEquals(bulleted.text.length, bulleted.styles.size)
        val plain = EditorModel.toggleBullets(bulleted.copy(value = bulleted.value.copy(selection = TextRange(0, bulleted.text.length))))
        assertEquals("un\ndeux\ntrois", plain.text)
    }

    @Test
    fun `la numerotation remplace les puces`() {
        val f = field("• un\n• deux", caret = 0, end = 11)
        assertEquals("1. un\n2. deux", EditorModel.toggleNumbering(f).text)
    }

    @Test
    fun `entree dans une liste continue la puce ou le numero`() {
        val bullets = field("• courses")
        assertEquals("• courses\n• ", type(bullets, "• courses\n", 10).text)
        val numbers = field("3. étape")
        assertEquals("3. étape\n4. ", type(numbers, "3. étape\n", 9).text)
    }

    @Test
    fun `entree sur un element vide termine la liste`() {
        val f = field("• a\n• ")
        val result = type(f, "• a\n• \n", 7)
        assertEquals("• a\n", result.text)
        assertEquals(result.text.length, result.value.selection.start)
    }

    @Test
    fun `entree hors liste ne touche a rien`() {
        val f = field("texte")
        assertNull(EditorModel.continueList(f, RichField.of("texte\n").copy(value = TextFieldValue("texte\n", TextRange(6))), CharStyle()))
    }

    @Test
    fun `changer la casse garde le style de chaque caractere`() {
        val f = field("bonjour", caret = 0, end = 7).let {
            it.copy(styles = it.styles.mapIndexed { i, s -> if (i == 0) s.copy(bold = true) else s })
        }
        val upper = EditorModel.changeCase(f, 0)
        assertEquals("BONJOUR", upper.text)
        assertTrue(upper.styles[0].bold)
        assertFalse(upper.styles[1].bold)
        assertEquals("Bonjour Le Monde", EditorModel.changeCase(field("bonjour le monde", 0, 16), 2).text)
    }

    // ------------------------------------------------------------ tableaux

    @Test
    fun `inserer un tableau coupe le texte au curseur`() {
        val doc = EditorDoc(listOf(EditorBlock.Text(newBlockId(), field("Avant\nAprès", caret = 6))))
        val (result, target) = EditorModel.insertTable(doc, Target(0), EditorModel.newTable(2, 3, header = true))
        assertEquals(3, result.blocks.size)
        assertEquals("Avant", (result.blocks[0] as EditorBlock.Text).field.text)
        assertEquals("Après", (result.blocks[2] as EditorBlock.Text).field.text)
        val table = (result.blocks[1] as EditorBlock.Table).table
        assertEquals(3, table.columnCount)
        assertEquals(Target(1, 0, 0), target)

        val removed = EditorModel.deleteTable(result, 1)
        assertEquals(1, removed.blocks.size)
        assertEquals("Avant\nAprès", (removed.blocks[0] as EditorBlock.Text).field.text)
    }

    @Test
    fun `un tableau en fin de document est suivi d un paragraphe`() {
        val doc = EditorDoc(listOf(EditorBlock.Text(newBlockId(), field("Fin"))))
        val (result, _) = EditorModel.insertTable(doc, Target(0), EditorModel.newTable(1, 1, header = false))
        assertTrue(result.blocks.last() is EditorBlock.Text)
    }

    private fun grid(vararg rows: List<Pair<String, Int>>) = TableData(
        rows.map { row -> row.map { (text, span) -> CellField(RichField.of(text), colSpan = span) } }
    )

    private fun widths(table: TableData) = table.rows.map { row -> row.sumOf { it.colSpan } }

    @Test
    fun `inserer et supprimer des colonnes respecte les cellules fusionnees`() {
        val table = grid(listOf("A" to 1, "B" to 1, "C" to 1), listOf("Total" to 2, "9" to 1))
        val wider = EditorModel.insertColumn(table, row = 0, cell = 0, after = true)
        assertEquals(listOf(4, 4), widths(wider))
        // La nouvelle colonne passe au milieu de « Total », qui s'élargit.
        assertEquals(3, wider.rows[1][0].colSpan)

        val narrower = EditorModel.deleteColumn(table, row = 0, cell = 1)!!
        assertEquals(listOf(2, 2), widths(narrower))
        assertEquals(1, narrower.rows[1][0].colSpan)
    }

    @Test
    fun `la derniere colonne ou ligne ne se supprime pas en laissant un tableau vide`() {
        val single = grid(listOf("seul" to 1))
        assertNull(EditorModel.deleteColumn(single, 0, 0))
        assertNull(EditorModel.deleteRow(single, 0))
    }

    @Test
    fun `fusionner puis scinder rend la meme largeur`() {
        val table = grid(listOf("a" to 1, "b" to 1, "c" to 1))
        val merged = EditorModel.mergeRight(table, 0, 0)
        assertEquals(2, merged.rows[0].size)
        assertEquals(2, merged.rows[0][0].colSpan)
        assertEquals("a\nb", merged.rows[0][0].field.text)
        val split = EditorModel.splitCell(merged, 0, 0)
        assertEquals(listOf(3), widths(split))
    }

    @Test
    fun `supprimer la ligne d origine d une fusion verticale la reporte en dessous`() {
        val table = TableData(
            listOf(
                listOf(CellField(RichField.of("Équipe")), CellField(RichField.of("Alice"))),
                listOf(CellField(mergedAbove = true), CellField(RichField.of("Bruno")))
            )
        )
        val result = EditorModel.deleteRow(table, 0)!!
        assertFalse(result.rows[0][0].mergedAbove)
        assertEquals("Équipe", result.rows[0][0].field.text)
    }

    @Test
    fun `une ligne inseree reprend la forme de sa voisine`() {
        val table = grid(listOf("Total" to 2, "9" to 1))
        val result = EditorModel.insertRow(table, 0, below = true)
        assertEquals(listOf(2, 1), result.rows[1].map { it.colSpan })
    }

    // ------------------------------------------------------------ fichiers

    private fun corpus(name: String) = File("../fileformats/src/test/resources/corpus/$name").readBytes()

    @Test
    fun `un docx a tableaux s ouvre dans l editeur et en ressort a l identique`() {
        val imported = (FileFormats.import("rapport.docx", corpus("rapport-word.docx")) as Imported.AsText).document
        val doc = EditorIO.fromTextDocument(imported)
        val tables = doc.blocks.filterIsInstance<EditorBlock.Table>()
        assertEquals(2, tables.size)
        assertEquals(2, tables[0].table.rows[3][0].colSpan)
        assertTrue(tables[1].table.rows[1][0].mergedAbove)
        // Les titres sont reconnus pour le plan.
        assertTrue(outlineOf(doc).any { it.text == "Rapport trimestriel" && it.level == 1 })

        val back = Docx.read(Docx.write(EditorIO.toTextDocument(doc, "Rapport")))
        val backTables = back.blocks.filterIsInstance<TextTable>()
        assertEquals(2, backTables.size)
        assertEquals(2, backTables[0].rows[3].cells[0].colSpan)
        assertTrue(backTables[1].rows[1].cells[0].mergedAbove)
        assertEquals(1, back.blocks.filterIsInstance<com.docssuite.fileformats.TextParagraph>().first().heading)
    }

    @Test
    fun `l enregistrement interne conserve tableaux, titres et interligne`() {
        val imported = (FileFormats.import("rapport.docx", corpus("rapport-word.docx")) as Imported.AsText).document
        val doc = EditorIO.fromTextDocument(imported).copy(lineSpacing = 150)
        val reloaded = EditorIO.fromJson(EditorIO.toJson(doc))
        assertEquals(150, reloaded.lineSpacing)
        assertEquals(doc.blocks.size, reloaded.blocks.size)
        assertEquals(
            (doc.blocks[1] as EditorBlock.Table).table.rows.map { r -> r.map { it.field.text to it.colSpan } },
            (reloaded.blocks[1] as EditorBlock.Table).table.rows.map { r -> r.map { it.field.text to it.colSpan } }
        )
        assertEquals(EditorModel.allText(doc), EditorModel.allText(reloaded))
    }

    @Test
    fun `un document enregistre avant les tableaux s ouvre toujours`() {
        val legacy = stylesToJson("Ancien\ndocument", List(15) { CharStyle(bold = true) })
        val doc = EditorIO.fromJson(legacy)
        assertEquals(1, doc.blocks.size)
        assertEquals("Ancien\ndocument", (doc.blocks[0] as EditorBlock.Text).field.text)
        assertEquals(2, (doc.blocks[0] as EditorBlock.Text).field.paras.size)
    }

    @Test
    fun `exposant et indice passent par Word, ODF et RTF`() {
        val f = field("x2").let { it.copy(styles = listOf(CharStyle(), CharStyle(baseline = 1))) }
        val doc = EditorDoc(listOf(EditorBlock.Text(newBlockId(), f)))
        val document = EditorIO.toTextDocument(doc, "Maths")
        listOf(
            Docx.read(Docx.write(document)),
            Odf.readText(Odf.writeText(document)),
            Rtf.read(Rtf.write(document))
        ).forEach { back ->
            val runs = back.paragraphs.first().runs
            assertEquals(1, runs.first { it.text == "2" }.baseline)
        }
    }
}
