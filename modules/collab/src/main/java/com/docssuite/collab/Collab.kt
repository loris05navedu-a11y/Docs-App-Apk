package com.docssuite.collab

import android.content.Context
import com.docssuite.collab.cloud.SharedDocs
import com.docssuite.collab.firebase.FirebaseAccounts
import com.docssuite.collab.firebase.FirebaseStore
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.MainScope

/** L'édition partagée est-elle prête ? */
sealed interface CollabSetup {
    data class Ready(val docs: SharedDocs) : CollabSetup

    /** Il manque la configuration Firebase : [what] dit quoi. */
    data class Missing(val what: Missing.Part) : CollabSetup {
        enum class Part { CONFIG_FILE, DATABASE }
    }
}

/**
 * Le point d'entrée de l'édition partagée.
 *
 * La configuration vient du fichier google-services.json du projet Firebase,
 * transformé en ressources au moment de construire l'application : sans lui,
 * l'application se construit et marche quand même, et l'édition partagée
 * explique ce qu'il manque.
 */
object Collab {
    /** Pour les tests : une édition partagée toute prête, à la place de Firebase. */
    @Volatile
    var override: CollabSetup? = null

    @Volatile
    private var cached: CollabSetup? = null

    fun setup(context: Context): CollabSetup {
        override?.let { return it }
        cached?.let { return it }
        return synchronized(this) {
            cached ?: build(context.applicationContext).also { cached = it }
        }
    }

    private fun build(context: Context): CollabSetup {
        fun string(name: String): String? {
            val id = context.resources.getIdentifier(name, "string", context.packageName)
            return if (id == 0) null else context.getString(id).trim().takeIf { it.isNotEmpty() }
        }
        val appId = string("google_app_id")
        val apiKey = string("google_api_key")
        val projectId = string("project_id")
        val databaseUrl = string("firebase_database_url")
        if (appId == null || apiKey == null || projectId == null) return CollabSetup.Missing(CollabSetup.Missing.Part.CONFIG_FILE)
        if (databaseUrl == null) return CollabSetup.Missing(CollabSetup.Missing.Part.DATABASE)

        val app = FirebaseApp.getApps(context).firstOrNull { it.name == FirebaseApp.DEFAULT_APP_NAME }
            ?: FirebaseApp.initializeApp(
                context,
                FirebaseOptions.Builder()
                    .setApplicationId(appId)
                    .setApiKey(apiKey)
                    .setProjectId(projectId)
                    .setDatabaseUrl(databaseUrl)
                    .build(),
            )
        val docs = SharedDocs(
            accounts = FirebaseAccounts(FirebaseAuth.getInstance(app), string("default_web_client_id")),
            store = FirebaseStore(FirebaseDatabase.getInstance(app)),
            linkHost = "$projectId.web.app",
            scope = MainScope(),
        )
        return CollabSetup.Ready(docs)
    }
}
