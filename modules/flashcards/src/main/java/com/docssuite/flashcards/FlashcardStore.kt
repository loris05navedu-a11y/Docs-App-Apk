package com.docssuite.flashcards

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Les paquets de fiches, dans un fichier JSON de l'app, réécrit à chaque
 * changement (fichier temporaire puis renommage : jamais à moitié écrit).
 */
class FlashcardStore(context: Context) {

    private val file = File(context.filesDir, "flashcards.json")
    private val decks = ArrayList<Deck>()

    init {
        load()
    }

    private fun load() {
        val root = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return
        val array = root.optJSONArray("decks") ?: return
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val cards = ArrayList<Card>()
            o.optJSONArray("cards")?.let { a ->
                for (j in 0 until a.length()) {
                    val c = a.optJSONObject(j) ?: continue
                    cards.add(
                        Card(
                            id = c.optString("id").ifEmpty { UUID.randomUUID().toString() },
                            front = c.optString("front"),
                            back = c.optString("back"),
                            box = c.optInt("box"),
                            due = c.optLong("due"),
                            reviews = c.optInt("reviews"),
                            lapses = c.optInt("lapses")
                        )
                    )
                }
            }
            decks.add(Deck(o.getString("id"), o.optString("name"), cards, o.optLong("createdAt")))
        }
    }

    private fun persist() {
        val root = JSONObject().put("decks", JSONArray().apply {
            decks.forEach { deck ->
                put(JSONObject().apply {
                    put("id", deck.id)
                    put("name", deck.name)
                    put("createdAt", deck.createdAt)
                    put("cards", JSONArray().apply {
                        deck.cards.forEach { c ->
                            put(JSONObject().apply {
                                put("id", c.id); put("front", c.front); put("back", c.back)
                                put("box", c.box); put("due", c.due); put("reviews", c.reviews); put("lapses", c.lapses)
                            })
                        }
                    })
                })
            }
        })
        val temp = File(file.parentFile, "flashcards.json.tmp")
        temp.writeText(root.toString())
        if (!temp.renameTo(file)) {
            file.delete()
            temp.renameTo(file)
        }
    }

    /** Les paquets, le plus récent d'abord. */
    fun decks(): List<Deck> = decks.sortedByDescending { it.createdAt }

    fun deck(id: String): Deck? = decks.firstOrNull { it.id == id }

    fun createDeck(name: String, now: Long = System.currentTimeMillis()): Deck =
        Deck(UUID.randomUUID().toString(), name.trim().ifBlank { "Nouveau paquet" }, emptyList(), now)
            .also { decks.add(it); persist() }

    fun renameDeck(id: String, name: String) = update(id) { it.copy(name = name.trim().ifBlank { it.name }) }

    fun deleteDeck(id: String) {
        decks.removeAll { it.id == id }
        persist()
    }

    /**
     * Ajoute des fiches, en sautant celles que le paquet contient déjà (même
     * recto et verso, à la casse et aux accents près). Renvoie le nombre ajouté.
     */
    fun addCards(deckId: String, pairs: List<Pair<String, String>>): Int {
        var added = 0
        update(deckId) { deck ->
            val known = deck.cards.map { CardImport.key(it.front, it.back) }.toHashSet()
            val fresh = pairs.mapNotNull { (front, back) ->
                val f = front.trim()
                val b = back.trim()
                if (f.isEmpty() || b.isEmpty() || !known.add(CardImport.key(f, b))) null
                else Card(UUID.randomUUID().toString(), f, b)
            }
            added = fresh.size
            deck.copy(cards = deck.cards + fresh)
        }
        return added
    }

    fun updateCard(deckId: String, card: Card) = update(deckId) { deck ->
        deck.copy(cards = deck.cards.map { if (it.id == card.id) card else it })
    }

    fun deleteCard(deckId: String, cardId: String) = update(deckId) { deck ->
        deck.copy(cards = deck.cards.filterNot { it.id == cardId })
    }

    /**
     * Reporte le résultat d'une séance : seulement la progression. Le texte
     * de la fiche reste celui du paquet, même corrigé entre-temps.
     */
    fun record(deckId: String, results: Collection<Card>) {
        if (results.isEmpty()) return
        val byId = results.associateBy { it.id }
        update(deckId) { deck ->
            deck.copy(cards = deck.cards.map { card ->
                byId[card.id]?.let { card.copy(box = it.box, due = it.due, reviews = it.reviews, lapses = it.lapses) } ?: card
            })
        }
    }

    /** Toutes les fiches remises à zéro, comme jamais révisées. */
    fun reset(deckId: String) = update(deckId) { deck ->
        deck.copy(cards = deck.cards.map { it.copy(box = 0, due = 0, reviews = 0, lapses = 0) })
    }

    private fun update(id: String, change: (Deck) -> Deck) {
        val index = decks.indexOfFirst { it.id == id }
        if (index < 0) return
        decks[index] = change(decks[index])
        persist()
    }
}
