/* Vérification de l'interface réelle, pilotée dans Chromium.
   L'application tourne en mode « headless » : la page servie est exactement
   celle que la fenêtre Windows affichera. */

const { chromium } = require('playwright');

const BASE = process.env.TABLEUR_URL || 'http://127.0.0.1:8777';
let failures = 0;

function check(label, condition, detail) {
  if (condition) {
    console.log(`  ok   ${label}`);
  } else {
    failures++;
    console.log(`  FAIL ${label}${detail ? ' — ' + detail : ''}`);
  }
}

async function cellText(page, ref) {
  return page.evaluate((r) => {
    const match = /^([A-Z]+)(\d+)$/.exec(r);
    let col = 0;
    for (const ch of match[1]) col = col * 26 + (ch.charCodeAt(0) - 64);
    const td = document.querySelector(`td[data-row="${Number(match[2]) - 1}"][data-col="${col - 1}"]`);
    return td ? td.textContent : null;
  }, ref);
}

async function clickCell(page, ref) {
  const match = /^([A-Z]+)(\d+)$/.exec(ref);
  let col = 0;
  for (const ch of match[1]) col = col * 26 + (ch.charCodeAt(0) - 64);
  await page.click(`td[data-row="${Number(match[2]) - 1}"][data-col="${col - 1}"]`);
}

async function typeInCell(page, ref, text) {
  await clickCell(page, ref);
  await page.keyboard.type(text);
  await page.keyboard.press('Enter');
  await page.waitForTimeout(120);
}

(async () => {
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 1280, height: 820 } });

  const errors = [];
  page.on('pageerror', (e) => errors.push(e.message));
  page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });

  await page.goto(BASE, { waitUntil: 'networkidle' });

  console.log('\n· Démarrage');
  check('la grille est rendue', (await page.locator('td').count()) > 100);
  check('les en-têtes de colonnes sont présents', (await page.locator('th[data-col]').count()) === 26);
  check('la cellule A1 est sélectionnée au départ',
    (await page.locator('#cellRef').textContent()) === 'A1');

  console.log('\n· Saisie et calcul');
  await typeInCell(page, 'A1', '10');
  await typeInCell(page, 'A2', '30');
  await typeInCell(page, 'A3', '20');
  await typeInCell(page, 'B1', '=SOMME(A1:A3)');
  check('la somme est calculée', (await cellText(page, 'B1')) === '60',
    'obtenu ' + (await cellText(page, 'B1')));

  await typeInCell(page, 'B2', '=MOYENNE(A1:A3)');
  check('la moyenne est calculée', (await cellText(page, 'B2')) === '20');

  await typeInCell(page, 'C1', 'Pomme');
  await typeInCell(page, 'C2', '=MAJUSCULE(C1)');
  check('une fonction de texte donne un texte', (await cellText(page, 'C2')) === 'POMME');

  await typeInCell(page, 'C3', '=1/0');
  check('une division par zéro est nommée', (await cellText(page, 'C3')) === '#DIV/0!');

  console.log('\n· Recalcul en cascade');
  await typeInCell(page, 'A1', '100');
  check('la somme suit la modification', (await cellText(page, 'B1')) === '150',
    'obtenu ' + (await cellText(page, 'B1')));

  console.log('\n· Barre de formule');
  await clickCell(page, 'B1');
  check('la barre montre la formule, pas le résultat',
    (await page.locator('#formulaInput').inputValue()) === '=SOMME(A1:A3)');
  check('la référence affichée suit la sélection',
    (await page.locator('#cellRef').textContent()) === 'B1');

  console.log('\n· Navigation au clavier');
  await clickCell(page, 'A1');
  await page.keyboard.press('ArrowDown');
  await page.keyboard.press('ArrowRight');
  check('les flèches déplacent la sélection',
    (await page.locator('#cellRef').textContent()) === 'B2');

  console.log('\n· Mise en forme');
  await clickCell(page, 'A1');
  await page.click('#btnBold');
  await page.waitForTimeout(150);
  check('le gras est appliqué',
    (await page.locator('td[data-row="0"][data-col="0"] .cellbox').evaluate(
      (el) => getComputedStyle(el).fontWeight)) === '600');
  check('le bouton gras est marqué actif',
    await page.locator('#btnBold').evaluate((el) => el.classList.contains('active')));

  console.log('\n· Somme de la sélection');
  await clickCell(page, 'A1');
  await page.keyboard.down('Shift');
  await page.keyboard.press('ArrowDown');
  await page.keyboard.press('ArrowDown');
  await page.keyboard.up('Shift');
  const stats = await page.locator('#statusStats').textContent();
  check('la barre d\'état totalise la sélection', stats.includes('150'), stats);

  console.log('\n· Assistant de fonctions');
  await page.click('#btnFunctions');
  await page.waitForSelector('#fnSearch');
  const groups = await page.locator('.fngroup').count();
  check('le catalogue est groupé par famille', groups === 7, groups + ' familles');
  await page.fill('#fnSearch', 'RECHERCHEV');
  await page.waitForTimeout(120);
  check('la recherche filtre le catalogue', (await page.locator('.fnrow').count()) === 1);
  await page.click('.fnrow');
  await page.waitForTimeout(150);
  check('choisir une fonction ouvre la saisie',
    (await page.locator('td input').count()) === 1);
  await page.keyboard.press('Escape');

  console.log('\n· Insertion de ligne');
  await clickCell(page, 'A2');
  await page.click('[data-op="insertRow"]');
  await page.waitForTimeout(200);
  check('le contenu est décalé', (await cellText(page, 'A3')) === '30',
    'obtenu ' + (await cellText(page, 'A3')));
  check('la formule suit la plage', (await cellText(page, 'B1')) === '150',
    'obtenu ' + (await cellText(page, 'B1')));
  await clickCell(page, 'B1');
  check('la plage a bien été élargie',
    (await page.locator('#formulaInput').inputValue()) === '=SOMME(A1:A4)',
    await page.locator('#formulaInput').inputValue());

  console.log('\n· Tri');
  await clickCell(page, 'A1');
  await page.click('#btnSortAsc');
  await page.waitForTimeout(200);
  const sorted = [await cellText(page, 'A1'), await cellText(page, 'A2'), await cellText(page, 'A3')];
  check('le tri croissant classe les nombres',
    sorted[0] === '20' && sorted[1] === '30' && sorted[2] === '100', sorted.join(','));

  console.log('\n· Suppression');
  await clickCell(page, 'C1');
  await page.keyboard.press('Delete');
  await page.waitForTimeout(200);
  check('la touche Suppr vide la cellule', (await cellText(page, 'C1')) === '');

  console.log('\n· Nouveau classeur');
  await page.click('#btnNew');
  await page.waitForTimeout(200);
  check('le classeur est vidé', (await cellText(page, 'A1')) === '');

  console.log('\n· Erreurs de page');
  check('aucune erreur JavaScript', errors.length === 0, errors.join(' | '));

  await page.screenshot({ path: '/tmp/tableur-ui.png', fullPage: false });
  await browser.close();

  console.log(failures === 0
    ? '\nTous les contrôles passent.\n'
    : `\n${failures} contrôle(s) en échec.\n`);
  process.exit(failures === 0 ? 0 : 1);
})();
