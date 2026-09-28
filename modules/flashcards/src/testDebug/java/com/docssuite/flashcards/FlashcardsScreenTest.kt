package com.docssuite.flashcards

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.docssuite.core.DocumentStorage
import com.docssuite.fileformats.TextDocument
import com.docssuite.fileformats.TextParagraph
import com.docssuite.fileformats.TextRun
import com.docssuite.texteditor.saveImportedTextDocument
import org.junit.Assert.assertEquals
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
class FlashcardsScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()
    private val output: String? = System.getProperty("screenshots")
    private val app get() = RuntimeEnvironment.getApplication()

    @Before
    fun clean() {
        File(app.filesDir, "flashcards.json").delete()
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

    private fun show() = compose.setContent {
        MaterialTheme(colorScheme = lightColorScheme()) { FlashcardsScreen(onBack = {}) }
    }

    private fun field(label: String) = compose.onNode(hasSetTextAction() and hasText(label, substring = true))

    private fun scrollTo(text: String) {
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText(text, substring = true))
    }

    private fun seed(): String {
        val store = FlashcardStore(app)
        val deck = store.createDeck("Histoire")
        store.addCards(deck.id, listOf("Révolution française" to "1789", "Chute du mur de Berlin" to "1989", "Sacre de Charlemagne" to "800"))
        return deck.id
    }

    @Test
    fun `creer un paquet, y ajouter des fiches, les reviser`() {
        show()
        compose.onNodeWithText("Apprendre avec des fiches").assertIsDisplayed()
        shoot("1-fiches-vide")

        compose.onNodeWithText("Nouveau paquet").performClick()
        field("Nom du paquet").performTextInput("Anglais")
        compose.onNodeWithText("Créer").performClick()

        compose.onNodeWithText("Ajouter").performClick()
        field("Question").performTextInput("chat")
        field("Réponse").performTextInput("cat")
        compose.onNodeWithText("Ajouter").performClick()
        field("Question").performTextInput("chien")
        field("Réponse").performTextInput("dog")
        shoot("2b-fiches-saisie")
        compose.onNodeWithText("Ajouter").performClick()
        compose.onNode(hasText("2 fiches ajoutées", substring = true)).assertIsDisplayed()
        // Une fiche déjà présente est signalée, pas ajoutée deux fois.
        field("Question").performTextInput("Chat")
        field("Réponse").performTextInput("CAT")
        compose.onNodeWithText("Ajouter").performClick()
        compose.onNodeWithText("Cette fiche est déjà dans le paquet.").assertIsDisplayed()
        compose.onNodeWithText("Terminé").performClick()

        compose.onNodeWithText("Réviser 2 fiches").performClick()
        compose.onNodeWithContentDescription("Question : chat").assertIsDisplayed()
        shoot("3-fiches-question")
        compose.onNodeWithText("Voir la réponse").performClick()
        compose.onNodeWithContentDescription("Réponse : cat").assertIsDisplayed()
        shoot("4-fiches-reponse")
        compose.onNodeWithText("Je savais").performClick()

        compose.onNodeWithContentDescription("Question : chien").assertIsDisplayed()
        compose.onNodeWithText("Voir la réponse").performClick()
        compose.onNodeWithText("À revoir").performClick()
        // Ratée : elle revient aussitôt.
        compose.onNodeWithContentDescription("Question : chien").assertIsDisplayed()
        compose.onNodeWithText("Voir la réponse").performClick()
        compose.onNodeWithText("Je savais").performClick()

        compose.onNodeWithText("Séance terminée").assertIsDisplayed()
        compose.onNode(hasText("2 fiches révisées · 1 erreur", substring = true)).assertIsDisplayed()
        compose.onNodeWithText("Prochaine révision : demain").assertIsDisplayed()
        compose.onNodeWithText("Terminer").performClick()

        compose.onNodeWithText("Tout est à jour : rien à revoir aujourd'hui.").assertIsDisplayed()
        val deck = FlashcardStore(app).decks().single()
        assertTrue(deck.cards.all { it.box == 1 })
        assertEquals(1, deck.cards.single { it.front == "chien" }.lapses)
    }

    @Test
    fun `la liste des paquets montre ce qui attend`() {
        seed()
        show()
        compose.onNodeWithText("Rien à revoir aujourd'hui.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Paquet Histoire").assertIsDisplayed()
        compose.onNode(hasText("3 fiches · 3 nouvelles", substring = true)).assertIsDisplayed()
        shoot("2-fiches-paquets")
        compose.onNodeWithContentDescription("Paquet Histoire").performClick()
        compose.onNodeWithText("Réviser 3 fiches").assertIsDisplayed()
        shoot("5-fiches-paquet")
    }

    @Test
    fun `en sens inverse on voit la reponse et on cherche la question`() {
        seed()
        show()
        compose.onNodeWithContentDescription("Paquet Histoire").performClick()
        compose.onNodeWithContentDescription("Sens inversé").performClick()
        compose.onNodeWithText("Réviser 3 fiches").performClick()
        compose.onNodeWithContentDescription("Question : 1789").assertIsDisplayed()
        compose.onNodeWithText("Voir la réponse").performClick()
        compose.onNodeWithContentDescription("Réponse : Révolution française").assertIsDisplayed()
    }

    @Test
    fun `importer un cours colle, une fiche par ligne`() {
        show()
        compose.onNodeWithText("Importer").performClick()
        compose.onNode(hasSetTextAction()).performTextInput(
            "Photosynthèse : production de sucre grâce à la lumière\nMitose : division cellulaire\nChapitre 2\nADN : support de l'hérédité"
        )
        compose.onNode(hasText("3 fiches trouvées", substring = true)).assertIsDisplayed()
        compose.onNode(hasText("1 ligne sans réponse ignorée", substring = true)).assertIsDisplayed()
        scrollTo("Nom du paquet")
        field("Nom du paquet").performTextReplacement("SVT")
        shoot("6-fiches-import")
        scrollTo("Créer le paquet (3)")
        compose.onNodeWithText("Créer le paquet (3)").performClick()

        compose.onNodeWithText("SVT").assertIsDisplayed()
        compose.onNodeWithText("Réviser 3 fiches").assertIsDisplayed()
        assertEquals(3, FlashcardStore(app).decks().single().cards.size)
    }

    @Test
    fun `importer depuis un document enregistre`() {
        val storage = DocumentStorage(app)
        saveImportedTextDocument(
            storage,
            TextDocument("Capitales", listOf("France : Paris", "Italie : Rome", "Espagne : Madrid").map { TextParagraph(listOf(TextRun(it))) }),
            "Capitales"
        )
        val deckId = seed()
        show()
        compose.onNodeWithContentDescription("Paquet Histoire").performClick()
        compose.onAllNodesWithText("Importer").onFirst().performClick()
        compose.onNodeWithText("Mes documents").performClick()
        compose.onNodeWithContentDescription("Source Capitales").performClick()
        compose.onNode(hasText("3 fiches trouvées", substring = true)).assertIsDisplayed()
        scrollTo("Ajouter 3 fiches")
        compose.onNodeWithText("Ajouter 3 fiches").performClick()
        assertEquals(6, FlashcardStore(app).deck(deckId)!!.cards.size)
    }

    @Test
    fun `modifier et supprimer une fiche`() {
        val deckId = seed()
        show()
        compose.onNodeWithContentDescription("Paquet Histoire").performClick()
        scrollTo("Sacre de Charlemagne")
        compose.onNodeWithContentDescription("Fiche Sacre de Charlemagne").performClick()
        field("Réponse").performTextReplacement("800, à Rome")
        compose.onNodeWithText("Enregistrer").performClick()
        assertEquals("800, à Rome", FlashcardStore(app).deck(deckId)!!.cards.single { it.front == "Sacre de Charlemagne" }.back)

        compose.onNodeWithContentDescription("Fiche Chute du mur de Berlin").performClick()
        compose.onNodeWithText("Supprimer la fiche").performClick()
        compose.onNodeWithText("Supprimer").performClick()
        assertEquals(2, FlashcardStore(app).deck(deckId)!!.cards.size)
    }
}
