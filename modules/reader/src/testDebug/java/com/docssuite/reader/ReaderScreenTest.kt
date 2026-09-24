package com.docssuite.reader

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.docssuite.core.DocType
import com.docssuite.core.DocumentStorage
import org.json.JSONArray
import org.json.JSONObject
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
class ReaderScreenTest {

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

    @Test
    fun `ecouter un document enregistre avec la phrase surlignee`() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("docssuite_reader", 0).edit().clear().commit()
        val storage = DocumentStorage(app)
        storage.list().forEach { storage.delete(it.id) }
        val paragraphs = listOf(
            "La photosynthèse",
            "Les plantes vertes fabriquent leur propre nourriture. Elles utilisent la lumière du soleil, l'eau et le dioxyde de carbone. Ce mécanisme s'appelle la photosynthèse.",
            "Où se passe-t-elle ?",
            "Dans les feuilles, grâce à la chlorophylle. C'est elle qui leur donne leur couleur verte !"
        )
        val payload = JSONObject().put("paragraphs", JSONArray(paragraphs.map { JSONObject().put("text", it) }))
        storage.save("cours", "Cours de SVT", DocType.TEXT, payload.toString())

        val voice = FakeSpeaker()
        compose.setContent { MaterialTheme(colorScheme = lightColorScheme()) { ReaderScreen(onBack = {}, speakerFactory = { voice }) } }
        compose.onNodeWithText("Coller un texte").assertIsDisplayed()
        shoot("1-lecture-choix")

        compose.onNodeWithContentDescription("Écouter Cours de SVT").performClick()
        compose.onNodeWithText("Phrase 1 sur 7", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Lire").performClick()
        compose.runOnIdle {
            voice.finishOne()
            voice.finishOne()
        }
        compose.onNodeWithText("Phrase 3 sur 7", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Pause").assertIsDisplayed()
        assertEquals(listOf("La photosynthèse", "Les plantes vertes fabriquent leur propre nourriture."), voice.spoken)
        assertEquals("Elles utilisent la lumière du soleil, l'eau et le dioxyde de carbone.", voice.queue.first().second)
        shoot("2-lecture-en-cours")

        compose.onNodeWithText("×1,5").performClick()
        compose.runOnIdle { assertEquals(1.5f, voice.currentRate) }
        compose.onNodeWithContentDescription("Pause").performClick()
        compose.onNodeWithContentDescription("Lire").assertIsDisplayed()

        // Retour à la liste : le document propose la reprise.
        compose.onNodeWithContentDescription("Retour").performClick()
        compose.onNodeWithText("Reprendre à la phrase 3 sur 7").assertIsDisplayed()
        assertTrue(voice.queue.isEmpty())
    }
}
