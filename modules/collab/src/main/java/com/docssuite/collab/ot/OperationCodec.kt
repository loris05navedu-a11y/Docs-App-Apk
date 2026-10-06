package com.docssuite.collab.ot

/**
 * Une opération en texte JSON, telle qu'elle est rangée dans le journal :
 * `[5,"abc",-2]` garde 5 caractères, insère « abc », en efface 2.
 *
 * Une chaîne plutôt qu'un tableau de la base : une opération vide (`[]`, un
 * texte vide qui le reste) s'y range aussi, et rien n'est réinterprété en route.
 */
object OperationCodec {

    fun encode(operation: TextOperation): String = buildString {
        append('[')
        operation.toJson().forEachIndexed { i, item ->
            if (i > 0) append(',')
            when (item) {
                is String -> appendQuoted(item)
                else -> append(item.toString())
            }
        }
        append(']')
    }

    /** L'opération, ou `null` si le texte n'en est pas une. */
    fun decode(json: String?): TextOperation? {
        if (json == null) return null
        val items = Parser(json).array() ?: return null
        return TextOperation.fromJson(items)
    }

    private fun StringBuilder.appendQuoted(text: String) {
        append('"')
        for (c in text) {
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c < ' ' || c == ' ' || c == ' ' -> append("\\u").append(String.format("%04x", c.code))
                else -> append(c)
            }
        }
        append('"')
    }

    /** Juste ce qu'il faut de JSON : un tableau d'entiers et de chaînes. */
    private class Parser(private val s: String) {
        private var i = 0

        fun array(): List<Any>? {
            space()
            if (!take('[')) return null
            val items = ArrayList<Any>()
            space()
            if (take(']')) return if (end()) items else null
            while (true) {
                space()
                items.add(value() ?: return null)
                space()
                if (take(']')) return if (end()) items else null
                if (!take(',')) return null
            }
        }

        private fun value(): Any? = when (s.getOrNull(i)) {
            '"' -> string()
            '-', in '0'..'9' -> number()
            else -> null
        }

        private fun number(): Long? {
            val start = i
            if (s.getOrNull(i) == '-') i++
            val digits = i
            while (s.getOrNull(i)?.isDigit() == true) i++
            if (i == digits || i - digits > 12) return null
            return s.substring(start, i).toLongOrNull()
        }

        private fun string(): String? {
            i++ // "
            val out = StringBuilder()
            while (true) {
                val c = s.getOrNull(i++) ?: return null
                when (c) {
                    '"' -> return out.toString()
                    '\\' -> when (s.getOrNull(i++)) {
                        '"' -> out.append('"')
                        '\\' -> out.append('\\')
                        '/' -> out.append('/')
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000C')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'u' -> {
                            if (i + 4 > s.length) return null
                            out.append(s.substring(i, i + 4).toIntOrNull(16)?.toChar() ?: return null)
                            i += 4
                        }
                        else -> return null
                    }
                    else -> if (c < ' ') return null else out.append(c)
                }
            }
        }

        private fun space() {
            while (s.getOrNull(i)?.let { it == ' ' || it == '\n' || it == '\r' || it == '\t' } == true) i++
        }

        private fun take(c: Char): Boolean = if (s.getOrNull(i) == c) { i++; true } else false

        private fun end(): Boolean {
            space()
            return i == s.length
        }
    }
}
