package com.docssuite.compare

/** Ce qu'une version a fait d'un élément. */
enum class Change { KEPT, REMOVED, ADDED }

data class Edit<T>(val change: Change, val value: T)

/**
 * Les différences entre deux suites : le plus petit jeu de suppressions et
 * d'ajouts qui transforme la première en la seconde.
 *
 * Les débuts et fins identiques sont mis de côté avant de chercher : sur deux
 * versions d'un même texte, seule la poignée de paragraphes retouchés passe
 * réellement par le calcul. Au-delà de [MAX_CELLS] combinaisons restantes —
 * deux textes qui n'ont plus rien en commun — on répond « tout remplacé »
 * plutôt que de faire attendre le téléphone.
 */
object Diff {

    /** Table de travail bornée à un mégaoctet. */
    private const val MAX_CELLS = 1_000_000L

    fun <T> of(before: List<T>, after: List<T>): List<Edit<T>> {
        var head = 0
        while (head < before.size && head < after.size && before[head] == after[head]) head++
        var tail = 0
        while (
            tail < before.size - head &&
            tail < after.size - head &&
            before[before.size - 1 - tail] == after[after.size - 1 - tail]
        ) tail++

        val out = ArrayList<Edit<T>>(before.size + after.size)
        for (i in 0 until head) out.add(Edit(Change.KEPT, before[i]))

        val left = before.subList(head, before.size - tail)
        val right = after.subList(head, after.size - tail)
        out.addAll(middle(left, right))

        for (i in before.size - tail until before.size) out.add(Edit(Change.KEPT, before[i]))
        return out
    }

    private fun <T> middle(left: List<T>, right: List<T>): List<Edit<T>> = when {
        left.isEmpty() -> right.map { Edit(Change.ADDED, it) }
        right.isEmpty() -> left.map { Edit(Change.REMOVED, it) }
        left.size.toLong() * right.size > MAX_CELLS ->
            left.map { Edit(Change.REMOVED, it) } + right.map { Edit(Change.ADDED, it) }
        else -> longestCommon(left, right)
    }

    /**
     * Plus longue sous-suite commune. Les longueurs se calculent sur deux
     * lignes seulement ; la direction prise dans chaque case est gardée pour
     * pouvoir remonter le chemin, à un octet la case.
     */
    private fun <T> longestCommon(a: List<T>, b: List<T>): List<Edit<T>> {
        val n = a.size
        val m = b.size
        val direction = ByteArray(n * m)
        var previous = IntArray(m + 1)
        var current = IntArray(m + 1)
        for (i in 1..n) {
            for (j in 1..m) {
                val cell = (i - 1) * m + (j - 1)
                when {
                    a[i - 1] == b[j - 1] -> {
                        current[j] = previous[j - 1] + 1
                        direction[cell] = DIAGONAL
                    }
                    // À égalité on passe à gauche : en remontant le chemin, la
                    // suppression ressort alors avant l'ajout, l'ancien texte
                    // avant le nouveau.
                    previous[j] > current[j - 1] -> {
                        current[j] = previous[j]
                        direction[cell] = UP
                    }
                    else -> {
                        current[j] = current[j - 1]
                        direction[cell] = LEFT
                    }
                }
            }
            val swap = previous
            previous = current
            current = swap
        }

        val reversed = ArrayList<Edit<T>>(n + m)
        var i = n
        var j = m
        while (i > 0 && j > 0) {
            when (direction[(i - 1) * m + (j - 1)]) {
                DIAGONAL -> { reversed.add(Edit(Change.KEPT, a[i - 1])); i--; j-- }
                UP -> { reversed.add(Edit(Change.REMOVED, a[i - 1])); i-- }
                else -> { reversed.add(Edit(Change.ADDED, b[j - 1])); j-- }
            }
        }
        while (i > 0) { reversed.add(Edit(Change.REMOVED, a[i - 1])); i-- }
        while (j > 0) { reversed.add(Edit(Change.ADDED, b[j - 1])); j-- }
        return reversed.asReversed()
    }

    private const val DIAGONAL: Byte = 1
    private const val UP: Byte = 2
    private const val LEFT: Byte = 3
}
