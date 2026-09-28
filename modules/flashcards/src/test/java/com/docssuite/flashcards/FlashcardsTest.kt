package com.docssuite.flashcards

import com.docssuite.fileformats.Sheet
import com.docssuite.fileformats.TableCell
import com.docssuite.fileformats.TableRow
import com.docssuite.fileformats.TextDocument
import com.docssuite.fileformats.TextParagraph
import com.docssuite.fileformats.TextRun
import com.docssuite.fileformats.TextTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.Calendar
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FlashcardsTest {

    private val today = 20_000L

    private fun card(id: String, box: Int = 0, due: Long = 0, reviews: Int = if (box == 0) 0 else 1) =
        Card(id, "recto $id", "verso $id", box, due, reviews)

    // ------------------------------------------------------------ Leitner

    @Test
    fun `chaque bonne reponse espace la revision suivante`() {
        var c = card("a")
        val gaps = ArrayList<Long>()
        repeat(7) {
            c = Leitner.known(c, today)
            gaps.add(c.due - today)
        }
        assertEquals(listOf(1L, 2L, 4L, 8L, 16L, 32L, 32L), gaps)
        assertEquals(Leitner.TOP, c.box)
        assertTrue(c.mastered)
    }

    @Test
    fun `une erreur renvoie dans la premiere boite, a revoir aujourd'hui`() {
        val c = Leitner.forgotten(card("a", box = 4, due = today), today)
        assertEquals(1, c.box)
        assertEquals(today, c.due)
        assertEquals(1, c.lapses)
    }

    @Test
    fun `une fiche neuve n'est pas en retard, elle est nouvelle`() {
        val c = card("a")
        assertTrue(c.isNew)
        assertFalse(c.isDue(today))
        assertTrue(card("b", box = 2, due = today).isDue(today))
        assertFalse(card("c", box = 2, due = today + 1).isDue(today))
    }

    // ------------------------------------------------------------ séance

    @Test
    fun `la seance commence par les fiches en retard, puis les nouvelles`() {
        val deck = Deck(
            "d", "Histoire",
            listOf(card("neuve1"), card("tard", box = 2, due = today - 3), card("pas-encore", box = 3, due = today + 2), card("aujourdhui", box = 1, due = today), card("neuve2"))
        )
        val session = StudySession.of(deck, today)
        val order = ArrayList<String>()
        while (!session.done) {
            order.add(session.current!!.id)
            session.answer(true)
        }
        assertEquals(listOf("tard", "aujourdhui", "neuve1", "neuve2"), order)
        assertEquals(4, session.known)
    }

    @Test
    fun `les nouvelles sont limitees par seance`() {
        val deck = Deck("d", "Vocabulaire", (1..50).map { card("n$it") })
        assertEquals(StudySession.NEW_PER_SESSION, StudySession.of(deck, today).total)
    }

    @Test
    fun `une fiche ratee revient en fin de seance jusqu'a etre sue`() {
        val deck = Deck("d", "Maths", listOf(card("a"), card("b")))
        val session = StudySession.of(deck, today)
        assertEquals("a", session.current!!.id)
        session.answer(false) // a ratée
        assertEquals("b", session.current!!.id)
        session.answer(true)
        assertEquals("a", session.current!!.id) // a revient
        session.answer(false)
        session.answer(true)
        assertTrue(session.done)
        assertEquals(2, session.again)
        assertEquals(2, session.known)

        val results = session.results().associateBy { it.id }
        // Ratée puis retrouvée : demain, première boîte, une seule chute comptée.
        assertEquals(1, results["a"]!!.box)
        assertEquals(today + 1, results["a"]!!.due)
        assertEquals(1, results["a"]!!.lapses)
        assertEquals(1, results["b"]!!.box)
    }

    @Test
    fun `en sens inverse on voit le verso et on repond le recto`() {
        val session = StudySession.of(Deck("d", "Anglais", listOf(Card("1", "chat", "cat"))), today, reversed = true)
        assertEquals("cat", session.question)
        assertEquals("chat", session.answer)
    }

    @Test
    fun `tout reviser prend tout le paquet, les moins sues d'abord`() {
        val deck = Deck("d", "Bac", listOf(card("su", box = 5, due = today + 10), card("fragile", box = 1, due = today + 1), card("neuve")))
        val session = StudySession.everything(deck, today)
        assertEquals(3, session.total)
        assertEquals("neuve", session.current!!.id)
    }

    @Test
    fun `un paquet sans rien a revoir donne une seance vide`() {
        val deck = Deck("d", "Fini", listOf(card("a", box = 3, due = today + 4)))
        assertTrue(StudySession.of(deck, today).done)
    }

    // ------------------------------------------------------------ jours

    @Test
    fun `le jour change a minuit, heure locale`() {
        val paris = TimeZone.getTimeZone("Europe/Paris")
        fun at(hour: Int, minute: Int) = Calendar.getInstance(paris).apply { clear(); set(2026, Calendar.SEPTEMBER, 28, hour, minute) }.timeInMillis
        assertEquals(Days.of(at(0, 5), paris), Days.of(at(23, 55), paris))
        assertEquals(Days.of(at(23, 55), paris) + 1, Days.of(at(23, 55) + 10 * 60_000L, paris))
    }

    // ------------------------------------------------------------ import

    @Test
    fun `une fiche par ligne, recto et verso separes par deux-points`() {
        val result = CardImport.fromText(
            """
            Révolution française : 1789
            Chute du mur de Berlin : 1989
            - Loi d'Ohm : U = R × I

            Titre sans réponse
            """.trimIndent()
        )
        assertEquals(
            listOf("Révolution française" to "1789", "Chute du mur de Berlin" to "1989", "Loi d'Ohm" to "U = R × I"),
            result.cards
        )
        assertEquals(1, result.skipped)
        assertEquals(":", result.separator)
    }

    @Test
    fun `le separateur le plus repandu l'emporte`() {
        val result = CardImport.fromText("dog ; chien\ncat ; chat : félin\nhouse ; maison")
        assertEquals(listOf("dog" to "chien", "cat" to "chat : félin", "house" to "maison"), result.cards)
        assertEquals(";", result.separator)
    }

    @Test
    fun `tirets, egal et tabulations marchent aussi`() {
        assertEquals(listOf("H2O" to "eau"), CardImport.fromText("H2O = eau").cards)
        assertEquals(listOf("arc-en-ciel" to "rainbow"), CardImport.fromText("arc-en-ciel - rainbow").cards)
        assertEquals(listOf("un" to "one", "deux" to "two"), CardImport.fromText("un\tone\ndeux\ttwo").cards)
        assertEquals(listOf("Paris" to "France"), CardImport.fromText("1. Paris → France").cards)
    }

    @Test
    fun `un texte sans separateur ne donne rien`() {
        val result = CardImport.fromText("Juste une phrase.\nEt une autre.")
        assertTrue(result.cards.isEmpty())
        assertEquals(2, result.skipped)
    }

    @Test
    fun `un document donne ses tableaux et ses lignes`() {
        fun cell(text: String) = TableCell(listOf(TextParagraph(listOf(TextRun(text)))))
        val table = TextTable(
            listOf(
                TableRow(listOf(cell("Mot"), cell("Traduction"))),
                TableRow(listOf(cell("apple"), cell("pomme"))),
                TableRow(listOf(cell("pear"), cell("poire")))
            ),
            headerRow = true
        )
        val document = TextDocument(
            "Anglais",
            listOf(TextParagraph(listOf(TextRun("Vocabulaire"))), table, TextParagraph(listOf(TextRun("grape : raisin"))))
        )
        val result = CardImport.fromDocument(document)
        assertEquals(listOf("apple" to "pomme", "pear" to "poire", "grape" to "raisin"), result.cards)
    }

    @Test
    fun `un tableur donne ses deux premieres colonnes, sans la ligne de titres`() {
        val sheet = Sheet(
            "Vocabulaire",
            mapOf(
                "B1" to "Français", "C1" to "Anglais",
                "B2" to "chien", "C2" to "dog",
                "B3" to "chat", "C3" to "cat",
                "B4" to "oiseau"
            )
        )
        val result = CardImport.fromSheet(sheet)
        assertEquals(listOf("chien" to "dog", "chat" to "cat"), result.cards)
        assertEquals(1, result.skipped)
    }

    @Test
    fun `une premiere ligne ordinaire n'est pas prise pour des titres`() {
        val sheet = Sheet("S", mapOf("A1" to "Paris", "B1" to "France", "A2" to "Rome", "B2" to "Italie"))
        assertEquals(2, CardImport.fromSheet(sheet).cards.size)
    }

    // ------------------------------------------------------------ enregistrement

    private val app get() = RuntimeEnvironment.getApplication()

    @Before
    fun clean() {
        File(app.filesDir, "flashcards.json").delete()
    }

    @Test
    fun `les paquets et leurs progres sont retenus`() {
        val store = FlashcardStore(app)
        val deck = store.createDeck("Géographie")
        assertEquals(2, store.addCards(deck.id, listOf("France" to "Paris", "Italie" to "Rome")))
        val first = store.deck(deck.id)!!.cards.first()
        store.record(deck.id, listOf(Leitner.known(first, today)))

        val reloaded = FlashcardStore(app).deck(deck.id)!!
        assertEquals("Géographie", reloaded.name)
        assertEquals(2, reloaded.cards.size)
        assertEquals(1, reloaded.cards.first { it.id == first.id }.box)
        assertEquals(today + 1, reloaded.cards.first { it.id == first.id }.due)
    }

    @Test
    fun `une fiche deja presente n'est pas ajoutee deux fois`() {
        val store = FlashcardStore(app)
        val deck = store.createDeck("Anglais")
        store.addCards(deck.id, listOf("chat" to "cat"))
        assertEquals(1, store.addCards(deck.id, listOf("Chat" to "CAT", "chien" to "dog", " " to "vide")))
        assertEquals(2, store.deck(deck.id)!!.cards.size)
    }

    @Test
    fun `modifier, supprimer, remettre a zero`() {
        val store = FlashcardStore(app)
        val deck = store.createDeck("Chimie")
        store.addCards(deck.id, listOf("H2O" to "eau", "NaCl" to "sel"))
        val water = store.deck(deck.id)!!.cards.first()
        store.updateCard(deck.id, water.copy(back = "eau (oxyde de dihydrogène)"))
        store.record(deck.id, listOf(Leitner.known(water, today)))
        store.deleteCard(deck.id, store.deck(deck.id)!!.cards.last().id)
        store.reset(deck.id)
        val after = store.deck(deck.id)!!
        assertEquals(1, after.cards.size)
        assertEquals("eau (oxyde de dihydrogène)", after.cards[0].back)
        assertTrue(after.cards[0].isNew)
        store.renameDeck(deck.id, "Chimie organique")
        assertEquals("Chimie organique", FlashcardStore(app).deck(deck.id)!!.name)
        store.deleteDeck(deck.id)
        assertNull(FlashcardStore(app).deck(deck.id))
    }

    @Test
    fun `les compteurs d'un paquet`() {
        val deck = Deck("d", "x", listOf(card("n"), card("due", box = 1, due = today), card("su", box = 5, due = today + 9)))
        assertEquals(1, deck.newCount)
        assertEquals(1, deck.dueCount(today))
        assertEquals(1, deck.masteredCount)
    }
}
