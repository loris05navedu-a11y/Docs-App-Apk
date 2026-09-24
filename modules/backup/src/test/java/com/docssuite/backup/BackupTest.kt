package com.docssuite.backup

import com.docssuite.core.DocMeta
import com.docssuite.core.DocType
import com.docssuite.core.DocumentLibrary
import com.docssuite.core.DocumentStorage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BackupTest {

    private val context get() = RuntimeEnvironment.getApplication()

    @Before
    fun clean() = wipePhone()

    private fun wipePhone() {
        listOf("docssuite_documents", "docssuite_library", "docssuite_backup").forEach {
            context.getSharedPreferences(it, 0).edit().clear().commit()
        }
    }

    private fun doc(id: String, name: String, type: DocType, time: Long, payload: String) =
        SavedDocument(DocMeta(id, name, type, time), payload)

    private val lettre = doc("a", "Lettre à la mairie (brouillon)", DocType.TEXT, 1_000, """{"v":2,"blocks":[{"t":"text","text":"Madame, Monsieur…","runs":[]}]}""")
    private val budget = doc("b", "Budget", DocType.SHEET, 2_000, """{"sheets":[{"name":"Janvier","cells":{"A1":"Loyer"}}]}""")
    private val expose = doc("c", "Exposé", DocType.DECK, 3_000, """{"slides":[{"title":"Volcans"}]}""")

    private fun seedPhone(vararg docs: SavedDocument) {
        val storage = DocumentStorage(context)
        docs.forEach { storage.restore(it.meta, it.payload) }
    }

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> entries.forEach { (name, data) -> z.putNextEntry(ZipEntry(name)); z.write(data); z.closeEntry() } }
        return out.toByteArray()
    }

    @Test
    fun `sauvegarder puis restaurer sur un telephone vide redonne tout a l identique`() {
        seedPhone(lettre, budget, expose)
        val library = DocumentLibrary(context)
        val folder = library.createFolder("Administratif")
        library.moveTo("a", folder.id)
        library.setFavorite("c", true)
        val archive = BackupService(context).createBackup(now = 5_000)
        File("build/sauvegarde-test.zip").writeBytes(archive)

        wipePhone() // nouveau téléphone
        val service = BackupService(context)
        val contents = BackupArchive.read(archive)
        assertEquals(5_000, contents.createdAt)
        val result = service.restore(contents, service.plan(contents), keepConflictCopies = true)
        assertEquals(RestoreResult(added = 3, updated = 0, copies = 0, unchanged = 0), result)

        val restored = service.currentDocuments().associateBy { it.meta.id }
        listOf(lettre, budget, expose).forEach { original ->
            assertEquals(original.meta, restored.getValue(original.meta.id).meta) // date de modification comprise
            assertEquals(original.payload, restored.getValue(original.meta.id).payload)
        }
        val newLibrary = DocumentLibrary(context)
        assertEquals("Administratif", newLibrary.folders().single().name)
        assertEquals(newLibrary.folders().single().id, newLibrary.folderOf("a"))
        assertEquals(setOf("c"), newLibrary.favorites())
    }

    @Test
    fun `restaurer ne supprime rien et garde la version la plus recente`() {
        val archive = BackupArchive.write(listOf(lettre, budget), JSONObject(), 4_000)
        val budgetModifie = budget.copy(meta = budget.meta.copy(updatedAt = 9_000), payload = """{"sheets":[{"name":"Janvier","cells":{"A1":"Loyer","B1":"800"}}]}""")
        val lettreAncienne = lettre.copy(meta = lettre.meta.copy(updatedAt = 500), payload = """{"text":"vieux"}""")
        seedPhone(lettreAncienne, budgetModifie, expose)

        val service = BackupService(context)
        val contents = BackupArchive.read(archive)
        val plan = service.plan(contents)
        assertEquals(listOf("a"), plan.updated.map { it.meta.id }) // plus récente dans la sauvegarde
        assertEquals(listOf("b"), plan.conflicts.map { it.meta.id }) // modifiée depuis sur le téléphone
        assertTrue(plan.added.isEmpty())

        service.restore(contents, plan, keepConflictCopies = true)
        val now = service.currentDocuments()
        assertEquals(4, now.size) // expose gardé + copie du budget ajoutée
        assertEquals(lettre.payload, now.first { it.meta.id == "a" }.payload)
        assertEquals(budgetModifie.payload, now.first { it.meta.id == "b" }.payload)
        val copy = now.single { it.meta.name.startsWith("Budget (sauvegarde du") }
        assertEquals(budget.payload, copy.payload)
    }

    @Test
    fun `une restauration refaite ne duplique rien`() {
        seedPhone(lettre, budget)
        val archive = BackupService(context).createBackup()
        val service = BackupService(context)
        val contents = BackupArchive.read(archive)
        val plan = service.plan(contents)
        assertEquals(2, plan.unchanged.size)
        service.restore(contents, plan, keepConflictCopies = true)
        assertEquals(2, service.currentDocuments().size)
    }

    @Test
    fun `une archive quelconque est refusee sans rien ecrire`() {
        seedPhone(expose)
        assertThrows(BackupFormatException::class.java) { BackupArchive.read("pas un zip".toByteArray()) }
        assertThrows(BackupFormatException::class.java) { BackupArchive.read(zip("photo.jpg" to ByteArray(10))) }
        assertThrows(BackupFormatException::class.java) {
            BackupArchive.read(zip("manifest.json" to """{"format":"autre-app"}""".toByteArray()))
        }
        assertEquals(1, BackupService(context).currentDocuments().size)
    }

    @Test
    fun `un document manquant ou illisible fait refuser toute la sauvegarde`() {
        val manifest = """{"format":"${BackupArchive.FORMAT}","version":1,"documents":[{"id":"x","name":"Note","type":"TEXT","file":"documents/1.json"}]}"""
        val missing = assertThrows(BackupFormatException::class.java) { BackupArchive.read(zip("manifest.json" to manifest.toByteArray())) }
        assertTrue(missing.message!!.contains("Note"))
        assertThrows(BackupFormatException::class.java) {
            BackupArchive.read(zip("manifest.json" to manifest.toByteArray(), "documents/1.json" to "{tronqué".toByteArray()))
        }
    }

    @Test
    fun `une sauvegarde d une version plus recente de l app est refusee clairement`() {
        val manifest = """{"format":"${BackupArchive.FORMAT}","version":99,"documents":[]}"""
        val error = assertThrows(BackupFormatException::class.java) { BackupArchive.read(zip("manifest.json" to manifest.toByteArray())) }
        assertTrue(error.message!!.contains("mets l'app à jour"))
    }

    @Test
    fun `une archive piegee qui se decompresse en un volume enorme est arretee`() {
        val bomb = ByteArrayOutputStream()
        ZipOutputStream(bomb).use { z ->
            z.putNextEntry(ZipEntry("documents/1.json"))
            val zeros = ByteArray(1024 * 1024)
            repeat(80) { z.write(zeros) } // 80 Mo de zéros, quelques dizaines de Ko compressés
            z.closeEntry()
        }
        assertTrue(bomb.size() < 1024 * 1024)
        val error = assertThrows(BackupFormatException::class.java) { BackupArchive.read(bomb.toByteArray()) }
        assertTrue(error.message!!.contains("volumineuse"))
    }

    @Test
    fun `la date de derniere sauvegarde n est notee qu a l enregistrement`() {
        val service = BackupService(context)
        service.createBackup()
        assertEquals(null, service.lastBackupAt())
        service.markSaved(now = 7_000)
        assertEquals(7_000L, service.lastBackupAt())
    }
}
