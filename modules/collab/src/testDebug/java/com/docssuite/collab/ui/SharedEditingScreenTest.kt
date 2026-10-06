package com.docssuite.collab.ui

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.docssuite.collab.CollabSetup
import com.docssuite.collab.cloud.Account
import com.docssuite.collab.cloud.DocSession
import com.docssuite.collab.cloud.Role
import com.docssuite.collab.cloud.SharedDocs
import com.docssuite.collab.store.MemoryServer
import com.docssuite.collab.testing.FakeAccounts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xxhdpi")
class SharedEditingScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()
    private val output: String? = System.getProperty("screenshots")

    private val lea = Account("lea", "Léa Martin", "lea.martin@gmail.com", verified = true)
    private val paul = Account("paul", "Paul Durand", "paul@exemple.fr", verified = true)

    private val main = Handler(Looper.getMainLooper())
    private val server = MemoryServer(post = { main.post(it) })
    private val scope = MainScope()

    private class Person(val accounts: FakeAccounts, val docs: SharedDocs)

    private fun person(signedIn: Account?): Person {
        val accounts = FakeAccounts(signedIn = signedIn)
        val docs = SharedDocs(accounts, server.device(signedIn?.uid ?: "nouveau"), "doc-app-suite.web.app", scope, heartbeatMillis = 0, keepOpenMillis = 0)
        return Person(accounts, docs)
    }

    private fun show(person: Person, openDocId: String? = null) = compose.setContent {
        MaterialTheme(colorScheme = lightColorScheme()) {
            SharedEditingScreen(onBack = {}, openDocId = openDocId, setup = CollabSetup.Ready(person.docs))
        }
    }

    private fun shoot(name: String) {
        val dir = output ?: return
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        File(dir).mkdirs()
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Laisser passer le temps (la présence part après un court délai). */
    private fun pass(millis: Long = 300) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))
        compose.waitForIdle()
    }

    private fun <T> await(block: suspend CoroutineScope.() -> T): T {
        val result = scope.async { block() }
        repeat(500) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
            @Suppress("OPT_IN_USAGE")
            if (result.isCompleted) return result.getCompleted()
        }
        error("Pas de réponse")
    }

    private fun open(person: Person, id: String): DocSession {
        val session = person.docs.acquire(id)
        pass()
        return session
    }

    private fun DocSession.typeAtEnd(text: String) {
        val live = this.text!!
        shown(live.value)
        val after = live.value.text + text
        onValueChange(TextFieldValue(after, TextRange(after.length)))
        pass()
    }

    private fun field(label: String) = compose.onNode(hasSetTextAction() and hasText(label, substring = true))

    private fun documentText() = compose.onNodeWithContentDescription("Texte du document")

    @Test
    fun `sans configuration Firebase, on explique ce qui manque`() {
        compose.setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                SharedEditingScreen(onBack = {}, setup = CollabSetup.Missing(CollabSetup.Missing.Part.CONFIG_FILE))
            }
        }
        compose.onNodeWithText("L'édition partagée n'est pas encore activée").assertIsDisplayed()
        compose.onNode(hasText("le fichier google-services.json", substring = true)).assertExists()
        shoot("partage-0-non-configure")
    }

    @Test
    fun `creer un compte, verifier l'adresse, creer un document et ecrire`() {
        val me = person(null)
        show(me)
        compose.onNodeWithText("Continuer avec Google").assertIsDisplayed()
        shoot("partage-1-connexion")

        compose.onNodeWithText("Créer un compte").performClick()
        field("Votre nom").performTextInput("Léa Martin")
        field("Adresse e-mail").performTextInput("lea.martin@gmail.com")
        field("Mot de passe").performTextInput("court")
        compose.onNodeWithText("Créer mon compte").performClick()
        compose.onNodeWithText("Choisissez un mot de passe d'au moins 8 caractères.").assertIsDisplayed()
        field("Mot de passe").performTextInput("-et-long")
        compose.onNodeWithText("Créer mon compte").performClick()

        compose.onNodeWithText("Un dernier pas").assertIsDisplayed()
        assertEquals(listOf("lea.martin@gmail.com"), me.accounts.verificationsSent)
        shoot("partage-2-verification")
        compose.onNodeWithText("J'ai touché le lien").performClick()
        compose.onNode(hasText("pas encore vérifiée", substring = true)).assertIsDisplayed()
        me.accounts.linkClicked = true
        compose.onNodeWithText("J'ai touché le lien").performClick()

        compose.onNodeWithText("Nouveau document").performClick()
        field("Titre").performTextInput("Compte rendu")
        compose.onNodeWithText("Créer").performClick()
        pass()
        compose.onNodeWithText("Personne d'autre n'a encore accès.").assertIsDisplayed()
        documentText().performTextInput("Bonjour à tous")
        pass()
        documentText().assert(hasText("Bonjour à tous"))
        compose.onNodeWithText("Enregistré").assertIsDisplayed()
        val id = (server.value("docs") as Map<*, *>).keys.single() as String
        assertTrue((server.value("docs/$id/history") as Map<*, *>).isNotEmpty())
    }

    @Test
    fun `une invitation acceptee, puis on ecrit a deux en direct`() {
        val owner = person(lea)
        val guest = person(paul)
        val id = await { owner.docs.create("Plan de la semaine", "Lundi :") }
        val leaSide = open(owner, id)
        await { leaSide.invite(paul.email, Role.EDITOR) }

        show(guest)
        pass()
        compose.onNodeWithText("Léa Martin vous invite à modifier").assertIsDisplayed()
        compose.onNodeWithText("Plan de la semaine").assertIsDisplayed()
        shoot("partage-3-invitation")
        compose.onNodeWithText("Accepter").performClick()
        pass()

        // Léa tape : Paul le voit aussitôt, avec son curseur.
        leaSide.typeAtEnd(" réunion d'équipe")
        documentText().assert(hasText("Lundi : réunion d'équipe"))
        compose.onNodeWithText("Léa").assertIsDisplayed()

        // Paul tape : Léa le voit.
        documentText().performTextInput("Mardi : bilan. ")
        pass()
        assertTrue(leaSide.text!!.value.text.contains("Mardi : bilan."))
        assertTrue(leaSide.others.any { it.name == "Paul Durand" })
        shoot("partage-4-editeur-en-direct")
    }

    @Test
    fun `le proprietaire invite, change un role, voit l'etat de l'e-mail`() {
        val owner = person(lea)
        val id = await { owner.docs.create("Budget 2027", "Total :") }
        show(owner)
        pass()
        compose.onNodeWithText("Budget 2027").performClick()
        pass()
        compose.onNodeWithContentDescription("Personnes et partage").performClick()
        field("Adresse e-mail").performTextInput("nina@exemple.fr")
        compose.onNode(hasText("Commentateur") and hasClickAction()).performClick()
        compose.onNodeWithText("Inviter").performClick()
        pass()
        compose.onNodeWithText("nina@exemple.fr").assertIsDisplayed()
        compose.onNodeWithText("E-mail en préparation…").assertIsDisplayed()
        // Le serveur a envoyé l'e-mail.
        server.write("docs/$id/invites/nina@exemple,fr/mail", mapOf("state" to "sent", "at" to System.currentTimeMillis()))
        pass()
        compose.onNodeWithText("E-mail d'invitation envoyé").assertIsDisplayed()
        shoot("partage-5-personnes")

        compose.onNodeWithContentDescription("Gérer l'invitation de nina@exemple.fr").performClick()
        compose.onNode(hasText("Lecteur") and hasClickAction() and hasAnyAncestor(isPopup())).performClick()
        pass()
        assertEquals("reader", server.value("docs/$id/invites/nina@exemple,fr/role"))
        assertEquals("reader", server.value("invitesByEmail/nina@exemple,fr/$id/role"))

        compose.onNodeWithContentDescription("Gérer l'invitation de nina@exemple.fr").performClick()
        compose.onNodeWithText("Annuler l'invitation").performClick()
        pass()
        assertEquals(null, server.value("docs/$id/invites"))
    }

    @Test
    fun `un commentateur lit en direct et commente un passage`() {
        val owner = person(lea)
        val guest = person(paul)
        val id = await { owner.docs.create("Programme", "Visite du musée à 10 h") }
        val leaSide = open(owner, id)
        await { leaSide.invite(paul.email, Role.COMMENTER) }
        show(guest)
        pass()
        compose.onNodeWithText("Accepter").performClick()
        pass()
        compose.onNodeWithText("Vous pouvez commenter (sélectionnez du texte), pas modifier.").assertIsDisplayed()

        compose.onNodeWithContentDescription("Commenter").performClick()
        field("Votre commentaire").performTextInput("On peut avancer à 9 h 30 ?")
        compose.onNodeWithContentDescription("Publier le commentaire").performClick()
        pass()
        compose.onNodeWithText("On peut avancer à 9 h 30 ?").assertIsDisplayed()
        shoot("partage-6-commentaires")
        assertEquals("On peut avancer à 9 h 30 ?", leaSide.comments.single().text)
    }

    @Test
    fun `un lecteur ne peut pas modifier`() {
        val owner = person(lea)
        val guest = person(paul)
        val id = await { owner.docs.create("Règlement", "Article 1") }
        val leaSide = open(owner, id)
        await { leaSide.invite(paul.email, Role.READER) }
        show(guest)
        pass()
        compose.onNodeWithText("Accepter").performClick()
        pass()
        compose.onNodeWithText("Lecture seule : vous voyez les modifications en direct.").assertIsDisplayed()
        leaSide.typeAtEnd(" — en vigueur")
        documentText().assert(hasText("Article 1 — en vigueur"))
        assertEquals(null, (server.value("docs/$id/history") as? Map<*, *>)?.values?.firstOrNull { (it as Map<*, *>)["a"] == "paul" })
    }

    @Test
    fun `un lien d'invitation ouvre l'invitation, puis le document`() {
        val owner = person(lea)
        val guest = person(paul)
        val id = await { owner.docs.create("Voyage", "Départ samedi") }
        val leaSide = open(owner, id)
        await { leaSide.invite(paul.email, Role.EDITOR) }
        show(guest, openDocId = id)
        pass()
        compose.onNodeWithText("Invitation").assertIsDisplayed()
        compose.onNodeWithText("Connecté en tant que paul@exemple.fr").assertIsDisplayed()
        compose.onNodeWithText("Accepter").performClick()
        pass()
        documentText().assert(hasText("Départ samedi"))
    }

    @Test
    fun `retire du document pendant qu'on le lit, on est prevenu`() {
        val owner = person(lea)
        val guest = person(paul)
        val id = await { owner.docs.create("Notes", "Texte") }
        val leaSide = open(owner, id)
        await { leaSide.invite(paul.email, Role.EDITOR) }
        show(guest)
        pass()
        compose.onNodeWithText("Accepter").performClick()
        pass()
        await { leaSide.remove(leaSide.members.single { it.uid == "paul" }) }
        pass()
        compose.onNodeWithText("Vous n'avez plus accès à ce document.").assertIsDisplayed()
        compose.onNode(hasText("paul@exemple.fr", substring = true)).assertIsDisplayed()
        compose.onNodeWithText("Retour à mes documents").performClick()
        pass()
        compose.onNodeWithText("Nouveau document").assertIsDisplayed()
    }
}
