#!/usr/bin/env bash
# Met en ligne le serveur de l'édition partagée de DocsApp Suite :
#   - les règles de la base (qui fait quoi : propriétaire, éditeur, commentateur, lecteur) ;
#   - la fonction qui envoie l'e-mail d'invitation ;
#   - le site des liens d'invitation (https://<projet>.web.app/d/…).
#
# À lancer dans Google Cloud Shell (https://shell.cloud.google.com), voir README.md :
#
#   git clone -b claude/apk-office-suite-app-ky4g8r https://github.com/loris05navedu-a11y/Docs-App-Apk.git
#   bash Docs-App-Apk/firebase/deploy.sh
#
# On peut le relancer autant de fois qu'on veut : il garde les réponses déjà données.
set -euo pipefail
cd "$(dirname "$0")"

FIREBASE_TOOLS_VERSION=15.32.1
firebase() { npx --yes "firebase-tools@${FIREBASE_TOOLS_VERSION}" "$@"; }

bold() { printf '\n\033[1m%s\033[0m\n' "$*"; }
fail() { printf '\n\033[1;31m%s\033[0m\n' "$*" >&2; exit 1; }
json() { node -e "let s='';process.stdin.on('data',d=>s+=d).on('end',()=>{const r=JSON.parse(s);$1})"; }

command -v node >/dev/null || fail "Node.js est introuvable. Lancez ce script dans Google Cloud Shell."

bold "1/6  Connexion à votre compte Google"
if ! firebase projects:list --json >/dev/null 2>&1; then
  echo "Ouvrez le lien affiché, choisissez le compte Google de votre projet Firebase, puis recopiez le code ici."
  firebase login --no-localhost
fi

bold "2/6  Projet Firebase"
PROJECT="${1:-}"
if [ -z "$PROJECT" ]; then
  mapfile -t PROJECTS < <(firebase projects:list --json | json "for (const p of r.result || []) console.log(p.projectId)")
  if [ "${#PROJECTS[@]}" -eq 0 ]; then
    fail "Aucun projet Firebase sur ce compte. Vérifiez que vous êtes connecté avec le bon compte Google."
  elif [ "${#PROJECTS[@]}" -eq 1 ]; then
    PROJECT="${PROJECTS[0]}"
  else
    echo "Vos projets :"
    for i in "${!PROJECTS[@]}"; do echo "  $((i + 1)). ${PROJECTS[$i]}"; done
    read -rp "Numéro du projet de DocsApp Suite : " CHOICE
    PROJECT="${PROJECTS[$((CHOICE - 1))]:-}"
    [ -n "$PROJECT" ] || fail "Numéro invalide."
  fi
fi
echo "Projet : $PROJECT"

bold "3/6  Base temps réel"
REGION=$(firebase database:instances:list --project "$PROJECT" --json 2>/dev/null | json "
  const all = r.result || [];
  const db = all.find((i) => String(i.type).toUpperCase() === 'DEFAULT_DATABASE') || all[0];
  if (db) console.log(db.location);
" || true)
[ -n "$REGION" ] || fail "Pas encore de base temps réel dans ce projet. Créez-la dans la console Firebase (Build > Realtime Database > Créer une base de données), puis relancez ce script."
echo "Région de la base : $REGION"

bold "4/6  E-mails d'invitation"
ENV_FILE="functions/.env.$PROJECT"
SMTP_USER_DEFAULT=""
DOWNLOAD_DEFAULT=""
if [ -f "$ENV_FILE" ]; then
  SMTP_USER_DEFAULT=$(sed -n 's/^SMTP_USER=//p' "$ENV_FILE")
  DOWNLOAD_DEFAULT=$(sed -n 's/^APP_DOWNLOAD_URL=//p' "$ENV_FILE")
fi
while :; do
  read -rp "Adresse Gmail qui enverra les invitations${SMTP_USER_DEFAULT:+ [$SMTP_USER_DEFAULT]} : " SMTP_USER
  SMTP_USER="${SMTP_USER:-$SMTP_USER_DEFAULT}"
  [[ "$SMTP_USER" =~ ^[^[:space:]@]+@[^[:space:]@]+\.[^[:space:]@]+$ ]] && break
  echo "Ce n'est pas une adresse e-mail."
done
read -rp "Lien de téléchargement de l'application (facultatif, Entrée pour passer)${DOWNLOAD_DEFAULT:+ [$DOWNLOAD_DEFAULT]} : " DOWNLOAD
DOWNLOAD="${DOWNLOAD:-$DOWNLOAD_DEFAULT}"
if [ -n "$DOWNLOAD" ] && [[ ! "$DOWNLOAD" =~ ^https:// ]]; then
  fail "Le lien de téléchargement doit commencer par https://"
fi

if firebase functions:secrets:get SMTP_PASSWORD --project "$PROJECT" >/dev/null 2>&1; then
  read -rp "Le mot de passe d'application est déjà enregistré. Le remplacer ? (o/N) " AGAIN
else
  AGAIN=o
fi
if [[ "$AGAIN" =~ ^[oOyY] ]]; then
  echo "Collez le mot de passe d'application de $SMTP_USER (16 lettres, il ne s'affiche pas), puis Entrée."
  firebase functions:secrets:set SMTP_PASSWORD --project "$PROJECT"
fi

bold "5/6  Site des liens d'invitation"
siteUrl() {
  firebase hosting:sites:list --project "$PROJECT" --json 2>/dev/null | json "
    const sites = (r.result && r.result.sites) || [];
    const site = sites.find((s) => s.type === 'DEFAULT_SITE') || sites[0];
    if (site) console.log(site.defaultUrl || 'https://' + site.name.split('/').pop() + '.web.app');
  " || true
}
APP_URL=$(siteUrl)
if [ -z "$APP_URL" ]; then
  firebase hosting:sites:create "$PROJECT" --project "$PROJECT"
  APP_URL=$(siteUrl)
  [ -n "$APP_URL" ] || APP_URL="https://$PROJECT.web.app"
fi
echo "Site : $APP_URL"

cat > "$ENV_FILE" <<EOF
# Écrit par deploy.sh : réglages de la fonction d'invitation pour le projet $PROJECT.
SMTP_USER=$SMTP_USER
SMTP_HOST=smtp.gmail.com
SMTP_PORT=465
APP_URL=$APP_URL
APP_DOWNLOAD_URL=$DOWNLOAD
INVITES_PER_DAY=30
DATABASE_REGION=$REGION
EOF
if [ -n "$DOWNLOAD" ]; then
  printf '{ "download": "%s" }\n' "$DOWNLOAD" > hosting/app.json
else
  rm -f hosting/app.json
fi

bold "6/6  Mise en ligne (quelques minutes)"
(cd functions && npm ci --no-audit --no-fund --loglevel=error)
deploy() { firebase deploy --only database,functions,hosting --project "$PROJECT" --force; }
if ! deploy; then
  echo
  echo "Premier déploiement : Google finit d'activer ses services. Nouvel essai dans 2 minutes…"
  sleep 120
  deploy || fail "La mise en ligne a échoué. Relancez simplement le script dans quelques minutes ; si l'erreur revient, copiez-la à Claude."
fi

bold "C'est en ligne."
echo "  Règles de la base : actives"
echo "  E-mails d'invitation : envoyés depuis $SMTP_USER"
echo "  Liens d'invitation : $APP_URL/d/…"
