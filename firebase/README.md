# Serveur de l'édition partagée

L'édition partagée de DocsApp Suite repose sur un projet Firebase :

| Service | Rôle | Forfait |
| --- | --- | --- |
| Authentication | comptes par e-mail et mot de passe, ou avec Google | gratuit |
| Realtime Database | le texte, lettre par lettre, les membres, les curseurs, les commentaires | gratuit (Spark) |
| Cloud Functions | l'e-mail d'invitation | Blaze (gratuit à ce volume, carte demandée) |
| Hosting | les liens d'invitation `https://<projet>.web.app/d/…` | gratuit |

## Ce qu'il y a ici

- `database.rules.json` : les règles de la base. Ce sont elles qui garantissent les rôles,
  pas l'application : seule la ou le propriétaire ajoute, change ou retire des personnes ;
  l'éditeur modifie le texte ; le commentateur commente ; le lecteur lit.
- `functions/` : la fonction `sendInvitation`, qui envoie l'e-mail d'invitation
  (texte et HTML, sans image ni traceur, au nom de la personne qui invite, réponse à son adresse).
- `hosting/` : la page qui ouvre l'application depuis le lien de l'e-mail, et
  `.well-known/assetlinks.json`, qui permet à Android d'ouvrir ces liens directement dans l'app.
- `deploy.sh` : met tout en ligne, en posant les questions nécessaires.
- `tests/` : les tests, dans les émulateurs Firebase.

## Mise en place, une seule fois

1. **Enregistrer l'application Android** (Paramètres du projet > Vos applications > Android) :
   - nom du package : `com.docssuite`
   - certificat SHA-1 : `3C:0B:4C:C0:3A:34:65:48:D5:FE:3F:3D:A4:5F:52:E2:99:60:A4:5C`
2. **Authentication** > Commencer > Mode de connexion : activer **Adresse e-mail/Mot de passe**
   et **Google**. Dans Paramètres du projet > Général, mettre le **nom public** à « DocsApp Suite » :
   c'est le nom qu'affichent les e-mails de vérification.
3. **Realtime Database** > Créer une base de données > emplacement **Belgique (europe-west1)**
   > mode verrouillé.
4. **Télécharger `google-services.json`** (Paramètres du projet > Vos applications), *après*
   l'étape 3 : il contient l'adresse de la base. L'APK est construit avec ce fichier.
5. **Forfait Blaze** (Utilisation et facturation) : nécessaire pour envoyer des e-mails.
   À ce volume, cela reste gratuit ; ajoutez une alerte de budget à 1 €.
6. **Un compte Gmail d'envoi**, par exemple `docsapp.invitations@gmail.com` : activer la
   validation en deux étapes, puis créer un **mot de passe d'application** sur
   <https://myaccount.google.com/apppasswords>.
7. **Mettre en ligne** dans Google Cloud Shell (<https://shell.cloud.google.com>, même compte Google) :

   ```sh
   git clone -b claude/apk-office-suite-app-ky4g8r https://github.com/loris05navedu-a11y/Docs-App-Apk.git
   bash Docs-App-Apk/firebase/deploy.sh
   ```

   Le script demande l'adresse Gmail d'envoi, son mot de passe d'application et, si vous en
   avez un, un lien de téléchargement de l'APK. On peut le relancer à tout moment, par exemple
   pour changer ces réglages.

Sans l'étape 5 (et donc sans e-mail), le partage marche quand même : l'invitation apparaît dans
l'application de la personne invitée, et l'application propose d'envoyer le lien soi-même.

## Tester

```sh
cd firebase/tests
npm install
npm test            # règles de la base, puis e-mail d'invitation et site des liens
```

`npm run test:rules` essaie chaque écriture de l'application, et chaque abus, contre les règles.
`npm run test:server` lance la vraie fonction dans l'émulateur : une invitation écrite dans la
base fait partir un e-mail vers un serveur de courrier local, que le test lit.
Les tests de l'e-mail lui-même sont dans `functions/` (`npm test`).
