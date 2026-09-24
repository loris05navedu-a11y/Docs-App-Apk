package com.docssuite.pdftools.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.docssuite.pdftools.PdfFile
import com.docssuite.pdftools.PdfTestFiles
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
class PdfSignScreenTest {

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
    fun `choisir un outil puis toucher la page y pose l element`() {
        lateinit var state: PdfSignState
        compose.setContent {
            state = rememberSignState()
            MaterialTheme(colorScheme = lightColorScheme()) { PdfSignScreen(onBack = {}, state = state) }
        }
        compose.onNodeWithText("Choisir un PDF").assertIsDisplayed()
        val app = RuntimeEnvironment.getApplication()
        val bytes = PdfTestFiles.bytes("alpha-simple.pdf")
        compose.runOnUiThread {
            state.load(PdfFile.parse(bytes), File(app.cacheDir, "a.pdf").apply { writeBytes(bytes) }, "Contrat de location.pdf")
            state.rememberSignature(signatureBitmap(listOf((0..40).map { Offset(it * 6f, 30 + 20 * kotlin.math.sin(it / 5.0).toFloat()) }), 6f)!!)
        }
        compose.onNodeWithText("Contrat de location").assertIsDisplayed()

        compose.onNodeWithContentDescription("Texte").performClick()
        compose.onNodeWithText("Touche la page à l'endroit où placer : texte").assertIsDisplayed()
        compose.onNodeWithContentDescription("Page 1").performTouchInput { click(Offset(width * 0.1f, height * 0.8f)) }
        compose.onNodeWithContentDescription("Texte posé : Texte").assertIsDisplayed()
        compose.runOnUiThread { state.update(state.selectedId!!) { it.copy(text = "Lu et approuvé — Léa Dupont") } }

        // L'élément posé reste sélectionné (ses actions remplacent les outils) jusqu'à « OK ».
        compose.onNodeWithText("Plus grand").assertIsDisplayed()
        compose.onNodeWithText("OK").performClick()
        compose.onNodeWithContentDescription("Signature").performClick()
        compose.onNodeWithContentDescription("Page 1").performTouchInput { click(Offset(width * 0.7f, height * 0.88f)) }
        compose.onNodeWithContentDescription("Signature posée").assertIsDisplayed()
        shoot("signature-page")

        assertEquals(2, state.placed.size)
        val text = state.placed.first { it.image == null }
        assertEquals(0.1f, text.u, 0.02f)
    }
}
