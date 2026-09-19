package com.docssuite.fileformats

/** Conversions entre référence A1 et coordonnées, sans dépendre du module core. */
object CellRef {

    fun columnLabel(index: Int): String {
        var n = index
        val sb = StringBuilder()
        while (n >= 0) {
            sb.insert(0, ('A' + n % 26))
            n = n / 26 - 1
        }
        return sb.toString()
    }

    fun columnIndex(label: String): Int {
        var value = 0
        for (ch in label.uppercase()) {
            if (ch !in 'A'..'Z') continue
            value = value * 26 + (ch - 'A' + 1)
        }
        return value - 1
    }

    fun key(row: Int, column: Int): String = "${columnLabel(column)}${row + 1}"

    /** `"B7"` → `(6, 1)`, ou `null` si la référence est mal formée. */
    fun parse(ref: String): Pair<Int, Int>? {
        val clean = ref.replace("$", "").uppercase()
        val letters = clean.takeWhile { it in 'A'..'Z' }
        val digits = clean.drop(letters.length)
        if (letters.isEmpty() || digits.isEmpty()) return null
        val row = digits.toIntOrNull() ?: return null
        if (row < 1) return null
        return (row - 1) to columnIndex(letters)
    }
}

/**
 * Les noms de fonctions se stockent en anglais dans les fichiers ; c'est la
 * suite bureautique qui les affiche traduits. Sans cette conversion, une
 * `=SOMME(A1:A5)` exportée arrive cassée dans Excel.
 */
private val frenchToEnglish = mapOf(
    "SOMME" to "SUM",
    "MOYENNE" to "AVERAGE",
    "NB" to "COUNT",
    "PRODUIT" to "PRODUCT",
    "MEDIANE" to "MEDIAN",
    "ECARTYPE" to "STDEV",
    "ARRONDI" to "ROUND",
    "ENT" to "INT",
    "RACINE" to "SQRT",
    "PUISSANCE" to "POWER",
    "SI" to "IF",
    "VRAI" to "TRUE",
    "FAUX" to "FALSE"
)

private val englishToFrench = frenchToEnglish.entries.associate { (fr, en) -> en to fr }

private val identifier = Regex("[A-Za-zÀ-ÿ_][A-Za-zÀ-ÿ0-9_]*")

private fun translate(formula: String, table: Map<String, String>): String =
    identifier.replace(formula) { match ->
        // Un identifiant suivi d'une parenthèse est un appel de fonction ;
        // sinon c'est une référence de cellule, qu'il ne faut pas toucher.
        val after = formula.getOrNull(match.range.last + 1)
        val upper = match.value.uppercase()
        val replacement = table[upper]
        when {
            replacement == null -> match.value
            after == '(' || upper == "VRAI" || upper == "FAUX" ||
                upper == "TRUE" || upper == "FALSE" -> replacement
            else -> match.value
        }
    }

/** Formule de l'app (français accepté) → formule de fichier (anglais). */
fun formulaToEnglish(formula: String): String = translate(formula, frenchToEnglish)

/** Formule lue dans un fichier → formule affichée dans l'app (français). */
fun formulaToFrench(formula: String): String = translate(formula, englishToFrench)
