package com.docssuite.library

import com.docssuite.core.DocType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LibraryStateTest {

    private val context get() = RuntimeEnvironment.getApplication()

    @Before
    fun seed() = LibraryFixtures.seed(context)

    private fun LibraryState.ids(query: String = "") = results(query, filtered()).map { it.meta.id }

    @Test
    fun `la recherche traverse documents tableurs et presentations`() {
        val state = LibraryState(context)
        assertEquals(listOf("b", "r"), state.ids("ecole").sorted())
        assertEquals(listOf("p"), state.ids("eruption"))
        assertEquals(listOf("b"), state.ids("loyer 750"))
    }

    @Test
    fun `les filtres se combinent avec la recherche`() {
        val state = LibraryState(context)
        state.kind = KindFilter.SHEET
        assertEquals(listOf("b"), state.ids("ecole"))
        state.kind = KindFilter.ALL
        state.toggleFavorite(state.documents.first { it.id == "r" })
        state.favoritesOnly = true
        assertEquals(listOf("r"), state.ids())
    }

    @Test
    fun `ranger dans un dossier puis filtrer par dossier`() {
        val state = LibraryState(context)
        val cours = state.createFolder("Cours")
        state.moveTo(state.documents.first { it.id == "p" }, cours.id)
        state.folder = FolderFilter.In(cours.id)
        assertEquals(listOf("p"), state.ids())
        state.folder = FolderFilter.None
        assertEquals(listOf("r", "b"), state.ids())
        assertEquals(1, state.countIn(cours.id))

        state.deleteFolder(cours.id)
        assertEquals(FolderFilter.Any, state.folder)
        assertEquals(3, state.ids().size)
    }

    @Test
    fun `renommer garde le document et son contenu cherchable`() {
        val state = LibraryState(context)
        state.rename(state.documents.first { it.id == "r" }, "Réunion de rentrée")
        assertEquals(listOf("r"), state.ids("rentree"))
        assertEquals(listOf("r"), state.ids("musee"))
    }

    @Test
    fun `supprimer retire aussi le favori`() {
        val state = LibraryState(context)
        val doc = state.documents.first { it.id == "b" }
        state.toggleFavorite(doc)
        state.delete(doc)
        assertTrue(state.documents.none { it.id == "b" })
        assertTrue("b" !in state.favorites)
    }

    @Test
    fun `le tri par nom ignore les accents`() {
        val state = LibraryState(context)
        state.sort = SortOrder.NAME
        assertEquals(listOf("Budget familial", "Compte rendu de réunion", "Exposé"), state.filtered().map { it.name })
        assertEquals(DocType.SHEET, state.filtered().first().type)
    }
}
