package com.docssuite.core

import java.util.Locale

/**
 * Valeur manipulée par le moteur de formules.
 *
 * Le tableur ne calculait que des nombres. Les fonctions de texte, les
 * recherches et les dates réclament de distinguer un nombre d'un libellé, et
 * une case vide d'un zéro : d'où ce type, qui porte aussi les erreurs pour
 * qu'elles se propagent au lieu d'être confondues avec un résultat.
 */
sealed interface Value {
    data class Num(val v: Double) : Value
    data class Txt(val s: String) : Value
    data class Bool(val b: Boolean) : Value
    data class Err(val code: String) : Value
    data object Blank : Value

    companion object {
        val VALUE = Err("#VALEUR!")
        val DIV0 = Err("#DIV/0!")
        val NA = Err("#N/A")
        val NAME = Err("#NOM?")
        val NUM = Err("#NOMBRE!")
        val REF = Err("#REF!")
        val CYCLE = Err("#CYCLE")

        val TRUE = Bool(true)
        val FALSE = Bool(false)

        fun of(b: Boolean) = if (b) TRUE else FALSE

        fun of(v: Double): Value = if (v.isNaN() || v.isInfinite()) NUM else Num(v)

        /** Lit une saisie brute de cellule : nombre, booléen, sinon texte. */
        fun literal(raw: String): Value {
            if (raw.isEmpty()) return Blank
            val compact = raw.replace(" ", "").replace(',', '.')
            val number = compact.toDoubleOrNull()
            if (number != null) return Num(number)
            return when (raw.trim().uppercase(Locale.ROOT)) {
                "VRAI", "TRUE" -> TRUE
                "FAUX", "FALSE" -> FALSE
                else -> Txt(raw)
            }
        }
    }
}

val Value.isError: Boolean get() = this is Value.Err

/** Nombre correspondant, ou `null` si la valeur n'en représente pas un. */
fun Value.asNumber(): Double? = when (this) {
    is Value.Num -> v
    is Value.Bool -> if (b) 1.0 else 0.0
    is Value.Blank -> 0.0
    is Value.Txt -> s.replace(" ", "").replace(',', '.').toDoubleOrNull()
    is Value.Err -> null
}

/** Nombre seulement si la valeur en est vraiment un : un texte ne compte pas. */
fun Value.strictNumber(): Double? = when (this) {
    is Value.Num -> v
    is Value.Bool -> if (b) 1.0 else 0.0
    else -> null
}

fun Value.asText(): String = when (this) {
    is Value.Num -> FormulaEngine.formatNumber(v)
    is Value.Txt -> s
    is Value.Bool -> if (b) "VRAI" else "FAUX"
    is Value.Err -> code
    is Value.Blank -> ""
}

fun Value.asBoolean(): Boolean = when (this) {
    is Value.Bool -> b
    is Value.Num -> v != 0.0
    is Value.Txt -> s.trim().uppercase(Locale.ROOT) in setOf("VRAI", "TRUE")
    else -> false
}
