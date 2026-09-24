package com.docssuite.spreadsheet

import com.docssuite.core.DocType
import com.docssuite.core.DocumentStorage
import com.docssuite.core.DocumentText
import com.docssuite.fileformats.Sheet
import com.docssuite.fileformats.Workbook
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** La recherche lit le vrai format enregistré par le tableur, toutes feuilles comprises. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SearchableSheetTest {
    @Test
    fun `cellules et noms de feuilles sont cherchables dans l ordre des rangees`() {
        val storage = DocumentStorage(RuntimeEnvironment.getApplication())
        val workbook = Workbook(
            title = "Budget",
            sheets = listOf(
                Sheet(name = "Janvier", cells = mapOf("B2" to "750", "A1" to "Poste", "A2" to "Loyer", "A10" to "Total"), columns = 4, rows = 12),
                Sheet(name = "Février", cells = mapOf("A1" to "Assurance"), columns = 2, rows = 2)
            )
        )
        val id = saveImportedWorkbook(storage, workbook, "Budget")
        val text = DocumentText.extract(DocType.SHEET, storage.load(id)!!)
        listOf("Janvier", "Février", "Loyer", "750", "Assurance").forEach { assertTrue("« $it » absent de : $text", text.contains(it)) }
        assertTrue("A2 avant A10 : $text", text.indexOf("Loyer") < text.indexOf("Total"))
    }
}
