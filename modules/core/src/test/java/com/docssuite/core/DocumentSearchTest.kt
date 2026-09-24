package com.docssuite.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentSearchTest {

    private fun doc(id: String, name: String, updatedAt: Long = 0) = DocMeta(id, name, DocType.TEXT, updatedAt)

    private val corpus = listOf(
        doc("1", "Compte rendu de réunion", 3) to "Présents : Léa, Hugo.\nOrdre du jour : budget de l'école et sortie scolaire.",
        doc("2", "Budget 2026", 2) to "Loyer 750\nCourses 312,5\nÉlectricité 60",
        doc("3", "Recette", 1) to "Mélanger le beurre et le sucre, ajouter un œuf. Cuire au four à cœur.",
        doc("4", "École — inscriptions", 4) to "Dossier à rendre avant le 15 septembre."
    )

    @Test
    fun `les accents et majuscules sont ignores`() {
        val hits = DocumentSearch.search("ecole", corpus)
        assertEquals(setOf("1", "4"), hits.map { it.meta.id }.toSet())
    }

    @Test
    fun `oe et ae se cherchent sans ligature`() {
        assertEquals(listOf("3"), DocumentSearch.search("coeur", corpus).map { it.meta.id })
        assertEquals(listOf("3"), DocumentSearch.search("oeuf", corpus).map { it.meta.id })
    }

    @Test
    fun `plusieurs mots doivent tous etre presents dans le nom ou le contenu`() {
        assertEquals(listOf("1"), DocumentSearch.search("budget sortie", corpus).map { it.meta.id })
        assertEquals(listOf("2"), DocumentSearch.search("budget loyer", corpus).map { it.meta.id })
        assertTrue(DocumentSearch.search("budget pizza", corpus).isEmpty())
    }

    @Test
    fun `un mot dans le nom passe avant un mot seulement dans le contenu`() {
        val hits = DocumentSearch.search("budget", corpus)
        assertEquals(listOf("2", "1"), hits.map { it.meta.id })
    }

    @Test
    fun `l extrait entoure le passage trouve et surligne les mots d origine`() {
        val hit = DocumentSearch.search("ecole sortie", corpus).single()
        assertTrue(hit.snippet, hit.snippet.contains("école"))
        val highlighted = hit.snippetMatches.map { hit.snippet.substring(it.first, it.last + 1) }
        assertEquals(listOf("école", "sortie"), highlighted)
        assertTrue("pas de retour à la ligne dans l'extrait", !hit.snippet.contains('\n'))
    }

    @Test
    fun `le surlignage du nom tombe sur les bonnes lettres malgre les accents`() {
        val hit = DocumentSearch.search("reunion", corpus).single()
        val range = hit.nameMatches.single()
        assertEquals("réunion", hit.meta.name.substring(range.first, range.last + 1))
    }

    @Test
    fun `un long texte donne un extrait court coupe aux mots`() {
        val long = "mot ".repeat(200) + "trésor caché " + "fin ".repeat(200)
        val hit = DocumentSearch.search("tresor", listOf(doc("x", "Long") to long)).single()
        assertTrue(hit.snippet.length < 160)
        assertTrue(hit.snippet.startsWith("…") && hit.snippet.endsWith("…"))
        assertTrue(hit.snippet.contains("trésor"))
    }

    @Test
    fun `une recherche vide ne renvoie rien`() {
        assertTrue(DocumentSearch.search("   ", corpus).isEmpty())
    }
}
