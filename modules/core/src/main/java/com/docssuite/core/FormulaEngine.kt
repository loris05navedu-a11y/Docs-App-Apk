package com.docssuite.core

import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToLong

internal class CycleException : RuntimeException()

/** Groupe de fonctions présenté dans l'aide du tableur. */
data class FunctionGroup(val title: String, val entries: List<FunctionHelp>)

data class FunctionHelp(val signature: String, val description: String) {
    /** Nom seul, pour insérer la fonction dans la barre de formule. */
    val name: String get() = signature.substringBefore('(')
}

/**
 * Moteur de formules du tableur : arithmétique, références (A1), plages
 * (A1:B5), texte entre guillemets, comparaisons et fonctions nommées, en
 * français comme en anglais.
 */
object FormulaEngine {

    /** Aide affichée dans l'assistant de formules. */
    val CATALOG: List<FunctionGroup> = FormulaFunctions.CATALOG

    /** Noms proposés en saisie rapide. */
    val FUNCTIONS: List<String> = CATALOG.flatMap { group -> group.entries.map { it.name } }

    /**
     * Évalue une expression libre (calculatrice). Renvoie `null` si le résultat
     * n'est pas un nombre exploitable.
     */
    fun evaluateExpression(expression: String, cells: Map<String, String> = emptyMap()): Double? {
        val value = evaluate(expression, cells) ?: return null
        return value.strictNumber()?.takeIf { it.isFinite() }
    }

    /** Évalue une expression et rend la valeur typée, ou `null` si elle est invalide. */
    fun evaluate(expression: String, cells: Map<String, String> = emptyMap()): Value? {
        val cleaned = expression.trim().removePrefix("=").trim()
        if (cleaned.isEmpty()) return null
        return try {
            Parser(cleaned, cells, HashSet()).parseAll()
        } catch (cycle: CycleException) {
            Value.CYCLE
        } catch (error: Exception) {
            null
        }
    }

    /** Valeur affichée d'une cellule ; évalue la formule si elle commence par `=`. */
    fun displayValue(ref: String, cells: Map<String, String>): String {
        val raw = cells[ref] ?: return ""
        if (!raw.startsWith("=")) return raw
        return try {
            Parser(raw.substring(1), cells, hashSetOf(ref), ref.substringBefore('!', "").ifEmpty { null })
                .parseAll().asText()
        } catch (cycle: CycleException) {
            "#CYCLE"
        } catch (error: Exception) {
            "#ERREUR"
        }
    }

    /** Valeur typée d'une cellule, utilisée par les fonctions et les graphiques. */
    internal fun valueAt(
        ref: String,
        cells: Map<String, String>,
        visiting: MutableSet<String>
    ): Value {
        if (!visiting.add(ref)) throw CycleException()
        try {
            val raw = cells[ref]?.trim() ?: return Value.Blank
            if (raw.isEmpty()) return Value.Blank
            // Une cellule d'une autre feuille calcule ses propres références
            // dans sa feuille : « =A1 » sur Feuille2 désigne Feuille2!A1.
            val sheet = ref.substringBefore('!', "").ifEmpty { null }
            return if (raw.startsWith("=")) {
                try {
                    Parser(raw.substring(1), cells, visiting, sheet).parseAll()
                } catch (cycle: CycleException) {
                    throw cycle
                } catch (error: Exception) {
                    Value.VALUE
                }
            } else {
                Value.literal(raw)
            }
        } finally {
            visiting.remove(ref)
        }
    }

    internal fun numberAt(
        ref: String,
        cells: Map<String, String>,
        visiting: MutableSet<String>
    ): Double = valueAt(ref, cells, visiting).asNumber() ?: 0.0

    fun formatNumber(v: Double): String {
        if (v.isNaN() || v.isInfinite()) return "#ERREUR"
        if (abs(v) < 1e15 && abs(v - v.roundToLong()) < 1e-9) return v.roundToLong().toString()
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
        for (c in label.uppercase(Locale.ROOT)) {
            if (!c.isLetter()) break
            n = n * 26 + (c - 'A' + 1)
        }
        return n - 1
    }

    fun cellKey(row: Int, col: Int): String = "${columnLabel(col)}${row + 1}"

    /**
     * Clé d'une cellule d'une autre feuille, telle qu'on la range dans la
     * table des cellules : nom de feuille en majuscules, « ! », référence.
     */
    fun qualifiedKey(sheet: String, ref: String): String =
        "${sheet.uppercase(Locale.ROOT)}!${ref.uppercase(Locale.ROOT)}"

    /**
     * Retire les `$` des références absolues (`$A$1`) hors des textes entre
     * guillemets : l'app ne recopie pas les formules par glissement, la
     * distinction n'a donc pas d'effet sur le calcul.
     */
    fun stripAbsolute(formula: String): String {
        if (!formula.contains('$')) return formula
        val sb = StringBuilder(formula.length)
        var quoted = false
        formula.forEach { c ->
            if (c == '"') quoted = !quoted
            if (c != '$' || quoted) sb.append(c)
        }
        return sb.toString()
    }

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

/**
 * Argument d'une fonction. Une plage garde sa forme rectangulaire : sans elle,
 * ni RECHERCHEV ni INDEX ne sauraient où chercher.
 */
internal sealed interface Arg {
    data class One(val value: Value) : Arg
    data class Range(val grid: List<List<Value>>) : Arg
}

internal fun List<Arg>.values(): List<Value> = flatMap { arg ->
    when (arg) {
        is Arg.One -> listOf(arg.value)
        is Arg.Range -> arg.grid.flatten()
    }
}

/** Nombres au sens d'Excel : les textes et les cases vides sont ignorés. */
internal fun List<Arg>.numbers(): List<Double> = values().mapNotNull { it.strictNumber() }

internal fun List<Arg>.value(index: Int): Value = when (val arg = getOrNull(index)) {
    is Arg.One -> arg.value
    is Arg.Range -> arg.grid.flatten().firstOrNull() ?: Value.Blank
    null -> Value.Blank
}

/**
 * Argument facultatif : `null` s'il n'a pas été écrit. Sans cette distinction,
 * une case vide vaut zéro et l'on ne peut plus différencier « argument omis »
 * de « argument valant 0 » — INDEX(plage;3) désignerait alors la colonne 0.
 */
internal fun List<Arg>.optionalNumber(index: Int): Double? =
    if (index < size) value(index).takeIf { it != Value.Blank }?.asNumber() else null

internal class Parser(
    source: String,
    private val cells: Map<String, String>,
    private val visiting: MutableSet<String>,
    /** Feuille de la cellule évaluée ; `null` pour la feuille affichée. */
    private val sheet: String? = null
) {
    private val src = FormulaEngine.stripAbsolute(source)
    private var pos = 0

    private fun key(ref: String, onSheet: String? = sheet): String =
        if (onSheet == null) ref.uppercase(Locale.ROOT) else FormulaEngine.qualifiedKey(onSheet, ref)

    /** Lit `'Nom avec espaces'!` ou `Nom!` ; renvoie le nom, ou `null` sans rien consommer. */
    private fun readSheetPrefix(): String? {
        val save = pos
        if (pos < src.length && src[pos] == '\'') {
            val sb = StringBuilder()
            pos++
            while (pos < src.length) {
                if (src[pos] == '\'') {
                    if (pos + 1 < src.length && src[pos + 1] == '\'') { sb.append('\''); pos += 2; continue }
                    break
                }
                sb.append(src[pos]); pos++
            }
            if (pos < src.length && src[pos] == '\'' && pos + 1 < src.length && src[pos + 1] == '!') {
                pos += 2
                return sb.toString()
            }
            pos = save
            return null
        }
        while (pos < src.length && (src[pos].isLetterOrDigit() || src[pos] == '_' || src[pos] == '.')) pos++
        if (pos > save && pos < src.length && src[pos] == '!') {
            val name = src.substring(save, pos)
            pos++
            return name
        }
        pos = save
        return null
    }

    private fun readCellToken(): String {
        val start = pos
        while (pos < src.length && src[pos].isLetter()) pos++
        while (pos < src.length && src[pos].isDigit()) pos++
        return src.substring(start, pos)
    }

    fun parseAll(): Value {
        val v = parseComparison()
        skipWs()
        if (pos < src.length) throw IllegalArgumentException("Caractère inattendu")
        return v
    }

    private fun skipWs() {
        while (pos < src.length && src[pos].isWhitespace()) pos++
    }

    private fun parseComparison(): Value {
        val left = parseConcat()
        skipWs()
        for (op in listOf("<>", ">=", "<=", ">", "<", "=")) {
            if (src.startsWith(op, pos)) {
                pos += op.length
                val right = parseConcat()
                if (left.isError) return left
                if (right.isError) return right
                return Value.of(compare(left, right, op))
            }
        }
        return left
    }

    private fun compare(left: Value, right: Value, op: String): Boolean {
        val a = left.strictNumber()
        val b = right.strictNumber()
        // Deux nombres se comparent numériquement ; dès qu'un texte entre en
        // jeu, la comparaison se fait sur le libellé, sans tenir compte de la
        // casse, comme dans un tableur.
        val result = if (a != null && b != null) a.compareTo(b)
        else left.asText().compareTo(right.asText(), ignoreCase = true)
        return when (op) {
            "<>" -> result != 0
            ">=" -> result >= 0
            "<=" -> result <= 0
            ">" -> result > 0
            "<" -> result < 0
            else -> result == 0
        }
    }

    /** `&` colle deux valeurs bout à bout, comme dans Excel. */
    private fun parseConcat(): Value {
        var v = parseExpr()
        while (true) {
            skipWs()
            if (pos < src.length && src[pos] == '&') {
                pos++
                val r = parseExpr()
                if (v.isError) return v
                if (r.isError) return r
                v = Value.Txt(v.asText() + r.asText())
            } else return v
        }
    }

    private fun parseExpr(): Value {
        var v = parseTerm()
        while (true) {
            skipWs()
            if (pos < src.length && (src[pos] == '+' || src[pos] == '-')) {
                val op = src[pos]; pos++
                val r = parseTerm()
                v = arithmetic(v, r) { a, b -> if (op == '+') a + b else a - b }
            } else return v
        }
    }

    private fun parseTerm(): Value {
        var v = parsePower()
        while (true) {
            skipWs()
            if (pos < src.length && (src[pos] == '*' || src[pos] == '/')) {
                val op = src[pos]; pos++
                val r = parsePower()
                if (op == '/' && r.asNumber() == 0.0) return Value.DIV0
                v = arithmetic(v, r) { a, b -> if (op == '*') a * b else a / b }
            } else return v
        }
    }

    private fun parsePower(): Value {
        val base = parseUnary()
        skipWs()
        if (pos < src.length && src[pos] == '^') {
            pos++
            return arithmetic(base, parsePower()) { a, b -> a.pow(b) }
        }
        return base
    }

    private fun parseUnary(): Value {
        skipWs()
        if (pos < src.length && src[pos] == '-') {
            pos++
            return arithmetic(Value.Num(0.0), parseUnary()) { a, b -> a - b }
        }
        if (pos < src.length && src[pos] == '+') { pos++; return parseUnary() }
        if (pos < src.length && src[pos] == '%') throw IllegalArgumentException("'%' isolé")
        val v = parsePrimary()
        skipWs()
        if (pos < src.length && src[pos] == '%') {
            pos++
            return arithmetic(v, Value.Num(100.0)) { a, b -> a / b }
        }
        return v
    }

    private inline fun arithmetic(a: Value, b: Value, op: (Double, Double) -> Double): Value {
        if (a.isError) return a
        if (b.isError) return b
        val x = a.asNumber() ?: return Value.VALUE
        val y = b.asNumber() ?: return Value.VALUE
        return Value.of(op(x, y))
    }

    private fun parsePrimary(): Value {
        skipWs()
        if (pos >= src.length) throw IllegalArgumentException("Formule incomplète")
        val c = src[pos]
        if (c == '(') {
            pos++
            val v = parseComparison()
            skipWs()
            if (pos < src.length && src[pos] == ')') pos++
            else throw IllegalArgumentException("')' manquante")
            return v
        }
        if (c == '"') return parseString()
        if (c == '\'') {
            val onSheet = readSheetPrefix() ?: throw IllegalArgumentException("Nom de feuille invalide")
            return sheetReference(onSheet)
        }
        if (c.isDigit() || c == '.') return parseNumber()
        if (c.isLetter() || c == '_') return parseNameOrRef()
        throw IllegalArgumentException("Caractère '$c'")
    }

    /** Texte entre guillemets ; `""` à l'intérieur insère un guillemet. */
    private fun parseString(): Value {
        pos++
        val sb = StringBuilder()
        while (pos < src.length) {
            val ch = src[pos]
            if (ch == '"') {
                if (pos + 1 < src.length && src[pos + 1] == '"') {
                    sb.append('"'); pos += 2; continue
                }
                pos++
                return Value.Txt(sb.toString())
            }
            sb.append(ch); pos++
        }
        throw IllegalArgumentException("Guillemet fermant manquant")
    }

    private fun parseNumber(): Value {
        val start = pos
        while (pos < src.length && (src[pos].isDigit() || src[pos] == '.')) pos++
        return Value.Num(src.substring(start, pos).toDouble())
    }

    private fun parseNameOrRef(): Value {
        val start = pos
        // Le point fait partie des noms français : NB.SI, ARRONDI.SUP…
        while (pos < src.length && (src[pos].isLetterOrDigit() || src[pos] == '_' || src[pos] == '.')) pos++
        var name = src.substring(start, pos)
        if (pos < src.length && src[pos] == '!') {
            pos++
            return sheetReference(name)
        }
        skipWs()
        if (pos < src.length && src[pos] == '(') {
            pos++
            val args = ArrayList<Arg>()
            skipWs()
            if (pos < src.length && src[pos] == ')') {
                pos++
            } else {
                while (true) {
                    args.add(parseArgument())
                    skipWs()
                    if (pos < src.length && (src[pos] == ';' || src[pos] == ',')) { pos++; continue }
                    if (pos < src.length && src[pos] == ')') { pos++; break }
                    throw IllegalArgumentException("Arguments invalides")
                }
            }
            return FormulaFunctions.apply(name.uppercase(Locale.ROOT), args)
        }
        // Une référence ne contient pas de point : on le rend à l'expression.
        if (name.contains('.') && name.substringBefore('.').isNotEmpty()) {
            val head = name.substringBefore('.')
            if (FormulaEngine.isCellRef(head)) {
                pos = start + head.length
                name = head
            }
        }
        return constantOrRef(name)
    }

    /** Référence après un préfixe de feuille : une cellule, ou une plage réduite à sa première cellule. */
    private fun sheetReference(onSheet: String): Value {
        val ref = readCellToken()
        if (!FormulaEngine.isCellRef(ref)) throw IllegalArgumentException("Référence invalide : $onSheet!$ref")
        return FormulaEngine.valueAt(key(ref, onSheet), cells, visiting)
    }

    /** Un argument peut être une plage A1:B3, sinon c'est une expression. */
    private fun parseArgument(): Arg {
        val save = pos
        skipWs()
        // Plage d'une autre feuille : Feuille2!A1:B3.
        val prefixStart = pos
        val onSheet = readSheetPrefix()
        if (onSheet != null) {
            val from = readCellToken()
            skipWs()
            if (FormulaEngine.isCellRef(from) && pos < src.length && src[pos] == ':') {
                pos++
                skipWs()
                val to = readCellToken()
                if (FormulaEngine.isCellRef(to)) return Arg.Range(grid(from, to, onSheet))
            }
            pos = prefixStart
        }
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
                        return Arg.Range(grid(src.substring(start, refEnd), src.substring(start2, pos)))
                    }
                }
            }
        }
        pos = save
        return Arg.One(parseComparison())
    }

    private fun grid(from: String, to: String, onSheet: String? = sheet): List<List<Value>> {
        val c1 = FormulaEngine.columnIndex(from.takeWhile { it.isLetter() })
        val r1 = from.dropWhile { it.isLetter() }.toInt() - 1
        val c2 = FormulaEngine.columnIndex(to.takeWhile { it.isLetter() })
        val r2 = to.dropWhile { it.isLetter() }.toInt() - 1
        val rows = ArrayList<List<Value>>()
        for (r in minOf(r1, r2)..maxOf(r1, r2)) {
            val row = ArrayList<Value>()
            for (c in minOf(c1, c2)..maxOf(c1, c2)) {
                row.add(FormulaEngine.valueAt(key(FormulaEngine.cellKey(r, c), onSheet), cells, visiting))
            }
            rows.add(row)
        }
        return rows
    }

    private fun constantOrRef(name: String): Value {
        when (name.uppercase(Locale.ROOT)) {
            "PI" -> return Value.Num(Math.PI)
            "E" -> return Value.Num(Math.E)
            "VRAI", "TRUE" -> return Value.TRUE
            "FAUX", "FALSE" -> return Value.FALSE
        }
        val letters = name.takeWhile { it.isLetter() }
        val digits = name.dropWhile { it.isLetter() }
        if (letters.isEmpty() || digits.isEmpty() || !digits.all { it.isDigit() }) {
            throw IllegalArgumentException("Référence invalide : $name")
        }
        return FormulaEngine.valueAt(key(name), cells, visiting)
    }
}
