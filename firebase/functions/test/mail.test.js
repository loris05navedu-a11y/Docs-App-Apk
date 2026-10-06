'use strict';

const { test } = require('node:test');
const assert = require('node:assert/strict');
const { invitationEmail, displayName } = require('../mail');

const base = {
  inviterName: 'Léa Martin',
  inviterEmail: 'lea.martin@gmail.com',
  recipient: 'jean.dupont@exemple.fr',
  title: 'Compte rendu du 6 octobre',
  role: 'editor',
  link: 'https://doc-app-suite.web.app/d/-NxAbC123',
};

test('le sujet dit qui invite, à quoi faire, et quel document', () => {
  assert.equal(invitationEmail(base).subject, 'Léa Martin vous invite à modifier « Compte rendu du 6 octobre »');
  assert.equal(invitationEmail({ ...base, role: 'commenter' }).subject, 'Léa Martin vous invite à commenter « Compte rendu du 6 octobre »');
  assert.equal(invitationEmail({ ...base, role: 'reader' }).subject, 'Léa Martin vous invite à consulter « Compte rendu du 6 octobre »');
});

test("l'expéditeur affiché est la personne, via l'application", () => {
  const mail = invitationEmail(base);
  assert.equal(mail.fromName, 'Léa Martin (via DocsApp Suite)');
  assert.equal(mail.inviter, 'Léa Martin');
});

test('le texte dit tout : qui, quoi, le rôle, le lien, comment ouvrir, pourquoi', () => {
  const { text } = invitationEmail(base);
  for (const expected of [
    'Léa Martin (lea.martin@gmail.com) vous invite à modifier',
    '« Compte rendu du 6 octobre »',
    'Votre rôle : Éditeur.',
    'https://doc-app-suite.web.app/d/-NxAbC123',
    'Connectez-vous avec cette adresse : jean.dupont@exemple.fr',
    'ouvrez « Édition partagée » : l’invitation vous y attend, touchez « Accepter »',
    'répondez simplement à cet e-mail',
    'Vous pouvez ignorer ce message',
  ]) {
    assert.ok(text.includes(expected), `il manque « ${expected} »`);
  }
});

test('chaque rôle explique ce qu’il permet', () => {
  assert.match(invitationEmail({ ...base, role: 'editor' }).text, /modifier le texte, en direct/);
  assert.match(invitationEmail({ ...base, role: 'commenter' }).text, /ajouter des commentaires, sans changer le texte/);
  assert.match(invitationEmail({ ...base, role: 'reader' }).text, /lire le document et suivre ses modifications en direct, sans le changer/);
  // Un rôle inconnu ne promet jamais plus que la lecture.
  assert.match(invitationEmail({ ...base, role: 'owner' }).subject, /consulter/);
});

test("sans lien de téléchargement, on propose de demander l'application en répondant", () => {
  const without = invitationEmail(base);
  assert.match(without.text, /Pour recevoir le fichier d’installation, répondez simplement à cet e-mail/);
  const withLink = invitationEmail({ ...base, downloadUrl: 'https://exemple.fr/DocsApp.apk' });
  assert.match(withLink.text, /https:\/\/exemple\.fr\/DocsApp\.apk/);
  assert.match(withLink.html, /href="https:\/\/exemple\.fr\/DocsApp\.apk"/);
});

test("le HTML n'a ni image, ni script, ni ressource extérieure, ni lien inattendu", () => {
  const { html } = invitationEmail(base);
  assert.doesNotMatch(html, /<img|<script|<link|<iframe|url\(|@import/i);
  const links = [...html.matchAll(/href="([^"]*)"/g)].map((m) => m[1]);
  assert.deepEqual([...new Set(links)].sort(), ['https://doc-app-suite.web.app/d/-NxAbC123', 'mailto:lea.martin@gmail.com']);
  assert.match(html, /<html lang="fr">/);
  assert.match(html, /<meta charset="utf-8">/);
});

test('ce que les gens écrivent est échappé, jamais interprété', () => {
  const mail = invitationEmail({
    ...base,
    inviterName: '<b>Léa</b> & "Cie"',
    title: '<script>alert(1)</script> Q&A',
  });
  assert.doesNotMatch(mail.html, /<script>|<b>Léa/);
  assert.match(mail.html, /&lt;script&gt;alert\(1\)&lt;\/script&gt; Q&amp;A/);
  assert.match(mail.html, /&lt;b&gt;Léa&lt;\/b&gt; &amp; &quot;Cie&quot;/);
});

test("aucun retour à la ligne ne peut se glisser dans le sujet ou l'expéditeur", () => {
  const mail = invitationEmail({
    ...base,
    inviterName: 'Léa\r\nX-Priority: 1',
    title: 'Titre\nX-Spam: oui',
  });
  assert.doesNotMatch(mail.subject, /[\r\n]/);
  assert.doesNotMatch(mail.fromName, /[\r\n]/);
  assert.equal(mail.subject, 'Léa X-Priority: 1 vous invite à modifier « Titre X-Spam: oui »');
  // Un nom qui cache une adresse est remplacé par le début de la vraie.
  const hidden = invitationEmail({ ...base, inviterName: 'Léa\r\nBcc: victime@exemple.fr' });
  assert.equal(hidden.fromName, 'lea.martin (via DocsApp Suite)');
});

test("le nom affiché n'est jamais une adresse e-mail", () => {
  assert.equal(displayName('', 'lea.martin@gmail.com'), 'lea.martin');
  assert.equal(displayName('paypal@securite.com', 'lea.martin@gmail.com'), 'lea.martin');
  assert.equal(displayName('  Léa   Martin ', 'x@y.fr'), 'Léa Martin');
});

test('les titres et les noms trop longs sont raccourcis proprement', () => {
  const mail = invitationEmail({ ...base, inviterName: 'N'.repeat(200), title: 'Très long titre '.repeat(20) });
  assert.ok(mail.fromName.length < 90);
  assert.ok(mail.subject.length < 170);
  assert.match(mail.subject, /… »$/);
  // Un emoji n'est jamais coupé en deux.
  const emoji = invitationEmail({ ...base, title: '😀'.repeat(100) });
  assert.doesNotMatch(emoji.subject, /[\uD800-\uDBFF](?![\uDC00-\uDFFF])/);
});

test('sans titre, le document a quand même un nom', () => {
  assert.match(invitationEmail({ ...base, title: '   ' }).subject, /« Document sans titre »/);
});
