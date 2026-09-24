package com.docssuite.library

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.docssuite.core.DocMeta
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
class LibraryScreenTest {

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

    private fun count(description: String) = compose.onAllNodes(hasContentDescription(description)).fetchSemanticsNodes().size

    @Test
    fun `sans document un message explique ou ils apparaitront`() {
        RuntimeEnvironment.getApplication().getSharedPreferences("docssuite_documents", 0).edit().clear().commit()
        compose.setContent { MaterialTheme(colorScheme = lightColorScheme()) { LibraryScreen(onBack = {}, onOpen = {}) } }
        compose.onNodeWithText("Aucun document pour l'instant").assertIsDisplayed()
    }

    @Test
    fun `chercher filtrer et ouvrir`() {
        LibraryFixtures.seed(RuntimeEnvironment.getApplication())
        var opened: DocMeta? = null
        compose.setContent { MaterialTheme(colorScheme = lightColorScheme()) { LibraryScreen(onBack = {}, onOpen = { opened = it }) } }
        compose.waitUntil(5_000) { count("Document Exposé") == 1 }
        shoot("1-biblio-liste")

        compose.onNodeWithText("Chercher un mot dans tous les documents").performTextInput("ecole")
        compose.waitUntil(5_000) { count("Document Exposé") == 0 }
        assertEquals(1, count("Document Compte rendu de réunion"))
        assertEquals(1, count("Document Budget familial"))
        shoot("2-biblio-recherche")

        compose.onNodeWithContentDescription("Effacer la recherche").performClick()
        compose.onNodeWithText("Présentations").performClick()
        compose.waitUntil(5_000) { count("Document Budget familial") == 0 }
        compose.onNodeWithContentDescription("Document Exposé").performClick()
        assertEquals("p", opened?.id)
    }
}
