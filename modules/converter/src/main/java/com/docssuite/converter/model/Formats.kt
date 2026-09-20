package com.docssuite.converter.model

/**
 * Familles de fichiers reconnues. La famille décide des conversions proposées
 * et des réglages affichés.
 */
enum class FileKind { IMAGE, PDF, AUDIO, VIDEO, TEXT, ARCHIVE, OTHER }

/**
 * Formats de sortie réellement produits par le moteur. Chaque entrée
 * correspond à un encodeur présent dans Android : rien n'est déclaré ici qui ne
 * soit implémenté dans `engine/`.
 */
enum class TargetFormat(
    val extension: String,
    val label: String,
    val mime: String,
    val description: String
) {
    PNG("png", "PNG", "image/png", "Sans perte, transparence conservée"),
    JPG("jpg", "JPG", "image/jpeg", "Photo compacte, très compatible"),
    WEBP("webp", "WEBP", "image/webp", "Moderne, fichiers plus légers"),
    PDF("pdf", "PDF", "application/pdf", "Document prêt à imprimer"),
    TXT("txt", "TXT", "text/plain", "Texte brut sans mise en forme"),
    M4A("m4a", "M4A", "audio/mp4", "Audio AAC, lisible partout"),
    WAV("wav", "WAV", "audio/wav", "Audio sans perte, fichier volumineux"),
    MP4("mp4", "MP4", "video/mp4", "Vidéo standard, très compatible"),
    ZIP("zip", "ZIP", "application/zip", "Archive compressée");

    /** Réglages pertinents pour ce format, dans l'ordre d'affichage. */
    val optionGroup: OptionGroup
        get() = when (this) {
            PNG, WEBP -> OptionGroup.IMAGE
            JPG -> OptionGroup.IMAGE
            PDF -> OptionGroup.PDF
            M4A, WAV -> OptionGroup.AUDIO
            MP4 -> OptionGroup.VIDEO
            TXT, ZIP -> OptionGroup.NONE
        }
}

enum class OptionGroup { IMAGE, PDF, AUDIO, VIDEO, NONE }

/** Extensions reconnues en entrée, par famille. */
private val IMAGE_EXTENSIONS =
    setOf("jpg", "jpeg", "jpe", "png", "webp", "gif", "bmp", "heic", "heif", "avif")
private val PDF_EXTENSIONS = setOf("pdf")
private val AUDIO_EXTENSIONS =
    setOf("mp3", "wav", "m4a", "aac", "ogg", "oga", "opus", "flac", "amr", "3ga", "mka")
private val VIDEO_EXTENSIONS =
    setOf("mp4", "m4v", "mkv", "webm", "3gp", "3gpp", "mov", "ts", "avi")
private val TEXT_EXTENSIONS =
    setOf("txt", "md", "markdown", "csv", "tsv", "log", "json", "xml", "html", "htm", "ini", "yml", "yaml")
private val ARCHIVE_EXTENSIONS = setOf("zip", "jar", "apk")

fun kindForExtension(extension: String): FileKind {
    val ext = extension.lowercase()
    return when {
        ext in IMAGE_EXTENSIONS -> FileKind.IMAGE
        ext in PDF_EXTENSIONS -> FileKind.PDF
        ext in AUDIO_EXTENSIONS -> FileKind.AUDIO
        ext in VIDEO_EXTENSIONS -> FileKind.VIDEO
        ext in TEXT_EXTENSIONS -> FileKind.TEXT
        ext in ARCHIVE_EXTENSIONS -> FileKind.ARCHIVE
        else -> FileKind.OTHER
    }
}

/**
 * Cibles envisageables d'après la seule extension. La liste définitive est
 * calculée par `MediaProbe` une fois le fichier réellement ouvert : un `.mkv`
 * dont la vidéo est en VP9 ne peut pas être remballé en MP4, et la carte ne
 * doit pas le proposer.
 */
fun candidateTargets(kind: FileKind, sourceExtension: String): List<TargetFormat> {
    val source = sourceExtension.lowercase()
    val targets = when (kind) {
        FileKind.IMAGE -> listOf(TargetFormat.PNG, TargetFormat.JPG, TargetFormat.WEBP, TargetFormat.PDF)
        FileKind.PDF -> listOf(TargetFormat.PNG, TargetFormat.JPG, TargetFormat.WEBP)
        FileKind.AUDIO -> listOf(TargetFormat.M4A, TargetFormat.WAV)
        FileKind.VIDEO -> listOf(TargetFormat.MP4, TargetFormat.M4A, TargetFormat.WAV)
        FileKind.TEXT -> listOf(TargetFormat.PDF, TargetFormat.TXT)
        FileKind.ARCHIVE, FileKind.OTHER -> emptyList()
    }
    // Convertir un fichier vers son propre format ne produirait rien d'utile,
    // sauf pour le texte où la conversion réécrit l'encodage en UTF-8.
    val filtered = targets.filterNot { it.extension == source && it != TargetFormat.TXT }
    return filtered + TargetFormat.ZIP
}

/**
 * Types MIME passés au sélecteur système. Le filtre reste volontairement
 * ouvert : beaucoup de fichiers arrivent sans type déclaré et seraient sinon
 * grisés dans le sélecteur.
 */
val PICKER_MIME_TYPES = arrayOf("*/*")
