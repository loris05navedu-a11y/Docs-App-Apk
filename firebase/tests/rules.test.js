// Les règles de la base, essayées contre l'émulateur : chaque écriture que
// l'application fait doit passer, et chaque abus doit être refusé.
//
//   cd firebase/tests && npm install && npm test
import { test, before, after, beforeEach, describe } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { initializeTestEnvironment, assertFails, assertSucceeds } from '@firebase/rules-unit-testing';

const TIMESTAMP = { '.sv': 'timestamp' };
const DOC = 'doc1';

const people = {
  owner: { uid: 'owner', email: 'lea.martin@gmail.com', name: 'Léa Martin' },
  editor: { uid: 'editor', email: 'paul@exemple.fr', name: 'Paul' },
  commenter: { uid: 'commenter', email: 'nina@exemple.fr', name: 'Nina' },
  reader: { uid: 'reader', email: 'omar@exemple.fr', name: 'Omar' },
  // Invitée, pas encore membre : plusieurs points dans l'adresse.
  guest: { uid: 'guest', email: 'Jean.Paul.Dupont@mail.exemple.fr', name: 'Jean-Paul' },
  stranger: { uid: 'stranger', email: 'intrus@exemple.fr', name: 'Intrus' },
};

const key = (email) => email.toLowerCase().replaceAll('.', ',');

let env;

before(async () => {
  env = await initializeTestEnvironment({
    projectId: 'demo-docsapp',
    database: {
      rules: readFileSync(new URL('../database.rules.json', import.meta.url), 'utf8'),
      host: '127.0.0.1',
      port: 9000,
    },
  });
});

after(async () => {
  await env.cleanup();
});

beforeEach(async () => {
  await env.clearDatabase();
});

function as(person, { verified = true } = {}) {
  return env
    .authenticatedContext(person.uid, { email: person.email, email_verified: verified, name: person.name })
    .database();
}

async function seed(value) {
  await env.withSecurityRulesDisabled((context) => context.database().ref().set(value));
}

async function read(path) {
  let value;
  await env.withSecurityRulesDisabled(async (context) => {
    value = (await context.database().ref(path).once('value')).val();
  });
  return value;
}

function member(person, role) {
  return { role, name: person.name, email: person.email, at: 1 };
}

/** Un document avec ses quatre rôles, et une invitation en attente pour `guest`. */
function sharedDoc({ guestRole = 'editor' } = {}) {
  return {
    docs: {
      [DOC]: {
        meta: { title: 'Compte rendu', ownerUid: 'owner', ownerName: 'Léa Martin', ownerEmail: people.owner.email, createdAt: 1 },
        members: {
          owner: member(people.owner, 'owner'),
          editor: member(people.editor, 'editor'),
          commenter: member(people.commenter, 'commenter'),
          reader: member(people.reader, 'reader'),
        },
        invites: {
          [key(people.guest.email)]: { email: people.guest.email, role: guestRole, at: 1, by: 'owner', byName: 'Léa Martin' },
        },
        checkpoint: { n: 0, text: 'Bonjour' },
      },
    },
    userDocs: { owner: { [DOC]: true }, editor: { [DOC]: true }, commenter: { [DOC]: true }, reader: { [DOC]: true } },
    invitesByEmail: {
      [key(people.guest.email)]: { [DOC]: { title: 'Compte rendu', role: guestRole, byName: 'Léa Martin', at: 1 } },
    },
  };
}

function creation(person, id = 'nouveau') {
  return {
    [`docs/${id}/meta`]: { title: 'Mon document', ownerUid: person.uid, ownerName: person.name, ownerEmail: person.email, createdAt: TIMESTAMP },
    [`docs/${id}/members/${person.uid}`]: { role: 'owner', name: person.name, email: person.email, at: TIMESTAMP },
    [`docs/${id}/checkpoint`]: { n: 0, text: 'Premier jet' },
    [`userDocs/${person.uid}/${id}`]: true,
  };
}

function acceptance(person, role) {
  return {
    [`docs/${DOC}/members/${person.uid}`]: { role, name: person.name, email: person.email.toLowerCase(), at: TIMESTAMP },
    [`docs/${DOC}/invites/${key(person.email)}`]: null,
    [`invitesByEmail/${key(person.email)}/${DOC}`]: null,
    [`userDocs/${person.uid}/${DOC}`]: true,
  };
}

function revision(person, clientId = `${person.uid}-tel`) {
  return { c: clientId, a: person.uid, o: JSON.stringify([7, ' à tous']), t: TIMESTAMP };
}

describe('créer un document', () => {
  test('une adresse vérifiée crée un document dont elle est propriétaire', async () => {
    await assertSucceeds(as(people.owner).ref().update(creation(people.owner)));
    assert.equal((await read('docs/nouveau/members/owner/role')), 'owner');
  });

  test("une adresse pas encore vérifiée ne peut rien créer", async () => {
    await assertFails(as(people.owner, { verified: false }).ref().update(creation(people.owner)));
  });

  test("personne ne crée un document au nom d'un autre", async () => {
    const forged = creation(people.owner);
    await assertFails(as(people.stranger).ref().update(forged));
  });

  test('on ne prend pas la place d’un document existant', async () => {
    await seed(sharedDoc());
    await assertFails(as(people.stranger).ref().update(creation(people.stranger, DOC)));
  });

  test('pas sans connexion', async () => {
    await assertFails(env.unauthenticatedContext().database().ref().update(creation(people.owner)));
  });
});

describe('lire', () => {
  test('chaque membre lit le document, pas les autres', async () => {
    await seed(sharedDoc());
    for (const person of [people.owner, people.editor, people.commenter, people.reader]) {
      await assertSucceeds(as(person).ref(`docs/${DOC}/meta`).once('value'));
      await assertSucceeds(as(person).ref(`docs/${DOC}/history`).orderByKey().startAt('r000000000').once('value'));
    }
    await assertFails(as(people.stranger).ref(`docs/${DOC}/meta`).once('value'));
    await assertFails(as(people.guest).ref(`docs/${DOC}/checkpoint`).once('value'));
    await assertFails(env.unauthenticatedContext().database().ref(`docs/${DOC}`).once('value'));
  });

  test('sa liste de documents ne se lit que par soi', async () => {
    await seed(sharedDoc());
    await assertSucceeds(as(people.editor).ref('userDocs/editor').once('value'));
    await assertFails(as(people.reader).ref('userDocs/editor').once('value'));
  });

  test('ses invitations se lisent avec son adresse vérifiée seulement', async () => {
    await seed(sharedDoc());
    const path = `invitesByEmail/${key(people.guest.email)}`;
    await assertSucceeds(as(people.guest).ref(path).once('value'));
    await assertFails(as(people.guest, { verified: false }).ref(path).once('value'));
    await assertFails(as(people.stranger).ref(path).once('value'));
  });
});

describe('inviter', () => {
  function invitation(email, role, by = people.owner) {
    return {
      [`docs/${DOC}/invites/${key(email)}`]: { email, role, at: TIMESTAMP, by: by.uid, byName: by.name },
      [`invitesByEmail/${key(email)}/${DOC}`]: { title: 'Compte rendu', role, byName: by.name, at: TIMESTAMP },
    };
  }

  test('le propriétaire invite comme éditeur, commentateur ou lecteur', async () => {
    await seed(sharedDoc());
    for (const [i, role] of ['editor', 'commenter', 'reader'].entries()) {
      await assertSucceeds(as(people.owner).ref().update(invitation(`ami.${i}@exemple.fr`, role)));
    }
  });

  test('personne d’autre ne peut inviter, même un éditeur', async () => {
    await seed(sharedDoc());
    for (const person of [people.editor, people.commenter, people.reader, people.stranger]) {
      await assertFails(as(person).ref().update(invitation('copain@exemple.fr', 'editor', person)));
      await assertFails(as(person).ref(`invitesByEmail/${key('copain@exemple.fr')}/${DOC}`)
        .set({ title: 'Compte rendu', role: 'editor', at: 1 }));
    }
  });

  test('on n’invite pas comme propriétaire, ni sous une fausse adresse', async () => {
    await seed(sharedDoc());
    await assertFails(as(people.owner).ref().update(invitation('ami@exemple.fr', 'owner')));
    await assertFails(as(people.owner).ref(`docs/${DOC}/invites/${key('autre@exemple.fr')}`)
      .set({ email: 'ami@exemple.fr', role: 'editor', at: 1, by: 'owner' }));
  });

  test("l'état de l'e-mail n'est écrit que par le serveur, et n'empêche pas de changer le rôle", async () => {
    await seed(sharedDoc());
    const invite = `docs/${DOC}/invites/${key(people.guest.email)}`;
    await assertFails(as(people.owner).ref(`${invite}/mail`).set({ state: 'sent' }));
    await env.withSecurityRulesDisabled((context) => context.database().ref(`${invite}/mail`).set({ state: 'sent', at: 2 }));
    await assertSucceeds(as(people.owner).ref().update({
      [`${invite}/role`]: 'reader',
      [`invitesByEmail/${key(people.guest.email)}/${DOC}/role`]: 'reader',
    }));
    assert.equal(await read(`${invite}/role`), 'reader');
  });

  test('le propriétaire annule une invitation', async () => {
    await seed(sharedDoc());
    await assertSucceeds(as(people.owner).ref().update({
      [`docs/${DOC}/invites/${key(people.guest.email)}`]: null,
      [`invitesByEmail/${key(people.guest.email)}/${DOC}`]: null,
    }));
  });
});

describe('accepter une invitation', () => {
  test('la personne invitée entre avec le rôle de son invitation', async () => {
    await seed(sharedDoc({ guestRole: 'commenter' }));
    await assertSucceeds(as(people.guest).ref().update(acceptance(people.guest, 'commenter')));
    assert.equal(await read(`docs/${DOC}/members/guest/role`), 'commenter');
    assert.equal(await read(`docs/${DOC}/invites/${key(people.guest.email)}`), null);
    await assertSucceeds(as(people.guest).ref(`docs/${DOC}/checkpoint`).once('value'));
  });

  test('pas avec un rôle plus élevé que celui donné', async () => {
    await seed(sharedDoc({ guestRole: 'reader' }));
    await assertFails(as(people.guest).ref().update(acceptance(people.guest, 'editor')));
    await assertFails(as(people.guest).ref().update(acceptance(people.guest, 'owner')));
  });

  test("l'invitation ne sert qu'une fois", async () => {
    await seed(sharedDoc({ guestRole: 'editor' }));
    const keep = acceptance(people.guest, 'editor');
    delete keep[`docs/${DOC}/invites/${key(people.guest.email)}`];
    await assertFails(as(people.guest).ref().update(keep));

    await assertSucceeds(as(people.guest).ref().update(acceptance(people.guest, 'editor')));
    // Rétrogradé par la propriétaire, il ne peut pas reprendre son ancien rôle.
    await assertSucceeds(as(people.owner).ref(`docs/${DOC}/members/guest/role`).set('reader'));
    await assertFails(as(people.guest).ref(`docs/${DOC}/members/guest/role`).set('editor'));
  });

  test('il faut avoir vérifié son adresse', async () => {
    await seed(sharedDoc());
    await assertFails(as(people.guest, { verified: false }).ref().update(acceptance(people.guest, 'editor')));
  });

  test("on n'accepte pas l'invitation d'un autre", async () => {
    await seed(sharedDoc());
    const theft = {
      [`docs/${DOC}/members/stranger`]: member(people.stranger, 'editor'),
      [`docs/${DOC}/invites/${key(people.guest.email)}`]: null,
    };
    await assertFails(as(people.stranger).ref().update(theft));
    await assertFails(as(people.stranger).ref(`docs/${DOC}/members/stranger`).set(member(people.stranger, 'reader')));
  });

  test('refuser efface l’invitation, seulement pour la personne invitée et vérifiée', async () => {
    await seed(sharedDoc());
    const refusal = {
      [`docs/${DOC}/invites/${key(people.guest.email)}`]: null,
      [`invitesByEmail/${key(people.guest.email)}/${DOC}`]: null,
    };
    await assertFails(as(people.stranger).ref().update(refusal));
    await assertFails(as(people.guest, { verified: false }).ref().update(refusal));
    await assertSucceeds(as(people.guest).ref().update(refusal));
  });
});

describe('rôles', () => {
  test('seul le propriétaire change les rôles', async () => {
    await seed(sharedDoc());
    await assertSucceeds(as(people.owner).ref(`docs/${DOC}/members/reader/role`).set('editor'));
    await assertSucceeds(as(people.owner).ref(`docs/${DOC}/members/editor/role`).set('commenter'));
    await assertFails(as(people.editor).ref(`docs/${DOC}/members/reader/role`).set('editor'));
    await assertFails(as(people.reader).ref(`docs/${DOC}/members/reader/role`).set('editor'));
    await assertFails(as(people.commenter).ref(`docs/${DOC}/members/commenter/role`).set('editor'));
  });

  test("un seul propriétaire, qui le reste", async () => {
    await seed(sharedDoc());
    await assertFails(as(people.owner).ref(`docs/${DOC}/members/editor/role`).set('owner'));
    await assertFails(as(people.owner).ref(`docs/${DOC}/members/owner/role`).set('editor'));
    await assertFails(as(people.owner).ref(`docs/${DOC}/members/owner`).remove());
    await assertFails(as(people.owner).ref(`docs/${DOC}/meta/ownerUid`).set('editor'));
    await assertFails(as(people.editor).ref().update({
      [`docs/${DOC}/meta/ownerUid`]: 'editor',
      [`docs/${DOC}/members/editor/role`]: 'owner',
    }));
  });

  test('le propriétaire retire quelqu’un, et sa liste avec', async () => {
    await seed(sharedDoc());
    await assertSucceeds(as(people.owner).ref().update({
      [`docs/${DOC}/members/reader`]: null,
      [`userDocs/reader/${DOC}`]: null,
    }));
    await assertFails(as(people.reader).ref(`docs/${DOC}/meta`).once('value'));
  });

  test('un membre ne retire personne, mais peut partir', async () => {
    await seed(sharedDoc());
    await assertFails(as(people.editor).ref(`docs/${DOC}/members/reader`).remove());
    await assertFails(as(people.editor).ref(`userDocs/reader/${DOC}`).remove());
    await assertSucceeds(as(people.reader).ref().update({
      [`docs/${DOC}/members/reader`]: null,
      [`userDocs/reader/${DOC}`]: null,
    }));
  });

  test('renommer : le propriétaire seulement', async () => {
    await seed(sharedDoc());
    await assertSucceeds(as(people.owner).ref(`docs/${DOC}/meta/title`).set('Compte rendu du 6 octobre'));
    await assertFails(as(people.owner).ref(`docs/${DOC}/meta/title`).set(''));
    await assertFails(as(people.owner).ref(`docs/${DOC}/meta/title`).set('x'.repeat(121)));
    await assertFails(as(people.editor).ref(`docs/${DOC}/meta/title`).set('Piraté'));
  });

  test('supprimer le document : le propriétaire seulement', async () => {
    await seed(sharedDoc());
    const removal = {
      [`docs/${DOC}`]: null,
      [`userDocs/owner/${DOC}`]: null,
      [`userDocs/editor/${DOC}`]: null,
      [`userDocs/commenter/${DOC}`]: null,
      [`userDocs/reader/${DOC}`]: null,
      [`invitesByEmail/${key(people.guest.email)}/${DOC}`]: null,
    };
    await assertFails(as(people.editor).ref().update(removal));
    await assertSucceeds(as(people.owner).ref().update(removal));
    assert.equal(await read(`docs/${DOC}`), null);
  });
});

describe('modifier le texte', () => {
  test('propriétaire et éditeur ajoutent au journal', async () => {
    await seed(sharedDoc());
    await assertSucceeds(as(people.owner).ref(`docs/${DOC}/history/r000000000`).set(revision(people.owner)));
    await assertSucceeds(as(people.editor).ref(`docs/${DOC}/history/r000000001`).set(revision(people.editor)));
  });

  test('commentateur et lecteur ne modifient pas le texte', async () => {
    await seed(sharedDoc());
    for (const person of [people.commenter, people.reader, people.stranger]) {
      await assertFails(as(person).ref(`docs/${DOC}/history/r000000000`).set(revision(person)));
      await assertFails(as(person).ref(`docs/${DOC}/checkpoint`).set({ n: 5, text: 'Piraté' }));
    }
  });

  test('une place prise ne se réécrit pas, le passé ne s’efface pas', async () => {
    await seed(sharedDoc());
    const slot = `docs/${DOC}/history/r000000000`;
    await assertSucceeds(as(people.editor).ref(slot).set(revision(people.editor)));
    await assertFails(as(people.owner).ref(slot).set(revision(people.owner)));
    await assertFails(as(people.editor).ref(slot).set(revision(people.editor)));
    await assertFails(as(people.editor).ref(slot).remove());
    await assertFails(as(people.editor).ref(`docs/${DOC}/history`).remove());
  });

  test('une révision porte son auteur, l’heure du serveur et une place bien formée', async () => {
    await seed(sharedDoc());
    const history = `docs/${DOC}/history`;
    await assertFails(as(people.editor).ref(`${history}/r000000000`).set({ ...revision(people.editor), a: 'owner' }));
    await assertFails(as(people.editor).ref(`${history}/r000000000`).set({ ...revision(people.editor), t: 12 }));
    await assertFails(as(people.editor).ref(`${history}/r000000000`).set({ ...revision(people.editor), x: 1 }));
    await assertFails(as(people.editor).ref(`${history}/r000000000`).set({ ...revision(people.editor), o: { 0: 7 } }));
    // Une opération vide (un texte vide qui le reste) s'écrit aussi.
    await assertSucceeds(as(people.editor).ref(`${history}/r000000000`).set({ ...revision(people.editor), o: '[]' }));
    await assertFails(as(people.editor).ref(`${history}/0`).set(revision(people.editor)));
    await assertFails(as(people.editor).ref(`${history}/r12`).set(revision(people.editor)));
  });

  test('deux personnes visent la même place : la seconde est prévenue, sans erreur', async () => {
    await seed(sharedDoc());
    const slot = `docs/${DOC}/history/r000000000`;
    const first = await as(people.owner).ref(slot).transaction((current) => (current === null ? revision(people.owner) : undefined), undefined, false);
    assert.equal(first.committed, true);
    // L'éditeur n'a encore rien reçu : sa transaction part d'une place vide,
    // le serveur lui renvoie la valeur réelle et il abandonne.
    let attempts = 0;
    const second = await as(people.editor).ref(slot).transaction((current) => {
      attempts++;
      return current === null ? revision(people.editor) : undefined;
    }, undefined, false);
    assert.equal(second.committed, false);
    assert.equal(second.snapshot.val().a, 'owner');
    assert.ok(attempts >= 1);
    assert.equal((await read(slot)).a, 'owner');
  });

  test("l'instantané ne fait qu'avancer", async () => {
    await seed(sharedDoc());
    const checkpoint = `docs/${DOC}/checkpoint`;
    await assertSucceeds(as(people.editor).ref(checkpoint).set({ n: 100, text: 'Bonjour à tous' }));
    await assertFails(as(people.owner).ref(checkpoint).set({ n: 100, text: 'Bonjour' }));
    await assertFails(as(people.owner).ref(checkpoint).set({ n: 50, text: 'Bonjour' }));
    await assertFails(as(people.owner).ref(checkpoint).remove());
  });

  test("l'activité dit qui a modifié en dernier, à l'heure du serveur", async () => {
    await seed(sharedDoc());
    const activity = `docs/${DOC}/activity`;
    await assertSucceeds(as(people.editor).ref(activity).set({ at: TIMESTAMP, by: 'editor', name: 'Paul' }));
    await assertFails(as(people.editor).ref(activity).set({ at: TIMESTAMP, by: 'owner', name: 'Léa' }));
    await assertFails(as(people.reader).ref(activity).set({ at: TIMESTAMP, by: 'reader', name: 'Omar' }));
  });
});

describe('commentaires', () => {
  const comment = (person, text = 'Ajouter la date ?') => ({ a: person.uid, name: person.name, text, quote: 'Bonjour', at: TIMESTAMP });

  test('propriétaire, éditeur et commentateur commentent, pas le lecteur', async () => {
    await seed(sharedDoc());
    const comments = `docs/${DOC}/comments`;
    for (const person of [people.owner, people.editor, people.commenter]) {
      await assertSucceeds(as(person).ref(comments).push(comment(person)));
    }
    await assertFails(as(people.reader).ref(`${comments}/c1`).set(comment(people.reader)));
    await assertFails(as(people.commenter).ref(`${comments}/c2`).set(comment(people.editor)));
    await assertFails(as(people.commenter).ref(`${comments}/c3`).set(comment(people.commenter, '')));
  });

  test('régler un commentaire, sans pouvoir le réécrire', async () => {
    await seed(sharedDoc());
    const path = `docs/${DOC}/comments/c1`;
    await env.withSecurityRulesDisabled((context) => context.database().ref(path).set({ ...comment(people.commenter), at: 1 }));
    await assertFails(as(people.reader).ref(`${path}/resolved`).set(true));
    await assertFails(as(people.editor).ref(`${path}/text`).set('Autre chose'));
    await assertSucceeds(as(people.editor).ref(`${path}/resolved`).set(true));
    await assertSucceeds(as(people.commenter).ref(`${path}/resolved`).set(false));
  });

  test('supprimer : son auteur ou le propriétaire', async () => {
    await seed(sharedDoc());
    const comments = `docs/${DOC}/comments`;
    await env.withSecurityRulesDisabled((context) => context.database().ref(comments).set({
      c1: { ...comment(people.commenter), at: 1 },
      c2: { ...comment(people.commenter), at: 1 },
    }));
    await assertFails(as(people.editor).ref(`${comments}/c1`).remove());
    await assertSucceeds(as(people.commenter).ref(`${comments}/c1`).remove());
    await assertSucceeds(as(people.owner).ref(`${comments}/c2`).remove());
  });
});

describe('présence', () => {
  const here = (person) => ({ a: person.uid, name: person.name, color: 3, c: 4, s: 4, at: TIMESTAMP });

  test('chaque membre annonce sa présence et son curseur', async () => {
    await seed(sharedDoc());
    const presence = `docs/${DOC}/presence`;
    await assertSucceeds(as(people.reader).ref(`${presence}/reader-tel`).set(here(people.reader)));
    await assertSucceeds(as(people.reader).ref(`${presence}/reader-tel/c`).set(9));
    await assertSucceeds(as(people.reader).ref(`${presence}/reader-tel`).onDisconnect().remove());
    await assertFails(as(people.stranger).ref(`${presence}/stranger-tel`).set(here(people.stranger)));
  });

  test("on ne touche pas à la présence d'un autre", async () => {
    await seed(sharedDoc());
    const presence = `docs/${DOC}/presence`;
    await assertSucceeds(as(people.reader).ref(`${presence}/reader-tel`).set(here(people.reader)));
    await assertFails(as(people.editor).ref(`${presence}/reader-tel`).set(here(people.editor)));
    await assertFails(as(people.editor).ref(`${presence}/reader-tel`).remove());
    await assertFails(as(people.editor).ref(`${presence}/editor-tel`).set(here(people.reader)));
  });
});
