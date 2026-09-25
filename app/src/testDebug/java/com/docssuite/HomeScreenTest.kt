package com.docssuite

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.docssuite.core.DocType
import com.docssuite.core.DocumentStorage
import com.docssuite.pdf.PdfPayload
import com.docssuite.ui.theme.DocsSuiteTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
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
class HomeScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()
    private val output: String? = System.getProperty("screenshots")

    private val app get() = RuntimeEnvironment.getApplication()
    private val storage get() = DocumentStorage(app)

    private val navigated = mutableListOf<String>()
    private val opened = mutableListOf<String>()

    @Before
    fun clean() {
        app.getSharedPreferences("docssuite_documents", 0).edit().clear().commit()
        app.getSharedPreferences("docssuite_home", 0).edit().clear().commit()
    }

    private fun shoot(name: String) {
        val dir = output ?: return
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        File(dir).mkdirs()
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun show(dark: Boolean = false) {
        compose.setContent {
            DocsSuiteTheme(darkTheme = dark) {
                HomeScreen(
                    onNavigate = { navigated.add(it) },
                    onOpenTextEditor = { opened.add("text:$it") },
                    onOpenSpreadsheet = { opened.add("sheet:$it") },
                    onOpenPresentation = { opened.add("deck:$it") },
                    onOpenPdf = { payload: PdfPayload? -> opened.add("pdf:${payload?.name}") }
                )
            }
        }
    }

    private fun saveSamples() {
        storage.save("t1", "Lettre de motivation", DocType.TEXT, "{}")
        storage.save("s1", "Budget 2026", DocType.SHEET, "{}")
        storage.save("d1", "Exposé histoire", DocType.DECK, "{}")
    }

    private fun scrollTo(description: String) {
        compose.onAllNodes(hasScrollAction()).onFirst()
            .performScrollToNode(hasContentDescription(description, substring = true))
    }

    /** Comme le bouton retour, une fois l'écran à jour. */
    private fun back() {
        compose.waitForIdle()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        assertTrue("le retour a quitté l'app au lieu de revenir", !compose.activity.isFinishing)
    }

    private fun tab(label: String) = compose.onNodeWithContentDescription("Onglet $label").performClick()

    // ------------------------------------------------------------ accueil

    @Test
    fun `l'accueil montre de quoi creer, commencer et reprendre`() {
        saveSamples()
        show()
        compose.onNodeWithText("DocsApp Suite").assertIsDisplayed()
        compose.onNodeWithText("Nouveau").assertIsDisplayed()
        compose.onNodeWithContentDescription("Nouveau : Document").assertIsDisplayed()
        compose.onNodeWithContentDescription("Nouveau : Tableur").assertIsDisplayed()
        compose.onNodeWithText("Pour commencer").assertIsDisplayed()
        compose.onNodeWithContentDescription("Outil Scanner").assertIsDisplayed()
        // Les raccourcis sont assez bas pour que les récents se voient sans défiler.
        compose.onNodeWithContentDescription("Ouvrir Exposé histoire").assertIsDisplayed()
        shoot("1-accueil")
        scrollTo("Ouvrir Budget 2026")
        compose.onNodeWithContentDescription("Ouvrir Budget 2026").assertIsDisplayed()
    }

    @Test
    fun `la creation ouvre l'editeur vide`() {
        show()
        compose.onNodeWithContentDescription("Nouveau : Tableur").performClick()
        compose.onNodeWithContentDescription("Nouveau : Présentation").performClick()
        assertEquals(listOf("sheet:null", "deck:null"), opened)
    }

    @Test
    fun `un document recent s'ouvre dans le bon editeur`() {
        saveSamples()
        show()
        scrollTo("Ouvrir Budget 2026")
        compose.onNodeWithContentDescription("Ouvrir Budget 2026").performClick()
        assertEquals(listOf("sheet:s1"), opened)
    }

    @Test
    fun `sans document l'accueil explique ou ils apparaitront`() {
        show()
        scrollTo("Famille Organiser")
        compose.onNodeWithText("Tes documents apparaîtront ici").assertExists()
    }

    // ------------------------------------------------------------ recherche

    @Test
    fun `la recherche trouve un outil par ce qu'on veut faire`() {
        show()
        compose.onNode(hasSetTextAction()).performTextInput("fusionner")
        compose.onNodeWithContentDescription("Outil Outils PDF").assertIsDisplayed()
        shoot("4-recherche")
        compose.onNodeWithContentDescription("Outil Outils PDF").performClick()
        assertEquals(listOf("pdftools"), navigated)
    }

    @Test
    fun `la recherche trouve aussi les documents par leur nom`() {
        saveSamples()
        show()
        compose.onNode(hasSetTextAction()).performTextInput("budget")
        compose.onNodeWithContentDescription("Ouvrir Budget 2026").performClick()
        assertEquals(listOf("sheet:s1"), opened)
    }

    @Test
    fun `une recherche sans resultat propose de chercher dans le contenu`() {
        show()
        compose.onNode(hasSetTextAction()).performTextInput("zzzz")
        compose.onNode(hasText("Rien ne correspond", substring = true)).assertIsDisplayed()
        compose.onNodeWithText("Chercher dans le contenu des documents").performClick()
        assertEquals(listOf("library"), navigated)
    }

    @Test
    fun `effacer la recherche rend l'accueil`() {
        show()
        compose.onNode(hasSetTextAction()).performTextInput("scan")
        compose.onNodeWithContentDescription("Effacer la recherche").performClick()
        compose.onNodeWithText("Nouveau").assertIsDisplayed()
    }

    // ------------------------------------------------------------ outils

    @Test
    fun `l'onglet outils range tout en familles`() {
        show()
        tab("Outils")
        compose.onNode(hasText("rangés par ce qu'ils font", substring = true)).assertIsDisplayed()
        ToolCategory.values().forEach { family ->
            compose.onAllNodes(hasScrollAction()).onFirst()
                .performScrollToNode(hasContentDescription("Outil " + Tools.of(family).last().title))
        }
        shoot("2-outils")
    }

    @Test
    fun `filtrer une famille ne montre qu'elle`() {
        show()
        tab("Outils")
        compose.onNode(hasText("PDF") and isSelectable()).performClick()
        Tools.of(ToolCategory.PDF).forEach { tool ->
            compose.onNodeWithContentDescription("Outil ${tool.title}").assertExists()
        }
        compose.onNodeWithContentDescription("Outil Tableur").assertDoesNotExist()
        compose.onNodeWithContentDescription("Outil Dictaphone et dictée").assertDoesNotExist()
        shoot("3-outils-pdf")
    }

    @Test
    fun `une famille choisie depuis l'accueil ouvre l'onglet outils filtre`() {
        show()
        scrollTo("Famille Voix et médias")
        compose.onNodeWithContentDescription("Famille Voix et médias").performClick()
        compose.onNodeWithContentDescription("Outil Lecture à voix haute").assertIsDisplayed()
        compose.onNodeWithContentDescription("Outil Scanner").assertDoesNotExist()
    }

    @Test
    fun `un outil utilise remonte dans les raccourcis`() {
        show()
        tab("Outils")
        compose.onAllNodes(hasScrollAction()).onFirst()
            .performScrollToNode(hasContentDescription("Outil Comparer deux versions"))
        compose.onNodeWithContentDescription("Outil Comparer deux versions").performClick()
        assertEquals(listOf("compare"), navigated)

        tab("Accueil")
        compose.onNodeWithText("Tes outils du moment").assertIsDisplayed()
        compose.onNodeWithContentDescription("Outil Comparer deux versions").assertIsDisplayed()
    }

    // ------------------------------------------------------------ documents

    @Test
    fun `l'onglet documents filtre par sorte`() {
        saveSamples()
        show()
        tab("Documents")
        compose.onNodeWithText("3 documents enregistrés").assertIsDisplayed()
        compose.onNodeWithContentDescription("Ouvrir Exposé histoire").assertIsDisplayed()
        shoot("5-documents")
        compose.onNodeWithText("Tableurs · 1").performClick()
        compose.onNodeWithContentDescription("Ouvrir Budget 2026").assertIsDisplayed()
        compose.onNodeWithContentDescription("Ouvrir Exposé histoire").assertDoesNotExist()
    }

    @Test
    fun `supprimer demande confirmation`() {
        saveSamples()
        show()
        tab("Documents")
        compose.onNodeWithContentDescription("Supprimer Budget 2026").performClick()
        compose.onNodeWithText("Supprimer ce document ?").assertIsDisplayed()
        compose.onNodeWithText("Annuler").performClick()
        assertTrue(storage.meta("s1") != null)

        compose.onNodeWithContentDescription("Supprimer Budget 2026").performClick()
        compose.onNodeWithText("Supprimer").performClick()
        compose.onNodeWithContentDescription("Ouvrir Budget 2026").assertDoesNotExist()
        assertNull(storage.meta("s1"))
        compose.onNodeWithText("2 documents enregistrés").assertIsDisplayed()
    }

    @Test
    fun `sans document l'onglet documents propose d'en creer`() {
        show()
        tab("Documents")
        compose.onNodeWithText("Rien pour l'instant").assertIsDisplayed()
        compose.onNodeWithText("Créer un document").performClick()
        assertEquals(listOf("text:null"), opened)
    }

    // ------------------------------------------------------------ retour

    @Test
    fun `le retour ramene d'abord a l'accueil`() {
        show()
        tab("Documents")
        back()
        compose.onNodeWithText("Nouveau").assertIsDisplayed()
    }

    @Test
    fun `le retour efface d'abord la recherche`() {
        show()
        compose.onNode(hasSetTextAction()).performTextInput("zzzz")
        back()
        compose.onNodeWithText("Nouveau").assertIsDisplayed()
    }

    // ------------------------------------------------------------ sombre

    @Test
    fun `l'accueil en mode sombre`() {
        saveSamples()
        show(dark = true)
        compose.onNodeWithText("Nouveau").assertIsDisplayed()
        shoot("6-accueil-sombre")
    }
}
