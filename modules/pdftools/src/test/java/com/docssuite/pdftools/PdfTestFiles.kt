package com.docssuite.pdftools

import java.io.ByteArrayOutputStream

/**
 * Fichiers de référence de `src/test/resources/pdf`, produits par MuPDF
 * (sauf `zeta-inherited`, écrit à la main pour l'arbre de pages imbriqué)
 * et relus par lui pour établir ce qu'ils contiennent :
 *
 * - alpha-simple : 3 pages, table classique, flux non compressés ;
 * - alpha-broken-xref : le même, décalages et `startxref` faussés ;
 * - beta-objstm : 4 pages, table compressée et flux d'objets (PDF 1.5) ;
 * - gamma-incremental : 2 pages + 1 ajoutée par mise à jour incrémentale ;
 * - delta-links : 3 pages, chacune avec un lien vers la suivante ;
 * - epsilon-rotated : 2 pages paysage, la seconde déjà tournée de 90° ;
 * - zeta-inherited : taille, rotation et polices héritées de nœuds parents ;
 * - secret-encrypted : chiffré AES-256 par mot de passe.
 */
object PdfTestFiles {
    fun bytes(name: String): ByteArray =
        PdfTestFiles::class.java.getResourceAsStream("/pdf/$name")!!.readBytes()

    fun open(name: String): PdfFile = PdfFile.parse(bytes(name))

    /** Les octets bruts du contenu d'une page (flux, ou suite de flux). */
    fun content(file: PdfFile, index: Int): ByteArray {
        val out = ByteArrayOutputStream()
        when (val contents = file.resolve(file.pages[index].dict["Contents"])) {
            is PdfStream -> out.write(contents.data)
            is PdfArray -> contents.items.forEach { (file.resolve(it) as PdfStream).data.let(out::write) }
            else -> {}
        }
        return out.toByteArray()
    }

    fun rotation(file: PdfFile, index: Int): Int =
        (file.resolve(file.pages[index].attribute("Rotate")) as? PdfNumber)?.intValue ?: 0

    fun mediaBox(file: PdfFile, index: Int): List<Double> =
        (file.resolve(file.pages[index].attribute("MediaBox")) as PdfArray).items.map {
            (file.resolve(it) as PdfNumber).doubleValue
        }
}
