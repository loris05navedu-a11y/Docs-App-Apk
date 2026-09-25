package com.docssuite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Calendar

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ToolsTest {

    // ------------------------------------------------------------ le catalogue

    @Test
    fun `chaque outil est unique et range dans une famille`() {
        assertEquals(Tools.all.size, Tools.all.map { it.id }.toSet().size)
        ToolCategory.values().forEach { family ->
            assertTrue(family.label, Tools.of(family).size in 3..6)
        }
        assertEquals(Tools.all.size, ToolCategory.values().sumOf { Tools.of(it).size })
    }

    @Test
    fun `chaque outil a une destination`() {
        val handledByHome = setOf(Tools.NEW_DOCUMENT, Tools.NEW_SHEET, Tools.NEW_DECK, Tools.OPEN_PDF, Tools.OPEN_FILE)
        Tools.all.forEach { tool ->
            assertTrue(tool.id, tool.route != null || tool.id in handledByHome)
            assertTrue(tool.id, tool.keywords.isNotEmpty())
        }
    }

    @Test
    fun `les raccourcis de creation existent tous`() {
        assertEquals(4, Tools.quickCreate.mapNotNull(Tools::byId).size)
        assertEquals(4, Tools.starters.mapNotNull(Tools::byId).size)
    }

    // ------------------------------------------------------------ la recherche

    private fun first(query: String) = Tools.search(query).firstOrNull()?.id

    @Test
    fun `on trouve un outil par ce qu'on veut faire`() {
        assertEquals("pdftools", first("fusionner"))
        assertEquals("pdfsign", first("signature"))
        assertEquals("ocr", first("ocr"))
        assertEquals("transfer", first("wifi"))
        assertEquals("compare", first("différences"))
        assertEquals("tasks", first("rappel"))
        assertEquals("templates", first("facture"))
        assertEquals("mailmerge", first("publipostage"))
    }

    @Test
    fun `on trouve un editeur par le nom du logiciel qu'on connait`() {
        assertEquals(Tools.NEW_DOCUMENT, first("word"))
        assertEquals(Tools.NEW_SHEET, first("Excel"))
        assertEquals(Tools.NEW_DECK, first("powerpoint"))
    }

    @Test
    fun `accents et majuscules sont ignores`() {
        assertEquals(first("présentation"), first("PRESENTATION"))
        assertEquals("tasks", first("taches"))
        assertEquals("pdfsign", first("SIGNER"))
    }

    @Test
    fun `un titre qui commence par le mot tape passe devant`() {
        assertEquals("scanner", first("scan"))
        assertEquals("converter", first("conv"))
    }

    @Test
    fun `plusieurs mots doivent tous se retrouver`() {
        val found = Tools.search("pdf signer").map { it.id }
        assertEquals(listOf("pdfsign"), found)
    }

    @Test
    fun `un mot inconnu ne trouve rien`() {
        assertTrue(Tools.search("zzzz").isEmpty())
        assertTrue(Tools.search("   ").isEmpty())
    }

    @Test
    fun `chercher PDF montre toute la famille PDF`() {
        val found = Tools.search("pdf").map { it.id }
        Tools.of(ToolCategory.PDF).forEach { assertTrue(it.id, it.id in found) }
    }

    // ------------------------------------------------------------ les raccourcis

    @Test
    fun `sans usage on propose de quoi commencer`() {
        assertEquals(Tools.starters, Tools.shortcuts(emptyMap()).map { it.id })
    }

    @Test
    fun `les plus utilises passent devant, a egalite le plus recent`() {
        val usage = mapOf(
            "compare" to ToolUse(3, 100),
            "ocr" to ToolUse(5, 50),
            "backup" to ToolUse(3, 200)
        )
        assertEquals(listOf("ocr", "backup", "compare", "scanner"), Tools.shortcuts(usage).map { it.id })
    }

    @Test
    fun `ce qui est deja dans Nouveau ne revient pas en raccourci`() {
        val usage = mapOf(Tools.NEW_DOCUMENT to ToolUse(40, 1), "templates" to ToolUse(12, 1))
        val ids = Tools.shortcuts(usage).map { it.id }
        assertFalse(Tools.NEW_DOCUMENT in ids)
        assertFalse("templates" in ids)
        assertEquals(Tools.starters, ids)
    }

    @Test
    fun `un outil retire du catalogue est ignore`() {
        val ids = Tools.shortcuts(mapOf("disparu" to ToolUse(99, 1))).map { it.id }
        assertEquals(Tools.starters, ids)
    }

    @Test
    fun `l'usage est retenu d'un lancement a l'autre`() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("docssuite_home", 0).edit().clear().commit()
        val usage = ToolUsage(app)
        assertFalse(usage.any)
        usage.record("compare", now = 10)
        usage.record("compare", now = 20)
        usage.record(Tools.NEW_DOCUMENT, now = 30)
        val all = ToolUsage(app).all()
        assertEquals(ToolUse(2, 20), all["compare"])
        assertTrue(ToolUsage(app).any)
        assertEquals("compare", Tools.shortcuts(all).first().id)
    }

    @Test
    fun `creer un document ne suffit pas a personnaliser les raccourcis`() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("docssuite_home", 0).edit().clear().commit()
        ToolUsage(app).record(Tools.NEW_SHEET)
        assertFalse(ToolUsage(app).any)
    }

    // ------------------------------------------------------------ les dates

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int) =
        Calendar.getInstance().apply { set(year, month, day, hour, minute, 0) }.timeInMillis

    private val now = at(2026, Calendar.SEPTEMBER, 25, 15, 0)

    @Test
    fun `une date se dit comme on la dirait`() {
        assertEquals("à l'instant", relativeTime(now - 20_000, now))
        assertEquals("il y a 5 min", relativeTime(now - 5 * 60_000, now))
        assertEquals("aujourd'hui, 09:30", relativeTime(at(2026, Calendar.SEPTEMBER, 25, 9, 30), now))
        assertEquals("hier, 18:40", relativeTime(at(2026, Calendar.SEPTEMBER, 24, 18, 40), now))
        assertEquals("12 sept.", relativeTime(at(2026, Calendar.SEPTEMBER, 12, 8, 0), now))
        assertEquals("3 mars 2025", relativeTime(at(2025, Calendar.MARCH, 3, 8, 0), now))
    }

    @Test
    fun `hier se reconnait aussi au changement d'annee`() {
        val newYear = at(2027, Calendar.JANUARY, 1, 10, 0)
        assertEquals("hier, 22:00", relativeTime(at(2026, Calendar.DECEMBER, 31, 22, 0), newYear))
    }
}
