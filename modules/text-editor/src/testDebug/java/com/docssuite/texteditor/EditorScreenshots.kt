package com.docssuite.texteditor

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.docssuite.core.DocumentStorage
import com.docssuite.fileformats.FileFormats
import com.docssuite.fileformats.Imported
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Captures d'écran de l'éditeur, pour relecture humaine. Elles ne tournent
 * que sur demande : `-Dscreenshots=chemin/du/dossier`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xxhdpi")
class EditorScreenshots {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val output: String? = System.getProperty("screenshots")

    /**
     * `captureToImage` attend un rafraîchissement que la boucle de Robolectric
     * ne déclenche pas : on dessine la vue racine nous-mêmes.
     */
    private fun shoot(name: String) {
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        File(output!!, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun capture() {
        assumeTrue(output != null)
        val context: Context = ApplicationProvider.getApplicationContext()
        val bytes = File("../fileformats/src/test/resources/corpus/rapport-word.docx").readBytes()
        val imported = FileFormats.import("rapport.docx", bytes) as Imported.AsText
        val id = saveImportedTextDocument(DocumentStorage(context), imported.document, "Rapport trimestriel")
        compose.setContent {
            MaterialTheme(colorScheme = lightColorScheme()) { TextEditorScreen(onBack = {}, initialDocId = id) }
        }
        compose.waitForIdle()
        shoot("editeur-tableau-importe")
        compose.onNodeWithText("Nord").performClick()
        compose.waitForIdle()
        shoot("editeur-cellule-active")
    }
}
