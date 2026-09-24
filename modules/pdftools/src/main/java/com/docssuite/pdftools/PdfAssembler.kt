package com.docssuite.pdftools

import java.io.ByteArrayOutputStream
import java.util.IdentityHashMap
import java.util.Locale

/**
 * Une page du document produit : laquelle, de quel fichier, combien de
 * quarts de tour en plus, et ce qu'on y ajoute (signature, texte, coches).
 */
data class PageSelection(
    val source: PdfFile,
    val pageIndex: Int,
    val extraQuarterTurns: Int = 0,
    val overlay: List<OverlayItem> = emptyList()
)

/**
 * Construit un nouveau PDF à partir de pages prises dans un ou plusieurs
 * fichiers : fusion, extraction, suppression, réordonnancement et rotation
 * sont tous des cas de cette seule opération.
 *
 * Chaque objet nécessaire (contenu, polices, images, annotations) est
 * recopié tel quel et renuméroté ; rien n'est recompressé ni rasterisé, le
 * texte reste sélectionnable. Une référence vers une page non retenue, ou
 * vers la structure du document d'origine (catalogue, arbre des pages),
 * est coupée : sans cela, un simple lien « aller à la page 3 » ferait
 * recopier tout le document d'origine.
 */
object PdfAssembler {

    private const val CATALOG = 1
    private const val PAGE_TREE = 2

    fun assemble(selection: List<PageSelection>, title: String? = null): ByteArray {
        require(selection.isNotEmpty()) { "Aucune page à enregistrer" }
        val objects = sortedMapOf<Int, PdfObject>()
        var next = PAGE_TREE + 1
        fun allocate() = next++

        class SourceState(val file: PdfFile) {
            val renumbered = HashMap<Int, Int>()
            val pageObjects: Set<Int> = file.pages.mapNotNull { it.ref?.num }.toSet()
            /** Première copie de chaque page d'origine, cible des liens internes. */
            val pageTargets = HashMap<Int, Int>()
        }
        val sources = IdentityHashMap<PdfFile, SourceState>()
        val pending = ArrayList<Pair<SourceState, Int>>()

        // Les pages d'abord, pour que les liens entre pages retenues se résolvent.
        val newPages = selection.map { pick ->
            val state = sources.getOrPut(pick.source) { SourceState(pick.source) }
            val page = pick.source.pages.getOrNull(pick.pageIndex)
                ?: throw IllegalArgumentException("Page ${pick.pageIndex + 1} inexistante")
            val num = allocate()
            page.ref?.let { state.pageTargets.putIfAbsent(it.num, num) }
            Triple(state, page, num) to pick.extraQuarterTurns
        }

        fun transform(state: SourceState, obj: PdfObject): PdfObject = when (obj) {
            is PdfRef -> {
                val target = obj.num
                when {
                    target in state.pageObjects -> state.pageTargets[target]?.let { PdfRef(it, 0) } ?: PdfNull
                    else -> {
                        val resolved = state.file.getObject(target)
                        val type = (resolved as? PdfDict)?.nameOf("Type")
                        if (type == "Pages" || type == "Catalog" || resolved === PdfNull) PdfNull
                        else PdfRef(
                            state.renumbered.getOrPut(target) {
                                allocate().also { pending.add(state to target) }
                            },
                            0
                        )
                    }
                }
            }
            is PdfDict -> PdfDict(LinkedHashMap<String, PdfObject>().apply {
                obj.entries.forEach { (key, value) -> put(key, transform(state, value)) }
            })
            is PdfArray -> PdfArray(obj.items.mapTo(ArrayList()) { transform(state, it) })
            is PdfStream -> PdfStream(
                transform(state, obj.dict.copy().apply { remove("Length") }) as PdfDict,
                obj.data
            )
            else -> obj
        }

        newPages.forEachIndexed { index, (placed, extraTurns) ->
            val (state, page, num) = placed
            val overlay = selection[index].overlay
            val dict = page.dict.copy()
            listOf("Parent", "B", "StructParents").forEach { dict.remove(it) }
            (state.file.resolve(dict["Annots"]) as? PdfArray)?.let { annots ->
                dict["Annots"] = PdfArray(annots.items.filterTo(ArrayList()) { keepAnnotation(state.file, it, state.pageObjects, state.pageTargets.keys) })
            }
            PdfFile.INHERITABLE.forEach { key ->
                if (dict[key] == null) page.inherited[key]?.let { dict[key] = it }
            }
            val original = (state.file.resolve(page.attribute("Rotate")) as? PdfNumber)?.intValue ?: 0
            val rotation = Math.floorMod(original + 90 * extraTurns, 360) / 90 * 90
            if (rotation != 0 || dict["Rotate"] != null) dict["Rotate"] = PdfNumber.of(rotation)
            if (overlay.isNotEmpty()) {
                // Ressources propres à cette page : on y ajoute nos polices et
                // images sans toucher à celles, peut-être partagées, d'origine.
                val resources = (state.file.resolve(dict["Resources"]) as? PdfDict)?.copy() ?: PdfDict()
                listOf("Font", "XObject").forEach { key ->
                    resources[key] = (state.file.resolve(resources[key]) as? PdfDict)?.copy() ?: PdfDict()
                }
                dict["Resources"] = resources
            }
            val copy = transform(state, dict) as PdfDict
            copy["Parent"] = PdfRef(PAGE_TREE, 0)
            copy["Type"] = PdfName("Page")
            if (overlay.isNotEmpty()) {
                val built = OverlayWriter.build(PageGeometry.of(state.file, page, rotation), overlay)
                val resources = copy["Resources"] as PdfDict
                (resources["Font"] as PdfDict).apply {
                    set(OverlayWriter.FONT, OverlayWriter.helvetica())
                    set(OverlayWriter.CHECK_FONT, OverlayWriter.zapf())
                }
                val xobjects = resources["XObject"] as PdfDict
                built.images.forEach { (name, image) ->
                    val (rgb, alpha) = OverlayWriter.imageStreams(image)
                    val maskNum = allocate()
                    objects[maskNum] = alpha
                    rgb.dict["SMask"] = PdfRef(maskNum, 0)
                    val imageNum = allocate()
                    objects[imageNum] = rgb
                    xobjects[name] = PdfRef(imageNum, 0)
                }
                // Le contenu d'origine est encadré par q … Q : s'il laisse l'état
                // graphique modifié (repère déplacé, couleur…), nos ajouts n'en
                // héritent pas et tombent exactement là où on les a posés.
                val before = allocate()
                objects[before] = PdfStream(PdfDict(), "q\n".toByteArray(Charsets.ISO_8859_1))
                val after = allocate()
                objects[after] = PdfStream(PdfDict(), "\nQ\n".toByteArray(Charsets.ISO_8859_1) + built.content)
                val original = when (val contents = copy["Contents"]) {
                    is PdfArray -> contents.items
                    null, PdfNull -> emptyList()
                    else -> listOf(contents)
                }
                copy["Contents"] = PdfArray((listOf<PdfObject>(PdfRef(before, 0)) + original + PdfRef(after, 0)).toMutableList())
            }
            objects[num] = copy
        }

        var cursor = 0
        while (cursor < pending.size) {
            val (state, oldNum) = pending[cursor++]
            val newNum = state.renumbered.getValue(oldNum)
            objects[newNum] = transform(state, state.file.getObject(oldNum))
        }

        objects[PAGE_TREE] = PdfDict().apply {
            set("Type", PdfName("Pages"))
            set("Kids", PdfArray(newPages.mapTo(ArrayList<PdfObject>()) { PdfRef(it.first.third, 0) }))
            set("Count", PdfNumber.of(newPages.size))
        }
        objects[CATALOG] = PdfDict().apply {
            set("Type", PdfName("Catalog"))
            set("Pages", PdfRef(PAGE_TREE, 0))
        }
        val info = allocate()
        objects[info] = PdfDict().apply {
            set("Producer", PdfString("(DocsApp Suite)".toByteArray(Charsets.ISO_8859_1)))
            if (!title.isNullOrBlank()) set("Title", PdfString(textString(title)))
        }

        val version = sources.keys.map { it.version }.maxByOrNull { it.toDoubleOrNull() ?: 1.4 }
            ?.takeIf { (it.toDoubleOrNull() ?: 0.0) > 1.4 } ?: "1.4"
        return write(objects, info, version)
    }

    /**
     * Un lien interne dont la page cible n'est pas retenue (ou qui passe par
     * une destination nommée, dont la table n'est pas recopiée) est retiré :
     * gardé, il mènerait au hasard, souvent sur la page elle-même.
     */
    private fun keepAnnotation(file: PdfFile, annot: PdfObject, pages: Set<Int>, kept: Set<Int>): Boolean {
        val dict = file.resolve(annot) as? PdfDict ?: return false
        if (dict.nameOf("Subtype") != "Link") return true
        val dest = if (dict["Dest"] != null) {
            file.resolve(dict["Dest"])
        } else {
            val action = file.resolve(dict["A"]) as? PdfDict ?: return true
            if (action.nameOf("S") != "GoTo") return true // lien web, fichier externe… : sans rapport avec les pages
            file.resolve(action["D"])
        }
        val target = (dest as? PdfArray)?.items?.firstOrNull() as? PdfRef ?: return false
        return target.num !in pages || target.num in kept
    }

    private fun write(objects: Map<Int, PdfObject>, info: Int, version: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.ascii("%PDF-$version\n")
        out.write(byteArrayOf('%'.code.toByte(), 0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), '\n'.code.toByte()))
        val size = (objects.keys.maxOrNull() ?: 0) + 1
        val offsets = IntArray(size)
        objects.forEach { (num, obj) ->
            offsets[num] = out.size()
            out.ascii("$num 0 obj\n")
            out.writePdf(obj)
            out.ascii("\nendobj\n")
        }
        val xref = out.size()
        out.ascii("xref\n0 $size\n0000000000 65535 f \n")
        for (num in 1 until size) {
            if (num in objects) out.ascii(String.format(Locale.US, "%010d 00000 n \n", offsets[num]))
            else out.ascii("0000000000 65535 f \n")
        }
        out.ascii("trailer\n<< /Size $size /Root $CATALOG 0 R /Info $info 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return out.toByteArray()
    }

    /** Chaîne PDF en UTF-16BE hexadécimal : accents et parenthèses sans échappement. */
    private fun textString(text: String): ByteArray {
        val builder = StringBuilder("<FEFF")
        text.forEach { builder.append(String.format(Locale.US, "%04X", it.code)) }
        return builder.append('>').toString().toByteArray(Charsets.ISO_8859_1)
    }
}
