package com.docssuite.texteditor

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.docssuite.core.DocType
import com.docssuite.core.DocumentStorage
import com.docssuite.fileformats.FileFormats
import com.docssuite.fileformats.Imported
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * L'écran réel, rendu et manipulé : un document Word à tableaux fusionnés
 * s'ouvre, on écrit dans une cellule, on ajoute une ligne, on insère un
 * tableau. Une erreur d'affichage (styles de paragraphe qui se chevauchent,
 * mesure impossible d'une cellule…) ferait échouer ce test.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], qualifiers = "w1280dp-h1000dp")
class TextEditorScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun importCorpus(name: String): String {
        val bytes = File("../fileformats/src/test/resources/corpus/$name").readBytes()
        val imported = FileFormats.import(name, bytes) as Imported.AsText
        return saveImportedTextDocument(DocumentStorage(context), imported.document, "Rapport")
    }

    private fun savedDoc(id: String): EditorDoc =
        EditorIO.fromJson(DocumentStorage(context).load(id)!!)

    @Test
    fun `un document Word a tableaux s affiche et se modifie`() {
        val id = importCorpus("rapport-word.docx")
        compose.setContent { MaterialTheme { TextEditorScreen(onBack = {}, initialDocId = id) } }
        compose.waitForIdle()

        // Le texte des cellules est à l'écran, dans sa grille.
        compose.onNodeWithText("Région").assertIsDisplayed()
        compose.onNodeWithText("Total").assertIsDisplayed()
        // Le second tableau est plus bas dans la page.
        compose.onNodeWithText("Bruno").assertExists()

        // Écrire dans une cellule, puis ajouter une ligne sous elle.
        compose.onNodeWithText("Nord").performClick()
        compose.onNodeWithText("Nord").performTextInput("-Est")
        compose.waitForIdle()
        compose.onNodeWithText("+ Ligne").performClick()
        compose.onNodeWithText("+ Colonne").performClick()
        compose.onNodeWithText("Fusionner →").performClick()
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Enregistrer").performClick()
        compose.onNodeWithText("OK").performClick()
        val saved = savedDoc(id)
        val table = saved.blocks.filterIsInstance<EditorBlock.Table>().first().table
        assertEquals(5, table.rows.size)
        assertEquals(4, table.columnCount)
        assertTrue(EditorModel.allText(saved).contains("-Est"))
        table.rows.forEach { row -> assertEquals(table.columnCount, row.sumOf { it.colSpan }) }
    }

    @Test
    fun `inserer un tableau depuis la barre d outils puis y ecrire`() {
        compose.setContent { MaterialTheme { TextEditorScreen(onBack = {}) } }
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Insérer un tableau").performClick()
        compose.onNodeWithText("Insérer").performClick()
        compose.waitForIdle()
        // La barre « Tableau » apparaît avec le curseur dans la première cellule.
        compose.onNodeWithText("Supprimer le tableau").assertIsDisplayed()
        compose.onNodeWithText("+ Ligne").performClick()
        compose.onNodeWithText("− Colonne").performClick()
        compose.onNodeWithText("Supprimer le tableau").performClick()
        compose.onAllNodesWithText("Supprimer")[0].performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Tableau").assertDoesNotExistSafe()
    }

    @Test
    fun `listes, titres, retrait et interligne s appliquent sans erreur`() {
        compose.setContent { MaterialTheme { TextEditorScreen(onBack = {}) } }
        compose.waitForIdle()
        val editor = compose.onNodeWithText("Commence à écrire… puis sélectionne un passage pour le mettre en forme.")
        editor.assertIsDisplayed()
        compose.onNodeWithContentDescription("Liste à puces").performClick()
        compose.onNodeWithContentDescription("Augmenter le retrait").performClick()
        compose.onNodeWithContentDescription("Centrer").performClick()
        compose.onNodeWithText("Normal").performClick()
        compose.onNodeWithText("Titre 1").performClick()
        compose.onNodeWithContentDescription("Exposant").performClick()
        compose.onNodeWithContentDescription("Reproduire la mise en forme").performClick()
        compose.onNodeWithText("Annuler").performClick()
        compose.onNodeWithContentDescription("Menu").performClick()
        compose.onNodeWithText("Interligne : Simple").performClick()
        compose.onNodeWithText("1,5").performClick()
        compose.onNodeWithContentDescription("Menu").performClick()
        compose.onNodeWithText("Plan du document").performClick()
        compose.onNodeWithText("Fermer").performClick()
        compose.waitForIdle()
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertDoesNotExistSafe() = assertDoesNotExist()
}
