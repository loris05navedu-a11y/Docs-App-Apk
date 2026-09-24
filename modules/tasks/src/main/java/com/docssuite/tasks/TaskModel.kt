package com.docssuite.tasks

import java.text.Normalizer
import java.util.Calendar
import java.util.TimeZone

enum class Repeat(val label: String) {
    NONE("Jamais"),
    DAILY("Chaque jour"),
    WEEKLY("Chaque semaine"),
    MONTHLY("Chaque mois")
}

data class TaskList(val id: String, val name: String)

data class Task(
    val id: String,
    val listId: String,
    val title: String,
    val notes: String = "",
    val done: Boolean = false,
    /** Heure du rappel (et échéance) ; `null` sans rappel. */
    val remindAt: Long? = null,
    val repeat: Repeat = Repeat.NONE,
    val createdAt: Long = 0,
    /**
     * Pour une répétition mensuelle : le jour choisi au départ. Sans lui,
     * un rappel le 31 passé au 28 février resterait ensuite au 28.
     */
    val repeatDay: Int? = null
)

/**
 * La prochaine occurrence d'une tâche qui se répète. Un rappel le 31 qui
 * revient chaque mois tombe le dernier jour des mois plus courts, puis
 * revient au 31 : on repart toujours du jour d'origine.
 */
fun nextOccurrence(time: Long, repeat: Repeat, zone: TimeZone = TimeZone.getDefault(), originalDay: Int? = null): Long {
    val c = Calendar.getInstance(zone).apply { timeInMillis = time }
    when (repeat) {
        Repeat.NONE -> return time
        Repeat.DAILY -> c.add(Calendar.DAY_OF_MONTH, 1)
        Repeat.WEEKLY -> c.add(Calendar.WEEK_OF_YEAR, 1)
        Repeat.MONTHLY -> {
            val day = originalDay ?: c.get(Calendar.DAY_OF_MONTH)
            c.set(Calendar.DAY_OF_MONTH, 1)
            c.add(Calendar.MONTH, 1)
            c.set(Calendar.DAY_OF_MONTH, minOf(day, c.getActualMaximum(Calendar.DAY_OF_MONTH)))
        }
    }
    return c.timeInMillis
}

/** Résultat de l'analyse d'une saisie rapide. */
data class QuickAdd(val title: String, val remindAt: Long?, val repeat: Repeat)

/**
 * Comprend une date et une heure écrites naturellement dans le titre :
 * « Appeler le dentiste demain 18h », « Payer le loyer le 5 à 9h30 chaque
 * mois », « Rendre le devoir vendredi », « Réunion le 12/10 14h ». Ce qui
 * a servi à la date est retiré du titre.
 */
object QuickAddParser {

    private val weekdays = listOf("lundi", "mardi", "mercredi", "jeudi", "vendredi", "samedi", "dimanche")
    private val months = listOf(
        "janvier", "fevrier", "mars", "avril", "mai", "juin",
        "juillet", "aout", "septembre", "octobre", "novembre", "decembre"
    )

    /** Heure par défaut quand seule la date est donnée. */
    private const val DEFAULT_HOUR = 9

    fun parse(input: String, now: Long, zone: TimeZone = TimeZone.getDefault()): QuickAdd {
        var text = " " + input.trim() + " "
        fun folded(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}"), "").lowercase()

        // Répétition.
        var repeat = Repeat.NONE
        listOf(
            Regex("(?i)\\s(tous les jours|chaque jour)\\s") to Repeat.DAILY,
            Regex("(?i)\\s(toutes les semaines|chaque semaine)\\s") to Repeat.WEEKLY,
            Regex("(?i)\\s(tous les mois|chaque mois)\\s") to Repeat.MONTHLY
        ).forEach { (regex, value) ->
            regex.find(text)?.let { m -> repeat = value; text = text.replaceRange(m.range, " ") }
        }

        // Heure : « 18h », « 18 h 30 », « 9h30 », « à 7:15 », « midi ».
        var hour: Int? = null
        var minute = 0
        Regex("(?i)\\s(?:à\\s+|a\\s+|vers\\s+)?(\\d{1,2})\\s?(?:h|:)\\s?(\\d{2})?(?=\\s)").find(text)?.let { m ->
            val h = m.groupValues[1].toInt()
            val min = m.groupValues[2].toIntOrNull() ?: 0
            if (h in 0..23 && min in 0..59) {
                hour = h; minute = min
                text = text.replaceRange(m.range, " ")
            }
        }
        if (hour == null) Regex("(?i)\\s(?:à\\s+)?(midi|minuit)(?=\\s)").find(text)?.let { m ->
            hour = if (m.groupValues[1].lowercase() == "midi") 12 else 0
            text = text.replaceRange(m.range, " ")
        }

        // Jour.
        val base = Calendar.getInstance(zone).apply { timeInMillis = now }
        var day: Calendar? = null
        fun at(offsetDays: Int) = (base.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, offsetDays) }

        val foldedText = folded(text)
        run {
            Regex("\\s(apres-demain|apres demain)\\s").find(foldedText)?.let { m ->
                day = at(2); text = text.replaceRange(m.range, " "); return@run
            }
            Regex("\\s(aujourd'hui|aujourdhui|ce soir)\\s").find(foldedText)?.let { m ->
                day = at(0)
                if (m.groupValues[1] == "ce soir" && hour == null) hour = 19
                text = text.replaceRange(m.range, " "); return@run
            }
            Regex("\\sdemain\\s").find(foldedText)?.let { m ->
                day = at(1); text = text.replaceRange(m.range, " "); return@run
            }
            // « le 12/10 », « 12/10/2026 »
            Regex("\\s(?:le\\s+)?(\\d{1,2})/(\\d{1,2})(?:/(\\d{2,4}))?\\s").find(foldedText)?.let { m ->
                val d = m.groupValues[1].toInt()
                val mo = m.groupValues[2].toInt() - 1
                val y = m.groupValues[3].takeIf { it.isNotEmpty() }?.toInt()?.let { if (it < 100) 2000 + it else it }
                if (d in 1..31 && mo in 0..11) {
                    day = dated(base, d, mo, y)
                    text = text.replaceRange(m.range, " "); return@run
                }
            }
            // « le 5 mars », « 14 juillet »
            Regex("\\s(?:le\\s+)?(\\d{1,2})\\s(${months.joinToString("|")})(?:\\s(\\d{4}))?\\s").find(foldedText)?.let { m ->
                day = dated(base, m.groupValues[1].toInt(), months.indexOf(m.groupValues[2]), m.groupValues[3].toIntOrNull())
                text = text.replaceRange(m.range, " "); return@run
            }
            // « lundi », « vendredi prochain »
            Regex("\\s(?:ce\\s+)?(${weekdays.joinToString("|")})(\\sprochain)?\\s").find(foldedText)?.let { m ->
                val target = weekdays.indexOf(m.groupValues[1]) // 0 = lundi
                val today = (base.get(Calendar.DAY_OF_WEEK) + 5) % 7
                var offset = (target - today + 7) % 7
                if (offset == 0) offset = 7
                day = at(offset)
                text = text.replaceRange(m.range, " "); return@run
            }
            // « le 5 » : le prochain 5 du mois
            Regex("\\sle\\s(\\d{1,2})(?:er)?\\s").find(foldedText)?.let { m ->
                val d = m.groupValues[1].toInt()
                if (d in 1..31) {
                    day = dated(base, d, base.get(Calendar.MONTH), null)
                    text = text.replaceRange(m.range, " ")
                }
            }
        }

        var remindAt: Long? = null
        val chosenDay = day
        if (chosenDay != null || hour != null) {
            val c = (chosenDay ?: base.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, hour ?: DEFAULT_HOUR)
                set(Calendar.MINUTE, if (hour != null) minute else 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            // Une heure seule déjà passée aujourd'hui : c'est pour demain.
            if (chosenDay == null && c.timeInMillis <= now) c.add(Calendar.DAY_OF_MONTH, 1)
            remindAt = c.timeInMillis
        }
        if (repeat != Repeat.NONE && remindAt == null) {
            remindAt = (base.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, DEFAULT_HOUR); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                if (timeInMillis <= now) add(Calendar.DAY_OF_MONTH, 1)
            }.timeInMillis
        }

        val title = text.replace(Regex("\\s+"), " ").trim().trimEnd(',', ';', '-').trim()
        return QuickAdd(title.ifEmpty { input.trim() }, remindAt, repeat)
    }

    /** Ce jour-là, cette année ; s'il est déjà passé, l'an prochain (ou le mois prochain pour « le 5 »). */
    private fun dated(base: Calendar, dayOfMonth: Int, month: Int, year: Int?): Calendar {
        val c = (base.clone() as Calendar).apply {
            set(Calendar.DAY_OF_MONTH, 1)
            if (year != null) set(Calendar.YEAR, year)
            set(Calendar.MONTH, month)
            set(Calendar.DAY_OF_MONTH, minOf(dayOfMonth, getActualMaximum(Calendar.DAY_OF_MONTH)))
        }
        val startOfToday = (base.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        if (year == null && c.before(startOfToday)) {
            if (month == base.get(Calendar.MONTH)) c.add(Calendar.MONTH, 1) else c.add(Calendar.YEAR, 1)
            c.set(Calendar.DAY_OF_MONTH, minOf(dayOfMonth, c.getActualMaximum(Calendar.DAY_OF_MONTH)))
        }
        return c
    }
}
