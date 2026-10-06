'use strict';

// L'e-mail d'invitation, en texte et en HTML.
//
// Il est fait pour qu'on lui fasse confiance au premier coup d'œil : il dit
// qui invite (nom et adresse), à quel document, avec quel rôle, comment
// l'ouvrir, et pourquoi on le reçoit. Pas d'image, pas de traceur, pas de lien
// raccourci : un seul lien, vers le document, aussi écrit en clair. Un second,
// facultatif, pour installer l'application.

const APP_NAME = 'DocsApp Suite';
const BLUE = '#2563EB';

const ROLES = {
  editor: {
    verb: 'modifier',
    label: 'Éditeur',
    can: 'Vous pourrez modifier le texte, en direct avec les autres personnes, et ajouter des commentaires.',
  },
  commenter: {
    verb: 'commenter',
    label: 'Commentateur',
    can: 'Vous pourrez lire le document et y ajouter des commentaires, sans changer le texte.',
  },
  reader: {
    verb: 'consulter',
    label: 'Lecteur',
    can: 'Vous pourrez lire le document et suivre ses modifications en direct, sans le changer.',
  },
};

/** Une ligne propre : sans retour à la ligne, sans caractère de contrôle, sans espaces en trop. */
function oneLine(value) {
  return String(value ?? '')
    .replace(/[\u0000-\u001F\u007F-\u009F\u2028\u2029]/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
}

function shorten(value, max) {
  const chars = Array.from(value);
  return chars.length <= max ? value : `${chars.slice(0, max - 1).join('').trimEnd()}…`;
}

function escapeHtml(value) {
  return String(value)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

/**
 * Le nom à afficher pour la personne qui invite. Jamais une adresse e-mail :
 * un nom d'expéditeur qui ressemble à une autre adresse est un signe de
 * fraude pour les filtres (et pour les gens).
 */
function displayName(name, email) {
  const clean = oneLine(name);
  if (clean && !clean.includes('@')) return shorten(clean, 60);
  const local = oneLine(email).split('@')[0];
  return shorten(local || 'Quelqu’un', 60);
}

function roleOf(role) {
  return ROLES[role] ?? ROLES.reader;
}

/**
 * @param {object} invitation
 * @param {string} invitation.inviterName  nom de la personne qui invite
 * @param {string} invitation.inviterEmail son adresse (vérifiée), en réponse
 * @param {string} invitation.recipient    l'adresse invitée
 * @param {string} invitation.title        le titre du document
 * @param {string} invitation.role         editor, commenter ou reader
 * @param {string} invitation.link         le lien du document
 * @param {string} [invitation.downloadUrl] où télécharger l'application
 * @returns {{subject: string, fromName: string, inviter: string, text: string, html: string}}
 */
function invitationEmail({ inviterName, inviterEmail, recipient, title, role, link, downloadUrl }) {
  const who = displayName(inviterName, inviterEmail);
  const from = oneLine(inviterEmail);
  const to = oneLine(recipient);
  const doc = shorten(oneLine(title) || 'Document sans titre', 120);
  const r = roleOf(role);
  const download = oneLine(downloadUrl);

  const subject = `${who} vous invite à ${r.verb} « ${shorten(doc, 80)} »`;
  const fromName = `${who} (via ${APP_NAME})`;

  const install = download
    ? `Installez ${APP_NAME} sur votre téléphone Android, si ce n’est pas déjà fait : ${download}`
    : `Installez ${APP_NAME} sur votre téléphone Android, si ce n’est pas déjà fait. Pour recevoir le fichier d’installation, répondez simplement à cet e-mail : votre réponse arrive chez ${who}.`;

  const text = [
    'Bonjour,',
    '',
    `${who} (${from}) vous invite à ${r.verb} un document dans l’application ${APP_NAME} :`,
    '',
    `    « ${doc} »`,
    `    Votre rôle : ${r.label}. ${r.can}`,
    '',
    'Ouvrir le document :',
    link,
    '',
    'Pour l’ouvrir sur votre téléphone :',
    `1. ${install}`,
    `2. Connectez-vous avec cette adresse : ${to} (avec un mot de passe, ou avec « Continuer avec Google »).`,
    '3. Le document vous attend dans « Partagés avec moi » : touchez « Accepter ».',
    '',
    ...(download ? [`Pour écrire à ${who}, répondez simplement à cet e-mail.`, ''] : []),
    'Vous ne vous attendiez pas à cette invitation ? Vous pouvez ignorer ce message : sans action de votre part, rien ne se passe. Ce n’est pas une inscription à une liste de diffusion.',
    '',
    '—',
    `Cet e-mail a été envoyé à ${to} par ${APP_NAME}, à la demande de ${who}.`,
    '',
  ].join('\n');

  const h = escapeHtml;
  const linkHtml = h(link);
  const installHtml = download
    ? `Installez ${APP_NAME} sur votre téléphone Android, si ce n’est pas déjà fait : <a href="${h(download)}" style="color:${BLUE};">télécharger l’application</a>.`
    : `Installez ${APP_NAME} sur votre téléphone Android, si ce n’est pas déjà fait. Pour recevoir le fichier d’installation, répondez simplement à cet e-mail&nbsp;: votre réponse arrive chez ${h(who)}.`;

  const html = `<!DOCTYPE html>
<html lang="fr">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="color-scheme" content="light dark">
<meta name="supported-color-schemes" content="light dark">
<title>${h(subject)}</title>
</head>
<body style="margin:0;padding:0;background:#F1F5F9;">
<div style="display:none;max-height:0;overflow:hidden;">${h(doc)} · rôle : ${h(r.label.toLowerCase())} · invitation de ${h(who)}</div>
<table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" style="background:#F1F5F9;">
<tr><td align="center" style="padding:24px 12px;">
<table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" style="max-width:560px;background:#FFFFFF;border-radius:12px;border:1px solid #E2E8F0;">
<tr><td style="padding:28px 28px 8px 28px;font-family:Roboto,'Segoe UI',Helvetica,Arial,sans-serif;font-size:13px;color:#64748B;letter-spacing:.3px;">${APP_NAME}</td></tr>
<tr><td style="padding:0 28px;font-family:Roboto,'Segoe UI',Helvetica,Arial,sans-serif;font-size:16px;line-height:24px;color:#0F172A;">
<p style="margin:12px 0;">Bonjour,</p>
<p style="margin:12px 0;"><strong>${h(who)}</strong> (<a href="mailto:${h(from)}" style="color:#334155;">${h(from)}</a>) vous invite à ${r.verb} un document dans l’application ${APP_NAME}&nbsp;:</p>
</td></tr>
<tr><td style="padding:8px 28px;">
<table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" style="background:#F8FAFC;border:1px solid #E2E8F0;border-radius:10px;">
<tr><td style="padding:16px 18px;font-family:Roboto,'Segoe UI',Helvetica,Arial,sans-serif;">
<div style="font-size:18px;line-height:26px;font-weight:bold;color:#0F172A;">${h(doc)}</div>
<div style="margin-top:6px;font-size:14px;line-height:21px;color:#334155;">Votre rôle&nbsp;: <strong>${h(r.label)}</strong>. ${h(r.can)}</div>
</td></tr>
</table>
</td></tr>
<tr><td align="left" style="padding:20px 28px 8px 28px;">
<table role="presentation" cellpadding="0" cellspacing="0" border="0"><tr>
<td bgcolor="${BLUE}" style="border-radius:8px;">
<a href="${linkHtml}" style="display:inline-block;padding:13px 26px;font-family:Roboto,'Segoe UI',Helvetica,Arial,sans-serif;font-size:16px;font-weight:bold;color:#FFFFFF;text-decoration:none;border-radius:8px;">Ouvrir le document</a>
</td></tr></table>
</td></tr>
<tr><td style="padding:4px 28px 0 28px;font-family:Roboto,'Segoe UI',Helvetica,Arial,sans-serif;font-size:13px;line-height:20px;color:#64748B;">
Le bouton ne marche pas&nbsp;? Copiez ce lien dans votre navigateur&nbsp;:<br>
<a href="${linkHtml}" style="color:${BLUE};word-break:break-all;">${linkHtml}</a>
</td></tr>
<tr><td style="padding:20px 28px 0 28px;font-family:Roboto,'Segoe UI',Helvetica,Arial,sans-serif;font-size:15px;line-height:23px;color:#0F172A;">
<p style="margin:0 0 8px 0;font-weight:bold;">Pour l’ouvrir sur votre téléphone</p>
<ol style="margin:0;padding-left:22px;">
<li style="margin:0 0 6px 0;">${installHtml}</li>
<li style="margin:0 0 6px 0;">Connectez-vous avec cette adresse&nbsp;: <strong>${h(to)}</strong> (avec un mot de passe, ou avec «&nbsp;Continuer avec Google&nbsp;»).</li>
<li style="margin:0;">Le document vous attend dans «&nbsp;Partagés avec moi&nbsp;»&nbsp;: touchez «&nbsp;Accepter&nbsp;».</li>
</ol>
${download ? `<p style="margin:16px 0 0 0;">Pour écrire à ${h(who)}, répondez simplement à cet e-mail.</p>\n` : ''}</td></tr>
<tr><td style="padding:20px 28px 28px 28px;font-family:Roboto,'Segoe UI',Helvetica,Arial,sans-serif;font-size:13px;line-height:20px;color:#64748B;">
<p style="margin:0;padding-top:16px;border-top:1px solid #E2E8F0;">Vous ne vous attendiez pas à cette invitation&nbsp;? Vous pouvez ignorer ce message&nbsp;: sans action de votre part, rien ne se passe. Ce n’est pas une inscription à une liste de diffusion.</p>
</td></tr>
</table>
<p style="max-width:560px;margin:14px auto 0 auto;font-family:Roboto,'Segoe UI',Helvetica,Arial,sans-serif;font-size:12px;line-height:18px;color:#94A3B8;text-align:center;">Cet e-mail a été envoyé à ${h(to)} par ${APP_NAME}, à la demande de ${h(who)}.</p>
</td></tr>
</table>
</body>
</html>
`;

  return { subject, fromName, inviter: who, text, html };
}

module.exports = { invitationEmail, displayName, oneLine, escapeHtml, roleOf, ROLES, APP_NAME };
