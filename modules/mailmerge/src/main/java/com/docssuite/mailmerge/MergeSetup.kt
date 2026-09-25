package com.docssuite.mailmerge

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.docssuite.core.DocType
import com.docssuite.core.DocumentStorage
import com.docssuite.fileformats.FileFormats
import com.docssuite.fileformats.Imported
import com.docssuite.fileformats.Sheet
import com.docssuite.fileformats.TextDocument
import com.docssuite.texteditor.loadTextDocument

/** L'étape en cours du publipostage. */
enum class MergeStep { MODEL, DATA, READY }

/**
 * Le montage d'un publipostage : un modèle, une liste de destinataires, et de
 * quoi produire les courriers. Séparé de l'écran pour être vérifiable sans
 * rendu.
 */
class MergeSetup(context: Context) {

    private val storage = DocumentStorage(context)

    var step by mutableStateOf(MergeStep.MODEL)
        private set
    var modelName by mutableStateOf("")
        private set
    var dataName by mutableStateOf("")
        private set
    var sheets by mutableStateOf<List<Sheet>>(emptyList())
        private set
    var sheetIndex by mutableIntStateOf(0)
        private set
    var recipients by mutableStateOf(Recipients.empty)
        private set
    var fields by mutableStateOf<List<String>>(emptyList())
        private set
    /** La colonne qui nomme les courriers produits. */
    var nameField by mutableStateOf<String?>(null)
    /** Tous les courriers dans un seul document plutôt qu'un document chacun. */
    var single by mutableStateOf(false)
    var problem by mutableStateOf<String?>(null)
        private set

    private var model: TextDocument? = null

    val plan: MergePlan get() = MergePlan(fields, recipients.headers)

    val savedModels: List<com.docssuite.core.DocMeta> get() = storage.list(DocType.TEXT)
    val savedData: List<com.docssuite.core.DocMeta> get() = storage.list(DocType.SHEET)

    fun back(): Boolean = when (step) {
        MergeStep.MODEL -> false
        MergeStep.DATA -> { step = MergeStep.MODEL; true }
        MergeStep.READY -> { step = MergeStep.DATA; true }
    }

    // ------------------------------------------------------------ le modèle

    fun chooseSavedModel(id: String) {
        val document = loadTextDocument(storage, id)
        if (document == null) {
            problem = "Ce document n'a pas pu être relu."
            return
        }
        useModel(document, document.title)
    }

    fun chooseModelFile(fileName: String?, bytes: ByteArray) {
        when (val imported = runCatching { FileFormats.import(fileName, bytes) }.getOrNull()) {
            is Imported.AsText -> useModel(imported.document, imported.suggestedName)
            null -> problem = "Ce fichier n'a pas pu être ouvert."
            else -> problem = "Le modèle doit être un document texte, pas un tableur ni une présentation."
        }
    }

    private fun useModel(document: TextDocument, name: String) {
        model = document
        modelName = name.ifBlank { "Modèle" }
        fields = MergeFields.of(document)
        problem = null
        step = MergeStep.DATA
    }

    // ------------------------------------------------------------ les données

    fun chooseSavedData(id: String) {
        val payload = storage.load(id)
        val meta = storage.meta(id)
        if (payload == null || meta == null) {
            problem = "Ce tableur n'a pas pu être relu."
            return
        }
        useSheets(SavedWorkbook.sheets(payload, meta.name), meta.name)
    }

    fun chooseDataFile(fileName: String?, bytes: ByteArray) {
        when (val imported = runCatching { FileFormats.import(fileName, bytes) }.getOrNull()) {
            is Imported.AsSheet -> useSheets(imported.workbook.sheets, imported.suggestedName)
            null -> problem = "Ce fichier n'a pas pu être ouvert."
            else -> problem = "Les destinataires doivent venir d'un tableur : xlsx, ods ou csv."
        }
    }

    private fun useSheets(loaded: List<Sheet>, name: String) {
        if (loaded.isEmpty()) {
            problem = "Ce tableur est vide."
            return
        }
        sheets = loaded
        dataName = name.ifBlank { "Destinataires" }
        // La première feuille qui a des destinataires, plutôt que la première
        // tout court : un classeur commence souvent par une feuille de garde.
        sheetIndex = loaded.indices.firstOrNull { MergeData.read(loaded[it]).records.isNotEmpty() } ?: 0
        readSheet()
        if (recipients.isEmpty) {
            problem = "Aucun destinataire trouvé : la première ligne doit donner les noms de colonnes."
            return
        }
        problem = null
        step = MergeStep.READY
    }

    fun chooseSheet(index: Int) {
        if (index !in sheets.indices) return
        sheetIndex = index
        readSheet()
    }

    private fun readSheet() {
        recipients = MergeData.read(sheets[sheetIndex])
        // La première colonne nomme les courriers : c'est là que se met le nom
        // du destinataire dans une liste, et le choix reste modifiable.
        nameField = recipients.headers.firstOrNull()
    }

    // ------------------------------------------------------------ le résultat

    fun options() = MergeOptions(nameField = nameField)

    fun letters(): List<MergedLetter> {
        val document = model ?: return emptyList()
        val letters = MailMerge.run(document, recipients, options())
        if (!single || letters.isEmpty()) return letters
        val name = "$modelName — ${letters.size} courriers"
        return listOf(MergedLetter(MailMerge.combine(letters, name), name))
    }

    /** Le premier courrier, tel qu'il sera produit. */
    fun preview(): String {
        val document = model ?: return ""
        val first = recipients.records.firstOrNull() ?: return document.plainText
        return MailMerge.letter(document, first, options()).document.plainText
    }

    fun dismissProblem() {
        problem = null
    }
}
