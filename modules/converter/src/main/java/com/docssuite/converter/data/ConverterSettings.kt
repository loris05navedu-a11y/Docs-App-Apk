package com.docssuite.converter.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import com.docssuite.converter.engine.ConversionException
import java.io.File

enum class ThemeMode(val label: String) {
    SYSTEM("Automatique"),
    LIGHT("Clair"),
    DARK("Sombre")
}

enum class HistoryRetention(val label: String, val days: Int) {
    WEEK("7 jours", 7),
    MONTH("30 jours", 30),
    FOREVER("Sans limite", 0)
}

/** Préférences du convertisseur, conservées localement. */
class ConverterSettings(private val context: Context) {

    private val preferences =
        context.getSharedPreferences("converter-settings", Context.MODE_PRIVATE)

    var themeMode: ThemeMode
        get() = runCatching { ThemeMode.valueOf(preferences.getString(KEY_THEME, null) ?: "") }
            .getOrDefault(ThemeMode.SYSTEM)
        set(value) = preferences.edit().putString(KEY_THEME, value.name).apply()

    var defaultQuality: Int
        get() = preferences.getInt(KEY_QUALITY, 90).coerceIn(40, 100)
        set(value) = preferences.edit().putInt(KEY_QUALITY, value.coerceIn(40, 100)).apply()

    var retention: HistoryRetention
        get() = runCatching { HistoryRetention.valueOf(preferences.getString(KEY_RETENTION, null) ?: "") }
            .getOrDefault(HistoryRetention.MONTH)
        set(value) = preferences.edit().putString(KEY_RETENTION, value.name).apply()

    /** Dossier choisi par l'utilisateur pour l'enregistrement direct, s'il en a désigné un. */
    var outputTree: Uri?
        get() = preferences.getString(KEY_OUTPUT_TREE, null)?.let(Uri::parse)
        set(value) = preferences.edit().putString(KEY_OUTPUT_TREE, value?.toString()).apply()

    var outputTreeLabel: String?
        get() = preferences.getString(KEY_OUTPUT_LABEL, null)
        set(value) = preferences.edit().putString(KEY_OUTPUT_LABEL, value).apply()

    /**
     * Retient l'autorisation d'écrire dans le dossier choisi. Sans cet appel,
     * l'accès serait perdu au prochain démarrage de l'application.
     */
    fun rememberOutputTree(uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        outputTree = uri
        outputTreeLabel = readableTreeName(uri)
    }

    fun forgetOutputTree() {
        outputTree?.let { uri ->
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
        }
        outputTree = null
        outputTreeLabel = null
    }

    /**
     * Écrit le résultat dans le dossier par défaut. Renvoie `null` si aucun
     * dossier n'est configuré : l'appelant ouvre alors le sélecteur système.
     */
    fun saveToDefaultFolder(file: File, displayName: String, mime: String): String? {
        val tree = outputTree ?: return null
        val resolver = context.contentResolver
        return runCatching {
            val parent = DocumentsContract.buildDocumentUriUsingTree(
                tree, DocumentsContract.getTreeDocumentId(tree)
            )
            val target = DocumentsContract.createDocument(resolver, parent, mime, displayName)
                ?: throw ConversionException("Le dossier par défaut n'est plus accessible")
            resolver.openOutputStream(target)?.use { output ->
                file.inputStream().buffered().use { it.copyTo(output) }
            } ?: throw ConversionException("Le dossier par défaut n'est plus accessible")
            outputTreeLabel ?: "le dossier choisi"
        }.getOrElse {
            // L'autorisation peut avoir été révoquée depuis les réglages du
            // système : on repasse alors par le sélecteur plutôt que d'échouer.
            forgetOutputTree()
            null
        }
    }

    private fun readableTreeName(uri: Uri): String {
        val id = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
            ?: return "Dossier choisi"
        val tail = id.substringAfterLast(':', "").trim('/')
        return tail.ifBlank { "Stockage interne" }
    }

    private companion object {
        const val KEY_THEME = "theme"
        const val KEY_QUALITY = "quality"
        const val KEY_RETENTION = "retention"
        const val KEY_OUTPUT_TREE = "output-tree"
        const val KEY_OUTPUT_LABEL = "output-label"
    }
}
