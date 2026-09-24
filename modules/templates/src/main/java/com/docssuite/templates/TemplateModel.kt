package com.docssuite.templates

import com.docssuite.fileformats.TextDocument
import com.docssuite.fileformats.Workbook
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Une information demandée avant de créer le document. */
data class Field(
    val key: String,
    val label: String,
    val hint: String = "",
    val multiline: Boolean = false,
    /** Coordonnées personnelles, retenues d'un modèle à l'autre. */
    val profile: Boolean = false,
    val default: String = ""
)

enum class TemplateGroup(val label: String) {
    WORK("Emploi"),
    LETTERS("Courriers"),
    MEETINGS("Travail en équipe"),
    MONEY("Argent")
}

sealed interface Built {
    val name: String

    data class Text(val document: TextDocument, override val name: String) : Built
    data class Sheet(val workbook: Workbook, override val name: String) : Built
}

class Template(
    val id: String,
    val title: String,
    val description: String,
    val group: TemplateGroup,
    val sheet: Boolean,
    val fields: List<Field>,
    private val builder: (Values) -> Built
) {
    fun build(entries: Map<String, String>, today: Date = Date()): Built = builder(Values(entries, fields, today))
}

/**
 * Ce que l'utilisateur a rempli. Un champ laissé vide devient un repère
 * entre crochets, « [Entreprise] », facile à retrouver et à compléter.
 */
class Values(private val entries: Map<String, String>, private val fields: List<Field>, val today: Date) {

    fun has(key: String): Boolean = !entries[key].isNullOrBlank()

    operator fun get(key: String): String =
        entries[key]?.trim()?.takeIf { it.isNotEmpty() }
            ?: fields.firstOrNull { it.key == key }?.let { field -> field.default.ifEmpty { "[${field.label}]" } }
            ?: "[$key]"

    /** Un champ sur plusieurs lignes : une entrée par ligne, lignes vides ignorées. */
    fun lines(key: String): List<String> =
        if (has(key)) entries[key]!!.lines().map { it.trim() }.filter { it.isNotEmpty() } else listOf(get(key))

    fun date(daysLater: Int = 0): String {
        val calendar = Calendar.getInstance().apply {
            time = today
            add(Calendar.DAY_OF_YEAR, daysLater)
        }
        return SimpleDateFormat("d MMMM yyyy", Locale.FRANCE).format(calendar.time)
    }

    fun shortDate(daysLater: Int = 0): String {
        val calendar = Calendar.getInstance().apply {
            time = today
            add(Calendar.DAY_OF_YEAR, daysLater)
        }
        return SimpleDateFormat("dd/MM/yyyy", Locale.FRANCE).format(calendar.time)
    }

    val year: Int get() = Calendar.getInstance().apply { time = today }.get(Calendar.YEAR)
}
