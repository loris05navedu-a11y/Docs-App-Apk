package com.docssuite.core

import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sqrt

private class CycleException : RuntimeException()

/**
 * Moteur de formules du tableur : arithmétique, références de cellules (A1),
 * plages (A1:B5), comparaisons et fonctions nommées (français + anglais).
 */
object FormulaEngine {

    val FUNCTIONS = listOf(
        "SOMME", "MOYENNE", "MIN", "MAX", "NB", "PRODUIT", "MEDIANE",
        "QUARTILE", "ECARTYPE", "ABS", "ARRONDI", "RACINE", "PUISSANCE",
        "SI", "ENT", "MOD", "LOG", "EXP"
    )

    /**
     * Évalue une expression libre (calculatrice). Les références de cellules
     * sont résolues si [cells] est fourni. Renvoie null si l'expression est invalide.
     */
    fun evaluateExpression(expression: String, cells: Map<String, String> = emptyMap()): Double? {
        val cleaned = expression.trim().removePrefix("=").trim()
        if (cleaned.isEmpty()) return null
        return try {
            Parser(cleaned, cells, HashSet()).parse().takeIf { it.isFinite() }
        } catch (e: Exception) {
            null
        }
    }

    /** Valeur affichée d'une cellule (évalue la formule si elle commence par "="). */
    fun displayValue(ref: String, cells: Map<String, String>): String {
        val raw = cells[ref] ?: return ""
        if (!raw.startsWith("=")) return raw
        return try {
            formatNumber(Parser(raw.substring(1), cells, hashSetOf(ref)).parse())
        } catch (e: CycleException) {
            "#CYCLE"
        } catch (e: Exception) {
            "#ERREUR"
        }
    }

    internal fun numberAt(ref: String, cells: Map<String, String>, visiting: MutableSet<String>): Double {
        if (!visiting.add(ref)) throw CycleException()
        try {
            val raw = cells[ref]?.trim() ?: return 0.0
            if (raw.isEmpty()) return 0.0
            return if (raw.startsWith("=")) {
                Parser(raw.substring(1), cells, visiting).parse()
            } else {
                raw.replace(" ", "").replace(',', '.').toDoubleOrNull() ?: 0.0
            }
        } finally {
            visiting.remove(ref)
        }
    }

    fun formatNumber(v: Double): String {
        if (v.isNaN() || v.isInfinite()) return "#ERREUR"
        if (abs(v - v.roundToLong()) < 1e-9) return v.roundToLong().toString()
        return String.format(Locale.US, "%.4f", v).trimEnd('0').trimEnd('.')
    }

    /** 0 -> A, 25 -> Z, 26 -> AA … */
    fun columnLabel(index: Int): String {
        var i = index
        val sb = StringBuilder()
        while (true) {
            sb.insert(0, 'A' + (i % 26))
            i = i / 26 - 1
            if (i < 0) break
        }
        return sb.toString()
    }

    fun columnIndex(label: String): Int {
        var n = 0
        for (c in label.uppercase()) {
            if (!c.isLetter()) break
            n = n * 26 + (c - 'A' + 1)
        }
        return n - 1
    }

    fun cellKey(row: Int, col: Int): String = "${columnLabel(col)}${row + 1}"

    fun isCellRef(text: String): Boolean {
        val letters = text.takeWhile { it.isLetter() }
        val digits = text.dropWhile { it.isLetter() }
        return letters.isNotEmpty() && digits.isNotEmpty() && digits.all { it.isDigit() }
    }

    /** "A1:B3" -> liste des cellules, ligne par ligne. */
    fun expandRange(range: String): List<String> {
        val parts = range.uppercase(Locale.ROOT).replace(" ", "").split(":")
        if (parts.any { !isCellRef(it) }) return emptyList()
        if (parts.size == 1) return listOf(parts[0])
        if (parts.size != 2) return emptyList()

        val firstColumn = columnIndex(parts[0].takeWhile { it.isLetter() })
        val firstRow = parts[0].dropWhile { it.isLetter() }.toInt() - 1
        val lastColumn = columnIndex(parts[1].takeWhile { it.isLetter() })
        val lastRow = parts[1].dropWhile { it.isLetter() }.toInt() - 1

        val out = ArrayList<String>()
        for (r in minOf(firstRow, lastRow)..maxOf(firstRow, lastRow)) {
            for (c in minOf(firstColumn, lastColumn)..maxOf(firstColumn, lastColumn)) {
                out.add(cellKey(r, c))
            }
        }
        return out
    }

    /** Somme/moyenne/compte rapides sur une liste de cellules (barre de statut). */
    fun quickStats(refs: List<String>, cells: Map<String, String>): Triple<Double, Double, Int> {
        val numbers = refs.mapNotNull { ref ->
            val shown = displayValue(ref, cells)
            if (shown.isBlank() || shown.startsWith("#")) null
            else shown.replace(" ", "").replace(',', '.').toDoubleOrNull()
        }
        val sum = numbers.sum()
        val avg = if (numbers.isEmpty()) 0.0 else sum / numbers.size
        return Triple(sum, avg, numbers.size)
    }
}

private class Parser(
    private val src: String,
    private val cells: Map<String, String>,
    private val visiting: MutableSet<String>
) {
    private var pos = 0

    fun parse(): Double {
        val v = parseComparison()
        skipWs()
        if (pos < src.length) throw IllegalArgumentException("Caractère inattendu")
        return v
    }

    private fun skipWs() {
        while (pos < src.length && src[pos].isWhitespace()) pos++
    }

    private fun parseComparison(): Double {
        val left = parseExpr()
        skipWs()
        for (op in listOf("<>", ">=", "<=", ">", "<", "=")) {
            if (src.startsWith(op, pos)) {
                pos += op.length
                val right = parseExpr()
                val result = when (op) {
                    "<>" -> abs(left - right) >= 1e-9
                    ">=" -> left >= right
                    "<=" -> left <= right
                    ">" -> left > right
                    "<" -> left < right
                    else -> abs(left - right) < 1e-9
                }
                return if (result) 1.0 else 0.0
            }
        }
        return left
    }

    private fun parseExpr(): Double {
        var v = parseTerm()
        while (true) {
            skipWs()
            if (pos < src.length && (src[pos] == '+' || src[pos] == '-')) {
                val op = src[pos]; pos++
                val r = parseTerm()
                v = if (op == '+') v + r else v - r
            } else return v
        }
    }

    private fun parseTerm(): Double {
        var v = parsePower()
        while (true) {
            skipWs()
            if (pos < src.length && (src[pos] == '*' || src[pos] == '/')) {
                val op = src[pos]; pos++
                val r = parsePower()
                if (op == '/' && r == 0.0) throw ArithmeticException("Division par zéro")
                v = if (op == '*') v * r else v / r
            } else return v
        }
    }

    private fun parsePower(): Double {
        val base = parseUnary()
        skipWs()
        if (pos < src.length && src[pos] == '^') {
            pos++
            return base.pow(parsePower())
        }
        return base
    }

    private fun parseUnary(): Double {
        skipWs()
        if (pos < src.length && src[pos] == '-') { pos++; return -parseUnary() }
        if (pos < src.length && src[pos] == '+') { pos++; return parseUnary() }
        return parsePrimary()
    }

    private fun parsePrimary(): Double {
        skipWs()
        if (pos >= src.length) throw IllegalArgumentException("Formule incomplète")
        val c = src[pos]
        if (c == '(') {
            pos++
            val v = parseComparison()
            skipWs()
            if (pos < src.length && src[pos] == ')') pos++ else throw IllegalArgumentException("')' manquante")
            return v
        }
        if (c.isDigit() || c == '.') return parseNumber()
        if (c.isLetter()) return parseNameOrRef()
        throw IllegalArgumentException("Caractère '$c'")
    }

    private fun parseNumber(): Double {
        val start = pos
        while (pos < src.length && (src[pos].isDigit() || src[pos] == '.')) pos++
        return src.substring(start, pos).toDouble()
    }

    private fun parseNameOrRef(): Double {
        val start = pos
        while (pos < src.length && (src[pos].isLetterOrDigit() || src[pos] == '_')) pos++
        val name = src.substring(start, pos)
        skipWs()
        if (pos < src.length && src[pos] == '(') {
            pos++
            val args = ArrayList<Double>()
            skipWs()
            if (pos < src.length && src[pos] == ')') {
                pos++
            } else {
                while (true) {
                    args.addAll(parseArgument())
                    skipWs()
                    if (pos < src.length && (src[pos] == ';' || src[pos] == ',')) { pos++; continue }
                    if (pos < src.length && src[pos] == ')') { pos++; break }
                    throw IllegalArgumentException("Arguments invalides")
                }
            }
            return applyFunction(name.uppercase(Locale.ROOT), args)
        }
        return constantOrRef(name)
    }

    /** Un argument peut être une plage A1:B3, sinon c'est une expression. */
    private fun parseArgument(): List<Double> {
        val save = pos
        skipWs()
        val start = pos
        if (pos < src.length && src[pos].isLetter()) {
            while (pos < src.length && src[pos].isLetter()) pos++
            val colEnd = pos
            while (pos < src.length && src[pos].isDigit()) pos++
            val refEnd = pos
            if (colEnd < refEnd) {
                skipWs()
                if (pos < src.length && src[pos] == ':') {
                    pos++
                    skipWs()
                    val start2 = pos
                    while (pos < src.length && src[pos].isLetter()) pos++
                    val colEnd2 = pos
                    while (pos < src.length && src[pos].isDigit()) pos++
                    if (colEnd2 < pos) {
                        return rangeValues(src.substring(start, refEnd), src.substring(start2, pos))
                    }
                }
            }
        }
        pos = save
        return listOf(parseComparison())
    }

    private fun rangeValues(from: String, to: String): List<Double> {
        val c1 = FormulaEngine.columnIndex(from.takeWhile { it.isLetter() })
        val r1 = from.dropWhile { it.isLetter() }.toInt() - 1
        val c2 = FormulaEngine.columnIndex(to.takeWhile { it.isLetter() })
        val r2 = to.dropWhile { it.isLetter() }.toInt() - 1
        val out = ArrayList<Double>()
        for (r in minOf(r1, r2)..maxOf(r1, r2)) {
            for (c in minOf(c1, c2)..maxOf(c1, c2)) {
                out.add(FormulaEngine.numberAt(FormulaEngine.cellKey(r, c), cells, visiting))
            }
        }
        return out
    }

    private fun constantOrRef(name: String): Double {
        when (name.uppercase(Locale.ROOT)) {
            "PI" -> return Math.PI
            "E" -> return Math.E
            "VRAI", "TRUE" -> return 1.0
            "FAUX", "FALSE" -> return 0.0
        }
        val letters = name.takeWhile { it.isLetter() }
        val digits = name.dropWhile { it.isLetter() }
        if (letters.isEmpty() || digits.isEmpty() || !digits.all { it.isDigit() }) {
            throw IllegalArgumentException("Référence invalide : $name")
        }
        return FormulaEngine.numberAt(name.uppercase(Locale.ROOT), cells, visiting)
    }

    private fun applyFunction(name: String, args: List<Double>): Double = when (name) {
        "SOMME", "SUM" -> args.sum()
        "MOYENNE", "AVERAGE", "AVG" -> if (args.isEmpty()) 0.0 else args.sum() / args.size
        "MIN" -> args.minOrNull() ?: 0.0
        "MAX" -> args.maxOrNull() ?: 0.0
        "NB", "COUNT" -> args.size.toDouble()
        "PRODUIT", "PRODUCT" -> args.fold(1.0) { a, b -> a * b }
        "MEDIANE", "MEDIAN" -> {
            if (args.isEmpty()) 0.0 else {
                val s = args.sorted()
                if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
            }
        }
        "QUARTILE" -> {
            // Même ordre d'arguments qu'Excel : QUARTILE(plage; n) avec n de 0 à 4.
            val rank = args.last().toInt().coerceIn(0, 4)
            quantile(args.dropLast(1).sorted(), rank / 4.0)
        }
        "ECARTYPE", "STDEV" -> {
            if (args.size < 2) 0.0 else {
                val mean = args.sum() / args.size
                sqrt(args.sumOf { (it - mean) * (it - mean) } / (args.size - 1))
            }
        }
        "ABS" -> abs(args.first())
        "ARRONDI", "ROUND" -> {
            val decimals = if (args.size > 1) args[1].toInt() else 0
            val factor = 10.0.pow(decimals)
            (args.first() * factor).roundToLong() / factor
        }
        "ENT", "INT" -> kotlin.math.floor(args.first())
        "RACINE", "SQRT" -> sqrt(args.first())
        "PUISSANCE", "POW", "POWER" -> args[0].pow(args[1])
        "MOD" -> args[0] % args[1]
        "LOG" -> if (args.size > 1) ln(args[0]) / ln(args[1]) else log10(args[0])
        "EXP" -> Math.E.pow(args.first())
        "SI", "IF" -> if (args[0] != 0.0) args[1] else args.getOrElse(2) { 0.0 }
        else -> throw IllegalArgumentException("Fonction inconnue : $name")
    }
}
