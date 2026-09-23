package com.docssuite.spreadsheet

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.docssuite.core.DocumentStorage
import com.docssuite.fileformats.FileFormats
import com.docssuite.fileformats.Imported
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Le tableur réel : un classeur Excel à deux feuilles s'ouvre, on passe d'une
 * feuille à l'autre, on modifie, on annule, on cherche, on fige l'en-tête.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xxhdpi")
class SpreadsheetScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val shots: String? = System.getProperty("screenshots")

    private fun shoot(name: String) {
        val folder = shots ?: return
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun open(name: String): String {
        val bytes = File("../fileformats/src/test/resources/corpus/$name").readBytes()
        val imported = FileFormats.import(name, bytes) as Imported.AsSheet
        return saveImportedWorkbook(DocumentStorage(compose.activity), imported.workbook, "Mes comptes")
    }

    @Test
    fun `un classeur Excel a deux feuilles s ouvre et se manipule`() {
        val id = open("classeur-excel.xlsx")
        compose.setContent {
            MaterialTheme(colorScheme = lightColorScheme()) { SpreadsheetScreen(onBack = {}, initialDocId = id) }
        }
        compose.waitForIdle()

        // Le total calculé et la date convertie sont affichés.
        compose.onNodeWithText("38").assertIsDisplayed()
        compose.onNodeWithText("Synthèse 2024").assertIsDisplayed()
        shoot("tableur-feuille-1")

        // Seconde feuille : sa formule va chercher la première.
        compose.onNodeWithText("Synthèse 2024").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("76").assertIsDisplayed()
        shoot("tableur-feuille-2")

        // Une saisie, puis Annuler.
        compose.onNode(hasSetTextAction() and hasText("Total ventes")).performTextReplacement("123")
        compose.onNodeWithContentDescription("Valider").performClick()
        compose.onNode(hasText("123") and !hasSetTextAction()).assertExists()
        compose.onNodeWithContentDescription("Annuler").performClick()
        compose.waitForIdle()

        // Nouvelle feuille.
        compose.onNodeWithContentDescription("Nouvelle feuille").performClick()
        compose.onNodeWithText("Feuille3").assertIsDisplayed()

        // Retour à la première feuille, en-tête figé.
        compose.onNodeWithText("Ventes").performClick()
        compose.onNodeWithContentDescription("Menu").performClick()
        compose.onNodeWithText("Figer la ligne 1").performClick()
        compose.waitForIdle()
        compose.onNode(hasText("Produit") and !hasSetTextAction()).assertIsDisplayed()

        // Recherche.
        compose.onNodeWithContentDescription("Rechercher").performClick()
        compose.onNodeWithText("Rechercher").performTextReplacement("Kiwi")
        compose.onNodeWithText("Suivant").performClick()
        compose.waitForIdle()
        shoot("tableur-recherche")

        compose.onNodeWithContentDescription("Enregistrer").performClick()
        compose.onNodeWithText("OK").performClick()
        val (sheets, _) = WorkbookOps.decode(DocumentStorage(compose.activity).load(id)!!)
        assertEquals(listOf("Ventes", "Synthèse 2024", "Feuille3"), sheets.map { it.name })
        // La saisie a été annulée.
        assertEquals("Total ventes", sheets[1].cells["A1"])
        assertEquals(true, sheets[0].freezeRow)
    }
}
