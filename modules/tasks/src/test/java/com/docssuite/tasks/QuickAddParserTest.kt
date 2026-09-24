package com.docssuite.tasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class QuickAddParserTest {

    private val paris = TimeZone.getTimeZone("Europe/Paris")

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0): Long =
        Calendar.getInstance(paris).apply { clear(); set(year, month - 1, day, hour, minute) }.timeInMillis

    /** Jeudi 24 septembre 2026, 14 h 30. */
    private val now = at(2026, 9, 24, 14, 30)

    private fun parse(text: String) = QuickAddParser.parse(text, now, paris)

    @Test
    fun `demain a une heure`() {
        val r = parse("Appeler le dentiste demain 18h")
        assertEquals("Appeler le dentiste", r.title)
        assertEquals(at(2026, 9, 25, 18), r.remindAt)
    }

    @Test
    fun `heures et minutes sous plusieurs formes`() {
        assertEquals(at(2026, 9, 25, 9, 30), parse("Réunion demain à 9h30").remindAt)
        assertEquals(at(2026, 9, 25, 7, 15), parse("Train demain 7:15").remindAt)
        assertEquals(at(2026, 9, 25, 12), parse("Déjeuner demain midi").remindAt)
    }

    @Test
    fun `une heure seule deja passee vise demain`() {
        assertEquals(at(2026, 9, 25, 8), parse("Sortir le chien 8h").remindAt)
        assertEquals(at(2026, 9, 24, 17), parse("Goûter 17h").remindAt)
    }

    @Test
    fun `un jour de la semaine est le prochain`() {
        val r = parse("Rendre le devoir vendredi")
        assertEquals("Rendre le devoir", r.title)
        assertEquals(at(2026, 9, 25, 9), r.remindAt) // demain vendredi, 9 h par défaut
        assertEquals(at(2026, 10, 1, 9), parse("Piscine jeudi").remindAt) // aujourd'hui jeudi : la semaine prochaine
        assertEquals(at(2026, 9, 28, 14), parse("Sport lundi 14h").remindAt)
    }

    @Test
    fun `dates ecrites en chiffres ou en lettres`() {
        assertEquals(at(2026, 10, 12, 14), parse("Réunion le 12/10 14h").remindAt)
        assertEquals(at(2027, 3, 5, 9), parse("Déclaration d'impôts le 5 mars").remindAt)
        assertEquals(at(2026, 12, 24, 20), parse("Réveillon 24 décembre 20h").remindAt)
        assertEquals(at(2027, 1, 15, 9), parse("Vaccin 15/01/2027").remindAt)
    }

    @Test
    fun `le numero du jour vise ce mois ou le suivant`() {
        assertEquals(at(2026, 9, 30, 9), parse("Payer la cantine le 30").remindAt)
        assertEquals(at(2026, 10, 5, 9), parse("Payer le loyer le 5").remindAt)
    }

    @Test
    fun `repetitions`() {
        val r = parse("Payer le loyer le 5 à 9h chaque mois")
        assertEquals("Payer le loyer", r.title)
        assertEquals(Repeat.MONTHLY, r.repeat)
        assertEquals(at(2026, 10, 5, 9), r.remindAt)
        val daily = parse("Prendre le médicament tous les jours 20h")
        assertEquals(Repeat.DAILY, daily.repeat)
        assertEquals(at(2026, 9, 24, 20), daily.remindAt)
    }

    @Test
    fun `sans date le titre reste intact et sans rappel`() {
        val r = parse("Acheter du pain")
        assertEquals("Acheter du pain", r.title)
        assertNull(r.remindAt)
        assertEquals("Lire 3 chapitres", parse("Lire 3 chapitres").title)
    }

    @Test
    fun `ce soir et apres-demain`() {
        assertEquals(at(2026, 9, 24, 19), parse("Appeler maman ce soir").remindAt)
        assertEquals(at(2026, 9, 26, 10), parse("Marché après-demain 10h").remindAt)
    }

    @Test
    fun `repetition mensuelle du 31 sans derive`() {
        val jan31 = at(2027, 1, 31, 9)
        val feb = nextOccurrence(jan31, Repeat.MONTHLY, paris, originalDay = 31)
        assertEquals(at(2027, 2, 28, 9), feb)
        assertEquals(at(2027, 3, 31, 9), nextOccurrence(feb, Repeat.MONTHLY, paris, originalDay = 31))
        assertEquals(at(2026, 10, 1, 14, 30), nextOccurrence(now, Repeat.WEEKLY, paris))
    }
}
