package com.docssuite.mailmerge

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.docssuite.core.DocumentStorage
import com.docssuite.fileformats.Sheet
import com.docssuite.fileformats.TextDocument
import com.docssuite.fileformats.TextParagraph
import com.docssuite.fileformats.TextRun
import com.docssuite.fileformats.Workbook
import com.docssuite.spreadsheet.saveImportedWorkbook
import com.docssuite.texteditor.TextEditorScreen
import com.docssuite.texteditor.saveImportedTextDocument
import org.junit.Assert.assertEquals
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
class MailMergeScreenTest {

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

    private fun model() = TextDocument(
        "Invitation",
        listOf(
            paragraph("Bonjour {{Prénom}} {{Nom}},"),
            paragraph("Nous t'attendons à {{Ville}} le {{date}}."),
            paragraph("À bientôt !")
        )
    )

    private fun paragraph(text: String) = TextParagraph(listOf(TextRun(text)))

    private fun guests() = Workbook(
        "Invités",
        listOf(
            Sheet(
                "Invités",
                mapOf(
                    "A1" to "Nom", "B1" to "Prénom", "C1" to "Ville",
                    "A2" to "Dupont", "B2" to "Léa", "C2" to "Lyon",
                    "A3" to "Martin", "B3" to "Paul", "C3" to "Brest"
                )
            )
        )
    )

    /** La liste verticale, et non les rubans de puces qui défilent aussi. */
    private fun scroll(text: String) {
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText(text, substring = true))
    }

    @Test
    fun `un modele et un tableur donnent un courrier par destinataire`() {
        val app = RuntimeEnvironment.getApplication()
        val storage = DocumentStorage(app)
        saveImportedTextDocument(storage, model(), "Invitation")
        saveImportedWorkbook(storage, guests(), "Invités")
        var produced: List<MergedLetter> = emptyList()

        compose.setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                MailMergeScreen(onBack = {}, onCreate = { produced = it })
            }
        }

        // 1 — le modèle
        compose.onNodeWithText("1 sur 3 · le modèle").assertIsDisplayed()
        shoot("1-publipostage-modele")
        compose.onNodeWithContentDescription("Invitation").performClick()

        // 2 — les destinataires : les champs du modèle sont annoncés
        compose.onNodeWithText("2 sur 3 · les destinataires").assertIsDisplayed()
        compose.onNode(hasText("{{Prénom}}", substring = true)).assertIsDisplayed()
        shoot("2-publipostage-destinataires")
        compose.onNodeWithContentDescription("Invités").performClick()

        // 3 — vérifier et créer
        compose.onNodeWithText("3 sur 3 · vérifier et créer").assertIsDisplayed()
        compose.onNode(hasText("Bonjour Léa Dupont,", substring = true)).assertIsDisplayed()
        shoot("3-publipostage-apercu")
        scroll("Créer les 2 courriers")
        compose.onNodeWithText("Créer les 2 courriers").performClick()

        assertEquals(2, produced.size)
        assertEquals("Invitation — Dupont", produced[0].name)
        assertEquals("Invitation — Martin", produced[1].name)
        assertTrue(produced[0].document.plainText, produced[0].document.plainText.startsWith("Bonjour Léa Dupont,"))
        assertTrue(produced[1].document.plainText.contains("à Brest"))
        // {{date}} n'a pas de colonne mais se remplit quand même.
        assertTrue(produced[0].document.plainText, !produced[0].document.plainText.contains("{{date}}"))
    }

    @Test
    fun `tout dans un seul document ne produit qu'un document`() {
        val app = RuntimeEnvironment.getApplication()
        val storage = DocumentStorage(app)
        saveImportedTextDocument(storage, model(), "Invitation")
        saveImportedWorkbook(storage, guests(), "Invités")
        var produced: List<MergedLetter> = emptyList()

        compose.setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                MailMergeScreen(onBack = {}, onCreate = { produced = it })
            }
        }
        compose.onNodeWithContentDescription("Invitation").performClick()
        compose.onNodeWithContentDescription("Invités").performClick()
        scroll("Tout dans un seul document")
        compose.onNodeWithContentDescription("Tout dans un seul document").performClick()
        scroll("Créer le document des 2 courriers")
        compose.onNodeWithText("Créer le document des 2 courriers").performClick()

        assertEquals(1, produced.size)
        val text = produced[0].document.plainText
        assertTrue(text, text.contains("Bonjour Léa Dupont,"))
        assertTrue(text, text.contains("Bonjour Paul Martin,"))
    }

    @Test
    fun `un champ sans colonne est signale et reste visible dans le courrier`() {
        val app = RuntimeEnvironment.getApplication()
        val storage = DocumentStorage(app)
        saveImportedTextDocument(
            storage,
            TextDocument("Relance", listOf(paragraph("Bonjour {{Nom}}, tél : {{Téléphone}}."))),
            "Relance"
        )
        saveImportedWorkbook(storage, guests(), "Invités")
        var produced: List<MergedLetter> = emptyList()

        compose.setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                MailMergeScreen(onBack = {}, onCreate = { produced = it })
            }
        }
        compose.onNodeWithContentDescription("Relance").performClick()
        compose.onNodeWithContentDescription("Invités").performClick()
        compose.onNode(hasText("Sans colonne", substring = true)).assertIsDisplayed()
        scroll("Créer les 2 courriers")
        compose.onNodeWithText("Créer les 2 courriers").performClick()

        assertEquals("Bonjour Dupont, tél : {{Téléphone}}.", produced[0].document.plainText)
    }

    @Test
    fun `le courrier produit s ouvre dans l editeur, mise en forme gardee`() {
        val app = RuntimeEnvironment.getApplication()
        val storage = DocumentStorage(app)
        val bold = TextDocument(
            "Convocation",
            listOf(TextParagraph(listOf(TextRun("Cher "), TextRun("{{Nom}}", bold = true), TextRun(", à demain."))))
        )
        saveImportedTextDocument(storage, bold, "Convocation")
        saveImportedWorkbook(storage, guests(), "Invités")
        var opened by mutableStateOf<String?>(null)

        compose.setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                val id = opened
                if (id == null) {
                    MailMergeScreen(onBack = {}, onCreate = { letters ->
                        opened = saveImportedTextDocument(storage, letters[0].document, letters[0].name)
                    })
                } else {
                    TextEditorScreen(onBack = {}, initialDocId = id)
                }
            }
        }
        compose.onNodeWithContentDescription("Convocation").performClick()
        compose.onNodeWithContentDescription("Invités").performClick()
        scroll("Créer les 2 courriers")
        compose.onNodeWithText("Créer les 2 courriers").performClick()
        compose.waitForIdle()

        compose.onNode(hasText("Cher Dupont, à demain.", substring = true)).assertIsDisplayed()
        shoot("4-publipostage-courrier-editeur")
    }

    @Test
    fun `un tableur sans destinataire ne fait pas passer a l'etape suivante`() {
        val app = RuntimeEnvironment.getApplication()
        val storage = DocumentStorage(app)
        saveImportedTextDocument(storage, model(), "Invitation")
        // Des en-têtes, mais aucune ligne en dessous.
        saveImportedWorkbook(storage, Workbook("Vide", listOf(Sheet("Vide", mapOf("A1" to "Nom")))), "Vide")

        compose.setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                MailMergeScreen(onBack = {}, onCreate = {})
            }
        }
        compose.onNodeWithContentDescription("Invitation").performClick()
        compose.onNodeWithContentDescription("Vide").performClick()
        // On reste sur le choix des destinataires, avec une explication.
        compose.onNodeWithText("2 sur 3 · les destinataires").assertIsDisplayed()
        compose.onNode(hasText("Aucun destinataire", substring = true)).assertIsDisplayed()
    }
}
