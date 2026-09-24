package com.docssuite.presentation

import com.docssuite.core.DocType
import com.docssuite.core.DocumentStorage
import com.docssuite.core.DocumentText
import com.docssuite.fileformats.Deck
import com.docssuite.fileformats.SlideModel
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** La recherche lit le vrai format enregistré par l'éditeur de présentations. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SearchableDeckTest {
    @Test
    fun `titres et contenus des diapositives sont cherchables`() {
        val storage = DocumentStorage(RuntimeEnvironment.getApplication())
        val deck = Deck(
            title = "Bilan",
            slides = listOf(
                SlideModel(title = "Introduction", content = "Chiffre d'affaires en hausse"),
                SlideModel(title = "Conclusion", content = "Merci !")
            )
        )
        val id = saveImportedDeck(storage, deck, "Bilan")
        val text = DocumentText.extract(DocType.DECK, storage.load(id)!!)
        listOf("Introduction", "Chiffre d'affaires", "Conclusion", "Merci !").forEach {
            assertTrue("« $it » absent de : $text", text.contains(it))
        }
    }
}
