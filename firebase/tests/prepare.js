// Avant de lancer la fonction dans l'émulateur : ses dépendances, et un mot de
// passe d'envoi factice (l'émulateur lit les secrets dans .secret.local, que
// git ignore).
import { existsSync, writeFileSync } from 'node:fs';
import { execSync } from 'node:child_process';

const functions = new URL('../functions/', import.meta.url);
if (!existsSync(new URL('node_modules/', functions))) {
  execSync('npm ci --no-audit --no-fund', { cwd: functions, stdio: 'inherit' });
}
const secret = new URL('.secret.local', functions);
if (!existsSync(secret)) writeFileSync(secret, 'SMTP_PASSWORD=mot-de-passe-de-test\n');
