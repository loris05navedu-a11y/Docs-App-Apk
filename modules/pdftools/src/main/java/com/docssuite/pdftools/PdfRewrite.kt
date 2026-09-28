package com.docssuite.pdftools

import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.Locale

/**
 * Réécrit un PDF entier, objet par objet, sous les mêmes numéros : signets,
 * liens, formulaires, pièces jointes et métadonnées restent en place.
 *
 * Contrairement à [PdfAssembler], qui reconstruit un document à partir de
 * pages choisies, on garde tout ; seuls changent les objets remplacés ou
 * ajoutés, et le chiffrement : aucun si [write] ne reçoit pas de protection
 * — c'est ainsi qu'un PDF ouvert avec son mot de passe est déverrouillé.
 */
class PdfRewrite(val file: PdfFile) {

    private val replaced = HashMap<Int, PdfObject>()
    private val added = sortedMapOf<Int, PdfObject>()
    private var next = file.highestObjectNumber + 1

    fun get(num: Int): PdfObject = replaced[num] ?: added[num] ?: file.getObject(num)

    fun replace(num: Int, obj: PdfObject) {
        replaced[num] = obj
    }

    fun add(obj: PdfObject): PdfRef {
        val num = next++
        added[num] = obj
        return PdfRef(num, 0)
    }

    fun write(protection: PdfProtection? = null): ByteArray {
        val objects = sortedMapOf<Int, Pair<Int, PdfObject>>()
        file.objectNumbers().forEach { num ->
            val obj = replaced[num] ?: file.getObject(num)
            // Les tables de linéarisation désignent des positions de l'ancien fichier.
            if (obj is PdfDict && obj["Linearized"] != null) return@forEach
            objects[num] = file.generation(num) to obj
        }
        added.forEach { (num, obj) -> objects[num] = 0 to obj }

        val root = file.trailer["Root"] as? PdfRef ?: throw PdfFormatException("Catalogue introuvable")
        if (protection != null) {
            // L'AES-256 est annoncé à la façon d'Acrobat X, pour les lecteurs qui vérifient.
            val (gen, catalog) = objects[root.num] ?: throw PdfFormatException("Catalogue introuvable")
            if (catalog is PdfDict && catalog["Extensions"] == null) {
                objects[root.num] = gen to catalog.copy().apply {
                    set("Extensions", PdfDict().apply {
                        set("ADBE", PdfDict().apply {
                            set("BaseVersion", PdfName("1.7"))
                            set("ExtensionLevel", PdfNumber.of(8))
                        })
                    })
                }
            }
        }
        val info: PdfRef? = when (val value = file.trailer["Info"]) {
            is PdfRef -> value.takeIf { it.num in objects }
            is PdfDict -> {
                val num = (objects.keys.maxOrNull() ?: 0) + 1
                objects[num] = 0 to value
                PdfRef(num, 0)
            }
            else -> null
        }

        val random = SecureRandom()
        val originalId = (file.resolve(file.trailer["ID"]) as? PdfArray)?.items
            ?.mapNotNull { (file.resolve(it) as? PdfString)?.bytes() }
            ?.takeIf { it.size == 2 }
        val id = originalId?.let { listOf(it[0], if (protection != null) ByteArray(16).also(random::nextBytes) else it[1]) }
            ?: ByteArray(16).also(random::nextBytes).let { listOf(it, it) }

        val version = if (protection != null && (file.version.toDoubleOrNull() ?: 1.4) < 1.7) "1.7" else file.version
        val out = ByteArrayOutputStream(file.bytes.size + 4096)
        out.ascii("%PDF-$version\n")
        out.write(byteArrayOf('%'.code.toByte(), 0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), '\n'.code.toByte()))
        val size = (objects.keys.maxOrNull() ?: 0) + 1
        val offsets = IntArray(size)
        val generations = IntArray(size)
        objects.forEach { (num, placed) ->
            val (gen, obj) = placed
            offsets[num] = out.size()
            generations[num] = gen
            out.ascii("$num $gen obj\n")
            out.writePdf(protection?.encrypt(obj) ?: obj)
            out.ascii("\nendobj\n")
        }
        val xref = out.size()
        out.ascii("xref\n0 $size\n0000000000 65535 f \n")
        for (num in 1 until size) {
            if (num in objects) out.ascii(String.format(Locale.US, "%010d %05d n \n", offsets[num], generations[num]))
            else out.ascii("0000000000 65535 f \n")
        }
        val trailer = PdfDict().apply {
            set("Size", PdfNumber.of(size))
            set("Root", root)
            if (info != null) set("Info", info)
            set("ID", PdfArray(id.mapTo(ArrayList<PdfObject>()) { PdfString.hex(it) }))
            if (protection != null) set("Encrypt", protection.dictionary())
        }
        out.ascii("trailer\n")
        out.writePdf(trailer)
        out.ascii("\nstartxref\n$xref\n%%EOF\n")
        return out.toByteArray()
    }
}

/** Mettre ou retirer un mot de passe. */
object PdfLock {

    /** Ce qu'on sait d'un PDF avant de l'ouvrir pour de bon. */
    sealed interface Status {
        object Open : Status
        /** Lisible sans mot de passe, mais impression ou copie restreintes. */
        data class Restricted(val description: String) : Status
        object NeedsPassword : Status
    }

    fun status(bytes: ByteArray): Status = try {
        val security = PdfFile.parse(bytes).security
        if (security == null) Status.Open else Status.Restricted(security.description)
    } catch (_: PdfEncryptedException) {
        Status.NeedsPassword
    }

    /** Le même document, sans protection. [password] : celui d'ouverture ou du propriétaire. */
    fun unlock(bytes: ByteArray, password: String? = null): ByteArray =
        PdfRewrite(PdfFile.parse(bytes, password)).write()

    /**
     * Le même document, protégé par [password] en AES-256. Un PDF déjà
     * protégé est d'abord ouvert avec [currentPassword].
     */
    fun protect(bytes: ByteArray, password: String, restrict: Boolean = false, currentPassword: String? = null): ByteArray {
        require(password.isNotEmpty()) { "Le mot de passe est vide" }
        return PdfRewrite(PdfFile.parse(bytes, currentPassword)).write(PdfProtection(password, restrict))
    }

    /**
     * La protection à remettre sur un document modifié (tamponné, compressé) :
     * un PDF qui demandait un mot de passe le demande encore après.
     */
    internal fun carry(file: PdfFile, password: String?): PdfProtection? {
        val security = file.security ?: return null
        if (!security.needsPassword || password.isNullOrEmpty()) return null
        return PdfProtection(password, restrict = security.permissions and 0x10 == 0)
    }
}
