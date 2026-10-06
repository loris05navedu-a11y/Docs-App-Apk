package com.docssuite.collab.cloud

import android.app.Activity
import kotlinx.coroutines.flow.StateFlow

/**
 * Les comptes : par e-mail et mot de passe, ou avec Google. Les erreurs
 * arrivent en [CollabException], avec un message à montrer tel quel.
 */
interface Accounts {
    /** La personne connectée, ou `null`. */
    val current: StateFlow<Account?>

    /** Vrai si « Continuer avec Google » est possible. */
    val googleAvailable: Boolean

    /** Créer un compte ; un e-mail de vérification part tout de suite. */
    suspend fun signUp(name: String, email: String, password: String)

    suspend fun signIn(email: String, password: String)

    /** Faux si la personne a renoncé. */
    suspend fun signInWithGoogle(activity: Activity): Boolean

    suspend fun sendVerification()

    /** Relire le compte (après avoir cliqué sur le lien de vérification). */
    suspend fun refresh(): Account?

    suspend fun resetPassword(email: String)

    fun signOut()
}

/** Ce qu'on vérifie avant même de demander au serveur. */
object AccountRules {
    fun checkSignUp(name: String, email: String, password: String) {
        if (name.isBlank()) throw CollabException("Indique ton nom : c'est lui que verront les personnes avec qui tu partages.")
        if (name.trim().length > 60) throw CollabException("Ce nom est trop long (60 caractères au plus).")
        checkEmail(email)
        if (password.length < 8) throw CollabException("Choisis un mot de passe d'au moins 8 caractères.")
    }

    fun checkEmail(email: String) {
        if (!isEmail(email)) throw CollabException("Cette adresse e-mail n'est pas valide.")
    }
}
