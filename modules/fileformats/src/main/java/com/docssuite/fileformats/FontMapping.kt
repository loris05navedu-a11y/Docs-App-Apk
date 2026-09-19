package com.docssuite.fileformats

/**
 * Les polices de l'application portent des noms Android (« Sans Serif »,
 * « Manuscrite »…) qui ne veulent rien dire pour Word ou LibreOffice. On
 * traduit dans les deux sens vers des familles que les suites bureautiques
 * connaissent réellement, sinon le document s'ouvre dans une police au hasard.
 */
private val appToOffice = listOf(
    "Défaut" to "Calibri",
    "Sans Serif" to "Arial",
    "Serif" to "Times New Roman",
    "Monospace" to "Courier New",
    "Manuscrite" to "Comic Sans MS",
    "Roboto" to "Arial",
    "Roboto Light" to "Arial Light",
    "Roboto Thin" to "Arial Light",
    "Roboto Medium" to "Arial",
    "Roboto Black" to "Arial Black",
    "Roboto Condensed" to "Arial Narrow",
    "Condensed Light" to "Arial Narrow",
    "Condensed Medium" to "Arial Narrow",
    "Petites capitales" to "Calibri",
    "Noto Serif" to "Georgia",
    "Serif Monospace" to "Courier New",
    "Décontractée" to "Comic Sans MS",
    "Cursive système" to "Comic Sans MS",
    "Droid Sans" to "Verdana",
    "Droid Serif" to "Georgia"
)

/** Nom de police à écrire dans le fichier, à partir du libellé de l'app. */
fun officeFontName(appLabel: String?): String {
    if (appLabel.isNullOrBlank()) return "Calibri"
    return appToOffice.firstOrNull { it.first.equals(appLabel, ignoreCase = true) }?.second
        ?: appLabel
}

/**
 * Libellé de l'app le plus proche d'un nom de police lu dans un fichier. On
 * tente l'équivalence exacte, puis une correspondance par familles
 * (serif / mono / manuscrite), et on retombe sur « Défaut ».
 */
fun appFontLabel(officeName: String?): String {
    val name = officeName?.trim().orEmpty()
    if (name.isEmpty()) return "Défaut"
    appToOffice.firstOrNull { it.second.equals(name, ignoreCase = true) }?.let { return it.first }
    appToOffice.firstOrNull { it.first.equals(name, ignoreCase = true) }?.let { return it.first }
    val lower = name.lowercase()
    return when {
        listOf("courier", "mono", "consolas", "menlo").any { it in lower } -> "Monospace"
        listOf("comic", "script", "hand", "brush").any { it in lower } -> "Manuscrite"
        listOf("times", "georgia", "garamond", "serif", "book", "cambria")
            .any { it in lower } && "sans" !in lower -> "Serif"
        else -> "Sans Serif"
    }
}
