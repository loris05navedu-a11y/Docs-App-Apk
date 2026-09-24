package com.docssuite.templates

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.docssuite.core.DocumentStorage
import com.docssuite.spreadsheet.SpreadsheetScreen
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
class TemplatesScreenTest {

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

    private fun field(label: String) = compose.onNode(hasSetTextAction() and hasText(label, substring = true))

    @Test
    fun `remplir une facture l ouvre calculee dans le tableur et retient les coordonnees`() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("docssuite_templates", 0).edit().clear().commit()
        val storage = DocumentStorage(app)
        var opened by mutableStateOf<String?>(null)
        var created: Built? = null

        compose.setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                val id = opened
                if (id == null) {
                    TemplatesScreen(onBack = {}, onCreate = { built ->
                        created = built
                        opened = when (built) {
                            is Built.Sheet -> "sheet:" + saveImportedWorkbook(storage, built.workbook, built.name)
                            is Built.Text -> "text:" + saveImportedTextDocument(storage, built.document, built.name)
                        }
                    })
                } else if (id.startsWith("sheet:")) {
                    SpreadsheetScreen(onBack = {}, initialDocId = id.removePrefix("sheet:"))
                } else {
                    TextEditorScreen(onBack = {}, initialDocId = id.removePrefix("text:"))
                }
            }
        }
        compose.onNodeWithText("Emploi").assertIsDisplayed()
        shoot("1-modeles-liste")

        compose.onNode(hasScrollAction()).performScrollToNode(hasContentDescription("Modèle Facture"))
        compose.onNodeWithContentDescription("Modèle Facture").performClick()
        field("Prénom et nom").performTextInput("Léa Martin Graphisme")
        field("Code postal et ville").performTextInput("69003 Lyon")
        compose.onNode(hasScrollAction()).performScrollToNode(hasSetTextAction() and hasText("Client"))
        field("Client").performTextInput("SARL Dupont")
        shoot("2-modeles-formulaire")
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Créer et ouvrir dans le tableur"))
        compose.onNodeWithText("Créer et ouvrir dans le tableur").performClick()
        compose.waitForIdle()

        val invoice = created as Built.Sheet
        assertEquals("SARL Dupont", invoice.workbook.sheets[0].cells["A8"])
        assertTrue(opened!!.startsWith("sheet:"))
        compose.onNodeWithText("FACTURE").assertIsDisplayed()
        shoot("3-modeles-facture-tableur")

        // Les coordonnées reviennent toutes seules dans le modèle suivant.
        assertEquals("Léa Martin Graphisme", TemplateProfile(app).get("nom"))
        assertEquals("69003 Lyon", TemplateProfile(app).get("ville"))
    }

    @Test
    fun `un cv s ouvre dans l editeur de texte`() {
        val app = RuntimeEnvironment.getApplication()
        val storage = DocumentStorage(app)
        val cv = Templates.byId("cv")!!.build(
            mapOf(
                "nom" to "Léa Martin", "metier" to "Assistante de gestion", "telephone" to "06 12 34 56 78", "email" to "lea.martin@mail.fr",
                "adresse" to "12 rue des Lilas", "ville" to "69003 Lyon",
                "profil" to "Rigoureuse et souriante, je cherche un poste en CDI dans une PME.",
                "experiences" to "2022-2024 – Assistante administrative – Cabinet Durand\n2020-2022 – Vendeuse – Boulangerie Martin",
                "formations" to "2020 – BTS Gestion de la PME – Lycée Ampère",
                "competences" to "Excel, facturation\nAccueil téléphonique\nPermis B",
                "langues" to "Anglais courant", "loisirs" to "Photographie, randonnée"
            )
        ) as Built.Text
        val id = saveImportedTextDocument(storage, cv.document, cv.name)
        compose.setContent { MaterialTheme(colorScheme = lightColorScheme()) { TextEditorScreen(onBack = {}, initialDocId = id) } }
        compose.waitForIdle()
        shoot("4-modeles-cv-editeur")
        assertTrue(storage.load(id)!!.contains("Cabinet Durand"))
    }
}
