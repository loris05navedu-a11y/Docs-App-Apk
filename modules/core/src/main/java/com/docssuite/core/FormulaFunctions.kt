package com.docssuite.core

import java.util.Calendar
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.random.Random

/**
 * Bibliothèque de fonctions du tableur. Les noms français et anglais mènent au
 * même calcul ; une plage passée en argument garde sa forme rectangulaire, ce
 * dont RECHERCHEV et INDEX ont besoin.
 */
internal object FormulaFunctions {

    fun apply(name: String, args: List<Arg>): Value {
        // SIERREUR et les fonctions d'inspection doivent voir l'erreur : pour
        // toutes les autres, une erreur en entrée est une erreur en sortie.
        if (name !in ERROR_TRANSPARENT) {
            args.values().firstOrNull { it.isError }?.let { return it }
        }
        return when (name) {
            in MATH_NAMES -> math(name, args)
            in STATS_NAMES -> stats(name, args)
            in CONDITIONAL_NAMES -> conditional(name, args)
            in LOGIC_NAMES -> logic(name, args)
            in TEXT_NAMES -> text(name, args)
            in LOOKUP_NAMES -> lookup(name, args)
            in DATE_NAMES -> dates(name, args)
            in FINANCE_NAMES -> finance(name, args)
            else -> Value.NAME
        }
    }

    // ------------------------------------------------------------ maths

    private val MATH_NAMES = setOf(
        "SOMME", "SUM", "PRODUIT", "PRODUCT", "ABS", "ARRONDI", "ROUND",
        "ARRONDI.SUP", "ROUNDUP", "ARRONDI.INF", "ROUNDDOWN", "ENT", "INT",
        "TRONQUE", "TRUNC", "MOD", "RACINE", "SQRT", "PUISSANCE", "POWER", "POW",
        "EXP", "LN", "LOG", "LOG10", "SIGNE", "SIGN", "PLAFOND", "CEILING",
        "PLANCHER", "FLOOR", "ARRONDI.AU.MULTIPLE", "MROUND", "PAIR", "EVEN",
        "IMPAIR", "ODD", "FACT", "PGCD", "GCD", "PPCM", "LCM", "SOMME.CARRES",
        "SUMSQ", "SOMMEPROD", "SUMPRODUCT", "ALEA", "RAND", "ALEA.ENTRE.BORNES",
        "RANDBETWEEN", "DEGRES", "DEGREES", "RADIANS", "SIN", "COS", "TAN",
        "ASIN", "ACOS", "ATAN", "ATAN2", "PI"
    )

    private fun math(name: String, args: List<Arg>): Value {
        val nums = args.numbers()
        return when (name) {
            "SOMME", "SUM" -> Value.of(nums.sum())
            "PRODUIT", "PRODUCT" -> Value.of(nums.fold(1.0) { a, b -> a * b })
            "ABS" -> num(args, 0) { Value.of(abs(it)) }
            "ARRONDI", "ROUND" -> {
                val decimals = args.value(1).asNumber()?.toInt() ?: 0
                num(args, 0) { Value.of(roundTo(it, decimals)) }
            }
            "ARRONDI.SUP", "ROUNDUP" -> {
                val f = 10.0.pow(args.value(1).asNumber()?.toInt() ?: 0)
                num(args, 0) { Value.of(if (it < 0) floor(it * f) / f else ceil(it * f) / f) }
            }
            "ARRONDI.INF", "ROUNDDOWN" -> {
                val f = 10.0.pow(args.value(1).asNumber()?.toInt() ?: 0)
                num(args, 0) { Value.of(if (it < 0) ceil(it * f) / f else floor(it * f) / f) }
            }
            "ENT", "INT" -> num(args, 0) { Value.of(floor(it)) }
            "TRONQUE", "TRUNC" -> {
                val f = 10.0.pow(args.value(1).asNumber()?.toInt() ?: 0)
                num(args, 0) { Value.of((if (it < 0) ceil(it * f) else floor(it * f)) / f) }
            }
            "MOD" -> {
                val divisor = args.value(1).asNumber() ?: return Value.VALUE
                if (divisor == 0.0) return Value.DIV0
                // Le reste suit le signe du diviseur, comme dans un tableur.
                num(args, 0) { Value.of(((it % divisor) + divisor) % divisor) }
            }
            "RACINE", "SQRT" -> num(args, 0) { if (it < 0) Value.NUM else Value.of(sqrt(it)) }
            "PUISSANCE", "POWER", "POW" -> {
                val e = args.value(1).asNumber() ?: return Value.VALUE
                num(args, 0) { Value.of(it.pow(e)) }
            }
            "EXP" -> num(args, 0) { Value.of(exp(it)) }
            "LN" -> num(args, 0) { if (it <= 0) Value.NUM else Value.of(ln(it)) }
            "LOG10" -> num(args, 0) { if (it <= 0) Value.NUM else Value.of(log10(it)) }
            "LOG" -> {
                val base = args.optionalNumber(1)
                num(args, 0) {
                    if (it <= 0) Value.NUM
                    else if (base == null) Value.of(log10(it)) else Value.of(ln(it) / ln(base))
                }
            }
            "SIGNE", "SIGN" -> num(args, 0) { Value.of(sign(it)) }
            "PLAFOND", "CEILING" -> {
                val step = args.optionalNumber(1) ?: 1.0
                if (step == 0.0) return Value.DIV0
                num(args, 0) { Value.of(ceil(it / step) * step) }
            }
            "PLANCHER", "FLOOR" -> {
                val step = args.optionalNumber(1) ?: 1.0
                if (step == 0.0) return Value.DIV0
                num(args, 0) { Value.of(floor(it / step) * step) }
            }
            "ARRONDI.AU.MULTIPLE", "MROUND" -> {
                val step = args.optionalNumber(1) ?: 1.0
                if (step == 0.0) return Value.of(0.0)
                num(args, 0) { Value.of((it / step).roundToLong() * step) }
            }
            "PAIR", "EVEN" -> num(args, 0) {
                val up = ceil(abs(it) / 2) * 2
                Value.of(if (it < 0) -up else up)
            }
            "IMPAIR", "ODD" -> num(args, 0) {
                var up = ceil(abs(it))
                if (up % 2.0 == 0.0) up += 1
                if (up == 0.0) up = 1.0
                Value.of(if (it < 0) -up else up)
            }
            "FACT" -> num(args, 0) {
                val n = floor(it).toInt()
                if (n < 0 || n > 170) Value.NUM
                else Value.of((1..n).fold(1.0) { a, b -> a * b })
            }
            "PGCD", "GCD" -> Value.of(
                nums.map { abs(it).toLong() }.fold(0L) { a, b -> gcd(a, b) }.toDouble()
            )
            "PPCM", "LCM" -> Value.of(
                nums.map { abs(it).toLong() }.fold(1L) { a, b ->
                    if (a == 0L || b == 0L) 0L else a / gcd(a, b) * b
                }.toDouble()
            )
            "SOMME.CARRES", "SUMSQ" -> Value.of(nums.sumOf { it * it })
            "SOMMEPROD", "SUMPRODUCT" -> sumProduct(args)
            "ALEA", "RAND" -> Value.of(Random.nextDouble())
            "ALEA.ENTRE.BORNES", "RANDBETWEEN" -> {
                val lo = args.value(0).asNumber()?.toLong() ?: return Value.VALUE
                val hi = args.value(1).asNumber()?.toLong() ?: return Value.VALUE
                if (hi < lo) Value.NUM else Value.of(Random.nextLong(lo, hi + 1).toDouble())
            }
            "DEGRES", "DEGREES" -> num(args, 0) { Value.of(it * 180.0 / Math.PI) }
            "RADIANS" -> num(args, 0) { Value.of(it * Math.PI / 180.0) }
            "SIN" -> num(args, 0) { Value.of(sin(it)) }
            "COS" -> num(args, 0) { Value.of(cos(it)) }
            "TAN" -> num(args, 0) { Value.of(tan(it)) }
            "ASIN" -> num(args, 0) { if (abs(it) > 1) Value.NUM else Value.of(asin(it)) }
            "ACOS" -> num(args, 0) { if (abs(it) > 1) Value.NUM else Value.of(acos(it)) }
            "ATAN" -> num(args, 0) { Value.of(atan(it)) }
            "ATAN2" -> {
                val y = args.value(1).asNumber() ?: return Value.VALUE
                num(args, 0) { Value.of(atan2(y, it)) }
            }
            "PI" -> Value.Num(Math.PI)
            else -> Value.NAME
        }
    }

    private fun sumProduct(args: List<Arg>): Value {
        val columns = args.map { arg ->
            when (arg) {
                is Arg.One -> listOf(arg.value.strictNumber() ?: 0.0)
                is Arg.Range -> arg.grid.flatten().map { it.strictNumber() ?: 0.0 }
            }
        }
        if (columns.isEmpty()) return Value.of(0.0)
        val size = columns.minOf { it.size }
        var total = 0.0
        for (i in 0 until size) total += columns.fold(1.0) { acc, col -> acc * col[i] }
        return Value.of(total)
    }

    // ------------------------------------------------------- statistiques

    private val STATS_NAMES = setOf(
        "MOYENNE", "AVERAGE", "AVG", "MIN", "MAX", "NB", "COUNT", "NBVAL",
        "COUNTA", "NB.VIDE", "COUNTBLANK", "MEDIANE", "MEDIAN", "MODE",
        "ECARTYPE", "STDEV", "ECARTYPEP", "STDEVP", "VAR", "VARP",
        "GRANDE.VALEUR", "LARGE", "PETITE.VALEUR", "SMALL", "RANG", "RANK",
        "CENTILE", "PERCENTILE", "QUARTILE"
    )

    private fun stats(name: String, args: List<Arg>): Value {
        val nums = args.numbers()
        return when (name) {
            "MOYENNE", "AVERAGE", "AVG" ->
                if (nums.isEmpty()) Value.DIV0 else Value.of(nums.sum() / nums.size)
            "MIN" -> Value.of(nums.minOrNull() ?: 0.0)
            "MAX" -> Value.of(nums.maxOrNull() ?: 0.0)
            "NB", "COUNT" -> Value.of(nums.size.toDouble())
            "NBVAL", "COUNTA" ->
                Value.of(args.values().count { it != Value.Blank }.toDouble())
            "NB.VIDE", "COUNTBLANK" ->
                Value.of(args.values().count { it == Value.Blank }.toDouble())
            "MEDIANE", "MEDIAN" ->
                if (nums.isEmpty()) Value.NUM else Value.of(median(nums.sorted()))
            "MODE" -> {
                val best = nums.groupingBy { it }.eachCount().filterValues { it > 1 }
                    .maxByOrNull { it.value }?.key
                if (best == null) Value.NA else Value.of(best)
            }
            "ECARTYPE", "STDEV" ->
                if (nums.size < 2) Value.DIV0 else Value.of(sqrt(variance(nums, sample = true)))
            "ECARTYPEP", "STDEVP" ->
                if (nums.isEmpty()) Value.DIV0 else Value.of(sqrt(variance(nums, sample = false)))
            "VAR" ->
                if (nums.size < 2) Value.DIV0 else Value.of(variance(nums, sample = true))
            "VARP" ->
                if (nums.isEmpty()) Value.DIV0 else Value.of(variance(nums, sample = false))
            "GRANDE.VALEUR", "LARGE" -> {
                val k = args.value(args.lastIndex).asNumber()?.toInt() ?: 1
                val pool = poolWithoutLast(args)
                pool.sortedDescending().getOrNull(k - 1)?.let { Value.of(it) } ?: Value.NUM
            }
            "PETITE.VALEUR", "SMALL" -> {
                val k = args.value(args.lastIndex).asNumber()?.toInt() ?: 1
                val pool = poolWithoutLast(args)
                pool.sorted().getOrNull(k - 1)?.let { Value.of(it) } ?: Value.NUM
            }
            "RANG", "RANK" -> {
                val target = args.value(0).asNumber() ?: return Value.VALUE
                val pool = (args.getOrNull(1) as? Arg.Range)?.grid?.flatten()
                    ?.mapNotNull { it.strictNumber() } ?: return Value.VALUE
                val ascending = (args.value(2).asNumber() ?: 0.0) != 0.0
                val ordered = if (ascending) pool.sorted() else pool.sortedDescending()
                val index = ordered.indexOfFirst { abs(it - target) < 1e-9 }
                if (index < 0) Value.NA else Value.of((index + 1).toDouble())
            }
            "CENTILE", "PERCENTILE" -> {
                val p = args.value(args.lastIndex).asNumber() ?: return Value.VALUE
                val pool = poolWithoutLast(args)
                if (pool.isEmpty()) Value.NUM else Value.of(quantile(pool.sorted(), p.coerceIn(0.0, 1.0)))
            }
            "QUARTILE" -> {
                val rank = (args.value(args.lastIndex).asNumber()?.toInt() ?: 2).coerceIn(0, 4)
                val pool = poolWithoutLast(args)
                if (pool.isEmpty()) Value.NUM else Value.of(quantile(pool.sorted(), rank / 4.0))
            }
            else -> Value.NAME
        }
    }

    /** Les fonctions à paramètre final (QUARTILE, RANG…) écartent ce dernier du lot. */
    private fun poolWithoutLast(args: List<Arg>): List<Double> =
        args.dropLast(1).numbers().ifEmpty { args.numbers().dropLast(1) }

    // --------------------------------------------------- conditionnelles

    private val CONDITIONAL_NAMES = setOf(
        "SOMME.SI", "SUMIF", "NB.SI", "COUNTIF", "MOYENNE.SI", "AVERAGEIF"
    )

    private fun conditional(name: String, args: List<Arg>): Value {
        val range = (args.getOrNull(0) as? Arg.Range)?.grid?.flatten() ?: return Value.VALUE
        val criterion = args.value(1)
        val matched = range.indices.filter { matches(range[it], criterion) }
        return when (name) {
            "NB.SI", "COUNTIF" -> Value.of(matched.size.toDouble())
            "SOMME.SI", "SUMIF" -> {
                // Un troisième argument déplace la somme sur une autre plage,
                // alignée position par position sur celle du critère.
                val target = (args.getOrNull(2) as? Arg.Range)?.grid?.flatten() ?: range
                Value.of(matched.sumOf { target.getOrNull(it)?.strictNumber() ?: 0.0 })
            }
            "MOYENNE.SI", "AVERAGEIF" -> {
                val target = (args.getOrNull(2) as? Arg.Range)?.grid?.flatten() ?: range
                val picked = matched.mapNotNull { target.getOrNull(it)?.strictNumber() }
                if (picked.isEmpty()) Value.DIV0 else Value.of(picked.sum() / picked.size)
            }
            else -> Value.NAME
        }
    }

    /** `">10"`, `"<>0"`, `"pomme"`, `"po*"` … */
    private fun matches(value: Value, criterion: Value): Boolean {
        val raw = criterion.asText().trim()
        val operator = listOf(">=", "<=", "<>", ">", "<", "=").firstOrNull { raw.startsWith(it) }
        val operand = if (operator == null) raw else raw.removePrefix(operator).trim()
        val target = Value.literal(operand)

        val a = value.strictNumber()
        val b = target.strictNumber()
        val comparison = if (a != null && b != null) a.compareTo(b)
        else value.asText().compareTo(target.asText(), ignoreCase = true)

        return when (operator) {
            ">=" -> comparison >= 0
            "<=" -> comparison <= 0
            "<>" -> comparison != 0
            ">" -> comparison > 0
            "<" -> comparison < 0
            else -> if (a == null && b == null && ('*' in operand || '?' in operand)) {
                wildcard(operand).matches(value.asText())
            } else {
                comparison == 0
            }
        }
    }

    private fun wildcard(pattern: String): Regex {
        val regex = buildString {
            pattern.forEach { ch ->
                when (ch) {
                    '*' -> append(".*")
                    '?' -> append('.')
                    else -> append(Regex.escape(ch.toString()))
                }
            }
        }
        return Regex(regex, RegexOption.IGNORE_CASE)
    }

    // ---------------------------------------------------------- logique

    private val LOGIC_NAMES = setOf(
        "SI", "IF", "ET", "AND", "OU", "OR", "NON", "NOT", "OUX", "XOR",
        "SIERREUR", "IFERROR", "SI.CONDITIONS", "IFS", "ESTNUM", "ISNUMBER",
        "ESTTEXTE", "ISTEXT", "ESTVIDE", "ISBLANK", "ESTERREUR", "ISERROR",
        "VRAI", "TRUE", "FAUX", "FALSE"
    )

    private val ERROR_TRANSPARENT = setOf(
        "SIERREUR", "IFERROR", "ESTERREUR", "ISERROR", "ESTVIDE", "ISBLANK"
    )

    private fun logic(name: String, args: List<Arg>): Value = when (name) {
        "SI", "IF" ->
            if (args.value(0).asBoolean()) args.value(1)
            else if (args.size > 2) args.value(2) else Value.FALSE
        "ET", "AND" -> Value.of(args.values().all { it.asBoolean() })
        "OU", "OR" -> Value.of(args.values().any { it.asBoolean() })
        "NON", "NOT" -> Value.of(!args.value(0).asBoolean())
        "OUX", "XOR" -> Value.of(args.values().count { it.asBoolean() } % 2 == 1)
        "SIERREUR", "IFERROR" ->
            if (args.value(0).isError) args.value(1) else args.value(0)
        "SI.CONDITIONS", "IFS" -> {
            val values = args.values()
            var result: Value = Value.NA
            var i = 0
            while (i + 1 < values.size) {
                if (values[i].asBoolean()) { result = values[i + 1]; break }
                i += 2
            }
            result
        }
        "ESTNUM", "ISNUMBER" -> Value.of(args.value(0) is Value.Num)
        "ESTTEXTE", "ISTEXT" -> Value.of(args.value(0) is Value.Txt)
        "ESTVIDE", "ISBLANK" -> Value.of(args.value(0) == Value.Blank)
        "ESTERREUR", "ISERROR" -> Value.of(args.value(0).isError)
        "VRAI", "TRUE" -> Value.TRUE
        "FAUX", "FALSE" -> Value.FALSE
        else -> Value.NAME
    }

    // ------------------------------------------------------------ texte

    private val TEXT_NAMES = setOf(
        "CONCATENER", "CONCAT", "CONCATENATE", "GAUCHE", "LEFT", "DROITE",
        "RIGHT", "STXT", "MID", "NBCAR", "LEN", "MAJUSCULE", "UPPER",
        "MINUSCULE", "LOWER", "NOMPROPRE", "PROPER", "SUPPRESPACE", "TRIM",
        "SUBSTITUE", "SUBSTITUTE", "REMPLACER", "REPLACE", "TROUVE", "FIND",
        "CHERCHE", "SEARCH", "REPT", "JOINDRE.TEXTE", "TEXTJOIN", "CNUM",
        "VALUE", "TEXTE", "TEXT", "EXACT", "CAR", "CHAR", "CODE"
    )

    private fun text(name: String, args: List<Arg>): Value {
        fun str(i: Int) = args.value(i).asText()
        return when (name) {
            "CONCATENER", "CONCAT", "CONCATENATE" ->
                Value.Txt(args.values().joinToString("") { it.asText() })
            "GAUCHE", "LEFT" -> {
                val n = (args.value(1).asNumber()?.toInt() ?: 1).coerceAtLeast(0)
                Value.Txt(str(0).take(n))
            }
            "DROITE", "RIGHT" -> {
                val n = (args.value(1).asNumber()?.toInt() ?: 1).coerceAtLeast(0)
                Value.Txt(str(0).takeLast(n))
            }
            "STXT", "MID" -> {
                val s = str(0)
                val start = (args.value(1).asNumber()?.toInt() ?: 1) - 1
                val len = (args.value(2).asNumber()?.toInt() ?: 0).coerceAtLeast(0)
                if (start < 0) Value.VALUE
                else Value.Txt(s.drop(start).take(len))
            }
            "NBCAR", "LEN" -> Value.of(str(0).length.toDouble())
            "MAJUSCULE", "UPPER" -> Value.Txt(str(0).uppercase(Locale.FRANCE))
            "MINUSCULE", "LOWER" -> Value.Txt(str(0).lowercase(Locale.FRANCE))
            "NOMPROPRE", "PROPER" -> Value.Txt(properCase(str(0)))
            "SUPPRESPACE", "TRIM" ->
                Value.Txt(str(0).trim().replace(Regex("\\s+"), " "))
            "SUBSTITUE", "SUBSTITUTE" ->
                Value.Txt(str(0).replace(str(1), str(2)))
            "REMPLACER", "REPLACE" -> {
                val s = str(0)
                val start = (args.value(1).asNumber()?.toInt() ?: 1) - 1
                val len = (args.value(2).asNumber()?.toInt() ?: 0).coerceAtLeast(0)
                if (start < 0 || start > s.length) Value.VALUE
                else Value.Txt(s.take(start) + str(3) + s.drop((start + len).coerceAtMost(s.length)))
            }
            "TROUVE", "FIND" -> {
                val from = (args.optionalNumber(2)?.toInt() ?: 1) - 1
                val at = str(1).indexOf(str(0), from.coerceAtLeast(0))
                if (at < 0) Value.NA else Value.of((at + 1).toDouble())
            }
            "CHERCHE", "SEARCH" -> {
                val from = (args.optionalNumber(2)?.toInt() ?: 1) - 1
                val at = str(1).indexOf(str(0), from.coerceAtLeast(0), ignoreCase = true)
                if (at < 0) Value.NA else Value.of((at + 1).toDouble())
            }
            "REPT" -> {
                val n = (args.value(1).asNumber()?.toInt() ?: 0).coerceIn(0, 10_000)
                Value.Txt(str(0).repeat(n))
            }
            "JOINDRE.TEXTE", "TEXTJOIN" -> {
                val separator = str(0)
                val skipEmpty = args.value(1).asBoolean()
                val parts = args.drop(2).values().map { it.asText() }
                Value.Txt(parts.filter { !skipEmpty || it.isNotEmpty() }.joinToString(separator))
            }
            "CNUM", "VALUE" ->
                args.value(0).asNumber()?.let { Value.of(it) } ?: Value.VALUE
            "TEXTE", "TEXT" -> Value.Txt(formatWith(args.value(0), str(1)))
            "EXACT" -> Value.of(str(0) == str(1))
            "CAR", "CHAR" -> {
                val code = args.value(0).asNumber()?.toInt() ?: return Value.VALUE
                if (code in 1..0x10FFFF) Value.Txt(code.toChar().toString()) else Value.VALUE
            }
            "CODE" -> str(0).firstOrNull()?.let { Value.of(it.code.toDouble()) } ?: Value.VALUE
            else -> Value.NAME
        }
    }

    private fun properCase(s: String): String = buildString {
        var newWord = true
        s.forEach { ch ->
            if (ch.isLetter()) {
                append(if (newWord) ch.uppercaseChar() else ch.lowercaseChar())
                newWord = false
            } else {
                append(ch)
                newWord = true
            }
        }
    }

    // -------------------------------------------------------- recherche

    private val LOOKUP_NAMES = setOf(
        "RECHERCHEV", "VLOOKUP", "RECHERCHEH", "HLOOKUP", "INDEX", "EQUIV",
        "MATCH", "CHOISIR", "CHOOSE", "LIGNES", "ROWS", "COLONNES", "COLUMNS"
    )

    private fun lookup(name: String, args: List<Arg>): Value {
        val grid = (args.getOrNull(1) as? Arg.Range)?.grid
        return when (name) {
            "RECHERCHEV", "VLOOKUP" -> {
                if (grid == null || grid.isEmpty()) return Value.REF
                val column = (args.value(2).asNumber()?.toInt() ?: 1) - 1
                if (column < 0 || column >= grid[0].size) return Value.REF
                val approximate = args.size < 4 || args.value(3).asBoolean()
                val row = findRow(grid.map { it.firstOrNull() ?: Value.Blank }, args.value(0), approximate)
                if (row < 0) Value.NA else grid[row].getOrElse(column) { Value.NA }
            }
            "RECHERCHEH", "HLOOKUP" -> {
                if (grid == null || grid.isEmpty()) return Value.REF
                val rowIndex = (args.value(2).asNumber()?.toInt() ?: 1) - 1
                if (rowIndex < 0 || rowIndex >= grid.size) return Value.REF
                val approximate = args.size < 4 || args.value(3).asBoolean()
                val column = findRow(grid[0], args.value(0), approximate)
                if (column < 0) Value.NA else grid[rowIndex].getOrElse(column) { Value.NA }
            }
            "INDEX" -> {
                val source = (args.getOrNull(0) as? Arg.Range)?.grid ?: return Value.REF
                val row = args.value(1).asNumber()?.toInt() ?: 1
                val column = args.optionalNumber(2)?.toInt()
                if (column == null && (source.size == 1 || source[0].size == 1)) {
                    // Plage sur une seule ligne ou colonne : un indice suffit.
                    val flat = source.flatten()
                    flat.getOrNull(row - 1) ?: Value.REF
                } else {
                    source.getOrNull(row - 1)?.getOrNull((column ?: 1) - 1) ?: Value.REF
                }
            }
            "EQUIV", "MATCH" -> {
                val pool = (args.getOrNull(1) as? Arg.Range)?.grid?.flatten() ?: return Value.NA
                val mode = args.optionalNumber(2)?.toInt() ?: 1
                val at = when (mode) {
                    0 -> pool.indexOfFirst { matches(it, args.value(0)) }
                    else -> findRow(pool, args.value(0), approximate = true)
                }
                if (at < 0) Value.NA else Value.of((at + 1).toDouble())
            }
            "CHOISIR", "CHOOSE" -> {
                val index = args.value(0).asNumber()?.toInt() ?: return Value.VALUE
                val options = args.drop(1).values()
                options.getOrNull(index - 1) ?: Value.VALUE
            }
            "LIGNES", "ROWS" -> Value.of((grid0(args)?.size ?: 1).toDouble())
            "COLONNES", "COLUMNS" -> Value.of((grid0(args)?.firstOrNull()?.size ?: 1).toDouble())
            else -> Value.NAME
        }
    }

    private fun grid0(args: List<Arg>): List<List<Value>>? =
        (args.getOrNull(0) as? Arg.Range)?.grid

    /**
     * Position de la valeur cherchée. En mode approché la colonne est supposée
     * triée et l'on retient la dernière entrée inférieure ou égale, comme le
     * fait un tableur.
     */
    private fun findRow(column: List<Value>, needle: Value, approximate: Boolean): Int {
        if (!approximate) return column.indexOfFirst { matches(it, needle) }
        val target = needle.strictNumber()
        var best = -1
        column.forEachIndexed { index, candidate ->
            val comparison = if (target != null && candidate.strictNumber() != null) {
                candidate.strictNumber()!!.compareTo(target)
            } else {
                candidate.asText().compareTo(needle.asText(), ignoreCase = true)
            }
            if (comparison <= 0) best = index
        }
        return best
    }

    // ---------------------------------------------------- date et heure

    private val DATE_NAMES = setOf(
        "AUJOURDHUI", "TODAY", "MAINTENANT", "NOW", "DATE", "ANNEE", "YEAR",
        "MOIS", "MONTH", "JOUR", "DAY", "JOURSEM", "WEEKDAY", "JOURS", "DAYS",
        "HEURE", "HOUR", "MINUTE", "MOIS.DECALER", "EDATE", "FIN.MOIS", "EOMONTH"
    )

    private fun dates(name: String, args: List<Arg>): Value {
        return when (name) {
        "AUJOURDHUI", "TODAY" -> Value.of(todaySerial())
        "MAINTENANT", "NOW" -> {
            val now = GregorianCalendar()
            val fraction = (now.get(Calendar.HOUR_OF_DAY) * 3600 +
                now.get(Calendar.MINUTE) * 60 + now.get(Calendar.SECOND)) / 86_400.0
            Value.of(todaySerial() + fraction)
        }
        "DATE" -> {
            val y = args.value(0).asNumber()?.toInt() ?: return Value.VALUE
            val m = args.value(1).asNumber()?.toInt() ?: return Value.VALUE
            val d = args.value(2).asNumber()?.toInt() ?: return Value.VALUE
            Value.of(dateSerial(y, m, d))
        }
        "ANNEE", "YEAR" -> field(args, Calendar.YEAR)
        "MOIS", "MONTH" -> field(args, Calendar.MONTH) { it + 1 }
        "JOUR", "DAY" -> field(args, Calendar.DAY_OF_MONTH)
        "JOURSEM", "WEEKDAY" -> field(args, Calendar.DAY_OF_WEEK)
        "HEURE", "HOUR" -> num(args, 0) {
            Value.of(floor((it - floor(it)) * 24))
        }
        "MINUTE" -> num(args, 0) {
            Value.of(floor(((it - floor(it)) * 1440) % 60))
        }
        "JOURS", "DAYS" -> {
            val end = args.value(0).asNumber() ?: return Value.VALUE
            val start = args.value(1).asNumber() ?: return Value.VALUE
            Value.of(floor(end) - floor(start))
        }
        "MOIS.DECALER", "EDATE" -> shiftMonths(args, endOfMonth = false)
        "FIN.MOIS", "EOMONTH" -> shiftMonths(args, endOfMonth = true)
        else -> Value.NAME
        }
    }

    private fun shiftMonths(args: List<Arg>, endOfMonth: Boolean): Value {
        val serial = args.value(0).asNumber() ?: return Value.VALUE
        val months = args.value(1).asNumber()?.toInt() ?: 0
        val cal = calendarOf(serial)
        cal.add(Calendar.MONTH, months)
        if (endOfMonth) cal.set(Calendar.DAY_OF_MONTH, cal.getActualMaximum(Calendar.DAY_OF_MONTH))
        return Value.of(
            dateSerial(
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH) + 1,
                cal.get(Calendar.DAY_OF_MONTH)
            )
        )
    }

    private fun field(args: List<Arg>, key: Int, transform: (Int) -> Int = { it }): Value {
        val serial = args.value(0).asNumber() ?: return Value.VALUE
        return Value.of(transform(calendarOf(serial).get(key)).toDouble())
    }

    /** Le jour 0 est le 30 décembre 1899, convention des tableurs. */
    private fun epochMillis(): Long {
        val c = GregorianCalendar(TimeZone.getTimeZone("UTC"))
        c.clear()
        c.set(1899, Calendar.DECEMBER, 30, 0, 0, 0)
        return c.timeInMillis
    }

    fun dateSerial(year: Int, month: Int, day: Int): Double {
        val c = GregorianCalendar(TimeZone.getTimeZone("UTC"))
        c.clear()
        c.set(year, month - 1, day, 0, 0, 0)
        return (c.timeInMillis - epochMillis()) / 86_400_000.0
    }

    private fun calendarOf(serial: Double): Calendar {
        val c = GregorianCalendar(TimeZone.getTimeZone("UTC"))
        c.timeInMillis = epochMillis() + (floor(serial) * 86_400_000.0).toLong()
        return c
    }

    private fun todaySerial(): Double {
        val now = GregorianCalendar()
        return dateSerial(
            now.get(Calendar.YEAR),
            now.get(Calendar.MONTH) + 1,
            now.get(Calendar.DAY_OF_MONTH)
        )
    }

    /** Mise en forme de TEXTE() : quelques motifs courants, réellement rendus. */
    private fun formatWith(value: Value, pattern: String): String {
        val lower = pattern.lowercase(Locale.ROOT)
        val number = value.asNumber() ?: return value.asText()
        if (lower.contains("aaaa") || lower.contains("yyyy") ||
            lower.contains("jj") || lower.contains("dd") || lower.contains("mm")
        ) {
            val c = calendarOf(number)
            val day = String.format(Locale.FRANCE, "%02d", c.get(Calendar.DAY_OF_MONTH))
            val month = String.format(Locale.FRANCE, "%02d", c.get(Calendar.MONTH) + 1)
            val year = c.get(Calendar.YEAR).toString()
            return lower
                .replace("aaaa", year).replace("yyyy", year)
                .replace("jj", day).replace("dd", day)
                .replace("mm", month)
        }
        if (lower.endsWith("%")) {
            val decimals = lower.removeSuffix("%").substringAfter('.', "").count { it == '0' }
            return String.format(Locale.FRANCE, "%.${decimals}f %%", number * 100)
        }
        val decimals = pattern.substringAfter('.', "").count { it == '0' }
        val grouped = pattern.contains(',') || pattern.contains(' ')
        val text = String.format(Locale.FRANCE, "%.${decimals}f", number)
        return if (grouped) groupThousands(text) else text
    }

    private fun groupThousands(text: String): String {
        val negative = text.startsWith("-")
        val body = text.removePrefix("-")
        val integer = body.substringBefore(',').substringBefore('.')
        val rest = body.removePrefix(integer)
        val spaced = integer.reversed().chunked(3).joinToString(" ").reversed()
        return (if (negative) "-" else "") + spaced + rest
    }

    // ---------------------------------------------------------- finance

    private val FINANCE_NAMES = setOf("VPM", "PMT", "VC", "FV", "VA", "PV", "NPM", "NPER")

    private fun finance(name: String, args: List<Arg>): Value {
        val rate = args.value(0).asNumber() ?: return Value.VALUE
        return when (name) {
            "VPM", "PMT" -> {
                val n = args.value(1).asNumber() ?: return Value.VALUE
                val pv = args.value(2).asNumber() ?: return Value.VALUE
                val fv = args.value(3).asNumber() ?: 0.0
                val type = args.value(4).asNumber() ?: 0.0
                if (n == 0.0) return Value.DIV0
                if (rate == 0.0) Value.of(-(pv + fv) / n)
                else {
                    val growth = (1 + rate).pow(n)
                    Value.of(-(pv * growth + fv) * rate / ((1 + rate * type) * (growth - 1)))
                }
            }
            "VC", "FV" -> {
                val n = args.value(1).asNumber() ?: return Value.VALUE
                val pmt = args.value(2).asNumber() ?: 0.0
                val pv = args.value(3).asNumber() ?: 0.0
                val type = args.value(4).asNumber() ?: 0.0
                if (rate == 0.0) Value.of(-(pv + pmt * n))
                else {
                    val growth = (1 + rate).pow(n)
                    Value.of(-(pv * growth + pmt * (1 + rate * type) * (growth - 1) / rate))
                }
            }
            "VA", "PV" -> {
                val n = args.value(1).asNumber() ?: return Value.VALUE
                val pmt = args.value(2).asNumber() ?: 0.0
                val fv = args.value(3).asNumber() ?: 0.0
                val type = args.value(4).asNumber() ?: 0.0
                if (rate == 0.0) Value.of(-(fv + pmt * n))
                else {
                    val growth = (1 + rate).pow(n)
                    Value.of(-(fv + pmt * (1 + rate * type) * (growth - 1) / rate) / growth)
                }
            }
            "NPM", "NPER" -> {
                val pmt = args.value(1).asNumber() ?: return Value.VALUE
                val pv = args.value(2).asNumber() ?: return Value.VALUE
                val fv = args.value(3).asNumber() ?: 0.0
                val type = args.value(4).asNumber() ?: 0.0
                if (rate == 0.0) {
                    if (pmt == 0.0) Value.DIV0 else Value.of(-(pv + fv) / pmt)
                } else {
                    val adjusted = pmt * (1 + rate * type)
                    val numerator = adjusted - fv * rate
                    val denominator = adjusted + pv * rate
                    if (numerator <= 0 || denominator <= 0) Value.NUM
                    else Value.of(ln(numerator / denominator) / ln(1 + rate))
                }
            }
            else -> Value.NAME
        }
    }

    // ---------------------------------------------------------- communs

    private inline fun num(args: List<Arg>, index: Int, block: (Double) -> Value): Value {
        val v = args.value(index).asNumber() ?: return Value.VALUE
        return block(v)
    }

    private fun roundTo(v: Double, decimals: Int): Double {
        val factor = 10.0.pow(decimals)
        return (v * factor).roundToLong() / factor
    }

    private fun median(sorted: List<Double>): Double =
        if (sorted.size % 2 == 1) sorted[sorted.size / 2]
        else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2

    private fun variance(values: List<Double>, sample: Boolean): Double {
        val mean = values.sum() / values.size
        val total = values.sumOf { (it - mean) * (it - mean) }
        return total / if (sample) (values.size - 1) else values.size
    }

    private tailrec fun gcd(a: Long, b: Long): Long = if (b == 0L) a else gcd(b, a % b)

    // ------------------------------------------------------------- aide

    val CATALOG: List<FunctionGroup> = listOf(
        FunctionGroup(
            "Maths",
            listOf(
                FunctionHelp("SOMME(plage)", "Additionne les nombres"),
                FunctionHelp("PRODUIT(plage)", "Multiplie les nombres"),
                FunctionHelp("ABS(n)", "Valeur absolue"),
                FunctionHelp("ARRONDI(n;décimales)", "Arrondit au plus proche"),
                FunctionHelp("ARRONDI.SUP(n;décimales)", "Arrondit au-dessus"),
                FunctionHelp("ARRONDI.INF(n;décimales)", "Arrondit au-dessous"),
                FunctionHelp("ENT(n)", "Partie entière"),
                FunctionHelp("TRONQUE(n;décimales)", "Tronque sans arrondir"),
                FunctionHelp("MOD(n;diviseur)", "Reste de la division"),
                FunctionHelp("RACINE(n)", "Racine carrée"),
                FunctionHelp("PUISSANCE(n;exposant)", "Élève à une puissance"),
                FunctionHelp("EXP(n)", "Exponentielle"),
                FunctionHelp("LN(n)", "Logarithme népérien"),
                FunctionHelp("LOG(n;base)", "Logarithme"),
                FunctionHelp("SIGNE(n)", "-1, 0 ou 1"),
                FunctionHelp("PLAFOND(n;pas)", "Arrondit au multiple supérieur"),
                FunctionHelp("PLANCHER(n;pas)", "Arrondit au multiple inférieur"),
                FunctionHelp("PAIR(n)", "Arrondit au pair supérieur"),
                FunctionHelp("IMPAIR(n)", "Arrondit à l'impair supérieur"),
                FunctionHelp("FACT(n)", "Factorielle"),
                FunctionHelp("PGCD(plage)", "Plus grand commun diviseur"),
                FunctionHelp("PPCM(plage)", "Plus petit commun multiple"),
                FunctionHelp("SOMMEPROD(p1;p2)", "Somme des produits"),
                FunctionHelp("ALEA()", "Nombre entre 0 et 1"),
                FunctionHelp("ALEA.ENTRE.BORNES(a;b)", "Entier au hasard")
            )
        ),
        FunctionGroup(
            "Statistiques",
            listOf(
                FunctionHelp("MOYENNE(plage)", "Moyenne des nombres"),
                FunctionHelp("MIN(plage)", "Plus petite valeur"),
                FunctionHelp("MAX(plage)", "Plus grande valeur"),
                FunctionHelp("NB(plage)", "Compte les nombres"),
                FunctionHelp("NBVAL(plage)", "Compte les cases remplies"),
                FunctionHelp("NB.VIDE(plage)", "Compte les cases vides"),
                FunctionHelp("MEDIANE(plage)", "Valeur médiane"),
                FunctionHelp("MODE(plage)", "Valeur la plus fréquente"),
                FunctionHelp("ECARTYPE(plage)", "Écart-type d'un échantillon"),
                FunctionHelp("ECARTYPEP(plage)", "Écart-type d'une population"),
                FunctionHelp("VAR(plage)", "Variance d'un échantillon"),
                FunctionHelp("GRANDE.VALEUR(plage;k)", "k-ième plus grande"),
                FunctionHelp("PETITE.VALEUR(plage;k)", "k-ième plus petite"),
                FunctionHelp("RANG(n;plage;ordre)", "Rang d'une valeur"),
                FunctionHelp("CENTILE(plage;p)", "Centile"),
                FunctionHelp("QUARTILE(plage;n)", "Quartile 0 à 4")
            )
        ),
        FunctionGroup(
            "Conditions",
            listOf(
                FunctionHelp("SI(test;alors;sinon)", "Choisit selon un test"),
                FunctionHelp("SI.CONDITIONS(t1;v1;t2;v2)", "Premier test vrai"),
                FunctionHelp("SIERREUR(valeur;secours)", "Remplace une erreur"),
                FunctionHelp("ET(a;b)", "Vrai si tout est vrai"),
                FunctionHelp("OU(a;b)", "Vrai si l'un est vrai"),
                FunctionHelp("NON(a)", "Inverse un test"),
                FunctionHelp("SOMME.SI(plage;critère;somme)", "Somme sous condition"),
                FunctionHelp("NB.SI(plage;critère)", "Compte sous condition"),
                FunctionHelp("MOYENNE.SI(plage;critère;moy)", "Moyenne sous condition"),
                FunctionHelp("ESTVIDE(valeur)", "Vrai si la case est vide"),
                FunctionHelp("ESTNUM(valeur)", "Vrai si c'est un nombre"),
                FunctionHelp("ESTTEXTE(valeur)", "Vrai si c'est du texte")
            )
        ),
        FunctionGroup(
            "Texte",
            listOf(
                FunctionHelp("CONCATENER(a;b)", "Colle des textes"),
                FunctionHelp("GAUCHE(texte;n)", "n premiers caractères"),
                FunctionHelp("DROITE(texte;n)", "n derniers caractères"),
                FunctionHelp("STXT(texte;début;n)", "Extrait au milieu"),
                FunctionHelp("NBCAR(texte)", "Nombre de caractères"),
                FunctionHelp("MAJUSCULE(texte)", "Tout en majuscules"),
                FunctionHelp("MINUSCULE(texte)", "Tout en minuscules"),
                FunctionHelp("NOMPROPRE(texte)", "Initiales en majuscule"),
                FunctionHelp("SUPPRESPACE(texte)", "Enlève les espaces en trop"),
                FunctionHelp("SUBSTITUE(texte;ancien;nouveau)", "Remplace un motif"),
                FunctionHelp("REMPLACER(texte;début;n;nouveau)", "Remplace une portion"),
                FunctionHelp("TROUVE(cherché;texte)", "Position, casse respectée"),
                FunctionHelp("CHERCHE(cherché;texte)", "Position, casse ignorée"),
                FunctionHelp("REPT(texte;n)", "Répète un texte"),
                FunctionHelp("JOINDRE.TEXTE(sép;ignorer;plage)", "Assemble une plage"),
                FunctionHelp("TEXTE(valeur;format)", "Met en forme un nombre"),
                FunctionHelp("CNUM(texte)", "Convertit en nombre"),
                FunctionHelp("EXACT(a;b)", "Compare, casse comprise")
            )
        ),
        FunctionGroup(
            "Recherche",
            listOf(
                FunctionHelp("RECHERCHEV(valeur;plage;colonne;approché)", "Cherche dans la 1re colonne"),
                FunctionHelp("RECHERCHEH(valeur;plage;ligne;approché)", "Cherche dans la 1re ligne"),
                FunctionHelp("INDEX(plage;ligne;colonne)", "Valeur à une position"),
                FunctionHelp("EQUIV(valeur;plage;type)", "Position d'une valeur"),
                FunctionHelp("CHOISIR(n;a;b)", "n-ième argument"),
                FunctionHelp("LIGNES(plage)", "Nombre de lignes"),
                FunctionHelp("COLONNES(plage)", "Nombre de colonnes")
            )
        ),
        FunctionGroup(
            "Date et heure",
            listOf(
                FunctionHelp("AUJOURDHUI()", "Date du jour"),
                FunctionHelp("MAINTENANT()", "Date et heure"),
                FunctionHelp("DATE(année;mois;jour)", "Construit une date"),
                FunctionHelp("ANNEE(date)", "Année d'une date"),
                FunctionHelp("MOIS(date)", "Mois d'une date"),
                FunctionHelp("JOUR(date)", "Jour d'une date"),
                FunctionHelp("JOURSEM(date)", "Jour de la semaine"),
                FunctionHelp("JOURS(fin;début)", "Nombre de jours"),
                FunctionHelp("MOIS.DECALER(date;n)", "Décale de n mois"),
                FunctionHelp("FIN.MOIS(date;n)", "Dernier jour du mois")
            )
        ),
        FunctionGroup(
            "Finance",
            listOf(
                FunctionHelp("VPM(taux;durée;capital)", "Mensualité d'un prêt"),
                FunctionHelp("VC(taux;durée;versement;capital)", "Valeur future"),
                FunctionHelp("VA(taux;durée;versement;valeur)", "Valeur actuelle"),
                FunctionHelp("NPM(taux;versement;capital)", "Nombre de périodes")
            )
        )
    )
}
