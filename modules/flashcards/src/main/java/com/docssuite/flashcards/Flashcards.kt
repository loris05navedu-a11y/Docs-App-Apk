package com.docssuite.flashcards

import com.docssuite.core.DocumentSearch
import com.docssuite.core.FormulaEngine
import com.docssuite.fileformats.CellRef
import com.docssuite.fileformats.Sheet
import com.docssuite.fileformats.TextDocument
import com.docssuite.fileformats.TextParagraph
import com.docssuite.fileformats.TextTable
import java.util.Calendar
import java.util.TimeZone

/**
 * Une fiche : une question (recto), sa réponse (verso), et où elle en est.
 * [box] : 0 pour une fiche jamais révisée, puis 1 à [Leitner.TOP] ;
 * [due] : le jour (compté depuis 1970) où la revoir.
 */
data class Card(
    val id: String,
    val front: String,
    val back: String,
    val box: Int = 0,
    val due: Long = 0,
    val reviews: Int = 0,
    val lapses: Int = 0
) {
    val isNew: Boolean get() = reviews == 0
    val mastered: Boolean get() = box >= Leitner.MASTERED
    fun isDue(today: Long): Boolean = !isNew && due <= today
}

data class Deck(
    val id: String,
    val name: String,
    val cards: List<Card> = emptyList(),
    val createdAt: Long = 0
) {
    fun dueCount(today: Long): Int = cards.count { it.isDue(today) }
    val newCount: Int get() = cards.count { it.isNew }
    val masteredCount: Int get() = cards.count { it.mastered }
}

/** Les jours comptés depuis 1970, à l'heure locale : minuit tombe au bon moment. */
object Days {
    fun of(millis: Long, zone: TimeZone = TimeZone.getDefault()): Long =
        Math.floorDiv(millis + zone.getOffset(millis), 86_400_000L)

    fun today(): Long = of(System.currentTimeMillis())

    fun calendarDay(year: Int, month: Int, day: Int): Long {
        val c = Calendar.getInstance().apply { clear(); set(year, month, day, 12, 0) }
        return of(c.timeInMillis)
    }
}

/**
 * Les boîtes de Leitner : chaque bonne réponse fait monter la fiche d'une
 * boîte et espace la révision suivante (1, 2, 4, 8, 16 puis 32 jours) ;
 * une erreur la renvoie dans la première. On revoit donc souvent ce qu'on
 * ne sait pas, rarement ce qu'on sait.
 */
object Leitner {
    const val TOP = 6
    const val MASTERED = 5
    private val intervals = intArrayOf(1, 2, 4, 8, 16, 32)

    fun interval(box: Int): Int = intervals[(box - 1).coerceIn(0, intervals.size - 1)]

    fun known(card: Card, today: Long): Card {
        val box = (card.box + 1).coerceAtMost(TOP)
        return card.copy(box = box, due = today + interval(box), reviews = card.reviews + 1)
    }

    /** Oubliée : première boîte, à revoir aujourd'hui même. */
    fun forgotten(card: Card, today: Long): Card =
        card.copy(box = 1, due = today, reviews = card.reviews + 1, lapses = card.lapses + 1)

    /** Retrouvée dans la séance où on l'avait oubliée : demain, sans sauter d'étape. */
    fun relearned(card: Card, today: Long): Card = card.copy(box = 1, due = today + interval(1))
}

/**
 * Une séance de révision : les fiches à revoir d'abord (les plus en retard
 * en tête), puis au plus [newLimit] nouvelles. Une fiche ratée revient en
 * fin de séance jusqu'à ce qu'elle soit sue.
 */
class StudySession private constructor(
    cards: List<Card>,
    private val today: Long,
    /** Montrer le verso et demander le recto : utile pour le vocabulaire. */
    val reversed: Boolean
) {
    private val queue = cards.toMutableList()
    private val failed = HashSet<String>()
    private val updated = LinkedHashMap<String, Card>()

    val total: Int = cards.size
    var known = 0
        private set
    var again = 0
        private set

    /** Fiches distinctes déjà sues dans cette séance. */
    val finished: Int get() = total - queue.map { it.id }.distinct().size

    val current: Card? get() = queue.firstOrNull()
    val done: Boolean get() = queue.isEmpty()

    val question: String get() = current?.let { if (reversed) it.back else it.front }.orEmpty()
    val answer: String get() = current?.let { if (reversed) it.front else it.back }.orEmpty()

    fun answer(knew: Boolean) {
        if (queue.isEmpty()) return
        val card = queue.removeAt(0)
        if (knew) {
            known++
            val next = if (card.id in failed) Leitner.relearned(card, today) else Leitner.known(card, today)
            updated[card.id] = next
        } else {
            again++
            // Une seule chute compte, même si la fiche est ratée plusieurs fois.
            val next = if (card.id in failed) card else Leitner.forgotten(card, today)
            failed.add(card.id)
            updated[card.id] = next
            queue.add(next)
        }
    }

    /** Les fiches révisées, à reporter dans le paquet. */
    fun results(): Collection<Card> = updated.values

    companion object {
        const val NEW_PER_SESSION = 20

        fun of(deck: Deck, today: Long, reversed: Boolean = false, newLimit: Int = NEW_PER_SESSION): StudySession {
            val due = deck.cards.filter { it.isDue(today) }.sortedWith(compareBy({ it.due }, { it.box }))
            val fresh = deck.cards.filter { it.isNew }.take(newLimit)
            return StudySession(due + fresh, today, reversed)
        }

        /** Tout le paquet, les fiches les moins sues d'abord : pour la veille d'un contrôle. */
        fun everything(deck: Deck, today: Long, reversed: Boolean = false): StudySession =
            StudySession(deck.cards.sortedBy { if (it.isNew) 0 else it.box }, today, reversed)
    }
}

/** Des fiches tirées d'un texte, d'un document ou d'un tableur. */
object CardImport {

    data class Result(val cards: List<Pair<String, String>>, val skipped: Int, val separator: String? = null)

    /** Du plus sûr au plus ambigu : une tabulation ne se trouve pas par hasard dans une phrase. */
    private val separators = listOf("\t", " ; ", ";", " → ", " -> ", " = ", " — ", " – ", " : ", ": ", " - ")

    private val bullet = Regex("^\\s*(?:[-•*·▪◦]|\\d{1,3}[.)])\\s+")

    /**
     * Une fiche par ligne, recto et verso séparés par « : », « ; », « = »,
     * un tiret ou une tabulation — le séparateur le plus répandu dans le
     * texte l'emporte. Le verso peut contenir le séparateur : seule la
     * première occurrence coupe (« Loi d'Ohm : U = R × I »).
     */
    fun fromText(text: String): Result {
        val lines = text.lines().map { it.replace(bullet, "").trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return Result(emptyList(), 0)
        val separator = separators.maxByOrNull { sep -> lines.count { line -> split(line, sep) != null } }
            ?.takeIf { sep -> lines.any { split(it, sep) != null } }
            ?: return Result(emptyList(), lines.size)
        val cards = lines.mapNotNull { split(it, separator) }
        return Result(cards, lines.size - cards.size, separator.trim().ifEmpty { "tabulation" })
    }

    private fun split(line: String, separator: String): Pair<String, String>? {
        val at = line.indexOf(separator)
        if (at <= 0) return null
        val front = clean(line.substring(0, at))
        val back = clean(line.substring(at + separator.length))
        return if (front.isEmpty() || back.isEmpty()) null else front to back
    }

    /** Les tableaux à deux colonnes, puis les lignes de texte. */
    fun fromDocument(document: TextDocument): Result {
        val cards = ArrayList<Pair<String, String>>()
        var skipped = 0
        val lines = StringBuilder()
        document.blocks.forEach { block ->
            when (block) {
                is TextParagraph -> lines.append(block.plainText).append('\n')
                is TextTable -> block.rows.forEachIndexed { index, row ->
                    if (index == 0 && block.headerRow) return@forEachIndexed
                    val cells = row.cells.map { clean(it.plainText) }.filter { it.isNotEmpty() }
                    if (cells.size >= 2) cards.add(cells[0] to cells[1]) else if (cells.isNotEmpty()) skipped++
                }
            }
        }
        val text = fromText(lines.toString())
        return Result(cards + text.cards, skipped + text.skipped, text.separator)
    }

    /**
     * Les deux premières colonnes remplies d'une feuille. Une première ligne
     * de titres (« Français | Anglais », « Question | Réponse »…) est sautée.
     */
    fun fromSheet(sheet: Sheet): Result {
        val grid = HashMap<Int, HashMap<Int, String>>()
        sheet.cells.forEach { (ref, raw) ->
            if (raw.isBlank()) return@forEach
            val (row, column) = CellRef.parse(ref) ?: return@forEach
            val shown = if (raw.trimStart().startsWith("=")) FormulaEngine.displayValue(ref, sheet.cells) else raw
            if (shown.isNotBlank()) grid.getOrPut(row) { HashMap() }[column] = clean(shown)
        }
        val columns = grid.values.flatMap { it.keys }.distinct().sorted().take(2)
        if (columns.size < 2) return Result(emptyList(), grid.size)
        val rows = grid.keys.sorted()
        var skipped = 0
        val cards = rows.mapIndexedNotNull { index, row ->
            val front = grid[row]?.get(columns[0]).orEmpty()
            val back = grid[row]?.get(columns[1]).orEmpty()
            when {
                index == 0 && looksLikeHeader(front) && looksLikeHeader(back) -> null
                front.isEmpty() || back.isEmpty() -> { skipped++; null }
                else -> front to back
            }
        }
        return Result(cards, skipped)
    }

    private val headerWords = setOf(
        "question", "questions", "reponse", "reponses", "recto", "verso", "mot", "mots", "terme", "termes",
        "definition", "definitions", "traduction", "francais", "anglais", "espagnol", "allemand", "italien",
        "date", "evenement", "notion", "sens", "front", "back", "word", "translation"
    )

    private fun looksLikeHeader(text: String): Boolean = DocumentSearch.fold(text).text.trim() in headerWords

    private fun clean(text: String): String = text.replace(Regex("\\s+"), " ").trim()

    /** Même fiche, à la casse et aux accents près : on ne l'importe pas deux fois. */
    fun key(front: String, back: String): String =
        DocumentSearch.fold(clean(front)).text + "\u0000" + DocumentSearch.fold(clean(back)).text
}
