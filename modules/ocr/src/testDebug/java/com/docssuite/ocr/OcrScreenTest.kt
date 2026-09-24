package com.docssuite.ocr

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
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
class OcrScreenTest {

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
    fun `de la photo au document`() {
        val engine = FakeOcrEngine(ArrayDeque(listOf(listOf(
            OcrBlock(listOf(OcrLine("Compte rendu", Box(40, 30, 300, 70))), Box(40, 30, 300, 70)),
            OcrBlock(
                listOf(
                    OcrLine("La réunion a validé le budget de", Box(40, 110, 330, 140)),
                    OcrLine("la sortie scolaire au musée.", Box(40, 150, 300, 180))
                ),
                Box(40, 110, 330, 180)
            )
        ))))
        lateinit var state: OcrState
        var created: Pair<String, String>? = null
        compose.setContent {
            state = rememberOcrState { engine }
            MaterialTheme(colorScheme = lightColorScheme()) {
                OcrScreen(onBack = {}, onCreateDocument = { t, x -> created = t to x }, state = state)
            }
        }
        compose.onNodeWithText("Prendre une photo").assertIsDisplayed()
        shoot("1-ocr-vide")

        val photo = writeSheetPhoto(File(RuntimeEnvironment.getApplication().filesDir, "galerie/cr.jpg"))
        compose.runOnUiThread { state.importImages(listOf(Uri.fromFile(photo))) }
        compose.waitUntil(20_000) { compose.onAllNodes(hasText("Créer un document avec ce texte")).fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(20_000) { state.busyMessage == null }
        shoot("2-ocr-resultat")

        compose.onNodeWithText("Créer un document avec ce texte").performClick()
        assertEquals("Compte rendu" to "Compte rendu\n\nLa réunion a validé le budget de la sortie scolaire au musée.", created)
    }
}
