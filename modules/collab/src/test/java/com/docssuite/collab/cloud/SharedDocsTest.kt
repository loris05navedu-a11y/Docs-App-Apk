package com.docssuite.collab.cloud

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.docssuite.collab.store.MemoryServer
import com.docssuite.collab.store.MemoryServer.Access
import com.docssuite.collab.store.MemoryStore
import com.docssuite.collab.testing.FakeAccounts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * L'édition partagée de bout en bout, sur une base en mémoire : plusieurs
 * personnes, chacune sur son appareil, avec des règles simplifiées (les vraies
 * sont essayées contre l'émulateur Firebase).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SharedDocsTest {

    private val lea = Account("lea", "Léa Martin", "lea.martin@gmail.com", verified = true)
    private val paul = Account("paul", "Paul", "paul@exemple.fr", verified = true)
    private val nina = Account("nina", "Nina", "nina@exemple.fr", verified = true)

    /** Assez proches des vraies règles pour que les refus arrivent au bon moment. */
    private fun MemoryServer.simpleRules() {
        rules = rules@{ access, path, uid ->
            val parts = path.split('/')
            if (uid == null) return@rules false
            when (parts[0]) {
                "userDocs" -> access == Access.WRITE || parts.getOrNull(1) == uid
                "invitesByEmail" -> true
                "docs" -> {
                    val doc = parts.getOrNull(1) ?: return@rules false
                    val role = value("docs/$doc/members/$uid/role") as? String
                    val creating = value("docs/$doc/meta") == null
                    if (access == Access.READ) return@rules role != null
                    when (parts.getOrNull(2)) {
                        null -> role == "owner"
                        "meta" -> creating || role == "owner"
                        "members" -> creating || role == "owner" || parts.getOrNull(3) == uid
                        "invites" -> true
                        "history", "checkpoint", "activity" -> creating || role == "owner" || role == "editor"
                        "comments" -> role != null && role != "reader"
                        "presence" -> role != null
                        else -> false
                    }
                }
                else -> false
            }
        }
    }

    private class Phone(val account: Account, val store: MemoryStore, val accounts: FakeAccounts, val docs: SharedDocs)

    private fun TestScope.world(checkpointEvery: Int = 100): (Account) -> Phone {
        val scope: CoroutineScope = this
        // Comme Firebase : les réponses arrivent plus tard, jamais pendant l'appel.
        val server = MemoryServer(post = { block -> scope.launch { block() } })
        server.simpleRules()
        return { account ->
            val accounts = FakeAccounts(signedIn = account)
            val store = server.device(account.uid)
            Phone(account, store, accounts, SharedDocs(accounts, store, "demo.web.app", scope, checkpointEvery, heartbeatMillis = 0, keepOpenMillis = 0))
        }
    }

    /** La dernière valeur du flux, suivie en arrière-plan. */
    private fun <T> TestScope.latest(flow: Flow<T>): () -> T? {
        var last: T? = null
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.collect { last = it } }
        return { last }
    }

    private fun DocSession.type(newText: String) {
        val live = checkNotNull(text) { "document pas encore chargé" }
        shown(live.value)
        onValueChange(TextFieldValue(newText, TextRange(newText.length)))
    }

    private suspend fun TestScope.invite(owner: Phone, guest: Phone, docId: String, role: Role) {
        val session = owner.docs.acquire(docId)
        advanceUntilIdle()
        session.invite(guest.account.email, role)
        advanceUntilIdle()
        val invitation = latest(guest.docs.invitations(guest.account))
        advanceUntilIdle()
        guest.docs.accept(invitation()!!.single { it.docId == docId })
        advanceUntilIdle()
        owner.docs.release(session)
    }

    @Test
    fun `un document cree apparait dans ma liste, avec son texte`() = runTest {
        val phone = world()(lea)
        val list = latest(phone.docs.documents(lea))
        val id = phone.docs.create("Compte rendu", "Bonjour")
        advanceUntilIdle()
        val doc = list()!!.single()
        assertEquals(id, doc.id)
        assertEquals("Compte rendu", doc.title)
        assertEquals(Role.OWNER, doc.role)
        val session = phone.docs.acquire(id)
        advanceUntilIdle()
        assertEquals(DocSession.State.Open, session.state)
        assertEquals("Bonjour", session.text!!.value.text)
        assertTrue(session.isOwner)
    }

    @Test
    fun `une adresse non verifiee ne cree rien`() = runTest {
        val phone = world()(lea.copy(verified = false))
        try {
            phone.docs.create("Essai", "")
            fail()
        } catch (e: CollabException) {
            assertEquals("Vérifie d'abord ton adresse e-mail.", e.message)
        }
    }

    @Test
    fun `inviter puis accepter donne acces avec le bon role`() = runTest {
        val connect = world()
        val owner = connect(lea)
        val guest = connect(paul)
        val id = owner.docs.create("Budget", "")
        val session = owner.docs.acquire(id)
        advanceUntilIdle()

        session.invite("Paul@Exemple.fr", Role.COMMENTER)
        advanceUntilIdle()
        assertEquals(listOf("Paul@Exemple.fr"), session.invites.map { it.email })

        val invitations = latest(guest.docs.invitations(paul))
        advanceUntilIdle()
        val invitation = invitations()!!.single()
        assertEquals("Budget", invitation.title)
        assertEquals("Léa Martin", invitation.byName)
        assertEquals(Role.COMMENTER, invitation.role)

        val guestList = latest(guest.docs.documents(paul))
        guest.docs.accept(invitation)
        advanceUntilIdle()
        assertEquals(Role.COMMENTER, guestList()!!.single().role)
        assertTrue(invitations()!!.isEmpty())
        assertTrue(session.invites.isEmpty())
        assertEquals(listOf("Léa Martin" to Role.OWNER, "Paul" to Role.COMMENTER), session.members.map { it.name to it.role })
    }

    @Test
    fun `on n'invite ni soi-meme, ni deux fois, ni une adresse fausse`() = runTest {
        val connect = world()
        val owner = connect(lea)
        val id = owner.docs.create("Budget", "")
        val session = owner.docs.acquire(id)
        advanceUntilIdle()
        for ((address, message) in listOf(
            "pas-une-adresse" to "Cette adresse e-mail n'est pas valide.",
            "LEA.MARTIN@gmail.com" to "C'est ta propre adresse.",
        )) {
            try {
                session.invite(address, Role.EDITOR)
                fail(address)
            } catch (e: CollabException) {
                assertEquals(message, e.message)
            }
        }
        session.invite("paul@exemple.fr", Role.EDITOR)
        advanceUntilIdle()
        try {
            session.invite("paul@exemple.fr", Role.READER)
            fail()
        } catch (e: CollabException) {
            assertTrue(e.message!!.startsWith("Une invitation attend déjà cette adresse"))
        }
    }

    @Test
    fun `deux personnes tapent en meme temps et voient les lettres de l'autre`() = runTest {
        val connect = world(checkpointEvery = 5)
        val owner = connect(lea)
        val guest = connect(paul)
        val id = owner.docs.create("Notes", "")
        invite(owner, guest, id, Role.EDITOR)
        val a = owner.docs.acquire(id)
        val b = guest.docs.acquire(id)
        advanceUntilIdle()

        a.type("Bonjour")
        b.type("Salut")
        advanceUntilIdle()
        assertEquals(a.text!!.value.text, b.text!!.value.text)
        assertTrue(a.text!!.value.text.contains("Bonjour") && a.text!!.value.text.contains("Salut"))

        // Lettre par lettre, chacun de son côté.
        repeat(20) { i ->
            val target = if (i % 2 == 0) a else b
            target.type(target.text!!.value.text + ('a' + i))
            if (i % 3 == 0) advanceUntilIdle()
        }
        advanceUntilIdle()
        assertEquals(a.text!!.value.text, b.text!!.value.text)
        assertEquals(12 + 20, a.text!!.value.text.length)
        assertTrue(a.text!!.synced && b.text!!.synced)
        // L'instantané a suivi.
        val checkpoint = (owner.store.read("docs/$id/checkpoint") as Map<*, *>)
        assertTrue((checkpoint["n"] as Long) >= 5)
    }

    @Test
    fun `un nouvel arrivant part de l'instantane et rattrape la suite`() = runTest {
        val connect = world(checkpointEvery = 4)
        val owner = connect(lea)
        val id = owner.docs.create("Journal", "")
        val a = owner.docs.acquire(id)
        advanceUntilIdle()
        repeat(15) {
            a.type(a.text!!.value.text + it)
            advanceUntilIdle()
        }
        val guest = connect(nina)
        invite(owner, guest, id, Role.READER)
        val late = guest.docs.acquire(id)
        advanceUntilIdle()
        assertEquals(a.text!!.value.text, late.text!!.value.text)
        assertTrue("part de l'instantané", late.text!!.revision >= 15)
    }

    @Test
    fun `un lecteur lit en direct mais ne modifie rien`() = runTest {
        val connect = world()
        val owner = connect(lea)
        val guest = connect(paul)
        val id = owner.docs.create("Plan", "Texte")
        invite(owner, guest, id, Role.READER)
        val a = owner.docs.acquire(id)
        val reader = guest.docs.acquire(id)
        advanceUntilIdle()
        assertFalse(reader.canEdit)
        assertFalse(reader.canComment)

        reader.type("Texte piraté")
        advanceUntilIdle()
        assertEquals("Texte", reader.text!!.value.text)
        assertNull(owner.store.read("docs/$id/history"))

        a.type("Texte final")
        advanceUntilIdle()
        assertEquals("Texte final", reader.text!!.value.text)
    }

    @Test
    fun `retrograde pendant qu'il tape hors ligne, il revient au texte enregistre et le sait`() = runTest {
        val connect = world()
        val owner = connect(lea)
        val guest = connect(paul)
        val id = owner.docs.create("Plan", "Texte")
        invite(owner, guest, id, Role.EDITOR)
        val editor = guest.docs.acquire(id)
        val a = owner.docs.acquire(id)
        advanceUntilIdle()

        guest.store.connected = false
        editor.type("Texte modifié hors ligne")
        advanceUntilIdle()
        a.setRole(a.members.single { it.uid == "paul" }, Role.READER)
        advanceUntilIdle()
        guest.store.connected = true
        advanceUntilIdle()

        assertEquals(Role.READER, editor.role)
        assertEquals("Texte", editor.text!!.value.text)
        assertNotNull(editor.notice)
        assertEquals("Texte", a.text!!.value.text)
    }

    @Test
    fun `retire du document, on est prevenu et il quitte la liste`() = runTest {
        val connect = world()
        val owner = connect(lea)
        val guest = connect(paul)
        val id = owner.docs.create("Plan", "")
        invite(owner, guest, id, Role.EDITOR)
        val list = latest(guest.docs.documents(paul))
        val mine = guest.docs.acquire(id)
        val a = owner.docs.acquire(id)
        advanceUntilIdle()
        assertEquals(1, list()!!.size)

        a.remove(a.members.single { it.uid == "paul" })
        advanceUntilIdle()
        assertEquals(DocSession.State.Closed("Tu n'as plus accès à ce document."), mine.state)
        assertTrue(list()!!.isEmpty())
    }

    @Test
    fun `supprime par le proprietaire, le document disparait pour tous`() = runTest {
        val connect = world()
        val owner = connect(lea)
        val guest = connect(paul)
        val id = owner.docs.create("Plan", "")
        invite(owner, guest, id, Role.EDITOR)
        val guestList = latest(guest.docs.documents(paul))
        val ownerList = latest(owner.docs.documents(lea))
        val theirs = guest.docs.acquire(id)
        val a = owner.docs.acquire(id)
        advanceUntilIdle()

        a.delete()
        advanceUntilIdle()
        assertTrue(theirs.state is DocSession.State.Closed)
        assertTrue(guestList()!!.isEmpty())
        assertTrue(ownerList()!!.isEmpty())
        assertNull(owner.store.read("userDocs/lea"))
        assertNull(guest.store.read("userDocs/paul"))
    }

    @Test
    fun `quitter un document partage`() = runTest {
        val connect = world()
        val owner = connect(lea)
        val guest = connect(paul)
        val id = owner.docs.create("Plan", "")
        invite(owner, guest, id, Role.COMMENTER)
        val list = latest(guest.docs.documents(paul))
        val mine = guest.docs.acquire(id)
        advanceUntilIdle()
        mine.leave()
        advanceUntilIdle()
        assertTrue(list()!!.isEmpty())
        val a = owner.docs.acquire(id)
        advanceUntilIdle()
        assertEquals(listOf("lea"), a.members.map { it.uid })
        try {
            a.leave()
            fail()
        } catch (e: CollabException) {
            assertTrue(e.message!!.startsWith("Le propriétaire ne peut pas quitter"))
        }
    }

    @Test
    fun `commenter, regler, supprimer`() = runTest {
        val connect = world()
        val owner = connect(lea)
        val guest = connect(paul)
        val id = owner.docs.create("Plan", "Bonjour à tous")
        invite(owner, guest, id, Role.COMMENTER)
        val commenter = guest.docs.acquire(id)
        val a = owner.docs.acquire(id)
        advanceUntilIdle()

        commenter.comment("Ajouter la date ?", quote = "Bonjour")
        advanceUntilIdle()
        val comment = a.comments.single()
        assertEquals("Paul", comment.name)
        assertEquals("Bonjour", comment.quote)
        assertFalse(comment.resolved)

        a.resolve(comment, true)
        advanceUntilIdle()
        assertTrue(commenter.comments.single().resolved)
        a.delete(commenter.comments.single())
        advanceUntilIdle()
        assertTrue(commenter.comments.isEmpty())

        try {
            commenter.comment("   ", null)
            fail()
        } catch (e: CollabException) {
            assertEquals("Le commentaire est vide.", e.message)
        }
    }

    @Test
    fun `on voit qui d'autre a le document ouvert, et son curseur`() = runTest {
        val connect = world()
        val owner = connect(lea)
        val guest = connect(paul)
        val id = owner.docs.create("Plan", "Bonjour à tous")
        invite(owner, guest, id, Role.EDITOR)
        val a = owner.docs.acquire(id)
        val b = guest.docs.acquire(id)
        advanceUntilIdle()

        b.shown(b.text!!.value)
        b.onValueChange(TextFieldValue("Bonjour à tous", TextRange(3, 7)))
        advanceTimeBy(200)
        runCurrent()
        val paulThere = a.others.single()
        assertEquals("Paul", paulThere.name)
        assertEquals(3 to 7, paulThere.anchor to paulThere.caret)
        assertEquals(com.docssuite.collab.live.RemoteCursor(3, 7), a.text!!.cursors[paulThere.clientId])
        assertEquals(listOf("Léa Martin"), b.others.map { it.name })

        // Paul ferme le document : il disparaît.
        guest.docs.release(b)
        advanceUntilIdle()
        assertTrue(a.others.isEmpty())
        assertTrue(a.text!!.cursors.isEmpty())
    }

    @Test
    fun `une coupure efface la presence, le retour la remet`() = runTest {
        val connect = world()
        val owner = connect(lea)
        val guest = connect(paul)
        val id = owner.docs.create("Plan", "")
        invite(owner, guest, id, Role.EDITOR)
        val a = owner.docs.acquire(id)
        val b = guest.docs.acquire(id)
        advanceUntilIdle()
        assertEquals(1, a.others.size)
        guest.store.connected = false
        advanceUntilIdle()
        assertTrue(a.others.isEmpty())
        assertFalse(b.connected)
        guest.store.connected = true
        advanceUntilIdle()
        assertEquals(1, a.others.size)
        assertTrue(b.connected)
    }

    @Test
    fun `renommer met a jour les invitations en attente`() = runTest {
        val connect = world()
        val owner = connect(lea)
        val guest = connect(paul)
        val id = owner.docs.create("Brouillon", "")
        val a = owner.docs.acquire(id)
        advanceUntilIdle()
        a.invite(paul.email, Role.READER)
        advanceUntilIdle()
        a.rename("  Version finale  ")
        advanceUntilIdle()
        assertEquals("Version finale", a.meta!!.title)
        val invitations = latest(guest.docs.invitations(paul))
        advanceUntilIdle()
        assertEquals("Version finale", invitations()!!.single().title)
    }

    @Test
    fun `changer le role d'une invitation, l'annuler, la renvoyer`() = runTest {
        val connect = world()
        val owner = connect(lea)
        val guest = connect(paul)
        val id = owner.docs.create("Brouillon", "")
        val a = owner.docs.acquire(id)
        advanceUntilIdle()
        a.invite(paul.email, Role.READER)
        advanceUntilIdle()
        a.setRole(a.invites.single(), Role.EDITOR)
        advanceUntilIdle()
        val invitations = latest(guest.docs.invitations(paul))
        advanceUntilIdle()
        assertEquals(Role.EDITOR, invitations()!!.single().role)
        assertEquals(Role.EDITOR, a.invites.single().role)

        a.resend(a.invites.single())
        advanceUntilIdle()
        assertEquals(Role.EDITOR, a.invites.single().role)

        a.cancel(a.invites.single())
        advanceUntilIdle()
        assertTrue(a.invites.isEmpty())
        assertTrue(invitations()!!.isEmpty())
    }

    @Test
    fun `un document garde sa session le temps de tourner l'ecran`() = runTest {
        val server = MemoryServer()
        val accounts = FakeAccounts(signedIn = lea)
        val docs = SharedDocs(accounts, server.device("lea"), null, this, heartbeatMillis = 0, keepOpenMillis = 1_000)
        val id = docs.create("Plan", "")
        val first = docs.acquire(id)
        advanceUntilIdle()
        docs.release(first)
        advanceTimeBy(500)
        val again = docs.acquire(id)
        assertTrue(first === again)
        docs.release(again)
        advanceTimeBy(2_000)
        runCurrent()
        val fresh = docs.acquire(id)
        assertFalse(first === fresh)
        assertNull(docs.link(id))
    }
}
