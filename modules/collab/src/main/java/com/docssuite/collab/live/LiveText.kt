package com.docssuite.collab.live

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.docssuite.collab.ot.Revision
import com.docssuite.collab.ot.RevisionLog
import com.docssuite.collab.ot.SyncEngine
import com.docssuite.collab.ot.TextDiff
import com.docssuite.collab.ot.TextOperation

/** Le curseur (ou la sélection, de [anchor] à [caret]) d'une autre personne. */
data class RemoteCursor(val anchor: Int, val caret: Int)

/**
 * Le texte d'un document partagé, tel que le champ de saisie l'affiche.
 *
 * Il fait le lien entre le champ et la synchronisation : chaque frappe devient
 * une opération envoyée au journal, chaque révision venue d'ailleurs est
 * appliquée au texte, au curseur et à la sélection.
 *
 * Une subtilité : entre le moment où une révision d'ailleurs change le texte et
 * celui où le champ se redessine, le clavier peut encore envoyer une frappe
 * calculée sur l'ancien texte. On garde donc ce que le champ affiche vraiment
 * ([shown]) et le chemin qui en mène au texte réel ; une frappe faite sur
 * l'ancien texte est transformée pour s'appliquer au nouveau, sans rien perdre.
 *
 * À n'utiliser que sur le fil principal, comme le champ lui-même.
 */
class LiveText(
    clientId: String,
    uid: String,
    log: RevisionLog,
    startIndex: Int,
    startText: String,
    checkpointEvery: Int = 100,
) {
    /** Le texte à afficher, avec la sélection et la composition du clavier. */
    var value: TextFieldValue by mutableStateOf(TextFieldValue(startText), neverEqualPolicy())
        private set

    /** Vrai quand toutes nos frappes sont dans le journal. */
    var synced: Boolean by mutableStateOf(true)
        private set

    /** Les curseurs des autres appareils, par appareil. */
    val cursors = mutableStateMapOf<String, RemoteCursor>()

    /** Appelé après chaque frappe locale qui change le texte. */
    var onLocalEdit: () -> Unit = {}

    private val engine = SyncEngine(clientId, uid, log, startIndex, startText, ::applyRemote, checkpointEvery)

    private var shown: TextFieldValue = value
    private var sinceShown: TextOperation = TextOperation.identity(startText.length)

    /** Le numéro de la prochaine révision attendue. */
    val revision: Int get() = engine.revision

    /** Le texte selon le journal seul. */
    val serverText: String get() = engine.serverText

    /** Le champ affiche désormais [field] : à appeler après chaque composition. */
    fun shown(field: TextFieldValue) {
        if (field.text == value.text) {
            shown = field
            sinceShown = TextOperation.identity(field.text.length)
        }
    }

    /**
     * Ce que le champ propose : une frappe, un déplacement du curseur, une
     * composition du clavier. Si [editable] est faux, le texte n'est pas modifié.
     */
    fun onValueChange(proposed: TextFieldValue, editable: Boolean = true) {
        val base = shown
        shown = proposed
        if (proposed.text == base.text || !editable) {
            if (proposed.text != base.text) sinceShown = TextDiff.between(proposed.text, value.text)
            value = value.copy(selection = sinceShown.map(proposed.selection), composition = proposed.composition?.let(sinceShown::map))
            return
        }
        val typed = TextDiff.between(base.text, proposed.text, proposed.selection.end)
        val (mine, theirs) = TextOperation.transform(typed, sinceShown)
        sinceShown = theirs
        moveCursors(mine, author = null)
        value = TextFieldValue(
            text = mine.apply(value.text),
            selection = theirs.map(proposed.selection),
            composition = proposed.composition?.let(theirs::map),
        )
        engine.local(mine)
        synced = engine.synced
        onLocalEdit()
    }

    /** Une révision lue dans le journal (`null` si illisible). */
    fun receive(index: Int, revision: Revision?) {
        engine.receive(index, revision)
        synced = engine.synced
    }

    /** Oublier les frappes pas encore enregistrées (on ne peut plus écrire). */
    fun discardLocal() {
        val text = engine.discardLocal()
        sinceShown = TextDiff.between(shown.text, text)
        val selection = TextRange(value.selection.start.coerceAtMost(text.length), value.selection.end.coerceAtMost(text.length))
        value = TextFieldValue(text, selection)
        synced = true
    }

    /** Un autre appareil annonce où est son curseur. */
    fun setCursor(clientId: String, anchor: Int, caret: Int) {
        val length = value.text.length
        cursors[clientId] = RemoteCursor(anchor.coerceIn(0, length), caret.coerceIn(0, length))
    }

    fun removeCursor(clientId: String) {
        cursors.remove(clientId)
    }

    private fun applyRemote(operation: TextOperation, author: String?) {
        val current = value
        value = TextFieldValue(
            text = operation.apply(current.text),
            selection = operation.map(current.selection),
            composition = current.composition?.let(operation::map),
        )
        sinceShown = sinceShown.compose(operation)
        moveCursors(operation, author)
    }

    /**
     * Les curseurs des autres suivent le texte. Pas celui de l'auteur de
     * l'opération : il annonce lui-même où il en est après sa frappe.
     */
    private fun moveCursors(operation: TextOperation, author: String?) {
        for ((id, cursor) in cursors.toMap()) {
            if (id == author) continue
            cursors[id] = RemoteCursor(operation.transformIndex(cursor.anchor), operation.transformIndex(cursor.caret))
        }
    }
}

/** Une sélection après l'opération. */
fun TextOperation.map(range: TextRange): TextRange = TextRange(transformIndex(range.start), transformIndex(range.end))
