package com.docssuite.converter.engine

import java.io.File
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Lecture de texte et écriture d'archives, sans dépendance externe. */
object DocumentConversions {

    /**
     * Lit un fichier texte en devinant son encodage. Un fichier produit sous
     * Windows est souvent en Latin-1 : décodé en UTF-8, il se remplit de
     * caractères de remplacement, ce qui sert ici de test.
     */
    fun readText(source: File): String {
        val bytes = source.readBytes()
        if (bytes.isEmpty()) return ""
        val utf8 = String(bytes, Charsets.UTF_8)
        return if (utf8.contains('�')) String(bytes, Charsets.ISO_8859_1) else utf8
    }

    /** Réécrit le texte en UTF-8 avec des fins de ligne normalisées. */
    fun toPlainText(source: File, destination: File) {
        val text = readText(source).replace("\r\n", "\n").replace('\r', '\n')
        destination.writeText(text, Charsets.UTF_8)
    }

    fun toZip(
        sources: List<File>,
        names: List<String>,
        destination: File,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean
    ) {
        if (sources.isEmpty()) throw ConversionException("Aucun fichier à compresser")
        ZipOutputStream(destination.outputStream().buffered()).use { zip ->
            zip.setLevel(Deflater.BEST_COMPRESSION)
            sources.forEachIndexed { index, file ->
                if (isCancelled()) throw ConversionCancelled()
                zip.putNextEntry(ZipEntry(names.getOrElse(index) { file.name }))
                file.inputStream().buffered().use { input ->
                    val chunk = ByteArray(64 * 1024)
                    while (true) {
                        if (isCancelled()) throw ConversionCancelled()
                        val read = input.read(chunk)
                        if (read <= 0) break
                        zip.write(chunk, 0, read)
                    }
                }
                zip.closeEntry()
                onProgress((index + 1f) / sources.size)
            }
        }
    }
}
