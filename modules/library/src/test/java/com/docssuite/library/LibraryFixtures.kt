package com.docssuite.library

import android.content.Context
import com.docssuite.core.DocMeta
import com.docssuite.core.DocType
import com.docssuite.core.DocumentStorage
import org.json.JSONArray
import org.json.JSONObject

/** Quelques documents enregistrés aux formats réels des trois éditeurs. */
object LibraryFixtures {
    private fun text(vararg paragraphs: String) = JSONObject().put("v", 2).put(
        "blocks",
        JSONArray().put(JSONObject().put("t", "text").put("text", paragraphs.joinToString("\n")).put("runs", JSONArray()))
    ).toString()

    fun seed(context: Context) {
        context.getSharedPreferences("docssuite_documents", 0).edit().clear().commit()
        context.getSharedPreferences("docssuite_library", 0).edit().clear().commit()
        val storage = DocumentStorage(context)
        storage.restore(
            DocMeta("r", "Compte rendu de réunion", DocType.TEXT, 3_000),
            text("Présents : Léa, Hugo.", "Ordre du jour : budget de l'école et sortie scolaire au musée.")
        )
        storage.restore(
            DocMeta("b", "Budget familial", DocType.SHEET, 2_000),
            JSONObject().put("sheets", JSONArray().put(
                JSONObject().put("name", "Janvier").put("cells", JSONObject().put("A1", "Loyer").put("B1", "750").put("A2", "Cantine école"))
            )).toString()
        )
        storage.restore(
            DocMeta("p", "Exposé", DocType.DECK, 1_000),
            JSONObject().put("slides", JSONArray().put(JSONObject().put("title", "Les volcans").put("content", "Éruptions et magma"))).toString()
        )
    }
}
