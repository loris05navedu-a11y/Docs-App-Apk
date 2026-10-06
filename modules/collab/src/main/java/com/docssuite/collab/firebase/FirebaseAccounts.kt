package com.docssuite.collab.firebase

import android.app.Activity
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.docssuite.collab.cloud.Account
import com.docssuite.collab.cloud.AccountRules
import com.docssuite.collab.cloud.Accounts
import com.docssuite.collab.cloud.CollabException
import com.google.android.gms.tasks.Task
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.UserProfileChangeRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Les comptes Firebase : e-mail et mot de passe (avec vérification de
 * l'adresse), ou Google par le gestionnaire d'identifiants d'Android.
 *
 * @param webClientId l'identifiant « client Web » du projet, sans lequel
 *   Google ne délivre pas de jeton pour Firebase.
 */
class FirebaseAccounts(private val auth: FirebaseAuth, private val webClientId: String?) : Accounts {

    private val state = MutableStateFlow(auth.currentUser?.toAccount())

    override val current: StateFlow<Account?> = state

    override val googleAvailable: Boolean = !webClientId.isNullOrBlank()

    init {
        // Les e-mails de Firebase (vérification, mot de passe oublié) partent en français.
        auth.setLanguageCode("fr")
        auth.addAuthStateListener { state.value = it.currentUser?.toAccount() }
    }

    override suspend fun signUp(name: String, email: String, password: String) {
        AccountRules.checkSignUp(name, email, password)
        guard {
            val user = auth.createUserWithEmailAndPassword(email.trim(), password).await().user
                ?: throw CollabException("Le compte n'a pas pu être créé.")
            runCatching { user.updateProfile(UserProfileChangeRequest.Builder().setDisplayName(name.trim()).build()).await() }
            runCatching { user.sendEmailVerification().await() }
            state.value = user.toAccount()
        }
    }

    override suspend fun signIn(email: String, password: String) {
        AccountRules.checkEmail(email)
        if (password.isEmpty()) throw CollabException("Entre ton mot de passe.")
        guard {
            auth.signInWithEmailAndPassword(email.trim(), password).await()
            state.value = auth.currentUser?.toAccount()
        }
    }

    override suspend fun signInWithGoogle(activity: Activity): Boolean {
        val clientId = webClientId
        if (clientId.isNullOrBlank()) {
            throw CollabException("La connexion avec Google n'est pas activée pour cette application.")
        }
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(GetSignInWithGoogleOption.Builder(clientId).build())
            .build()
        val credential = try {
            CredentialManager.create(activity).getCredential(activity, request).credential
        } catch (e: GetCredentialCancellationException) {
            return false
        } catch (e: NoCredentialException) {
            throw CollabException("Aucun compte Google n'est disponible sur ce téléphone. Ajoutes-en un dans les réglages, ou utilise ton e-mail.", e)
        } catch (e: GetCredentialException) {
            throw CollabException(googleFailure(e), e)
        }
        if (credential !is CustomCredential || credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            throw CollabException("Google n'a pas renvoyé d'identité utilisable. Réessaie.")
        }
        val token = GoogleIdTokenCredential.createFrom(credential.data).idToken
        guard {
            auth.signInWithCredential(GoogleAuthProvider.getCredential(token, null)).await()
            state.value = auth.currentUser?.toAccount()
        }
        return true
    }

    override suspend fun sendVerification() {
        val user = auth.currentUser ?: throw CollabException("Connecte-toi d'abord.")
        guard { user.sendEmailVerification().await() }
    }

    override suspend fun refresh(): Account? {
        val user = auth.currentUser ?: return null
        guard {
            user.reload().await()
            // Un jeton neuf, pour que la base sache que l'adresse est vérifiée.
            if (user.isEmailVerified) user.getIdToken(true).await()
        }
        val account = auth.currentUser?.toAccount()
        state.value = account
        return account
    }

    override suspend fun resetPassword(email: String) {
        AccountRules.checkEmail(email)
        guard { auth.sendPasswordResetEmail(email.trim()).await() }
    }

    override fun signOut() {
        auth.signOut()
        state.value = null
    }

    private fun FirebaseUser.toAccount(): Account {
        val address = email.orEmpty().lowercase()
        val viaGoogle = providerData.any { it.providerId == GoogleAuthProvider.PROVIDER_ID }
        return Account(
            uid = uid,
            name = displayName?.trim()?.takeIf { it.isNotEmpty() } ?: address.substringBefore('@'),
            email = address,
            verified = isEmailVerified,
            viaGoogle = viaGoogle,
        )
    }

    private suspend fun guard(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CollabException) {
            throw e
        } catch (e: Exception) {
            throw CollabException(describe(e), e)
        }
    }

    internal companion object {
        /** Ce que l'erreur veut dire, en clair. */
        fun describe(e: Exception): String = when (e) {
            is FirebaseNetworkException -> "Pas de connexion à Internet. Réessaie dans un instant."
            is FirebaseTooManyRequestsException -> "Trop d'essais d'affilée. Patiente quelques minutes avant de réessayer."
            is FirebaseAuthWeakPasswordException -> "Ce mot de passe est trop faible. Choisis-en un plus long."
            is FirebaseAuthUserCollisionException -> when (e.errorCode) {
                "ERROR_EMAIL_ALREADY_IN_USE" -> "Un compte existe déjà avec cette adresse : connecte-toi (ou utilise « Mot de passe oublié »)."
                else -> "Cette adresse est déjà liée à un autre mode de connexion. Connecte-toi avec ton mot de passe."
            }
            is FirebaseAuthInvalidUserException -> when (e.errorCode) {
                "ERROR_USER_DISABLED" -> "Ce compte a été désactivé."
                else -> "E-mail ou mot de passe incorrect."
            }
            is FirebaseAuthInvalidCredentialsException -> when (e.errorCode) {
                "ERROR_INVALID_EMAIL" -> "Cette adresse e-mail n'est pas valide."
                else -> "E-mail ou mot de passe incorrect."
            }
            is FirebaseAuthException -> when (e.errorCode) {
                "ERROR_OPERATION_NOT_ALLOWED" -> "Ce mode de connexion n'est pas activé dans Firebase (Authentication > Mode de connexion)."
                else -> "La connexion a échoué (${e.errorCode})."
            }
            else -> "La connexion a échoué : ${e.message ?: e::class.java.simpleName}"
        }

        fun googleFailure(e: GetCredentialException): String {
            val detail = e.message.orEmpty()
            return if (detail.contains("28444") || detail.contains("10:") || detail.contains("Developer console", ignoreCase = true)) {
                "Google refuse la connexion : l'empreinte SHA-1 de l'application n'est pas enregistrée dans Firebase."
            } else {
                "La connexion avec Google n'a pas abouti. Réessaie, ou utilise ton e-mail."
            }
        }
    }
}

/** Attendre une tâche Firebase sans bloquer. */
internal suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener { task ->
        val error = task.exception
        when {
            error != null -> continuation.resumeWithException(error)
            task.isCanceled -> continuation.cancel()
            else -> continuation.resume(task.result)
        }
    }
}
