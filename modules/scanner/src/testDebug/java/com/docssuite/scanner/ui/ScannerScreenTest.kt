package com.docssuite.scanner.ui

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.docssuite.scanner.SyntheticPhoto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * L'écran réel, rendu avec le moteur graphique natif. Les pages sont
 * préparées sur disque avant l'affichage, comme au retour dans l'app
 * après un scan. Avec `-Pscreenshots=dossier`, chaque étape est aussi
 * enregistrée en PNG pour relecture.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xxhdpi")
class ScannerScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val output: String? = System.getProperty("screenshots")

    @Before
    fun cleanSession() {
        File(context.cacheDir, "scans").deleteRecursively()
    }

    /** `captureToImage` attend un rafraîchissement que Robolectric ne déclenche pas : on dessine la vue nous-mêmes. */
    private fun shoot(name: String) {
        val dir = output ?: return
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        File(dir).mkdirs()
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun seedPages(count: Int) {
        val state = ScannerState(context, CoroutineScope(SupervisorJob() + Dispatchers.Main))
        val uris = (1..count).map { Uri.fromFile(SyntheticPhoto.writeJpeg(File(context.filesDir, "galerie/$it.jpg"))) }
        state.importImages(uris)
        val deadline = System.currentTimeMillis() + 20_000
        while (state.pages.size < count || state.busyMessage != null) {
            shadowOf(Looper.getMainLooper()).idle()
            check(System.currentTimeMillis() < deadline) { "Pages non préparées" }
            Thread.sleep(10)
        }
    }

    private fun show(onOpenPdf: ((String, ByteArray) -> Unit)? = { _, _ -> }) {
        compose.setContent {
            MaterialTheme(colorScheme = lightColorScheme()) { ScannerScreen(onBack = {}, onOpenPdf = onOpenPdf) }
        }
        compose.waitForIdle()
    }

    @Test
    fun `sans page l ecran propose de photographier ou d importer`() {
        show()
        compose.onNodeWithText("Prendre une photo").assertIsDisplayed()
        compose.onNodeWithText("Importer des photos").assertIsDisplayed()
        shoot("1-scanner-vide")
    }

    @Test
    fun `les pages deja scannees s affichent et se reordonnent`() {
        seedPages(2)
        show()
        compose.onNodeWithText("Scanner — 2 page(s)").assertIsDisplayed()
        compose.onNodeWithContentDescription("Page 1").assertIsDisplayed()
        compose.onNodeWithContentDescription("Page 2").assertIsDisplayed()
        compose.onNodeWithText("Exporter en PDF").assertIsDisplayed()
        shoot("2-scanner-pages")

        compose.onNodeWithContentDescription("Supprimer la page 2").performClick()
        compose.waitForIdle()
        assertEquals(0, compose.onAllNodesWithContentDescription("Page 2").fetchSemanticsNodes().size)
        compose.onNodeWithText("Scanner — 1 page(s)").assertIsDisplayed()
    }

    @Test
    fun `une page s ouvre dans le recadrage puis montre son resultat`() {
        seedPages(1)
        show()
        compose.onNodeWithContentDescription("Page 1").performClick()
        compose.waitUntil(20_000) {
            compose.onAllNodesWithContentDescription("Zone de recadrage").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Recadrer la page").assertIsDisplayed()
        compose.onNodeWithText("Noir et blanc").assertIsDisplayed()
        shoot("3-scanner-recadrage")

        compose.onNodeWithText("Noir et blanc").performClick()
        compose.onNodeWithText("Résultat").performClick()
        compose.waitUntil(20_000) {
            compose.onAllNodesWithContentDescription("Aperçu de la page").fetchSemanticsNodes().isNotEmpty()
        }
        shoot("4-scanner-resultat")

        compose.onNodeWithText("Valider").performClick()
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText("Exporter en PDF").fetchSemanticsNodes().isNotEmpty()
        }
        // La vignette recalculée se recharge après validation.
        compose.waitUntil(20_000) {
            compose.onAllNodesWithContentDescription("Vignette de la page 1", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        shoot("5-scanner-page-validee")
    }
}

private fun androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>.onAllNodesWithText(text: String) =
    onAllNodes(androidx.compose.ui.test.hasText(text))
