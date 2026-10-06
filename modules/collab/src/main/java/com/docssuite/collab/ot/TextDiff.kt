package com.docssuite.collab.ot

/**
 * D'un texte à l'autre après une frappe : l'opération qui fait passer de
 * [before] à [after].
 *
 * Une frappe ne change qu'un endroit : on garde le début et la fin communs,
 * et ce qui est entre les deux est remplacé. [caret], la position du curseur
 * après la frappe, lève l'ambiguïté des lettres répétées : taper un « a » au
 * milieu de « aa » insère bien au milieu, pas à la fin — même résultat à
 * l'écran, mais pas pour les autres, qui tapent peut-être juste à côté.
 *
 * Une coupure ne tombe jamais au milieu d'un émoji (paire de substitution
 * UTF-16) : une moitié d'émoji envoyée seule serait abîmée en chemin.
 */
object TextDiff {

    fun between(before: String, after: String, caret: Int = after.length): TextOperation {
        if (before == after) return TextOperation.identity(before.length)
        val shortest = minOf(before.length, after.length)

        var prefix = 0
        while (prefix < shortest && before[prefix] == after[prefix]) prefix++
        // Le texte inséré se termine au curseur : le début commun ne peut pas
        // dépasser l'endroit où la frappe a commencé.
        val growth = after.length - before.length
        val start = caret - maxOf(0, growth)
        if (start in 0 until prefix) prefix = start
        if (prefix > 0 && Character.isHighSurrogate(before[prefix - 1])) prefix--

        var suffix = 0
        while (suffix < shortest - prefix && before[before.length - 1 - suffix] == after[after.length - 1 - suffix]) suffix++
        if (suffix > 0 && Character.isLowSurrogate(before[before.length - suffix])) suffix--

        return TextOperation()
            .retain(prefix)
            .delete(before.length - prefix - suffix)
            .insert(after.substring(prefix, after.length - suffix))
            .retain(suffix)
    }
}
