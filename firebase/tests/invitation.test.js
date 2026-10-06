// De bout en bout, dans les émulateurs : une invitation écrite dans la base
// déclenche la vraie fonction, qui envoie l'e-mail à un petit serveur de
// courrier local ; on lit ce qui arrive, comme le ferait une boîte mail.
//
//   cd firebase/tests && npm install && npm run test:server
import { test, before, after, describe } from 'node:test';
import assert from 'node:assert/strict';
import { SMTPServer } from 'smtp-server';
import { simpleParser } from 'mailparser';
import { initializeApp } from 'firebase-admin/app';
import { getAuth } from 'firebase-admin/auth';
import { getDatabase } from 'firebase-admin/database';

const PROJECT = 'demo-docsapp';
const SENDER = 'invitations@docsapp.test';
const HOSTING = 'http://127.0.0.1:5005';

const key = (email) => email.toLowerCase().replaceAll('.', ',');

/** Ce que le serveur de courrier a reçu. */
const inbox = [];
let smtp;
let db;

before(async () => {
  smtp = new SMTPServer({
    secure: false,
    authOptional: false,
    allowInsecureAuth: true,
    disabledCommands: ['STARTTLS'],
    logger: false,
    onAuth(auth, session, callback) {
      callback(null, { user: auth.username });
    },
    onData(stream, session, callback) {
      simpleParser(stream).then(
        (mail) => {
          inbox.push({ mail, user: session.user, rcpt: session.envelope.rcptTo.map((r) => r.address) });
          callback();
        },
        callback,
      );
    },
  });
  await new Promise((resolve) => smtp.listen(2525, '127.0.0.1', resolve));

  initializeApp({ projectId: PROJECT, databaseURL: `http://${process.env.FIREBASE_DATABASE_EMULATOR_HOST}?ns=${PROJECT}-default-rtdb` });
  db = getDatabase();
});

after(async () => {
  await new Promise((resolve) => smtp.close(resolve));
  await db.app.delete();
});

async function eventually(check, what, timeout = 30_000) {
  const end = Date.now() + timeout;
  for (;;) {
    const value = await check();
    if (value) return value;
    if (Date.now() > end) throw new Error(`Toujours pas : ${what}`);
    await new Promise((resolve) => setTimeout(resolve, 200));
  }
}

async function person(uid, email, name, verified = true) {
  await getAuth().createUser({ uid, email, emailVerified: verified, displayName: name });
}

async function documentOf(owner, id, title) {
  await db.ref(`docs/${id}`).set({
    meta: { title, ownerUid: owner, ownerName: 'ignoré', createdAt: 1 },
    members: { [owner]: { role: 'owner', name: 'x', email: 'x', at: 1 } },
    checkpoint: { n: 0, text: '' },
  });
}

async function invite(id, email, role, by) {
  await db.ref(`docs/${id}/invites/${key(email)}`).set({ email, role, at: Date.now(), by, byName: 'nom écrit par l’app' });
}

const mailState = async (id, email) => (await db.ref(`docs/${id}/invites/${key(email)}/mail/state`).get()).val();

const received = (to) => inbox.filter((entry) => entry.rcpt.includes(to));

describe("l'e-mail d'invitation", () => {
  test('part, bien formé, depuis le compte d’envoi, au nom de la personne qui invite', async () => {
    await person('lea', 'lea.martin@gmail.com', 'Léa Martin');
    await documentOf('lea', 'doc-1', 'Compte rendu du 6 octobre');
    await invite('doc-1', 'Jean.Dupont@exemple.fr', 'editor', 'lea');

    await eventually(async () => (await mailState('doc-1', 'Jean.Dupont@exemple.fr')) === 'sent', "l'état « envoyé »");
    const [{ mail, user }] = received('Jean.Dupont@exemple.fr');

    assert.equal(user, SENDER);
    assert.equal(mail.from.value[0].address, SENDER);
    assert.equal(mail.from.value[0].name, 'Léa Martin (via DocsApp Suite)');
    assert.equal(mail.replyTo.value[0].address, 'lea.martin@gmail.com');
    assert.equal(mail.replyTo.value[0].name, 'Léa Martin');
    assert.equal(mail.to.value[0].address, 'Jean.Dupont@exemple.fr');
    assert.equal(mail.subject, 'Léa Martin vous invite à modifier « Compte rendu du 6 octobre »');
    assert.equal(mail.headers.get('auto-submitted'), 'auto-generated');
    assert.equal(mail.headers.get('content-language'), 'fr');
    assert.ok(mail.messageId, 'un identifiant de message');
    assert.ok(mail.date instanceof Date, 'une date');
    assert.equal(mail.attachments.length, 0);
    // Le lien mène au document, sur le site du projet.
    assert.ok(mail.text.includes(`https://${PROJECT}.web.app/d/doc-1`));
    assert.ok(mail.html.includes(`href="https://${PROJECT}.web.app/d/doc-1"`));
    assert.ok(mail.text.includes('Votre rôle : Éditeur.'));
  });

  test("n'est jamais envoyé deux fois pour la même invitation", async () => {
    await new Promise((resolve) => setTimeout(resolve, 3000));
    assert.equal(received('Jean.Dupont@exemple.fr').length, 1);
  });

  test("ne part pas si l'invitation ne vient pas du propriétaire", async () => {
    await person('paul', 'paul@exemple.fr', 'Paul');
    await documentOf('lea', 'doc-2', 'Budget');
    await invite('doc-2', 'cible@exemple.fr', 'editor', 'paul');
    await eventually(async () => (await mailState('doc-2', 'cible@exemple.fr')) === 'refused', "l'état « refusé »");
    assert.equal(received('cible@exemple.fr').length, 0);
  });

  test("ne part pas d'un compte à l'adresse non vérifiée", async () => {
    await person('nouveau', 'nouveau@exemple.fr', 'Nouveau', false);
    await documentOf('nouveau', 'doc-3', 'Brouillon');
    await invite('doc-3', 'ami@exemple.fr', 'reader', 'nouveau');
    await eventually(async () => (await mailState('doc-3', 'ami@exemple.fr')) === 'refused', "l'état « refusé »");
    assert.equal(received('ami@exemple.fr').length, 0);
  });

  test('respecte la limite du jour, puis le dit', async () => {
    // Trois e-mails par jour et par personne dans l'émulateur (functions/.env.demo-docsapp).
    await person('nina', 'nina@exemple.fr', 'Nina');
    await documentOf('nina', 'doc-4', 'Liste');
    const guests = ['a@exemple.fr', 'b@exemple.fr', 'c@exemple.fr', 'd@exemple.fr'];
    for (const guest of guests) await invite('doc-4', guest, 'commenter', 'nina');
    const states = await eventually(async () => {
      const all = await Promise.all(guests.map((guest) => mailState('doc-4', guest)));
      return all.every((state) => state && state !== 'sending') ? all : null;
    }, 'les quatre états');
    assert.deepEqual(states.filter((s) => s === 'sent').length, 3);
    assert.deepEqual(states.filter((s) => s === 'quota').length, 1);
    assert.equal(guests.flatMap(received).length, 3);
  });

  test("ne recrée pas une invitation annulée pendant l'envoi", async () => {
    await documentOf('lea', 'doc-5', 'Annulé');
    await invite('doc-5', 'vite@exemple.fr', 'reader', 'lea');
    await db.ref(`docs/doc-5/invites/${key('vite@exemple.fr')}`).remove();
    await new Promise((resolve) => setTimeout(resolve, 3000));
    assert.equal((await db.ref(`docs/doc-5/invites`).get()).val(), null);
  });
});

describe('le site des liens', () => {
  test('un lien de document ouvre la page qui lance l’application', async () => {
    const response = await fetch(`${HOSTING}/d/-NxAbC123`);
    assert.equal(response.status, 200);
    const html = await response.text();
    assert.ok(html.includes('Un document a été partagé avec vous'));
    assert.ok(html.includes("scheme=docssuite;package=com.docssuite"));
  });

  test("Android y trouve la preuve que les liens sont à l'application", async () => {
    const response = await fetch(`${HOSTING}/.well-known/assetlinks.json`);
    assert.equal(response.status, 200);
    assert.match(response.headers.get('content-type'), /^application\/json/);
    const [statement] = await response.json();
    assert.equal(statement.target.package_name, 'com.docssuite');
    assert.deepEqual(statement.relation, ['delegate_permission/common.handle_all_urls']);
    assert.match(statement.target.sha256_cert_fingerprints[0], /^([0-9A-F]{2}:){31}[0-9A-F]{2}$/);
  });

  test('une adresse inconnue répond « introuvable »', async () => {
    const response = await fetch(`${HOSTING}/rien/ici`);
    assert.equal(response.status, 404);
    assert.ok((await response.text()).includes('Page introuvable'));
  });
});
