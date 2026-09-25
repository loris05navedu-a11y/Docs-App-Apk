package com.docssuite.compare

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.docssuite.core.DocumentStorage
import com.docssuite.fileformats.TextDocument
import com.docssuite.fileformats.TextParagraph
import com.docssuite.fileformats.TextRun
import com.docssuite.texteditor.TextEditorScreen
import com.docssuite.texteditor.saveImportedTextDocument
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xxhdpi")
class CompareScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()
    private val output: String? = System.getProperty("screenshots")

    private fun shoot(name: String) {
        val dir = output ?: return
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        File(dir).mkdirs()
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun document(vararg lines: String) =
        TextDocument("Contrat", lines.map { TextParagraph(listOf(TextRun(it))) })

    private fun scroll(text: String) {
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText(text, substring = true))
    }

    private fun saveVersions() {
        val storage = DocumentStorage(RuntimeEnvironment.getApplication())
        saveImportedTextDocument(
            storage,
            document(
                "Contrat de location.",
                "Le loyer est de 500 euros par mois.",
                "Le dépôt de garantie est d'un mois.",
                "Fait à Lyon."
            ),
            "Contrat v1"
        )
        saveImportedTextDocument(
            storage,
            document(
                "Contrat de location.",
                "Le loyer est de 550 euros par mois.",
                "Les charges sont comprises.",
                "Fait à Lyon."
            ),
            "Contrat v2"
        )
    }

    private fun pickBoth() {
        compose.onNodeWithContentDescription("Avant : à choisir").performClick()
        compose.onNodeWithContentDescription("Contrat v1").performClick()
        compose.onNodeWithContentDescription("Après : à choisir").performClick()
        compose.onNodeWithContentDescription("Contrat v2").performClick()
    }

    @Test
    fun `deux versions choisies montrent ce qui a change`() {
        saveVersions()
        compose.setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                CompareScreen(onBack = {}, onSaveReport = { _, _ -> })
            }
        }
        compose.onNodeWithText("Comparer deux versions").assertIsDisplayed()
        shoot("1-comparer-choix")

        compose.onNodeWithContentDescription("Avant : à choisir").performClick()
        compose.onNodeWithText("Quelle est l'ancienne version ?").assertIsDisplayed()
        compose.onNodeWithContentDescription("Contrat v1").performClick()
        compose.onNodeWithContentDescription("Avant : Contrat v1").assertIsDisplayed()

        compose.onNodeWithContentDescription("Après : à choisir").performClick()
        compose.onNodeWithContentDescription("Contrat v2").performClick()

        // Un paragraphe retouché, un supprimé, un ajouté.
        compose.onNode(hasText("1 ajout", substring = true)).assertIsDisplayed()
        compose.onNodeWithContentDescription("Retouché 1").assertIsDisplayed()
        shoot("2-comparer-changements")
    }

    @Test
    fun `les paragraphes inchanges se montrent a la demande`() {
        saveVersions()
        compose.setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                CompareScreen(onBack = {}, onSaveReport = { _, _ -> })
            }
        }
        pickBoth()
        compose.onNodeWithText("Contrat de location.").assertDoesNotExist()
        compose.onNodeWithContentDescription("Montrer aussi ce qui n'a pas changé").performClick()
        scroll("Contrat de location.")
        compose.onNodeWithText("Contrat de location.").assertIsDisplayed()
    }

    @Test
    fun `le rapport s enregistre et s ouvre dans l editeur`() {
        saveVersions()
        val storage = DocumentStorage(RuntimeEnvironment.getApplication())
        var opened by mutableStateOf<String?>(null)
        var reported: TextDocument? = null

        compose.setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                val id = opened
                if (id == null) {
                    CompareScreen(onBack = {}, onSaveReport = { report, name ->
                        reported = report
                        opened = saveImportedTextDocument(storage, report, name)
                    })
                } else {
                    TextEditorScreen(onBack = {}, initialDocId = id)
                }
            }
        }
        pickBoth()
        scroll("Enregistrer le rapport")
        compose.onNodeWithText("Enregistrer le rapport").performClick()
        compose.waitForIdle()

        assertNotNull(reported)
        val text = reported!!.plainText
        assertTrue(text, text.contains("Avant : Contrat v1"))
        assertTrue(text, text.contains("Après : Contrat v2"))
        assertTrue(text, text.contains("550"))
        // Le rapport est bien devenu un document que l'éditeur ouvre.
        assertNotNull(opened)
        val editor = compose.onRoot().printToString(maxDepth = 12)
        assertTrue(editor, editor.contains("Comparaison"))
        assertTrue(editor, editor.contains("Avant : Contrat v1"))
        shoot("3-comparer-rapport")
    }

    @Test
    fun `inverser les versions inverse ajouts et suppressions`() {
        saveVersions()
        compose.setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                CompareScreen(onBack = {}, onSaveReport = { _, _ -> })
            }
        }
        pickBoth()
        compose.onNodeWithContentDescription("Inverser les versions").performClick()
        compose.onNodeWithContentDescription("Avant : Contrat v2").assertIsDisplayed()
        compose.onNodeWithContentDescription("Après : Contrat v1").assertIsDisplayed()
    }

    @Test
    fun `deux fois la meme version annonce l'absence de changement`() {
        saveVersions()
        compose.setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                CompareScreen(onBack = {}, onSaveReport = { _, _ -> })
            }
        }
        compose.onNodeWithContentDescription("Avant : à choisir").performClick()
        compose.onNodeWithContentDescription("Contrat v1").performClick()
        compose.onNodeWithContentDescription("Après : à choisir").performClick()
        compose.onNodeWithContentDescription("Contrat v1").performClick()

        compose.onNodeWithContentDescription("Les deux versions sont identiques.").assertIsDisplayed()
        // Rien à enregistrer quand rien ne change.
        compose.onNodeWithText("Enregistrer le rapport").assertDoesNotExist()
    }

    @Test
    fun `retirer une version rend le choix au depart`() {
        saveVersions()
        compose.setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                CompareScreen(onBack = {}, onSaveReport = { _, _ -> })
            }
        }
        pickBoth()
        compose.onNodeWithContentDescription("Retirer Avant").performClick()
        compose.onNodeWithContentDescription("Avant : à choisir").assertIsDisplayed()
        compose.onNodeWithText("Enregistrer le rapport").assertDoesNotExist()
    }
}
