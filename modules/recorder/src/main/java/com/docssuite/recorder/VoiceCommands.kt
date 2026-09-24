package com.docssuite.recorder

/**
 * La dictée rend des mots, pas de ponctuation : « virgule », « à la
 * ligne »… sont remplacés par ce qu'ils désignent, puis la typographie
 * française est rétablie (espaces, majuscules en début de phrase).
 */
object VoiceCommands {

    /** Du plus long au plus court : « point d'interrogation » avant « point ». */
    private val commands: List<Pair<Regex, String>> = listOf(
        "nouveau paragraphe" to "\n\n",
        "point à la ligne" to ".\n",
        "à la ligne" to "\n",
        "nouvelle ligne" to "\n",
        "point d'interrogation" to "?",
        "point d'exclamation" to "!",
        "points de suspension" to "…",
        "point-virgule" to ";",
        "point virgule" to ";",
        "deux-points" to ":",
        "deux points" to ":",
        "virgule" to ",",
        "ouvrez les guillemets" to "«",
        "ouvrir les guillemets" to "«",
        "fermez les guillemets" to "»",
        "fermer les guillemets" to "»",
        "ouvrez la parenthèse" to "(",
        "fermez la parenthèse" to ")",
        "tiret" to "-"
    ).map { (words, symbol) -> Regex("(?i)(?<![\\p{L}'-])${Regex.escape(words)}(?![\\p{L}'-])") to symbol }

    /**
     * « point » seul est ambigu (« le point de vue ») : il n'est remplacé
     * qu'en fin de dictée, là où on le dit pour finir la phrase.
     */
    private val finalPoint = Regex("(?i)(?<![\\p{L}'-])point\\s*$")

    fun apply(spoken: String): String {
        var text = spoken
        commands.forEach { (regex, symbol) -> text = regex.replace(text) { symbol } }
        text = finalPoint.replace(text, ".")
        return typography(text)
    }

    /** Espaces et majuscules à la française. */
    fun typography(text: String): String {
        var t = text
        t = t.replace(Regex("[ \\t]+"), " ")
        t = t.replace(Regex(" *\\n *"), "\n")
        t = t.replace(Regex(" +([,.…)])"), "$1")                  // pas d'espace avant , . … )
        t = t.replace(Regex("\\( +"), "(")
        t = t.replace(Regex(" *([;:?!]) *"), " $1 ")               // une espace avant ; : ? !
        t = t.replace(Regex("« *"), "« ").replace(Regex(" *»"), " »")
        t = t.replace(Regex("([,.…;:?!)])(?=[\\p{L}\\d«(])"), "$1 ") // une espace après la ponctuation
        t = t.replace(Regex("[ ]+"), " ").replace(Regex(" *\\n *"), "\n").trim(' ')
        return capitalize(t)
    }

    /** Majuscule en début de texte, après . ? ! … et en début de ligne. */
    private fun capitalize(text: String): String {
        val out = StringBuilder(text.length)
        var upper = true
        for (c in text) {
            if (upper && c.isLetter()) {
                out.append(c.uppercaseChar())
                upper = false
            } else {
                out.append(c)
                if (c.isLetter() || c.isDigit()) upper = false
            }
            if (c == '.' || c == '?' || c == '!' || c == '…' || c == '\n') upper = true
        }
        return out.toString()
    }

    /**
     * Ajoute une dictée au texte déjà écrit : une espace entre les deux,
     * sauf après un retour à la ligne ; majuscule si la phrase précédente
     * est finie.
     */
    fun append(existing: String, spoken: String): String {
        val addition = apply(spoken)
        if (addition.isEmpty()) return existing
        if (existing.isEmpty()) return addition
        val sentenceOver = existing.trimEnd(' ').let { it.isEmpty() || it.last() in ".?!…\n" }
        // La majuscule de début ajoutée par [apply] n'a pas lieu d'être en
        // milieu de phrase ; celle venue de la reconnaissance (« Paris ») reste.
        val spokenLower = spoken.trimStart().firstOrNull()?.isLowerCase() == true
        val piece = if (!sentenceOver && spokenLower) addition.replaceFirstChar { it.lowercaseChar() } else addition
        val glue = when {
            existing.endsWith("\n") || existing.endsWith(" ") || piece.startsWith("\n") -> ""
            piece.first() in ",.…;:?!)" -> if (piece.first() in ";:?!") " " else ""
            else -> " "
        }
        return existing + glue + piece
    }
}
