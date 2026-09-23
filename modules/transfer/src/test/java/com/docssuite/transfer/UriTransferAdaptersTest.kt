package com.docssuite.transfer

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * `file://` se résout par le même `ContentResolver.openInputStream` qu'une
 * vraie `content://` : ces tests vérifient la lecture des métadonnées et le
 * flux d'octets sans avoir besoin d'un fournisseur de contenu factice.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class UriTransferAdaptersTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun `taille et octets d un fichier local sont lus correctement`() {
        val file = File(context.cacheDir, "rapport.txt").apply { writeText("Bonjour le monde") }
        val offered = urisToOfferedFiles(context, listOf(Uri.fromFile(file)))

        assertEquals(1, offered.size)
        assertEquals("rapport.txt", offered[0].meta.name)
        assertEquals(file.length(), offered[0].meta.size)
        val bytes = offered[0].source.open().use { it.readBytes() }
        assertEquals("Bonjour le monde", String(bytes))
    }

    @Test
    fun `plusieurs fichiers de types differents sont tous proposes`() {
        val texte = File(context.cacheDir, "notes.md").apply { writeText("# Titre") }
        val binaire = File(context.cacheDir, "image.bin").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }

        val offered = urisToOfferedFiles(context, listOf(Uri.fromFile(texte), Uri.fromFile(binaire)))

        assertEquals(setOf("notes.md", "image.bin"), offered.map { it.meta.name }.toSet())
        assertEquals(4L, offered.first { it.meta.name == "image.bin" }.meta.size)
    }
}
