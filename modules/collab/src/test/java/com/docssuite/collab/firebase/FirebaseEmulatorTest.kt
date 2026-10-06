package com.docssuite.collab.firebase

import android.os.Looper
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.core.app.ApplicationProvider
import com.docssuite.collab.cloud.CollabException
import com.docssuite.collab.cloud.DocSession
import com.docssuite.collab.cloud.Invitation
import com.docssuite.collab.cloud.Role
import com.docssuite.collab.cloud.SharedDocs
import com.docssuite.collab.store.StoreException
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.net.HttpURLConnection
import java.net.URL
import java.time.Duration
import kotlin.concurrent.thread

/**
 * Le vrai SDK Firebase de l'application, contre les émulateurs Firebase et
 * les vraies règles de la base : inscription, vérification de l'adresse,
 * invitation, acceptation, et deux téléphones qui tapent en même temps.
 *
 * Ne tourne que si les émulateurs sont lancés :
 *
 *   cd firebase && npx firebase emulators:exec --only auth,database --project demo-docsapp \
 *     "cd .. && ./gradlew :modules:collab:testDebugUnitTest --tests '*FirebaseEmulatorTest*' -PfirebaseEmulators=true"
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FirebaseEmulatorTest {

    private val project = "demo-docsapp"
    private val apiKey = "fake-api-key"
    private val run = System.nanoTime().toString(36)
    private val apps = ArrayList<FirebaseApp>()
    private val scope: CoroutineScope = MainScope()

    private class Phone(val name: String, val email: String, val auth: FirebaseAuth, val docs: SharedDocs)

    @Before
    fun emulatorsRunning() {
        assumeTrue("émulateurs Firebase non lancés", System.getProperty("firebaseEmulators") == "true")
    }

    @After
    fun tearDown() {
        apps.forEach { app ->
            runCatching { FirebaseAuth.getInstance(app).signOut() }
            runCatching { FirebaseDatabase.getInstance(app).goOffline() }
            runCatching { app.delete() }
        }
    }

    private fun phone(name: String): Phone {
        val app = FirebaseApp.initializeApp(
            ApplicationProvider.getApplicationContext(),
            FirebaseOptions.Builder()
                .setProjectId(project)
                .setApiKey(apiKey)
                .setApplicationId("1:1234567890:android:0123456789abcdef")
                .setDatabaseUrl("http://127.0.0.1:9000?ns=$project-default-rtdb")
                .build(),
            "$name-$run",
        )
        apps += app
        val auth = FirebaseAuth.getInstance(app).apply { useEmulator("127.0.0.1", 9099) }
        val database = FirebaseDatabase.getInstance(app).apply { useEmulator("127.0.0.1", 9000) }
        val docs = SharedDocs(FirebaseAccounts(auth, webClientId = null), FirebaseStore(database), "$project.web.app", scope, checkpointEvery = 10)
        return Phone(name, "$name.$run@exemple.fr", auth, docs)
    }

    // --- Faire tourner le fil principal pendant qu'on attend le réseau ---

    private fun <T> await(what: String = "réponse", block: suspend CoroutineScope.() -> T): T {
        val result = scope.async { block() }
        waitUntil(what) { result.isCompleted }
        @Suppress("OPT_IN_USAGE")
        return result.getCompleted()
    }

    private fun waitUntil(what: String, condition: () -> Boolean) {
        repeat(2_000) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
            if (condition()) return
            Thread.sleep(10)
        }
        fail("Toujours pas : $what")
    }

    private fun <T> latest(flow: Flow<T>): () -> T? {
        var last: T? = null
        scope.launch { flow.collect { last = it } }
        return { last }
    }

    /** L'émulateur garde les e-mails de vérification : on « clique » sur le lien. */
    private fun clickVerificationLink(email: String) {
        var failure: Throwable? = null
        thread {
            runCatching {
                val codes = http("GET", "http://127.0.0.1:9099/emulator/v1/projects/$project/oobCodes")
                val list = JSONObject(codes).getJSONArray("oobCodes")
                val code = (0 until list.length()).map { list.getJSONObject(it) }
                    .last { it.getString("email") == email && it.getString("requestType") == "VERIFY_EMAIL" }
                    .getString("oobCode")
                http("POST", "http://127.0.0.1:9099/identitytoolkit.googleapis.com/v1/accounts:update?key=$apiKey", """{"oobCode":"$code"}""")
            }.onFailure { failure = it }
        }.join()
        failure?.let { throw it }
    }

    private fun http(method: String, url: String, body: String? = null): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = method
        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body.toByteArray()) }
        }
        check(connection.responseCode in 200..299) { "$method $url : ${connection.responseCode}" }
        return connection.inputStream.bufferedReader().readText()
    }

    private fun signUpVerified(phone: Phone, displayName: String) {
        await("inscription") { phone.docs.accounts.signUp(displayName, phone.email, "mot-de-passe-1") }
        assertFalse(phone.docs.accounts.current.value!!.verified)
        clickVerificationLink(phone.email)
        val account = await("vérification") { phone.docs.accounts.refresh() }!!
        assertTrue(account.verified)
        assertEquals(displayName, account.name)
    }

    private fun DocSession.type(text: String, at: Int = this.text!!.value.text.length) {
        val live = this.text!!
        shown(live.value)
        val before = live.value.text
        onValueChange(TextFieldValue(before.substring(0, at) + text + before.substring(at), TextRange(at + text.length)))
    }

    private fun open(phone: Phone, id: String): DocSession {
        val session = phone.docs.acquire(id)
        waitUntil("ouverture chez ${phone.name}") { session.state != DocSession.State.Loading }
        assertEquals(DocSession.State.Open, session.state)
        return session
    }

    private fun share(owner: Phone, guest: Phone, id: String, role: Role): Invitation {
        val session = open(owner, id)
        await("invitation") { session.invite(guest.email, role) }
        val invitations = latest(guest.docs.invitations(guest.docs.accounts.current.value!!))
        waitUntil("invitation reçue") { invitations()?.any { it.docId == id } == true }
        val invitation = invitations()!!.single { it.docId == id }
        await("acceptation") { guest.docs.accept(invitation) }
        return invitation
    }

    @Test
    fun `inscription, invitation, et deux telephones qui tapent en meme temps`() {
        val lea = phone("lea")
        val paul = phone("paul")
        signUpVerified(lea, "Léa Martin")
        signUpVerified(paul, "Paul")

        val id = await("création") { lea.docs.create("Compte rendu", "Bonjour") }
        val invitation = share(lea, paul, id, Role.EDITOR)
        assertEquals("Compte rendu", invitation.title)
        assertEquals("Léa Martin", invitation.byName)

        val a = open(lea, id)
        val b = open(paul, id)
        assertEquals("Bonjour", b.text!!.value.text)
        waitUntil("Paul et Léa membres") { a.members.size == 2 }

        // Lettre par lettre, en même temps, sans attendre le serveur.
        val left = "abcdefghijklmnop"
        val right = "0123456789ABCDEF"
        for (i in left.indices) {
            a.type(left[i].toString())
            b.type(right[i].toString(), at = 0)
            if (i % 4 == 0) shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(5))
        }
        waitUntil("même texte des deux côtés") {
            a.text!!.synced && b.text!!.synced && a.text!!.value.text == b.text!!.value.text &&
                a.text!!.value.text.length == "Bonjour".length + left.length + right.length
        }
        val text = a.text!!.value.text
        assertTrue(text, text.contains("Bonjour"))
        assertTrue(text, left.all { it in text } && right.all { it in text })

        // Chacun voit l'autre, et son curseur.
        waitUntil("présences") { a.others.any { it.name == "Paul" } && b.others.any { it.name == "Léa Martin" } }

        // Un commentaire arrive chez l'autre.
        await("commentaire") { b.comment("Ajouter la date ?", "Bonjour") }
        waitUntil("commentaire reçu") { a.comments.any { it.text == "Ajouter la date ?" && it.name == "Paul" } }

        // Un nouvel arrivant part de l'instantané et retrouve le même texte.
        val nina = phone("nina")
        signUpVerified(nina, "Nina")
        share(lea, nina, id, Role.READER)
        // Assez de révisions, une par une, pour qu'un instantané soit écrit.
        repeat(12) {
            a.type(".")
            waitUntil("frappe enregistrée") { a.text!!.synced }
        }
        waitUntil("Paul a tout reçu") { b.text!!.value.text == a.text!!.value.text }
        val final = a.text!!.value.text
        val checkpoint = await("instantané") { FirebaseStore(FirebaseDatabase.getInstance(apps[0])).read("docs/$id/checkpoint") } as Map<*, *>
        assertTrue("un instantané a été écrit : $checkpoint", (checkpoint["n"] as Number).toInt() >= 10)
        val c = open(nina, id)
        waitUntil("le retardataire rattrape") { c.text!!.value.text == final && c.text!!.revision == a.text!!.revision }
    }

    @Test
    fun `les regles tiennent pour un lecteur, un promu et un retire`() {
        val lea = phone("lea")
        val paul = phone("paul")
        signUpVerified(lea, "Léa")
        signUpVerified(paul, "Paul")
        val id = await("création") { lea.docs.create("Budget", "Total") }
        share(lea, paul, id, Role.READER)
        val a = open(lea, id)
        val b = open(paul, id)

        // Un lecteur ne modifie rien, même en écrivant directement dans la base.
        assertFalse(b.canEdit)
        val store = FirebaseStore(FirebaseDatabase.getInstance(apps[1]))
        var denied: StoreException? = null
        store.claim("docs/$id/history/r000000000", mapOf("c" to "x", "a" to paul.auth.uid, "o" to "[5,\"!\"]", "t" to com.docssuite.collab.store.ServerTime)) { _, e -> denied = e }
        waitUntil("refus") { denied != null }
        assertTrue(denied is StoreException.PermissionDenied)
        try {
            await("invitation interdite") { b.invite("autre.$run@exemple.fr", Role.EDITOR) }
            fail()
        } catch (e: CollabException) {
            assertEquals("Seul le propriétaire du document peut faire cela.", e.message)
        }

        // Promu éditeur : il écrit ; Léa le voit.
        await("promotion") { a.setRole(a.members.single { it.name == "Paul" }, Role.EDITOR) }
        waitUntil("Paul éditeur") { b.canEdit }
        b.type(" : 120 €")
        waitUntil("Léa voit la frappe") { a.text!!.value.text == "Total : 120 €" }

        // Retiré : son document se ferme.
        await("retrait") { a.remove(a.members.single { it.name == "Paul" }) }
        waitUntil("fermé chez Paul") { b.state is DocSession.State.Closed }
    }
}
