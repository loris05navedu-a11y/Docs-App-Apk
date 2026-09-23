package com.docssuite.texteditor

import androidx.compose.ui.text.style.TextAlign
import com.docssuite.core.DocType
import com.docssuite.core.DocumentStorage
import com.docssuite.fileformats.TextDocument

internal fun alignFromInt(align: Int): TextAlign = when (align) {
    1 -> TextAlign.Center
    2 -> TextAlign.End
    3 -> TextAlign.Justify
    else -> TextAlign.Start
}

/**
 * Enregistre un document importé et renvoie son identifiant, pour que l'écran
 * d'accueil puisse ouvrir l'éditeur directement dessus.
 */
fun saveImportedTextDocument(
    storage: DocumentStorage,
    document: TextDocument,
    name: String
): String {
    val id = storage.newId()
    storage.save(id, name, DocType.TEXT, EditorIO.toJson(EditorIO.fromTextDocument(document)))
    return id
}
