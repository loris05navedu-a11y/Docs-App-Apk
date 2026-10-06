package com.docssuite.collab.testing

import android.app.Activity
import com.docssuite.collab.cloud.Account
import com.docssuite.collab.cloud.AccountRules
import com.docssuite.collab.cloud.Accounts
import com.docssuite.collab.cloud.CollabException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Des comptes en mémoire, pour les tests et les aperçus. */
class FakeAccounts(
    signedIn: Account? = null,
    override val googleAvailable: Boolean = true,
    /** Le compte qu'on obtient avec « Continuer avec Google ». */
    var google: Account? = Account("g-1", "Camille Google", "camille@gmail.com", verified = true, viaGoogle = true),
) : Accounts {

    private class Registered(val account: Account, val password: String)

    private val state = MutableStateFlow(signedIn)
    private val registered = HashMap<String, Registered>()
    private var next = 0

    override val current: StateFlow<Account?> = state

    /** Les e-mails de vérification et de mot de passe oublié « envoyés ». */
    val verificationsSent = ArrayList<String>()
    val resetsSent = ArrayList<String>()

    /** Vrai si la personne a cliqué sur le lien de vérification. */
    var linkClicked = false

    fun register(account: Account, password: String) {
        registered[account.email.lowercase()] = Registered(account, password)
    }

    override suspend fun signUp(name: String, email: String, password: String) {
        AccountRules.checkSignUp(name, email, password)
        val address = email.trim().lowercase()
        if (registered.containsKey(address)) throw CollabException("Un compte existe déjà avec cette adresse : connectez-vous.")
        val account = Account("u-${++next}", name.trim(), address, verified = false)
        register(account, password)
        state.value = account
        verificationsSent += address
    }

    override suspend fun signIn(email: String, password: String) {
        AccountRules.checkEmail(email)
        val found = registered[email.trim().lowercase()]
        if (found == null || found.password != password) throw CollabException("E-mail ou mot de passe incorrect.")
        state.value = found.account
    }

    override suspend fun signInWithGoogle(activity: Activity): Boolean {
        val account = google ?: return false
        state.value = account
        return true
    }

    override suspend fun sendVerification() {
        state.value?.let { verificationsSent += it.email }
    }

    override suspend fun refresh(): Account? {
        val account = state.value ?: return null
        if (linkClicked && !account.verified) {
            val verified = account.copy(verified = true)
            registered[account.email]?.let { register(verified, it.password) }
            state.value = verified
        }
        return state.value
    }

    override suspend fun resetPassword(email: String) {
        AccountRules.checkEmail(email)
        resetsSent += email.trim().lowercase()
    }

    override fun signOut() {
        state.value = null
    }
}
