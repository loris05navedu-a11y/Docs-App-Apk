package com.docssuite.recorder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RecordingsTest {

    @Test
    fun `le chronometre ne compte pas les pauses`() {
        var t = 0L
        val clock = RecordingClock { t }
        clock.start(); t = 10_000
        clock.pause(); t = 60_000          // 50 s de pause
        assertEquals(10_000, clock.elapsed())
        clock.start(); t = 65_000
        assertEquals(15_000, clock.elapsed())
        clock.start()                      // un second départ ne remet rien à zéro
        assertEquals(15_000, clock.elapsed())
    }

    @Test
    fun `le niveau suit une echelle en decibels`() {
        assertEquals(0f, levelOf(0), 0f)
        assertEquals(1f, levelOf(32767), 0.001f)
        assertTrue("une voix moyenne se voit", levelOf(3000) > 0.4f)
        assertTrue("le silence reste bas", levelOf(30) < 0.05f)
    }

    @Test
    fun `durees lisibles`() {
        assertEquals("0:07", formatDuration(7_400))
        assertEquals("1:05", formatDuration(65_000))
        assertEquals("1:02:05", formatDuration(3_725_000))
    }

    @Test
    fun `un enregistrement se garde avec ses reperes se renomme et se supprime`() {
        val store = RecordingStore(RuntimeEnvironment.getApplication())
        val id = store.newId()
        store.audioFile(id).writeBytes(ByteArray(100))
        store.save(Recording(id, "Cours d'histoire", 1_000, 3_600_000, listOf(60_000, 1_200_000), store.audioFile(id)))

        val read = store.list().single { it.id == id }
        assertEquals(listOf(60_000L, 1_200_000L), read.markers)
        assertEquals(3_600_000, read.durationMs)

        store.rename(id, "Histoire — chapitre 3")
        assertEquals("Histoire — chapitre 3", store.read(id)!!.name)

        store.delete(id)
        assertTrue(store.list().none { it.id == id })
        assertTrue(!store.audioFile(id).exists())
    }

    @Test
    fun `des informations sans fichier audio ne sont pas listees`() {
        val store = RecordingStore(RuntimeEnvironment.getApplication())
        val id = store.newId()
        store.save(Recording(id, "Orphelin", 1, 1, emptyList(), store.audioFile(id)))
        assertTrue(store.list().none { it.id == id })
    }
}
