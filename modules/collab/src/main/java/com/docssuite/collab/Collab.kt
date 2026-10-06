package com.docssuite.collab

import android.content.Context
import com.docssuite.collab.cloud.CollabException
import com.docssuite.collab.cloud.SharedDocs
import com.docssuite.collab.firebase.FirebaseAccounts
import com.docssuite.collab.firebase.FirebaseStore
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.MainScope
import org.json.JSONArray
import org.json.JSONObject

/** L'édition partagée est-elle prête ? */
sealed interface CollabSetup {
    data class Ready(val docs: SharedDocs) : CollabSetup

    /** Il manque la configuration Firebase : [what] dit quoi. */
    data class Missing(val what: Missing.Part) : CollabSetup {
        enum class Part { CONFIG_FILE, DATABASE }
    }
}

/** Ce qu'il faut savoir du projet Firebase, tiré de google-services.json. */
internal data class FirebaseConfig(
    val appId: String,
    val apiKey: String,
    val projectId: String,
    val databaseUrl: String?,
    val webClientId: String?,
) {
    companion object {
        /** Les ressources écrites à la construction (voir app/build.gradle). */
        fun fromResources(context: Context): FirebaseConfig? {
            fun string(name: String): String? {
                val id = context.resources.getIdentifier(name, "string", context.packageName)
                return if (id == 0) null else context.getString(id).trim().takeIf { it.isNotEmpty() }
            }
            return FirebaseConfig(
                appId = string("google_app_id") ?: return null,
                apiKey = string("google_api_key") ?: return null,
                projectId = string("project_id") ?: return null,
                databaseUrl = string("firebase_database_url"),
                webClientId = string("default_web_client_id"),
            )
        }

        /** Le fichier lui-même ; `null` s'il ne concerne pas l'application [packageName]. */
        fun parse(json: String, packageName: String): FirebaseConfig? = runCatching {
            val root = JSONObject(json)
            val project = root.getJSONObject("project_info")
            val client = root.getJSONArray("client").objects().firstOrNull {
                it.optJSONObject("client_info")?.optJSONObject("android_client_info")?.optString("package_name") == packageName
            } ?: return null
            val oauth = client.optJSONArray("oauth_client").objects() +
                client.optJSONObject("services")?.optJSONObject("appinvite_service")?.optJSONArray("other_platform_oauth_client").objects()
            FirebaseConfig(
                appId = client.getJSONObject("client_info").getString("mobilesdk_app_id"),
                apiKey = client.getJSONArray("api_key").getJSONObject(0).getString("current_key"),
                projectId = project.getString("project_id"),
                databaseUrl = project.optString("firebase_url").takeIf { it.startsWith("https://") },
                webClientId = oauth.firstOrNull { it.optInt("client_type") == 3 }?.optString("client_id")?.takeIf { it.isNotEmpty() },
            ).takeIf { it.appId.isNotEmpty() && it.apiKey.isNotEmpty() && it.projectId.isNotEmpty() }
        }.getOrNull()

        private fun JSONArray?.objects(): List<JSONObject> =
            if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
    }
}

/**
 * Le point d'entrée de l'édition partagée.
 *
 * La configuration vient du fichier google-services.json du projet Firebase :
 * transformé en ressources au moment de construire l'application, ou, à
 * défaut, importé sur le téléphone depuis l'écran de l'édition partagée.
 * Sans elle, l'application marche quand même, et l'édition partagée explique
 * ce qu'il manque.
 */
object Collab {
    /** Pour les tests : une édition partagée toute prête, à la place de Firebase. */
    @Volatile
    var override: CollabSetup? = null

    @Volatile
    private var cached: CollabSetup? = null

    private const val PREFS = "docssuite_collab"
    private const val IMPORTED = "google_services_json"

    fun setup(context: Context): CollabSetup {
        override?.let { return it }
        cached?.let { return it }
        return synchronized(this) {
            cached ?: build(context.applicationContext).also { cached = it }
        }
    }

    /**
     * Activer l'édition partagée avec un google-services.json choisi sur le
     * téléphone, quand l'application a été construite sans.
     */
    fun importConfig(context: Context, json: String): CollabSetup {
        val config = FirebaseConfig.parse(json, context.packageName)
            ?: throw CollabException("Ce fichier n'est pas le google-services.json de l'application « ${context.packageName} ».")
        if (config.databaseUrl == null) {
            throw CollabException("Ce fichier ne contient pas l'adresse de la base temps réel : crée la base dans Firebase, puis télécharge à nouveau google-services.json.")
        }
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(IMPORTED, json).commit()
        synchronized(this) {
            if (cached !is CollabSetup.Ready) cached = null
        }
        return setup(context)
    }

    /** Pour les tests : comme un nouveau lancement, en oubliant aussi la configuration importée si [forgetImport]. */
    internal fun reset(context: Context, forgetImport: Boolean = true) {
        synchronized(this) { cached = null }
        if (forgetImport) context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun build(context: Context): CollabSetup {
        val imported = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(IMPORTED, null)
            ?.let { FirebaseConfig.parse(it, context.packageName) }
        val fromBuild = FirebaseConfig.fromResources(context)
        val config = fromBuild?.takeIf { it.databaseUrl != null } ?: imported ?: fromBuild
            ?: return CollabSetup.Missing(CollabSetup.Missing.Part.CONFIG_FILE)
        val databaseUrl = config.databaseUrl ?: return CollabSetup.Missing(CollabSetup.Missing.Part.DATABASE)

        // La configuration de la construction est celle que Firebase charge seul au
        // démarrage ; une configuration importée a son application à elle.
        val app = if (config == fromBuild) {
            FirebaseApp.getApps(context).firstOrNull { it.name == FirebaseApp.DEFAULT_APP_NAME } ?: initialize(context, config, databaseUrl, null)
        } else {
            val name = "docsapp-" + config.projectId
            FirebaseApp.getApps(context).firstOrNull { it.name == name } ?: initialize(context, config, databaseUrl, name)
        }
        val docs = SharedDocs(
            accounts = FirebaseAccounts(FirebaseAuth.getInstance(app), config.webClientId),
            store = FirebaseStore(FirebaseDatabase.getInstance(app)),
            linkHost = "${config.projectId}.web.app",
            scope = MainScope(),
        )
        return CollabSetup.Ready(docs)
    }

    private fun initialize(context: Context, config: FirebaseConfig, databaseUrl: String, name: String?): FirebaseApp {
        val options = FirebaseOptions.Builder()
            .setApplicationId(config.appId)
            .setApiKey(config.apiKey)
            .setProjectId(config.projectId)
            .setDatabaseUrl(databaseUrl)
            .build()
        return if (name == null) FirebaseApp.initializeApp(context, options) else FirebaseApp.initializeApp(context, options, name)
    }
}
