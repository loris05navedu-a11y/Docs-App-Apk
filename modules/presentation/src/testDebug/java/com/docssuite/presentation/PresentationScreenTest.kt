package com.docssuite.presentation

import android.graphics.Bitmap
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
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
import com.docssuite.fileformats.SlideLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w411dp-h2400dp-xxhdpi")
class PresentationScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val shots: String? = System.getProperty("screenshots")

    /** Capture la fenêtre du dialogue plein écran s'il y en a un, sinon l'activité. */
    private fun shoot(name: String, view: View = compose.activity.window.decorView) {
        val folder = shots ?: return
        if (compose.mainClock.autoAdvance) compose.waitForIdle()
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun `une presentation PowerPoint s ouvre, se met en page et se projette`() {
        val bytes = File("../fileformats/src/test/resources/corpus/presentation-powerpoint.pptx").readBytes()
        val imported = FileFormats.import("p.pptx", bytes) as Imported.AsDeck
        val storage = DocumentStorage(compose.activity)
        val id = saveImportedDeck(storage, imported.deck, "Annuel")
        compose.setContent {
            MaterialTheme(colorScheme = lightColorScheme()) { PresentationScreen(onBack = {}, initialDocId = id) }
        }
        compose.waitForIdle()
        compose.onAllNodesWithText("Présentation annuelle")[0].assertIsDisplayed()
        shoot("presentation-importee")

        // Deuxième diapositive : deux colonnes et puces.
        compose.onAllNodesWithText("Deux colonnes")[1].performClick()
        compose.onNodeWithText("Colonne de droite").performTextReplacement("Rapide\nÉconome")
        compose.onAllNodesWithContentDescription("Liste à puces")[1].performClick()
        compose.waitForIdle()

        shoot("presentation-deux-colonnes")

        // Annuler retire les puces, Rétablir les remet.
        compose.onNodeWithContentDescription("Annuler").performClick()
        compose.onNodeWithContentDescription("Rétablir").performClick()

        // Diaporama et mode présentateur.
        compose.onNodeWithContentDescription("Lancer le diaporama").performClick()
        compose.waitForIdle()
        // Le chronomètre avance sans fin : l'horloge est alors pilotée à la main.
        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription("Mode présentateur").performClick()
        compose.mainClock.advanceTimeBy(2_000)
        compose.onNodeWithText("Suivante ▶").performClick()
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNode(hasText("Parler lentement") and !hasSetTextAction()).assertExists()
        // Le diaporama est une fenêtre à part : on la capture elle-même.
        shots?.let {
            val global = Class.forName("android.view.WindowManagerGlobal")
            val instance = global.getMethod("getInstance").invoke(null)
            val field = global.getDeclaredField("mViews")
            field.isAccessible = true
            (((field.get(instance) as List<*>).lastOrNull()) as? View)?.let { view ->
                shoot("presentation-presentateur", view)
            }
        }
        compose.onNodeWithContentDescription("Quitter le diaporama").performClick()
        compose.mainClock.advanceTimeBy(1_000)
        compose.mainClock.autoAdvance = true

        compose.onNodeWithContentDescription("Enregistrer").performClick()
        compose.onNodeWithText("OK").performClick()
        val payload = storage.load(id)!!
        val slides = decodeDeck(payload)
        val settings = decodeSettings(payload)
        assertEquals(SlideLayout.TWO_COLUMNS, slides[1].layout)
        assertEquals("Rapide\nÉconome", slides[1].secondContent)
        assertTrue(slides[1].bullets)
        assertEquals(DeckSettings(), settings)
    }

    /**
     * La boîte « Pied de page » n'est pas pilotée ici : Robolectric ne se
     * stabilise pas quand une boîte de dialogue à champ texte s'ouvre sur une
     * liste modifiée, même sur un écran minimal sans code de l'app. Ses
     * réglages sont vérifiés par l'enregistrement et par les fichiers.
     */
    @Test
    fun `pied de page et numeros sont enregistres avec la presentation`() {
        val settings = DeckSettings("Réunion annuelle", true)
        val payload = encodeDeck(listOf(Slide(title = "A")), settings)
        assertEquals(settings, decodeSettings(payload))
        assertEquals(DeckSettings(), decodeSettings("""{"slides":[]}"""))
    }
}
