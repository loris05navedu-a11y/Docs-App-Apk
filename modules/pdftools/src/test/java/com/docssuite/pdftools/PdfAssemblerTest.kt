package com.docssuite.pdftools

import com.docssuite.pdftools.PdfTestFiles.content
import com.docssuite.pdftools.PdfTestFiles.mediaBox
import com.docssuite.pdftools.PdfTestFiles.open
import com.docssuite.pdftools.PdfTestFiles.rotation
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PdfAssemblerTest {

    private fun all(file: PdfFile) = file.pages.indices.map { PageSelection(file, it) }

    /** Garde le résultat dans build/ pour une vérification avec un lecteur PDF indépendant. */
    private fun keep(name: String, bytes: ByteArray): PdfFile {
        File("build/pdftools-out").apply { mkdirs() }.resolve(name).writeBytes(bytes)
        return PdfFile.parse(bytes).also { assertFalse("$name a dû être réparé", it.repaired) }
    }

    /** Toute référence du fichier produit mène à un objet qui existe. */
    private fun assertNoDanglingReferences(file: PdfFile) {
        val seen = HashSet<Int>()
        fun visit(obj: PdfObject?) {
            when (obj) {
                is PdfRef -> {
                    assertTrue("référence ${obj.num} pendante", file.getObject(obj.num) != PdfNull)
                    if (seen.add(obj.num)) visit(file.getObject(obj.num))
                }
                is PdfDict -> obj.entries.values.forEach(::visit)
                is PdfArray -> obj.items.forEach(::visit)
                is PdfStream -> visit(obj.dict)
                else -> {}
            }
        }
        visit(file.trailer)
    }

    @Test
    fun `deux pdf fusionnes gardent chaque page intacte et dans l ordre`() {
        val alpha = open("alpha-simple.pdf")
        val beta = open("beta-objstm.pdf")
        val merged = keep("fusion-alpha-beta.pdf", PdfAssembler.assemble(all(alpha) + all(beta), "Fusion"))

        assertEquals(7, merged.pages.size)
        for (i in 0 until 3) assertArrayEquals(content(alpha, i), content(merged, i))
        for (i in 0 until 4) assertArrayEquals(content(beta, i), content(merged, 3 + i))
        assertNoDanglingReferences(merged)
    }

    @Test
    fun `extraire et reordonner quelques pages`() {
        val beta = open("beta-objstm.pdf")
        val out = keep("extrait-beta-4-1.pdf", PdfAssembler.assemble(listOf(PageSelection(beta, 3), PageSelection(beta, 0))))
        assertEquals(2, out.pages.size)
        assertArrayEquals(content(beta, 3), content(out, 0))
        assertArrayEquals(content(beta, 0), content(out, 1))
    }

    @Test
    fun `la rotation s ajoute a celle deja presente`() {
        val epsilon = open("epsilon-rotated.pdf")
        val out = keep(
            "rotation-epsilon.pdf",
            PdfAssembler.assemble(listOf(PageSelection(epsilon, 0, 3), PageSelection(epsilon, 1, 1)))
        )
        assertEquals(270, rotation(out, 0))
        assertEquals(180, rotation(out, 1))
    }

    @Test
    fun `les attributs herites sont recopies sur chaque page`() {
        val zeta = open("zeta-inherited.pdf")
        val out = keep("herite-zeta.pdf", PdfAssembler.assemble(listOf(PageSelection(zeta, 2), PageSelection(zeta, 0, 1))))
        assertEquals(listOf(0.0, 0.0, 300.0, 400.0), mediaBox(out, 0))
        assertEquals(90, rotation(out, 0))
        assertEquals(listOf(0.0, 0.0, 200.0, 200.0), mediaBox(out, 1))
        assertEquals(180, rotation(out, 1))
        // Plus d'héritage : chaque page porte elle-même ses ressources.
        assertTrue(out.pages.all { it.dict["Resources"] != null && it.inherited.isEmpty() })
        assertNoDanglingReferences(out)
    }

    @Test
    fun `une page extraite n emporte pas les pages vers lesquelles elle pointe`() {
        val delta = open("delta-links.pdf")
        val bytes = PdfAssembler.assemble(listOf(PageSelection(delta, 0)))
        val out = keep("lien-coupe.pdf", bytes)
        assertEquals(1, out.pages.size)
        val text = String(bytes, Charsets.ISO_8859_1)
        assertEquals("une seule page recopiée", 1, Regex("/Type /Page\\b").findAll(text).count())
        // Le lien « page suivante » n'a plus de cible : il est retiré, pas redirigé au hasard.
        val annots = out.resolve(out.pages[0].dict["Annots"]) as? PdfArray
        assertTrue(annots == null || annots.items.none { (out.resolve(it) as PdfDict).nameOf("Subtype") == "Link" })
        assertNoDanglingReferences(out)
    }

    @Test
    fun `un lien vers une page retenue pointe sur sa copie`() {
        val delta = open("delta-links.pdf")
        val out = keep("lien-garde.pdf", PdfAssembler.assemble(listOf(PageSelection(delta, 0), PageSelection(delta, 1))))
        val secondPage = out.pages[1].ref!!
        val annots = out.resolve(out.pages[0].dict["Annots"]) as PdfArray
        val link = annots.items.map { out.resolve(it) as PdfDict }.first { it.nameOf("Subtype") == "Link" }
        val dest = out.resolve(link["Dest"]) as? PdfArray
            ?: out.resolve((out.resolve(link["A"]) as PdfDict)["D"]) as PdfArray
        assertEquals(secondPage, dest.items[0])
    }

    @Test
    fun `une page seule pese moins que le document entier`() {
        val alpha = PdfTestFiles.bytes("alpha-simple.pdf")
        val one = PdfAssembler.assemble(listOf(PageSelection(PdfFile.parse(alpha), 1)))
        assertTrue("${one.size} >= ${alpha.size}", one.size < alpha.size)
    }

    @Test
    fun `un document repare puis mis a jour s assemble aussi`() {
        val broken = open("alpha-broken-xref.pdf")
        val gamma = open("gamma-incremental.pdf")
        val out = keep("repare-plus-incremental.pdf", PdfAssembler.assemble(all(gamma) + listOf(PageSelection(broken, 2))))
        assertEquals(4, out.pages.size)
        assertArrayEquals(content(gamma, 2), content(out, 2))
        assertNoDanglingReferences(out)
    }
}
