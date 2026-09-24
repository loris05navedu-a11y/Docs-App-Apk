package com.docssuite.pdftools.ui

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.docssuite.pdftools.PdfTestFiles
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * L'écran réel avec deux PDF ouverts. Avec `-Pscreenshots=dossier`, chaque
 * étape est aussi enregistrée en PNG. Robolectric n'a pas le moteur PDF
 * natif : les vignettes y sont remplacées par le numéro de page, ce qui
 * vérifie au passage ce repli.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xxhdpi")
class PdfToolsScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
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

    private fun uri(name: String): Uri =
        Uri.fromFile(File(context.filesDir, "docs/$name").apply { parentFile!!.mkdirs(); writeBytes(PdfTestFiles.bytes(name)) })

    private lateinit var state: PdfToolsState

    private fun show() {
        compose.setContent {
            state = rememberPdfToolsState()
            MaterialTheme(colorScheme = lightColorScheme()) { PdfToolsScreen(onBack = {}, onOpenPdf = { _, _ -> }, state = state) }
        }
        compose.waitForIdle()
    }

    private fun count(description: String) =
        compose.onAllNodes(hasContentDescription(description)).fetchSemanticsNodes().size

    @Test
    fun `sans document l ecran propose d en choisir`() {
        show()
        compose.onNodeWithText("Choisir des PDF").assertIsDisplayed()
        shoot("1-pdf-vide")
    }

    @Test
    fun `selectionner supprimer puis annuler`() {
        show()
        compose.runOnUiThread { state.import(listOf(uri("alpha-simple.pdf"), uri("beta-objstm.pdf"))) }
        compose.waitUntil(20_000) { count("Page 7") == 1 }
        compose.onNodeWithText("Outils PDF — 7 page(s)").assertIsDisplayed()
        compose.onNodeWithText("alpha-simple.pdf").assertIsDisplayed()
        shoot("2-pdf-deux-documents")

        compose.onNodeWithContentDescription("Page 2").performClick()
        compose.onNodeWithContentDescription("Page 3").performClick()
        compose.onNodeWithText("2 sélectionnée(s)").assertIsDisplayed()
        compose.onNodeWithText("Extraire").assertIsDisplayed()
        shoot("3-pdf-selection")

        compose.onNodeWithText("Supprimer").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Annuler")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Outils PDF — 5 page(s)").assertIsDisplayed()
        shoot("4-pdf-supprime")

        compose.onNodeWithText("Annuler").performClick()
        compose.waitUntil(10_000) { count("Page 7") == 1 }
        assertEquals(7, state.pages.size)
    }
}
