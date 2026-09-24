package com.docssuite.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DocumentLibraryTest {

    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun `dossiers et favoris se retrouvent apres reouverture`() {
        val library = DocumentLibrary(context)
        val cours = library.createFolder("Cours")
        library.createFolder("Administratif")
        library.moveTo("doc1", cours.id)
        library.setFavorite("doc2", true)

        val reopened = DocumentLibrary(context)
        assertEquals(listOf("Administratif", "Cours"), reopened.folders().map { it.name })
        assertEquals(cours.id, reopened.folderOf("doc1"))
        assertEquals(setOf("doc2"), reopened.favorites())
    }

    @Test
    fun `supprimer un dossier garde ses documents`() {
        val library = DocumentLibrary(context)
        val folder = library.createFolder("Temporaire")
        library.moveTo("doc1", folder.id)
        library.deleteFolder(folder.id)
        assertTrue(library.folders().isEmpty())
        assertNull(library.folderOf("doc1"))
    }

    @Test
    fun `fusionner un rangement sauvegarde ne perd rien`() {
        val saved = DocumentLibrary(context).run {
            val cours = createFolder("Cours")
            moveTo("a", cours.id)
            setFavorite("b", true)
            exportJson()
        }
        context.getSharedPreferences("docssuite_library", 0).edit().clear().commit()

        val current = DocumentLibrary(context)
        val existing = current.createFolder("cours") // même nom, autre casse
        current.setFavorite("c", true)
        current.mergeJson(saved)

        assertEquals(1, current.folders().size)
        assertEquals(existing.id, current.folderOf("a"))
        assertEquals(setOf("b", "c"), current.favorites())
    }

    @Test
    fun `renommer et restaurer gardent la date de modification`() {
        val storage = DocumentStorage(context)
        storage.restore(DocMeta("x", "Ancien nom", DocType.TEXT, 1_000L), "{\"text\":\"bonjour\"}")
        storage.rename("x", "Nouveau nom")
        val meta = storage.meta("x")!!
        assertEquals("Nouveau nom", meta.name)
        assertEquals(1_000L, meta.updatedAt)
        assertEquals("{\"text\":\"bonjour\"}", storage.load("x"))
    }
}
