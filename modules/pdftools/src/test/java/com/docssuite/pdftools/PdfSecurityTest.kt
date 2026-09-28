package com.docssuite.pdftools

import com.docssuite.pdftools.PdfTestFiles.bytes
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Les fichiers `enc-*` sont produits par MuPDF, mot de passe d'ouverture
 * « lune », du propriétaire « soleil » ; `enc-owner-only` n'a qu'un mot de
 * passe de propriétaire et restreint copie et modification.
 */
class PdfSecurityTest {

    private val encrypted = mapOf(
        "enc-rc4-40.pdf" to "RC4 40 bits",
        "enc-rc4-128.pdf" to "RC4 128 bits",
        "enc-aes-128.pdf" to "AES 128 bits",
        "enc-aes-256.pdf" to "AES 256 bits",
        "enc-aes-256-objstm.pdf" to "AES 256 bits"
    )

    /** Ce que les tests écrivent, pour vérification par un autre lecteur (-Ppdfout=dossier). */
    private fun keep(name: String, bytes: ByteArray) {
        val dir = System.getProperty("pdfout") ?: return
        File(dir).mkdirs()
        File(dir, name).writeBytes(bytes)
    }

    private fun title(file: PdfFile): String? {
        val info = file.resolve(file.trailer["Info"]) as? PdfDict ?: return null
        val raw = (file.resolve(info["Title"]) as? PdfString)?.bytes() ?: return null
        return if (raw.size >= 2 && raw[0] == 0xFE.toByte() && raw[1] == 0xFF.toByte()) {
            String(raw, 2, raw.size - 2, Charsets.UTF_16BE)
        } else {
            String(raw, Charsets.ISO_8859_1)
        }
    }

    private fun outlineCount(file: PdfFile): Int {
        val catalog = file.resolve(file.trailer["Root"]) as PdfDict
        val outlines = file.resolve(catalog["Outlines"]) as? PdfDict ?: return 0
        var count = 0
        var item = file.resolve(outlines["First"]) as? PdfDict
        while (item != null && count < 100) {
            count++
            item = file.resolve(item["Next"]) as? PdfDict
        }
        return count
    }

    /** Le texte d'une page, décompressé : de quoi vérifier que le déchiffrement est juste. */
    private fun pageText(file: PdfFile, index: Int): String {
        val stream = when (val contents = file.resolve(file.pages[index].dict["Contents"])) {
            is PdfStream -> listOf(contents)
            is PdfArray -> contents.items.map { file.resolve(it) as PdfStream }
            else -> emptyList()
        }
        return stream.joinToString("\n") { String(PdfFilters.decode(it, file::resolve), Charsets.ISO_8859_1) }
    }

    // ------------------------------------------------------------ ouvrir

    @Test
    fun `les quatre generations de chiffrement s'ouvrent avec le mot de passe`() {
        val reference = PdfFile.parse(bytes("plain-bulletin.pdf"))
        encrypted.forEach { (name, description) ->
            val file = PdfFile.parse(bytes(name), "lune")
            assertEquals(name, 2, file.pages.size)
            assertEquals(name, description, file.security?.description)
            assertTrue(name, file.security!!.needsPassword)
            assertFalse(name, file.security!!.owner)
            assertEquals(name, "Bulletin confidentiel", title(file))
            assertEquals(name, 2, outlineCount(file))
            // Le contenu déchiffré est exactement celui du fichier en clair.
            assertEquals(name, pageText(reference, 0), pageText(file, 0))
        }
    }

    @Test
    fun `le mot de passe du proprietaire ouvre aussi`() {
        encrypted.keys.forEach { name ->
            val file = PdfFile.parse(bytes(name), "soleil")
            assertTrue(name, file.security!!.owner)
            assertEquals(name, "Bulletin confidentiel", title(file))
        }
    }

    @Test
    fun `sans mot de passe on sait qu'il en faut un`() {
        encrypted.keys.forEach { name ->
            assertThrows(name, PdfEncryptedException::class.java) { PdfFile.parse(bytes(name)) }
            assertEquals(name, PdfLock.Status.NeedsPassword, PdfLock.status(bytes(name)))
        }
    }

    @Test
    fun `un mauvais mot de passe est refuse`() {
        encrypted.keys.forEach { name ->
            assertThrows(name, PdfWrongPasswordException::class.java) { PdfFile.parse(bytes(name), "Lune") }
        }
    }

    @Test
    fun `un PDF seulement restreint s'ouvre sans mot de passe`() {
        val file = PdfFile.parse(bytes("enc-owner-only.pdf"))
        assertEquals(2, file.pages.size)
        assertFalse(file.security!!.needsPassword)
        assertEquals("Bulletin confidentiel", title(file))
        assertEquals(PdfLock.Status.Restricted("AES 128 bits"), PdfLock.status(bytes("enc-owner-only.pdf")))
    }

    @Test
    fun `un PDF sans protection le dit`() {
        assertNull(PdfFile.parse(bytes("plain-bulletin.pdf")).security)
        assertEquals(PdfLock.Status.Open, PdfLock.status(bytes("plain-bulletin.pdf")))
    }

    // ------------------------------------------------------------ déverrouiller

    @Test
    fun `deverrouiller donne le meme document, sans mot de passe`() {
        encrypted.keys.forEach { name ->
            val unlocked = PdfLock.unlock(bytes(name), "lune")
            keep("unlocked-$name", unlocked)
            val file = PdfFile.parse(unlocked)
            assertNull(name, file.security)
            assertEquals(name, 2, file.pages.size)
            assertEquals(name, "Bulletin confidentiel", title(file))
            assertEquals(name, 2, outlineCount(file))
            assertEquals(name, pageText(PdfFile.parse(bytes(name), "lune"), 1), pageText(file, 1))
        }
    }

    @Test
    fun `deverrouiller leve les restrictions d'un PDF restreint`() {
        val unlocked = PdfLock.unlock(bytes("enc-owner-only.pdf"))
        keep("unlocked-owner-only.pdf", unlocked)
        assertNull(PdfFile.parse(unlocked).security)
    }

    @Test
    fun `un PDF deverrouille se fusionne comme un autre`() {
        val unlocked = PdfFile.parse(PdfLock.unlock(bytes("enc-aes-128.pdf"), "lune"))
        val merged = PdfAssembler.assemble(listOf(PageSelection(unlocked, 1), PageSelection(unlocked, 0)))
        assertEquals(2, PdfFile.parse(merged).pages.size)
    }

    // ------------------------------------------------------------ protéger

    @Test
    fun `proteger demande ensuite le mot de passe`() {
        val protected = PdfLock.protect(bytes("plain-bulletin.pdf"), "Été 2026 ✓")
        keep("protected.pdf", protected)
        assertThrows(PdfEncryptedException::class.java) { PdfFile.parse(protected) }
        assertThrows(PdfWrongPasswordException::class.java) { PdfFile.parse(protected, "ete 2026") }

        val file = PdfFile.parse(protected, "Été 2026 ✓")
        assertEquals("AES 256 bits", file.security!!.description)
        assertEquals(2, file.pages.size)
        assertEquals("Bulletin confidentiel", title(file))
        assertEquals(2, outlineCount(file))
        assertEquals(pageText(PdfFile.parse(bytes("plain-bulletin.pdf")), 0), pageText(file, 0))
    }

    @Test
    fun `le texte ne se lit plus en clair dans le fichier protege`() {
        val protected = PdfLock.protect(bytes("plain-bulletin.pdf"), "lune")
        val raw = String(protected, Charsets.ISO_8859_1)
        assertFalse(raw.contains("Bulletin confidentiel"))
        assertFalse(raw.contains("Service paie"))
    }

    @Test
    fun `restreindre garde lecture et impression, retire copie et modification`() {
        val protected = PdfLock.protect(bytes("plain-bulletin.pdf"), "lune", restrict = true)
        keep("protected-restricted.pdf", protected)
        val permissions = PdfFile.parse(protected, "lune").security!!.permissions
        assertTrue(permissions and 0x4 != 0) // impression
        assertTrue(permissions and 0x8 == 0) // modification
        assertTrue(permissions and 0x10 == 0) // copie
        assertEquals(-4, PdfFile.parse(PdfLock.protect(bytes("plain-bulletin.pdf"), "lune"), "lune").security!!.permissions)
    }

    @Test
    fun `changer de mot de passe`() {
        val changed = PdfLock.protect(bytes("enc-rc4-40.pdf"), "nouveau", currentPassword = "lune")
        keep("password-changed.pdf", changed)
        assertThrows(PdfWrongPasswordException::class.java) { PdfFile.parse(changed, "lune") }
        val file = PdfFile.parse(changed, "nouveau")
        assertEquals("AES 256 bits", file.security!!.description)
        assertEquals("Bulletin confidentiel", title(file))
    }

    @Test
    fun `deux protections du meme fichier ne se ressemblent pas`() {
        val a = PdfLock.protect(bytes("plain-bulletin.pdf"), "lune")
        val b = PdfLock.protect(bytes("plain-bulletin.pdf"), "lune")
        assertFalse(a.contentEquals(b))
    }

    @Test
    fun `proteger puis deverrouiller rend le contenu intact`() {
        val original = PdfFile.parse(bytes("beta-objstm.pdf"))
        val back = PdfFile.parse(PdfLock.unlock(PdfLock.protect(bytes("beta-objstm.pdf"), "x"), "x"))
        assertEquals(original.pages.size, back.pages.size)
        for (i in original.pages.indices) {
            assertArrayEquals(PdfTestFiles.content(original, i), PdfTestFiles.content(back, i))
        }
    }

    @Test
    fun `un mot de passe vide est refuse`() {
        assertThrows(IllegalArgumentException::class.java) { PdfLock.protect(bytes("plain-bulletin.pdf"), "") }
    }

    // ------------------------------------------------------------ chaînes

    @Test
    fun `les chaines litterales se decodent`() {
        fun decode(text: String) = String(PdfString(text.toByteArray(Charsets.ISO_8859_1)).bytes(), Charsets.ISO_8859_1)
        assertEquals("a(b)c", decode("(a\\(b\\)c)"))
        assertEquals("ligne\nsuite", decode("(ligne\\nsuite)"))
        assertEquals("A", decode("(\\101)"))
        assertEquals("coupée", decode("(cou\\\npée)"))
        assertEquals("x\\y", decode("(x\\\\y)"))
        assertEquals("AB", String(PdfString("<4142>".toByteArray()).bytes()))
        // Un chiffre hexadécimal seul se complète d'un 0 : <5> vaut <50>, « P ».
        assertEquals("P", String(PdfString("<5>".toByteArray()).bytes()))
    }
}
