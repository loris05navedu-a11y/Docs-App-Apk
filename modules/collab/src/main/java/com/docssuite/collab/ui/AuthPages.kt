package com.docssuite.collab.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.MarkEmailUnread
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.docssuite.collab.CollabSetup
import com.docssuite.collab.cloud.Account
import com.docssuite.collab.cloud.Accounts
import com.docssuite.collab.cloud.CollabException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SimpleTopBar(title: String, onBack: () -> Unit) {
    TopAppBar(
        title = { Text(title, fontWeight = FontWeight.Bold) },
        navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour") }
        },
    )
}

internal fun Context.findActivity(): Activity? {
    var context = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}

/** Se connecter ou créer un compte. */
@Composable
internal fun AuthPage(accounts: Accounts, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var creating by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }

    fun run(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        info = null
        scope.launch {
            try {
                action()
            } catch (e: CollabException) {
                error = e.message
            } finally {
                busy = false
            }
        }
    }

    Scaffold(topBar = { SimpleTopBar("Édition partagée", onBack) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Explanation(
                "Écrire à plusieurs, en direct",
                "Chaque lettre tapée apparaît aussitôt chez les autres, où qu'ils soient : il suffit d'une connexion " +
                    "à Internet. Un compte sert à retrouver vos documents partagés et à recevoir les invitations, " +
                    "à votre adresse e-mail.",
            )
            if (accounts.googleAvailable) {
                OutlinedButton(
                    onClick = {
                        val activity = context.findActivity() ?: return@OutlinedButton
                        run { accounts.signInWithGoogle(activity) }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    Icon(Icons.Filled.AccountCircle, contentDescription = null)
                    Spacer(Modifier.width(10.dp))
                    Text("Continuer avec Google", style = MaterialTheme.typography.titleSmall)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HorizontalDivider(Modifier.weight(1f))
                    Text("ou avec votre e-mail", Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    HorizontalDivider(Modifier.weight(1f))
                }
            }
            TabRow(selectedTabIndex = if (creating) 1 else 0) {
                Tab(selected = !creating, onClick = { creating = false; error = null; info = null }, text = { Text("Se connecter") })
                Tab(selected = creating, onClick = { creating = true; error = null; info = null }, text = { Text("Créer un compte") })
            }
            if (creating) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Votre nom") },
                    placeholder = { Text("Prénom Nom") },
                    supportingText = { Text("Il s'affiche auprès des personnes avec qui vous partagez.") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("Adresse e-mail") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Mot de passe") },
                supportingText = { if (creating) Text("8 caractères au moins.") },
                singleLine = true,
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                trailingIcon = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(
                            if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (showPassword) "Masquer le mot de passe" else "Afficher le mot de passe",
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            info?.let { Text(it, color = Accent, style = MaterialTheme.typography.bodyMedium) }
            Button(
                onClick = {
                    if (creating) run { accounts.signUp(name, email, password) }
                    else run { accounts.signIn(email, password) }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Text(if (creating) "Créer mon compte" else "Se connecter", style = MaterialTheme.typography.titleSmall)
                }
            }
            if (!creating) {
                TextButton(
                    onClick = {
                        run {
                            accounts.resetPassword(email)
                            info = "Un e-mail pour choisir un nouveau mot de passe a été envoyé à ${email.trim()}."
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                ) { Text("Mot de passe oublié ?") }
            }
        }
    }
}

/** Avant d'utiliser l'édition partagée : cliquer sur le lien reçu par e-mail. */
@Composable
internal fun VerifyPage(accounts: Accounts, account: Account, onBack: () -> Unit, pollMillis: Long = 4_000) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    // Dès que le lien est cliqué, on passe à la suite, sans rien toucher.
    LaunchedEffect(account.uid) {
        while (pollMillis > 0) {
            delay(pollMillis)
            runCatching { accounts.refresh() }
        }
    }

    fun run(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                action()
            } catch (e: CollabException) {
                message = e.message
            } finally {
                busy = false
            }
        }
    }

    Scaffold(topBar = { SimpleTopBar("Vérifiez votre adresse", onBack) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(Icons.Filled.MarkEmailUnread, contentDescription = null, tint = Accent, modifier = Modifier.size(64.dp))
            Text("Un dernier pas", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                "Un e-mail vient d'être envoyé à ${account.email}. Ouvrez-le et touchez le lien de vérification, " +
                    "puis revenez ici : la suite s'ouvre toute seule.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                "Rien reçu ? Regardez dans les courriers indésirables, ou renvoyez l'e-mail.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            message?.let { Text(it, color = Accent, style = MaterialTheme.typography.bodyMedium) }
            Button(
                onClick = {
                    run {
                        val now = accounts.refresh()
                        if (now?.verified != true) message = "L'adresse n'est pas encore vérifiée : touchez le lien de l'e-mail."
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("J'ai touché le lien") }
            OutlinedButton(
                onClick = {
                    run {
                        accounts.sendVerification()
                        message = "E-mail renvoyé à ${account.email}."
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Renvoyer l'e-mail") }
            TextButton(onClick = { accounts.signOut() }) { Text("Utiliser un autre compte") }
        }
    }
}

/** Cette version de l'application n'a pas la configuration Firebase. */
@Composable
internal fun NotConfiguredPage(missing: CollabSetup.Missing.Part, onBack: () -> Unit) {
    Scaffold(topBar = { SimpleTopBar("Édition partagée", onBack) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(Icons.Filled.CloudOff, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(56.dp))
            Text("L'édition partagée n'est pas encore activée", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                when (missing) {
                    CollabSetup.Missing.Part.CONFIG_FILE ->
                        "Cette version de l'application a été construite sans la configuration Firebase " +
                            "(le fichier google-services.json). Il faut l'ajouter puis reconstruire l'application."
                    CollabSetup.Missing.Part.DATABASE ->
                        "La configuration Firebase de cette version ne contient pas l'adresse de la base temps réel : " +
                            "la base a sans doute été créée après le téléchargement de google-services.json."
                },
                style = MaterialTheme.typography.bodyLarge,
            )
            Explanation(
                "Pour la personne qui gère l'application",
                "1. Dans la console Firebase, enregistrez l'application Android « com.docssuite ».\n" +
                    "2. Authentication : activez « Adresse e-mail/Mot de passe » et « Google ».\n" +
                    "3. Realtime Database : créez la base (Belgique, europe-west1).\n" +
                    "4. Téléchargez google-services.json et reconstruisez l'application avec.\n" +
                    "5. Mettez en ligne les règles, l'e-mail d'invitation et les liens (firebase/deploy.sh).",
            )
        }
    }
}
