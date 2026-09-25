package com.docssuite.compare

import com.docssuite.fileformats.TextDocument
import com.docssuite.fileformats.TextParagraph
import com.docssuite.fileformats.TextRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompareTest {

    // ------------------------------------------------------------ les suites

    private fun diff(before: String, after: String): String =
        Diff.of(before.toList(), after.toList()).joinToString("") { edit ->
            when (edit.change) {
                Change.KEPT -> edit.value.toString()
                Change.ADDED -> "+${edit.value}"
                Change.REMOVED -> "-${edit.value}"
            }
        }

    @Test
    fun `deux suites identiques ne changent pas`() {
        assertEquals("abc", diff("abc", "abc"))
    }

    @Test
    fun `une insertion au milieu`() {
        assertEquals("a+xbc", diff("abc", "axbc"))
    }

    @Test
    fun `une suppression au milieu`() {
        assertEquals("a-xbc", diff("axbc", "abc"))
    }

    @Test
    fun `l'ancien passe avant le nouveau`() {
        assertEquals("-x+y", diff("x", "y"))
    }

    @Test
    fun `une suite vide face a une autre`() {
        assertEquals("+a+b", diff("", "ab"))
        assertEquals("-a-b", diff("ab", ""))
        assertEquals("", diff("", ""))
    }

    @Test
    fun `le plus petit jeu de changements est retenu`() {
        // « abcabba » → « cbabac » : la sous-suite commune la plus longue fait
        // quatre caractères, donc trois suppressions et deux ajouts.
        val edits = Diff.of("abcabba".toList(), "cbabac".toList())
        assertEquals(4, edits.count { it.change == Change.KEPT })
        assertEquals(3, edits.count { it.change == Change.REMOVED })
        assertEquals(2, edits.count { it.change == Change.ADDED })
    }

    @Test
    fun `les ordres sont respectes`() {
        val edits = Diff.of(listOf("a", "b", "c"), listOf("a", "c"))
        assertEquals(listOf("a", "b", "c"), edits.filter { it.change != Change.ADDED }.map { it.value })
        assertEquals(listOf("a", "c"), edits.filter { it.change != Change.REMOVED }.map { it.value })
    }

    @Test
    fun `deux textes sans rien en commun restent traitables`() {
        // Au-delà de la table de travail, la réponse est « tout remplacé » —
        // et elle arrive tout de suite.
        val before = (1..1500).map { "ligne a $it" }
        val after = (1..1500).map { "ligne b $it" }
        val edits = Diff.of(before, after)
        assertEquals(1500, edits.count { it.change == Change.REMOVED })
        assertEquals(1500, edits.count { it.change == Change.ADDED })
        assertEquals(0, edits.count { it.change == Change.KEPT })
    }

    @Test
    fun `un long texte dont une ligne change se compare vite`() {
        val before = (1..5000).map { "paragraphe $it" }
        val after = before.toMutableList().apply { this[2500] = "paragraphe retouché" }
        val edits = Diff.of(before, after)
        assertEquals(4999, edits.count { it.change == Change.KEPT })
        assertEquals(1, edits.count { it.change == Change.REMOVED })
        assertEquals(1, edits.count { it.change == Change.ADDED })
    }

    // ------------------------------------------------------------ les versions

    private fun compare(before: List<String>, after: List<String>) = CompareDocuments.of(before, after)

    @Test
    fun `deux versions identiques le disent`() {
        val comparison = compare(listOf("Bonjour.", "Merci."), listOf("Bonjour.", "Merci."))
        assertTrue(comparison.identical)
        assertEquals(2, comparison.same)
        assertEquals("Les deux versions sont identiques.", comparison.summary)
    }

    @Test
    fun `un paragraphe ajoute est repere`() {
        val comparison = compare(listOf("Un.", "Trois."), listOf("Un.", "Deux.", "Trois."))
        assertEquals(1, comparison.added)
        assertEquals(0, comparison.removed)
        assertEquals(Kind.ADDED, comparison.rows[1].kind)
        assertEquals("Deux.", comparison.rows[1].after)
    }

    @Test
    fun `un paragraphe supprime est repere`() {
        val comparison = compare(listOf("Un.", "Deux.", "Trois."), listOf("Un.", "Trois."))
        assertEquals(1, comparison.removed)
        assertEquals(0, comparison.added)
        assertEquals(Kind.REMOVED, comparison.rows[1].kind)
        assertEquals("Deux.", comparison.rows[1].before)
    }

    @Test
    fun `un paragraphe retouche montre les mots qui bougent`() {
        val comparison = compare(
            listOf("Le loyer est de 500 euros par mois."),
            listOf("Le loyer est de 550 euros par mois.")
        )
        assertEquals(1, comparison.changed)
        val row = comparison.rows[0]
        assertEquals(Kind.CHANGED, row.kind)
        assertEquals(listOf("500"), row.words.filter { it.change == Change.REMOVED }.map { it.value })
        assertEquals(listOf("550"), row.words.filter { it.change == Change.ADDED }.map { it.value })
        assertEquals(7, row.words.count { it.change == Change.KEPT })
    }

    @Test
    fun `deux paragraphes qui n'ont rien a voir ne sont pas une retouche`() {
        val comparison = compare(
            listOf("Le chat dort sur le canapé."),
            listOf("Facture numéro 4297 du mois de mars.")
        )
        assertEquals(0, comparison.changed)
        assertEquals(1, comparison.removed)
        assertEquals(1, comparison.added)
        // L'ancien d'abord, le nouveau ensuite.
        assertEquals(Kind.REMOVED, comparison.rows[0].kind)
        assertEquals(Kind.ADDED, comparison.rows[1].kind)
    }

    @Test
    fun `plusieurs paragraphes retouches se repondent deux a deux`() {
        val comparison = compare(
            listOf("Article 1 : le prix est de 100 euros.", "Article 2 : le délai est de 10 jours."),
            listOf("Article 1 : le prix est de 120 euros.", "Article 2 : le délai est de 15 jours.")
        )
        assertEquals(2, comparison.changed)
        assertEquals(0, comparison.added)
        assertEquals(0, comparison.removed)
    }

    @Test
    fun `un paragraphe retouche et un ajoute se distinguent`() {
        val comparison = compare(
            listOf("Le prix est de 100 euros."),
            listOf("Le prix est de 120 euros.", "Paiement à trente jours.")
        )
        assertEquals(1, comparison.changed)
        assertEquals(1, comparison.added)
    }

    @Test
    fun `les espaces et les lignes vides ne comptent pas pour un changement`() {
        val comparison = compare(listOf("Bonjour.", "", "  Merci.  "), listOf("Bonjour.", "Merci."))
        assertTrue(comparison.summary, comparison.identical)
    }

    @Test
    fun `le resume compte au singulier et au pluriel`() {
        assertEquals(
            "1 ajout.",
            compare(listOf("Un."), listOf("Un.", "Deux.")).summary
        )
        assertEquals(
            "2 ajouts.",
            compare(listOf("Un."), listOf("Un.", "Deux.", "Trois.")).summary
        )
    }

    @Test
    fun `un resume melange nomme chaque sorte de changement`() {
        val comparison = compare(
            listOf("Garde.", "Pars.", "Le prix est de 10 euros."),
            listOf("Garde.", "Arrive ici maintenant.", "Le prix est de 12 euros.", "Nouveau.")
        )
        val summary = comparison.summary
        assertTrue(summary, summary.contains("ajout"))
        assertTrue(summary, summary.contains("suppression"))
        assertTrue(summary, summary.contains("retouché"))
    }

    @Test
    fun `comparer deux documents passe par leurs paragraphes`() {
        val before = document("Bonjour.", "Le prix est de 100 euros.")
        val after = document("Bonjour.", "Le prix est de 200 euros.")
        val comparison = CompareDocuments.of(before, after)
        assertEquals(1, comparison.same)
        assertEquals(1, comparison.changed)
    }

    // ------------------------------------------------------------ le rapport

    @Test
    fun `le rapport nomme les deux versions et resume`() {
        val comparison = compare(listOf("Un."), listOf("Un.", "Deux."))
        val report = CompareDocuments.report(comparison, "contrat", "contrat v2")
        assertEquals("Comparaison", report.title)
        val text = report.plainText
        assertTrue(text, text.contains("Avant : contrat"))
        assertTrue(text, text.contains("Après : contrat v2"))
        assertTrue(text, text.contains("1 ajout."))
        assertTrue(text, text.contains("Deux."))
    }

    @Test
    fun `le rapport laisse de cote les paragraphes inchanges, sauf si on les demande`() {
        val comparison = compare(listOf("Garde.", "Un."), listOf("Garde.", "Deux."))
        assertFalse(CompareDocuments.report(comparison, "a", "b").plainText.contains("Garde."))
        assertTrue(
            CompareDocuments.report(comparison, "a", "b", includeUnchanged = true)
                .plainText.contains("Garde.")
        )
    }

    @Test
    fun `dans le rapport l'ajout est surligne et la suppression barree`() {
        val comparison = compare(
            listOf("Le prix est de 100 euros."),
            listOf("Le prix est de 120 euros.")
        )
        val paragraph = CompareDocuments.report(comparison, "a", "b").paragraphs.last()
        val gone = paragraph.runs.single { it.text.trim() == "100" }
        val arrived = paragraph.runs.single { it.text.trim() == "120" }
        assertTrue(gone.strike)
        assertEquals(0L, gone.highlight)
        assertFalse(arrived.strike)
        assertTrue(arrived.highlight != 0L)
        // Le paragraphe reste lisible d'un bout à l'autre.
        assertEquals("Le prix est de 100 120 euros.", paragraph.plainText.replace(Regex(" +"), " ").trim())
    }

    @Test
    fun `un rapport sans changement le dit`() {
        val comparison = compare(listOf("Un."), listOf("Un."))
        assertTrue(
            CompareDocuments.report(comparison, "a", "b").plainText
                .contains("Les deux versions sont identiques.")
        )
    }

    private fun document(vararg lines: String) =
        TextDocument("Doc", lines.map { TextParagraph(listOf(TextRun(it))) })
}
