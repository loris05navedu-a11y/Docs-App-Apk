package com.docssuite.converter

import com.docssuite.converter.model.FileKind
import com.docssuite.converter.model.TargetFormat
import com.docssuite.converter.model.candidateTargets
import com.docssuite.converter.model.formatDuration
import com.docssuite.converter.model.formatSize
import com.docssuite.converter.model.kindForExtension
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La promesse de l'application est qu'aucune conversion impossible n'est
 * proposée. Ces tests verrouillent les règles qui la tiennent.
 */
class ConversionRulesTest {

    @Test
    fun `les extensions sont rangees dans la bonne famille`() {
        assertEquals(FileKind.IMAGE, kindForExtension("jpg"))
        assertEquals(FileKind.IMAGE, kindForExtension("HEIC"))
        assertEquals(FileKind.PDF, kindForExtension("pdf"))
        assertEquals(FileKind.AUDIO, kindForExtension("flac"))
        assertEquals(FileKind.VIDEO, kindForExtension("mkv"))
        assertEquals(FileKind.TEXT, kindForExtension("csv"))
        assertEquals(FileKind.ARCHIVE, kindForExtension("zip"))
        assertEquals(FileKind.OTHER, kindForExtension("xyz"))
    }

    @Test
    fun `une image ne se convertit pas vers son propre format`() {
        val targets = candidateTargets(FileKind.IMAGE, "png")
        assertFalse(TargetFormat.PNG in targets)
        assertTrue(TargetFormat.JPG in targets)
        assertTrue(TargetFormat.WEBP in targets)
        assertTrue(TargetFormat.PDF in targets)
    }

    @Test
    fun `un PDF ne donne que des images`() {
        val targets = candidateTargets(FileKind.PDF, "pdf")
        assertEquals(
            listOf(TargetFormat.PNG, TargetFormat.JPG, TargetFormat.WEBP, TargetFormat.ZIP),
            targets
        )
    }

    @Test
    fun `le texte reste convertible vers le texte pour normaliser l encodage`() {
        val targets = candidateTargets(FileKind.TEXT, "txt")
        assertTrue(TargetFormat.TXT in targets)
        assertTrue(TargetFormat.PDF in targets)
    }

    @Test
    fun `tout fichier peut au moins etre compresse`() {
        FileKind.values().forEach { kind ->
            assertTrue(
                "Aucune cible pour $kind",
                TargetFormat.ZIP in candidateTargets(kind, "bin")
            )
        }
    }

    @Test
    fun `aucun format de sortie ne se declare sans extension ni type mime`() {
        TargetFormat.values().forEach { format ->
            assertTrue(format.extension.isNotBlank())
            assertTrue(format.mime.contains('/'))
            assertTrue(format.label.isNotBlank())
        }
    }

    @Test
    fun `les tailles sont lisibles en francais`() {
        assertEquals("512 o", formatSize(512))
        assertEquals("1,0 ko", formatSize(1024))
        assertEquals("4,8 Mo", formatSize((4.8 * 1024 * 1024).toLong()))
        assertEquals("—", formatSize(-1))
    }

    @Test
    fun `les durees sont lisibles`() {
        assertEquals("3 min 42 s", formatDuration(222_000))
        assertEquals("1 h 01 min", formatDuration(3_660_000))
    }
}
