'use strict';

// Le serveur de DocsApp Suite : une seule fonction, qui envoie l'e-mail
// d'invitation quand la ou le propriétaire d'un document invite quelqu'un.
//
// Elle ne fait confiance qu'à ce qu'elle vérifie elle-même : l'invitation doit
// venir de la personne propriétaire du document, à l'adresse vérifiée, dans la
// limite d'envois du jour. Elle écrit ensuite où en est l'e-mail dans
// l'invitation (`mail.state`), que l'application affiche :
//
//   sending  en cours d'envoi
//   sent     envoyé
//   failed   le serveur d'envoi a refusé (mauvais mot de passe, adresse invalide…)
//   quota    trop d'invitations aujourd'hui pour cette personne
//   refused  invitation incohérente : rien n'est envoyé

const { onValueCreated } = require('firebase-functions/v2/database');
const { defineInt, defineSecret, defineString, projectID } = require('firebase-functions/params');
const logger = require('firebase-functions/logger');
const { initializeApp } = require('firebase-admin/app');
const { getAuth } = require('firebase-admin/auth');
const { ServerValue } = require('firebase-admin/database');
const nodemailer = require('nodemailer');
const { invitationEmail } = require('./mail');

initializeApp();

const SMTP_PASSWORD = defineSecret('SMTP_PASSWORD');
const SMTP_USER = defineString('SMTP_USER', {
  description: "L'adresse qui envoie les invitations (par exemple docsapp.invitations@gmail.com).",
});
const SMTP_HOST = defineString('SMTP_HOST', { default: 'smtp.gmail.com', description: "Le serveur d'envoi." });
const SMTP_PORT = defineInt('SMTP_PORT', { default: 465, description: 'Son port : 465 (SSL) ou 587 (STARTTLS).' });
const APP_URL = defineString('APP_URL', {
  default: '',
  description: "Le début des liens d'invitation. Vide : https://<projet>.web.app",
});
const APP_DOWNLOAD_URL = defineString('APP_DOWNLOAD_URL', {
  default: '',
  description: "Où télécharger l'application, montré dans l'e-mail. Vide : on propose de répondre pour la demander.",
});
const INVITES_PER_DAY = defineInt('INVITES_PER_DAY', {
  default: 30,
  description: "Nombre d'e-mails d'invitation qu'une personne peut faire partir par jour.",
});
// Une fonction déclenchée par la base doit vivre dans la même région qu'elle.
const DATABASE_REGION = defineString('DATABASE_REGION', {
  default: 'europe-west1',
  description: 'La région de la base temps réel : europe-west1, us-central1 ou asia-southeast1.',
});

const EMAIL = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

exports.sendInvitation = onValueCreated(
  {
    ref: '/docs/{docId}/invites/{emailKey}',
    region: DATABASE_REGION,
    secrets: [SMTP_PASSWORD],
    memory: '256MiB',
    timeoutSeconds: 60,
    maxInstances: 5,
  },
  async (event) => {
    const { docId } = event.params;
    // La base même qui a déclenché la fonction (pas forcément celle par défaut).
    const inviteRef = event.data.ref;
    const invite = await claim(inviteRef);
    if (!invite) return;

    let state;
    try {
      state = await deliver(inviteRef.root, docId, invite);
    } catch (error) {
      logger.error("L'e-mail d'invitation n'est pas parti", { docId, code: error.code, message: error.message });
      state = 'failed';
    }
    await markMail(inviteRef, state);
    logger.info("Invitation traitée", { docId, state });
  },
);

/**
 * Réserve l'invitation pour cet envoi et la renvoie telle qu'elle est à cet
 * instant. Rien si elle a déjà été traitée (un déclenchement en double ne fait
 * pas partir deux e-mails) ou annulée entre-temps.
 */
async function claim(inviteRef) {
  const result = await inviteRef.transaction((current) => {
    // Rien en mémoire au premier passage : on propose « rien », le serveur
    // refuse si l'invitation existe et on recommence avec sa vraie valeur.
    if (current === null) return null;
    if (current.mail) return undefined;
    return { ...current, mail: { state: 'sending', at: ServerValue.TIMESTAMP } };
  });
  return result.committed && result.snapshot.child('mail/state').val() === 'sending' ? result.snapshot.val() : null;
}

/** Écrit où en est l'e-mail, sans recréer une invitation annulée entre-temps. */
async function markMail(inviteRef, state) {
  await inviteRef.transaction((current) => {
    if (current === null) return null;
    return { ...current, mail: { state, at: ServerValue.TIMESTAMP } };
  });
}

async function deliver(root, docId, invite) {
  if (!invite || typeof invite.email !== 'string' || !EMAIL.test(invite.email)) return 'refused';

  const meta = (await root.child(`docs/${docId}/meta`).get()).val();
  if (!meta || meta.ownerUid !== invite.by) return 'refused';

  const inviter = await getAuth().getUser(invite.by);
  if (!inviter.email || !inviter.emailVerified) return 'refused';
  if (inviter.email.toLowerCase() === invite.email.toLowerCase()) return 'refused';

  if (!(await withinDailyLimit(root, inviter.uid))) return 'quota';

  const base = (APP_URL.value() || `https://${projectID.value()}.web.app`).replace(/\/+$/, '');
  const mail = invitationEmail({
    inviterName: inviter.displayName || invite.byName || meta.ownerName,
    inviterEmail: inviter.email,
    recipient: invite.email,
    title: meta.title,
    role: invite.role,
    link: `${base}/d/${encodeURIComponent(docId)}`,
    downloadUrl: APP_DOWNLOAD_URL.value(),
  });

  await transport().sendMail({
    from: { name: mail.fromName, address: SMTP_USER.value() },
    replyTo: { name: mail.inviter, address: inviter.email },
    to: invite.email,
    subject: mail.subject,
    text: mail.text,
    html: mail.html,
    headers: {
      'Content-Language': 'fr',
      // Un envoi automatique : pas de réponse automatique (absence, vacances) en retour.
      'Auto-Submitted': 'auto-generated',
    },
  });
  return 'sent';
}

/** Compte les envois de la personne pour aujourd'hui ; faux une fois la limite atteinte. */
async function withinDailyLimit(root, uid) {
  const day = new Date().toISOString().slice(0, 10);
  const limit = INVITES_PER_DAY.value();
  const result = await root.child(`mailQuota/${uid}`).transaction((current) => {
    const count = current && current.day === day ? current.count : 0;
    if (count >= limit) return undefined;
    return { day, count: count + 1 };
  });
  return result.committed;
}

let cachedTransport;

function transport() {
  if (!cachedTransport) {
    const host = SMTP_HOST.value();
    const port = SMTP_PORT.value();
    let pass = SMTP_PASSWORD.value();
    // Google affiche les mots de passe d'application par groupes de quatre lettres.
    if (host === 'smtp.gmail.com') pass = pass.replace(/\s+/g, '');
    cachedTransport = nodemailer.createTransport({
      host,
      port,
      secure: port === 465,
      auth: { user: SMTP_USER.value(), pass },
    });
  }
  return cachedTransport;
}
