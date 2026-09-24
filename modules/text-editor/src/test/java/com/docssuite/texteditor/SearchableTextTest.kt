package com.docssuite.texteditor

import com.docssuite.core.DocType
import com.docssuite.core.DocumentStorage
import com.docssuite.core.DocumentText
import com.docssuite.fileformats.FileFormats
import com.docssuite.fileformats.Imported
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** La recherche lit le vrai format enregistré par l'éditeur, tableaux compris. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SearchableTextTest {
    @Test
    fun `le texte d un document et de ses tableaux est cherchable`() {
        val storage = DocumentStorage(RuntimeEnvironment.getApplication())
        val bytes = File("../fileformats/src/test/resources/corpus/rapport-word.docx").readBytes()
        val imported = FileFormats.import("rapport.docx", bytes) as Imported.AsText
        val id = saveImportedTextDocument(storage, imported.document, "Rapport")
        val text = DocumentText.extract(DocType.TEXT, storage.load(id)!!)
        listOf("Région", "Nord", "Bruno").forEach { assertTrue("« $it » absent de : $text", text.contains(it)) }
        assertTrue("aucun style ne doit fuiter", !text.contains("\"b\""))
    }
}
