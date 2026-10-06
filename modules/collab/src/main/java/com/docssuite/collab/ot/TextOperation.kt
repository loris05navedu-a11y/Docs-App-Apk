package com.docssuite.collab.ot

/**
 * Une modification de texte, décrite de bout en bout : garder `n` caractères,
 * en insérer, en supprimer. « Garder 12, insérer "a", garder 30 » est la frappe
 * d'un « a » en 13ᵉ position d'un texte de 42 caractères.
 *
 * C'est le modèle d'ot.js, celui de Firepad : deux modifications faites en même
 * temps sur le même texte se [transform]ent l'une par rapport à l'autre pour
 * donner, appliquées dans n'importe quel ordre, exactement le même résultat.
 *
 * En interne : un entier positif garde, un entier négatif supprime, une chaîne
 * insère. La forme est toujours canonique (morceaux de même sorte fusionnés,
 * insertion avant suppression au même endroit), si bien que deux opérations
 * de même effet sont égales.
 *
 * Les longueurs se comptent en unités UTF-16, comme `String.length` : tous les
 * appareils comptent pareil.
 */
class TextOperation {

    private val ops = ArrayList<Any>()

    /** Longueur du texte auquel l'opération s'applique. */
    var baseLength = 0
        private set

    /** Longueur du texte qu'elle produit. */
    var targetLength = 0
        private set

    fun retain(n: Int): TextOperation {
        require(n >= 0) { "retain négatif" }
        if (n == 0) return this
        baseLength += n
        targetLength += n
        val last = ops.lastOrNull()
        if (last is Int && last > 0) ops[ops.size - 1] = last + n else ops.add(n)
        return this
    }

    fun insert(text: String): TextOperation {
        if (text.isEmpty()) return this
        targetLength += text.length
        val n = ops.size
        val last = ops.lastOrNull()
        when {
            last is String -> ops[n - 1] = last + text
            last is Int && last < 0 -> {
                // Supprimer puis insérer, ou l'inverse, c'est la même chose :
                // on met toujours l'insertion d'abord, pour une forme unique.
                val before = if (n >= 2) ops[n - 2] else null
                if (before is String) ops[n - 2] = before + text else ops.add(n - 1, text)
            }
            else -> ops.add(text)
        }
        return this
    }

    fun delete(n: Int): TextOperation {
        require(n >= 0) { "delete négatif" }
        if (n == 0) return this
        baseLength += n
        val last = ops.lastOrNull()
        if (last is Int && last < 0) ops[ops.size - 1] = last - n else ops.add(-n)
        return this
    }

    /** Ne change rien : seulement des « garder ». */
    val isNoop: Boolean get() = ops.isEmpty() || (ops.size == 1 && (ops[0] as? Int ?: 0) > 0)

    fun apply(text: String): String {
        require(text.length == baseLength) { "L'opération attend un texte de $baseLength caractères, pas ${text.length}" }
        val out = StringBuilder(targetLength)
        var index = 0
        for (op in ops) {
            when {
                op is String -> out.append(op)
                (op as Int) > 0 -> {
                    out.append(text, index, index + op)
                    index += op
                }
                else -> index -= op
            }
        }
        return out.toString()
    }

    /**
     * La même chose en une seule opération : `this` puis [other].
     * `a.compose(b).apply(s) == b.apply(a.apply(s))`.
     */
    fun compose(other: TextOperation): TextOperation {
        require(targetLength == other.baseLength) { "compose : longueurs incompatibles ($targetLength / ${other.baseLength})" }
        val result = TextOperation()
        val ops1 = ops
        val ops2 = other.ops
        var i1 = 0
        var i2 = 0
        var op1: Any? = ops1.getOrNull(i1++)
        var op2: Any? = ops2.getOrNull(i2++)
        while (op1 != null || op2 != null) {
            if (op1 is Int && op1 < 0) {
                result.delete(-op1)
                op1 = ops1.getOrNull(i1++)
                continue
            }
            if (op2 is String) {
                result.insert(op2)
                op2 = ops2.getOrNull(i2++)
                continue
            }
            checkNotNull(op1) { "compose : première opération trop courte" }
            checkNotNull(op2) { "compose : première opération trop longue" }
            when {
                op1 is Int && op2 is Int && op1 > 0 && op2 > 0 -> when {
                    op1 > op2 -> { result.retain(op2); op1 -= op2; op2 = ops2.getOrNull(i2++) }
                    op1 == op2 -> { result.retain(op1); op1 = ops1.getOrNull(i1++); op2 = ops2.getOrNull(i2++) }
                    else -> { result.retain(op1); op2 -= op1; op1 = ops1.getOrNull(i1++) }
                }
                op1 is String && op2 is Int && op2 < 0 -> when {
                    op1.length > -op2 -> { op1 = op1.substring(-op2); op2 = ops2.getOrNull(i2++) }
                    op1.length == -op2 -> { op1 = ops1.getOrNull(i1++); op2 = ops2.getOrNull(i2++) }
                    else -> { op2 += op1.length; op1 = ops1.getOrNull(i1++) }
                }
                op1 is String && op2 is Int && op2 > 0 -> when {
                    op1.length > op2 -> { result.insert(op1.substring(0, op2)); op1 = op1.substring(op2); op2 = ops2.getOrNull(i2++) }
                    op1.length == op2 -> { result.insert(op1); op1 = ops1.getOrNull(i1++); op2 = ops2.getOrNull(i2++) }
                    else -> { result.insert(op1); op2 -= op1.length; op1 = ops1.getOrNull(i1++) }
                }
                op1 is Int && op1 > 0 && op2 is Int && op2 < 0 -> when {
                    op1 > -op2 -> { result.delete(-op2); op1 += op2; op2 = ops2.getOrNull(i2++) }
                    op1 == -op2 -> { result.delete(-op2); op1 = ops1.getOrNull(i1++); op2 = ops2.getOrNull(i2++) }
                    else -> { result.delete(op1); op2 += op1; op1 = ops1.getOrNull(i1++) }
                }
                else -> error("compose : cas impossible ($op1, $op2)")
            }
        }
        return result
    }

    /**
     * Ce que devient une position (curseur, début de sélection) quand cette
     * opération est appliquée. Une insertion pile à la position la repousse
     * après le texte inséré.
     */
    fun transformIndex(position: Int): Int {
        var remaining = position
        var moved = position
        for (op in ops) {
            when {
                op is String -> moved += op.length
                (op as Int) > 0 -> remaining -= op
                else -> {
                    moved -= minOf(remaining, -op)
                    remaining += op
                }
            }
            if (remaining < 0) break
        }
        return moved
    }

    /** Pour l'enregistrement : entiers et chaînes, comme ot.js. */
    fun toJson(): List<Any> = ops.toList()

    override fun equals(other: Any?): Boolean = other is TextOperation && other.ops == ops

    override fun hashCode(): Int = ops.hashCode()

    override fun toString(): String = ops.joinToString(", ", "[", "]") {
        when {
            it is String -> "insert(\"$it\")"
            (it as Int) > 0 -> "retain($it)"
            else -> "delete(${-it})"
        }
    }

    companion object {

        /** Ne change rien à un texte de [length] caractères. */
        fun identity(length: Int): TextOperation = TextOperation().retain(length)

        /**
         * Relit une opération enregistrée ; `null` si elle est mal formée —
         * une donnée abîmée ne doit pas faire planter l'éditeur.
         */
        fun fromJson(items: List<*>?): TextOperation? {
            if (items == null) return null
            val op = TextOperation()
            for (item in items) {
                when (item) {
                    is String -> if (item.isEmpty()) return null else op.insert(item)
                    is Number -> {
                        val n = item.toLong()
                        if (n == 0L || n > Int.MAX_VALUE || n < -Int.MAX_VALUE || item.toDouble() != n.toDouble()) return null
                        if (n > 0) op.retain(n.toInt()) else op.delete((-n).toInt())
                    }
                    else -> return null
                }
            }
            return op
        }

        /**
         * Deux opérations faites en même temps sur le même texte → les deux
         * versions à appliquer après l'autre :
         * `b'.apply(a.apply(s)) == a'.apply(b.apply(s))` pour `(a', b') = transform(a, b)`.
         * À la même position, l'insertion de [a] passe devant celle de [b].
         */
        fun transform(a: TextOperation, b: TextOperation): Pair<TextOperation, TextOperation> {
            require(a.baseLength == b.baseLength) { "transform : longueurs de base différentes (${a.baseLength} / ${b.baseLength})" }
            val aPrime = TextOperation()
            val bPrime = TextOperation()
            val ops1 = a.ops
            val ops2 = b.ops
            var i1 = 0
            var i2 = 0
            var op1: Any? = ops1.getOrNull(i1++)
            var op2: Any? = ops2.getOrNull(i2++)
            while (op1 != null || op2 != null) {
                if (op1 is String) {
                    aPrime.insert(op1)
                    bPrime.retain(op1.length)
                    op1 = ops1.getOrNull(i1++)
                    continue
                }
                if (op2 is String) {
                    aPrime.retain(op2.length)
                    bPrime.insert(op2)
                    op2 = ops2.getOrNull(i2++)
                    continue
                }
                checkNotNull(op1) { "transform : première opération trop courte" }
                checkNotNull(op2) { "transform : première opération trop longue" }
                val x = op1 as Int
                val y = op2 as Int
                when {
                    x > 0 && y > 0 -> {
                        val min: Int
                        when {
                            x > y -> { min = y; op1 = x - y; op2 = ops2.getOrNull(i2++) }
                            x == y -> { min = y; op1 = ops1.getOrNull(i1++); op2 = ops2.getOrNull(i2++) }
                            else -> { min = x; op2 = y - x; op1 = ops1.getOrNull(i1++) }
                        }
                        aPrime.retain(min)
                        bPrime.retain(min)
                    }
                    x < 0 && y < 0 -> when {
                        // Les deux suppriment les mêmes caractères : rien à refaire.
                        -x > -y -> { op1 = x - y; op2 = ops2.getOrNull(i2++) }
                        x == y -> { op1 = ops1.getOrNull(i1++); op2 = ops2.getOrNull(i2++) }
                        else -> { op2 = y - x; op1 = ops1.getOrNull(i1++) }
                    }
                    x < 0 && y > 0 -> {
                        val min: Int
                        when {
                            -x > y -> { min = y; op1 = x + y; op2 = ops2.getOrNull(i2++) }
                            -x == y -> { min = y; op1 = ops1.getOrNull(i1++); op2 = ops2.getOrNull(i2++) }
                            else -> { min = -x; op2 = y + x; op1 = ops1.getOrNull(i1++) }
                        }
                        aPrime.delete(min)
                    }
                    x > 0 && y < 0 -> {
                        val min: Int
                        when {
                            x > -y -> { min = -y; op1 = x + y; op2 = ops2.getOrNull(i2++) }
                            x == -y -> { min = x; op1 = ops1.getOrNull(i1++); op2 = ops2.getOrNull(i2++) }
                            else -> { min = x; op2 = y + x; op1 = ops1.getOrNull(i1++) }
                        }
                        bPrime.delete(min)
                    }
                    else -> error("transform : opérations incompatibles")
                }
            }
            return aPrime to bPrime
        }
    }
}
