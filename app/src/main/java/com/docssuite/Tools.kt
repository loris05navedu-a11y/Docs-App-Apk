package com.docssuite

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Difference
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.FileCopy
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.automirrored.filled.ManageSearch
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.automirrored.filled.SendToMobile
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.automirrored.filled.TextSnippet
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.docssuite.core.DocumentSearch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Les familles d'outils, dans l'ordre où elles s'affichent. */
enum class ToolCategory(val label: String, val color: Color) {
    CREATE("Créer", Color(0xFF2563EB)),
    PDF("PDF", Color(0xFFDC2626)),
    VOICE("Voix et médias", Color(0xFF7C3AED)),
    CONVERT("Convertir et partager", Color(0xFF0D9488)),
    ORGANIZE("Organiser", Color(0xFFD97706))
}

/**
 * Un outil de l'accueil. [route] est l'écran à ouvrir ; les quelques outils
 * qui n'en ont pas (nouveau document, ouvrir un fichier…) sont traités par
 * l'accueil lui-même. [keywords] sert à la recherche : on trouve « Outils
 * PDF » en tapant « fusionner », comme on le dirait.
 */
data class Tool(
    val id: String,
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val category: ToolCategory,
    val route: String? = null,
    val keywords: List<String> = emptyList(),
    /** Teinte propre, pour les trois éditeurs qu'on reconnaît à leur couleur. */
    val tint: Color? = null
) {
    val color: Color get() = tint ?: category.color
}

object Tools {

    const val NEW_DOCUMENT = "document"
    const val NEW_SHEET = "sheet"
    const val NEW_DECK = "deck"
    const val OPEN_PDF = "pdf"
    const val OPEN_FILE = "open"

    val all: List<Tool> = listOf(
        // ---------------------------------------------------------------- créer
        Tool(
            NEW_DOCUMENT, "Document", "Écrire et mettre en forme du texte",
            Icons.Filled.Description, ToolCategory.CREATE,
            keywords = listOf("word", "docx", "texte", "écrire", "rédiger", "lettre", "odt"),
            tint = Color(0xFF2563EB)
        ),
        Tool(
            NEW_SHEET, "Tableur", "Organiser des données et calculer",
            Icons.Filled.TableChart, ToolCategory.CREATE,
            keywords = listOf("excel", "xlsx", "calcul", "formule", "tableau", "chiffres", "ods", "csv"),
            tint = Color(0xFF16A34A)
        ),
        Tool(
            NEW_DECK, "Présentation", "Diapositives et diaporama",
            Icons.Filled.Slideshow, ToolCategory.CREATE,
            keywords = listOf("powerpoint", "pptx", "slides", "diaporama", "exposé", "odp"),
            tint = Color(0xFFEA580C)
        ),
        Tool(
            "templates", "Modèles", "CV, lettres, facture, devis, budget…",
            Icons.Filled.AutoAwesome, ToolCategory.CREATE, route = "templates",
            keywords = listOf("cv", "curriculum", "motivation", "facture", "devis", "budget", "résiliation", "attestation", "compte rendu"),
            tint = Color(0xFF0891B2)
        ),
        Tool(
            "mailmerge", "Courriers en série", "Un modèle, une liste : un courrier chacun",
            Icons.Filled.Groups, ToolCategory.CREATE, route = "mailmerge",
            keywords = listOf("publipostage", "invitation", "mailing", "personnaliser", "liste", "destinataires")
        ),

        // ---------------------------------------------------------------- pdf
        Tool(
            OPEN_PDF, "Lire un PDF", "Ouvrir, zoomer, partager",
            Icons.Filled.PictureAsPdf, ToolCategory.PDF,
            keywords = listOf("lecteur", "visionneuse", "ouvrir")
        ),
        Tool(
            "pdfsign", "Signer un PDF", "Signature au doigt, texte, date, coches",
            Icons.Filled.Draw, ToolCategory.PDF, route = "pdfsign",
            keywords = listOf("signature", "remplir", "formulaire", "parapher", "contrat")
        ),
        Tool(
            "pdftools", "Outils PDF", "Fusionner, réordonner, tourner, extraire",
            Icons.Filled.FileCopy, ToolCategory.PDF, route = "pdftools",
            keywords = listOf("fusionner", "fusion", "assembler", "découper", "pages", "rotation", "supprimer page", "extraire")
        ),
        Tool(
            "scanner", "Scanner", "Une feuille photographiée devient un PDF",
            Icons.Filled.DocumentScanner, ToolCategory.PDF, route = "scanner",
            keywords = listOf("numériser", "photo", "appareil photo", "caméra", "redresser", "camscanner")
        ),

        // ---------------------------------------------------------------- voix et médias
        Tool(
            "recorder", "Dictaphone et dictée", "Enregistrer un cours, dicter un texte",
            Icons.Filled.Mic, ToolCategory.VOICE, route = "recorder",
            keywords = listOf("micro", "enregistrer", "enregistrement", "audio", "vocal", "voix", "cours", "réunion")
        ),
        Tool(
            "reader", "Lecture à voix haute", "Écouter un document, phrase par phrase",
            Icons.Filled.RecordVoiceOver, ToolCategory.VOICE, route = "reader",
            keywords = listOf("écouter", "lire", "synthèse vocale", "tts", "audio", "voix")
        ),
        Tool(
            "media", "Lecteur vidéo et audio", "MP4, MKV, WebM, MP3, FLAC…",
            Icons.Filled.PlayCircle, ToolCategory.VOICE, route = "media",
            keywords = listOf("vidéo", "film", "musique", "son", "mp3", "mp4", "lecteur")
        ),

        // ---------------------------------------------------------------- convertir et partager
        Tool(
            OPEN_FILE, "Ouvrir un fichier", "Word, Excel, PowerPoint, PDF, CSV…",
            Icons.Filled.FolderOpen, ToolCategory.CONVERT,
            keywords = listOf("importer", "fichier", "docx", "xlsx", "pptx", "odt", "rtf", "télécharger")
        ),
        Tool(
            "converter", "Convertisseur", "Images, PDF, audio et vidéo, hors ligne",
            Icons.Filled.SwapHoriz, ToolCategory.CONVERT, route = "converter",
            keywords = listOf("convertir", "format", "jpg", "png", "webp", "mp3", "compresser", "image")
        ),
        Tool(
            "ocr", "Texte depuis une photo", "Récupérer le texte sans le retaper",
            Icons.AutoMirrored.Filled.TextSnippet, ToolCategory.CONVERT, route = "ocr",
            keywords = listOf("ocr", "reconnaissance", "image", "copier", "extraire texte", "photo")
        ),
        Tool(
            "transfer", "Transfert", "Vers un autre appareil, par Wi-Fi ou câble",
            Icons.AutoMirrored.Filled.SendToMobile, ToolCategory.CONVERT, route = "transfer",
            keywords = listOf("envoyer", "recevoir", "partager", "wifi", "wi-fi direct", "téléphone", "ordinateur")
        ),

        // ---------------------------------------------------------------- organiser
        Tool(
            "library", "Mes documents", "Chercher partout, dossiers, favoris",
            Icons.AutoMirrored.Filled.ManageSearch, ToolCategory.ORGANIZE, route = "library",
            keywords = listOf("rechercher", "trouver", "dossier", "favori", "ranger", "classer")
        ),
        Tool(
            "tasks", "Tâches et rappels", "Listes, échéances, rappels notifiés",
            Icons.Filled.Checklist, ToolCategory.ORGANIZE, route = "tasks",
            keywords = listOf("todo", "à faire", "rappel", "alarme", "liste", "agenda", "échéance", "notification")
        ),
        Tool(
            "compare", "Comparer deux versions", "Ce qui a été ajouté, retiré, retouché",
            Icons.Filled.Difference, ToolCategory.ORGANIZE, route = "compare",
            keywords = listOf("différences", "diff", "versions", "modifications", "changements", "révision")
        ),
        Tool(
            "backup", "Sauvegarde", "Tout garder, tout retrouver ailleurs",
            Icons.Filled.Backup, ToolCategory.ORGANIZE, route = "backup",
            keywords = listOf("sauvegarder", "restaurer", "exporter", "nouveau téléphone", "archive")
        )
    )

    fun byId(id: String): Tool? = all.firstOrNull { it.id == id }

    fun of(category: ToolCategory): List<Tool> = all.filter { it.category == category }

    /** Les trois éditeurs et les modèles, toujours visibles en tête de l'accueil. */
    val quickCreate: List<String> = listOf(NEW_DOCUMENT, NEW_SHEET, NEW_DECK, "templates")

    /** Proposés tant qu'on n'a encore rien utilisé. */
    val starters: List<String> = listOf("scanner", OPEN_PDF, "tasks", "library")

    /**
     * Les outils qui répondent à [query]. Chaque mot tapé doit se retrouver
     * quelque part — titre, description, mots-clés ou famille —, sans tenir
     * compte des accents ni des majuscules. Un titre qui commence par ce qu'on
     * tape passe devant.
     */
    fun search(query: String): List<Tool> {
        val terms = DocumentSearch.terms(query)
        if (terms.isEmpty()) return emptyList()
        return all.mapNotNull { tool ->
            val title = DocumentSearch.fold(tool.title).text
            val keywords = tool.keywords.joinToString(" ") { DocumentSearch.fold(it).text }
            val rest = DocumentSearch.fold("${tool.subtitle} ${tool.category.label}").text
            var score = 0
            for (term in terms) {
                score += when {
                    title.startsWith(term) || title.contains(" $term") -> 8
                    title.contains(term) -> 5
                    keywords.contains(term) -> 4
                    rest.contains(term) -> 2
                    else -> return@mapNotNull null
                }
            }
            tool to score
        }.sortedByDescending { it.second }.map { it.first }
    }

    /**
     * Les raccourcis de l'accueil : les plus utilisés d'abord, à égalité le
     * plus récent ; complétés par [starters]. Ce qui est déjà dans « Nouveau »
     * n'y figure pas, pour ne pas montrer deux fois la même chose.
     */
    fun shortcuts(usage: Map<String, ToolUse>, count: Int = 4): List<Tool> {
        val used = usage.entries
            .filter { it.key !in quickCreate && it.value.count > 0 && byId(it.key) != null }
            .sortedWith(compareByDescending<Map.Entry<String, ToolUse>> { it.value.count }.thenByDescending { it.value.last })
            .map { it.key }
        return (used + starters).distinct().take(count).mapNotNull(::byId)
    }
}

data class ToolUse(val count: Int, val last: Long)

/** Combien de fois, et quand pour la dernière fois, chaque outil a servi. */
class ToolUsage(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("docssuite_home", Context.MODE_PRIVATE)

    fun all(): Map<String, ToolUse> = Tools.all.mapNotNull { tool ->
        val count = prefs.getInt("count:${tool.id}", 0)
        if (count == 0) null else tool.id to ToolUse(count, prefs.getLong("last:${tool.id}", 0L))
    }.toMap()

    val any: Boolean get() = all().keys.any { it !in Tools.quickCreate }

    fun record(id: String, now: Long = System.currentTimeMillis()) {
        prefs.edit()
            .putInt("count:$id", prefs.getInt("count:$id", 0) + 1)
            .putLong("last:$id", now)
            .apply()
    }
}

/**
 * Une date de modification comme on la dit : « à l'instant », « il y a
 * 5 min », « hier, 18:40 », « 12 sept. ».
 */
fun relativeTime(then: Long, now: Long = System.currentTimeMillis()): String {
    val elapsed = now - then
    if (elapsed < 60_000L) return "à l'instant"
    if (elapsed < 3_600_000L) return "il y a ${elapsed / 60_000L} min"
    val at = Calendar.getInstance().apply { timeInMillis = then }
    val today = Calendar.getInstance().apply { timeInMillis = now }
    val hour = SimpleDateFormat("HH:mm", Locale.FRANCE).format(at.time)
    fun sameDay(a: Calendar, b: Calendar) =
        a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    if (sameDay(at, today)) return "aujourd'hui, $hour"
    val yesterday = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
    if (sameDay(at, yesterday)) return "hier, $hour"
    val pattern = if (at.get(Calendar.YEAR) == today.get(Calendar.YEAR)) "d MMM" else "d MMM yyyy"
    return SimpleDateFormat(pattern, Locale.FRANCE).format(at.time)
}
