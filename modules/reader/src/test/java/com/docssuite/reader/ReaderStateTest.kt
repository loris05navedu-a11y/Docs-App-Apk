package com.docssuite.reader

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ReaderStateTest {

    private val context get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val text = "Première phrase. Deuxième phrase ! Troisième ?\nQuatrième ligne."

    @Before
    fun clean() {
        context.getSharedPreferences("docssuite_reader", 0).edit().clear().commit()
    }

    private fun reader(speaker: FakeSpeaker = FakeSpeaker()) = ReaderState(speaker, ReaderPositions(context)) to speaker

    @Test
    fun `la lecture enchaine les phrases sans blanc et s arrete a la fin`() {
        val (state, voice) = reader()
        state.load("doc:1", "Cours", text)
        assertEquals(4, state.sentences.size)
        state.play()
        // La phrase en cours et la suivante sont déjà confiées à la voix.
        assertEquals(listOf("Première phrase.", "Deuxième phrase !"), voice.queue.map { it.second })
        voice.finishOne()
        assertEquals(1, state.index)
        assertEquals(listOf("Deuxième phrase !", "Troisième ?"), voice.queue.map { it.second })
        repeat(3) { voice.finishOne() }
        assertEquals(listOf("Première phrase.", "Deuxième phrase !", "Troisième ?", "Quatrième ligne."), voice.spoken)
        assertFalse(state.playing)
        assertTrue(state.finished)
        // Relancer après la fin repart du début.
        state.play()
        assertEquals(0, state.index)
        assertEquals("Première phrase.", voice.queue.first().second)
    }

    @Test
    fun `pause puis reprise au debut de la phrase interrompue, meme plus tard`() {
        val (state, voice) = reader()
        state.load("doc:1", "Cours", text)
        state.play()
        voice.finishOne()
        voice.finishOne()
        state.pause()
        assertFalse(state.playing)
        assertEquals(2, state.index)

        // Un « fini » tardif de l'ancienne lecture ne fait pas avancer.
        voice.onDone("1:2")
        assertEquals(2, state.index)

        val (again, voice2) = reader()
        again.load("doc:1", "Cours", text)
        assertEquals(2, again.index)
        again.play()
        assertEquals("Troisième ?", voice2.queue.first().second)

        // Un texte modifié depuis ne reprend pas à une position qui n'a plus de sens.
        val (changed, _) = reader()
        changed.load("doc:1", "Cours", "$text Une phrase ajoutée.")
        assertEquals(0, changed.index)
    }

    @Test
    fun `sauter a une phrase, suivante et precedente`() {
        val (state, voice) = reader()
        state.load("paste", "Texte", text)
        state.jumpTo(3)
        assertEquals(3, state.index)
        assertTrue(voice.queue.isEmpty())
        state.play()
        state.previous()
        assertEquals(2, state.index)
        assertTrue(state.playing)
        assertEquals(listOf("Troisième ?", "Quatrième ligne."), voice.queue.map { it.second })
        state.next()
        state.next()
        assertEquals(3, state.index)
        assertEquals(listOf("Quatrième ligne."), voice.queue.map { it.second })
    }

    @Test
    fun `la vitesse est retenue et relance la phrase en cours`() {
        val (state, voice) = reader()
        state.load("paste", "Texte", text)
        state.play()
        voice.finishOne()
        val stops = voice.stops
        state.changeRate(1.5f)
        assertEquals(1.5f, voice.currentRate)
        assertEquals(stops + 1, voice.stops)
        assertEquals("Deuxième phrase !", voice.queue.first().second)
        val (later, laterVoice) = reader()
        assertEquals(1.5f, later.rate)
        assertEquals(1.5f, laterVoice.currentRate)
    }

    @Test
    fun `lire avant que la voix soit prete demarre des qu elle l est`() {
        val (state, voice) = reader(FakeSpeaker(ready = null))
        state.load("paste", "Texte", text)
        state.play()
        assertTrue(voice.queue.isEmpty())
        voice.becomeReady(true)
        assertEquals("Première phrase.", voice.queue.first().second)
        assertTrue(state.playing)
    }

    @Test
    fun `sans voix francaise on l explique au lieu de rester muet`() {
        val (state, voice) = reader(FakeSpeaker(ready = null))
        state.load("paste", "Texte", text)
        state.play()
        voice.becomeReady(false, "La voix française n'est pas installée.")
        assertEquals("La voix française n'est pas installée.", state.voiceProblem)
        assertFalse(state.playing)
        assertTrue(voice.queue.isEmpty())
    }

    @Test
    fun `une phrase en erreur ne bloque pas la lecture`() {
        val (state, voice) = reader()
        state.load("paste", "Texte", text)
        state.play()
        voice.onError(voice.queue.first().first)
        assertEquals(1, state.index)
        assertTrue(state.playing)
    }

    @Test
    fun `temps restant et fermeture`() {
        val (state, voice) = reader()
        state.load("paste", "Texte", "Mot. ".repeat(400).trim())
        assertEquals(2, state.remainingMinutes())
        state.changeRate(2f)
        assertEquals(1, state.remainingMinutes())
        state.release()
        assertTrue(voice.shutDown)
        state.unload()
        assertFalse(state.loaded)
        assertNull(ReaderPositions(context).get("paste"))
    }
}
