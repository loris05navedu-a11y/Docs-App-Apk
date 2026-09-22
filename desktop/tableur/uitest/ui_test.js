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

const colOf = (letters) => {
  let col = 0;
  for (const ch of letters) col = col * 26 + (ch.charCodeAt(0) - 64);
  return col - 1;
};

function cellSelector(ref) {
  const m = /^([A-Z]+)(\d+)$/.exec(ref);
  return `td[data-row="${Number(m[2]) - 1}"][data-col="${colOf(m[1])}"]`;
}

const cellText = (page, ref) =>
  page.evaluate((s) => document.querySelector(s)?.textContent ?? null, cellSelector(ref));

const clickCell = (page, ref) => page.click(cellSelector(ref));

async function typeInCell(page, ref, text) {
  await clickCell(page, ref);
  await page.keyboard.type(text);
  await page.keyboard.press('Enter');
  await page.waitForTimeout(110);
}

/** Les commandes du ruban et des menus partagent data-cmd : on vise le ruban. */
const ribbon = (cmd) => `.ribbon [data-cmd="${cmd}"]`;

async function menu(page, name, cmd) {
  await page.click(`.menutrigger[data-menu="${name}"]`);
  await page.click(`.dropdown[data-for="${name}"] [data-cmd="${cmd}"]`);
  await page.waitForTimeout(180);
}

(async () => {
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 1320, height: 860 } });

  const errors = [];
  page.on('pageerror', (e) => errors.push(e.message));
  page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });

  await page.goto(BASE, { waitUntil: 'networkidle' });
  // Le serveur garde le classeur entre deux exécutions : on repart de zéro,
  // sans quoi une bascule comme le gras s'inverserait au second passage.
  await menu(page, 'fichier', 'file:new');

  console.log('\n· Démarrage');
  check('la grille est rendue', (await page.locator('td').count()) > 100);
  check('les en-têtes de colonnes sont présents', (await page.locator('th[data-col]').count()) === 26);
  check('A1 est sélectionnée au départ', (await page.locator('#cellRef').textContent()) === 'A1');
  check('le logo est chargé', (await page.locator('.identity img').count()) === 1);
  check('une seule feuille au départ', (await page.locator('.tab').count()) === 1);

  console.log('\n· Saisie et calcul');
  await typeInCell(page, 'A1', '10');
  await typeInCell(page, 'A2', '30');
  await typeInCell(page, 'A3', '20');
  await typeInCell(page, 'B1', '=SOMME(A1:A3)');
  check('la somme est calculée', (await cellText(page, 'B1')) === '60', await cellText(page, 'B1'));

  await typeInCell(page, 'C1', 'Pomme');
  await typeInCell(page, 'C2', '=MAJUSCULE(C1)');
  check('une fonction de texte donne un texte', (await cellText(page, 'C2')) === 'POMME');
  await typeInCell(page, 'C3', '=1/0');
  check('une division par zéro est nommée', (await cellText(page, 'C3')) === '#DIV/0!');

  await typeInCell(page, 'A1', '100');
  check('le recalcul est en cascade', (await cellText(page, 'B1')) === '150', await cellText(page, 'B1'));

  console.log('\n· Barre de formule');
  await clickCell(page, 'B1');
  check('la barre montre la formule, pas le résultat',
    (await page.locator('#formulaInput').inputValue()) === '=SOMME(A1:A3)');
  check('la référence suit la sélection', (await page.locator('#cellRef').textContent()) === 'B1');

  console.log('\n· Navigation au clavier');
  await clickCell(page, 'A1');
  await page.keyboard.press('ArrowDown');
  await page.keyboard.press('ArrowRight');
  check('les flèches déplacent la sélection', (await page.locator('#cellRef').textContent()) === 'B2');

  console.log('\n· Mise en forme');
  await clickCell(page, 'A1');
  await page.click(ribbon('format:bold'));
  await page.waitForTimeout(150);
  check('le gras est appliqué',
    (await page.locator(cellSelector('A1') + ' .cellbox').evaluate(
      (el) => getComputedStyle(el).fontWeight)) === '600');
  check('le bouton est marqué actif',
    await page.locator('#tBold').evaluate((el) => el.classList.contains('on')));

  await page.click(ribbon('align:center'));
  await page.waitForTimeout(150);
  check('l\'alignement est appliqué',
    (await page.locator(cellSelector('A1') + ' .cellbox').evaluate(
      (el) => getComputedStyle(el).textAlign)) === 'center');

  console.log('\n· Somme de la sélection');
  await clickCell(page, 'A1');
  await page.keyboard.down('Shift');
  await page.keyboard.press('ArrowDown');
  await page.keyboard.press('ArrowDown');
  await page.keyboard.up('Shift');
  const readout = await page.locator('#readout').textContent();
  check('la barre d\'état totalise la sélection', readout.includes('150'), readout);

  console.log('\n· Barre de menus');
  await page.click('.menutrigger[data-menu="fichier"]');
  check('le menu s\'ouvre', await page.locator('.dropdown[data-for="fichier"]').isVisible());
  await page.keyboard.press('Escape');
  await page.click('body', { position: { x: 5, y: 400 } });
  await page.waitForTimeout(100);
  check('le menu se referme',
    !(await page.locator('.dropdown[data-for="fichier"]').isVisible()));

  console.log('\n· Assistant de fonctions');
  await page.click(ribbon('insert:function'));
  await page.waitForSelector('#fnq');
  check('le catalogue est groupé par famille', (await page.locator('.fngroup').count()) === 7);
  await page.fill('#fnq', 'RECHERCHEV');
  await page.waitForTimeout(120);
  check('la recherche filtre le catalogue', (await page.locator('.fnrow').count()) === 1);
  await page.click('.fnrow');
  await page.waitForTimeout(150);
  check('choisir une fonction ouvre la saisie', (await page.locator('td input').count()) === 1);
  await page.keyboard.press('Escape');

  console.log('\n· Insertion de ligne');
  await clickCell(page, 'A2');
  await page.click(ribbon('op:insertRow'));
  await page.waitForTimeout(200);
  check('le contenu est décalé', (await cellText(page, 'A3')) === '30', await cellText(page, 'A3'));
  await clickCell(page, 'B1');
  check('la plage de la formule est élargie',
    (await page.locator('#formulaInput').inputValue()) === '=SOMME(A1:A4)',
    await page.locator('#formulaInput').inputValue());

  console.log('\n· Tri');
  await clickCell(page, 'A1');
  await page.click(ribbon('sort:asc'));
  await page.waitForTimeout(220);
  const sorted = [await cellText(page, 'A1'), await cellText(page, 'A2'), await cellText(page, 'A3')];
  check('le tri croissant classe les nombres',
    sorted[0] === '20' && sorted[1] === '30' && sorted[2] === '100', sorted.join(','));

  console.log('\n· Feuilles');
  await page.click('#addSheet');
  await page.waitForTimeout(200);
  check('une feuille est ajoutée', (await page.locator('.tab').count()) === 2);
  check('la nouvelle feuille est active',
    (await page.locator('.tab.active').textContent()) === 'Feuille2');
  check('la nouvelle feuille est vide', (await cellText(page, 'A1')) === '');

  await typeInCell(page, 'A1', '=Feuille1!A1*2');
  check('une formule traverse les feuilles', (await cellText(page, 'A1')) === '40',
    await cellText(page, 'A1'));

  await page.click('.tab:nth-child(1)');
  await page.waitForTimeout(200);
  check('revenir sur la première feuille restaure son contenu',
    (await cellText(page, 'A1')) === '20', await cellText(page, 'A1'));

  console.log('\n· Graphiques');
  await clickCell(page, 'A1');
  await page.keyboard.down('Shift');
  await page.keyboard.press('ArrowDown');
  await page.keyboard.press('ArrowDown');
  await page.keyboard.up('Shift');
  await page.click(ribbon('insert:chart'));
  await page.waitForSelector('#chSeries');
  check('la plage sélectionnée est proposée',
    (await page.locator('#chSeries').inputValue()) === 'A1:A3',
    await page.locator('#chSeries').inputValue());
  check('la boîte à moustache est proposée en premier',
    (await page.locator('.kindopt').first().textContent()).includes('Boîte à moustache'));
  check('dix types sont offerts', (await page.locator('.kindopt').count()) === 10);

  await page.fill('#chTitle', 'Répartition');
  await page.click('#chOk');
  await page.waitForTimeout(300);
  check('le graphique est créé', (await page.locator('.chartcard').count()) === 1);
  check('le panneau s\'ouvre', await page.locator('#chartPane').isVisible());
  check('une boîte à moustache est tracée',
    (await page.locator('.chartcard svg').count()) >= 1);
  const boxSvg = await page.locator('.chartcard figure').innerHTML();
  check('la médiane est tracée', boxSvg.includes('stroke-width="2"'), 'SVG sans trait de médiane');
  // Le titre vit dans l'en-tête de la carte, pas dans le tracé.
  check('le titre apparaît',
    (await page.locator('.chartcard h3').first().textContent()) === 'Répartition');

  // Un deuxième graphique, d'un autre type, sur les mêmes valeurs.
  await page.click(ribbon('insert:chart'));
  await page.waitForSelector('#chSeries');
  await page.fill('#chSeries', 'A1:A3');
  await page.click('.kindopt:nth-child(2)');
  await page.click('#chOk');
  await page.waitForTimeout(300);
  check('un second graphique coexiste', (await page.locator('.chartcard').count()) === 2);

  await page.click('[data-chart-remove]');
  await page.waitForTimeout(250);
  check('un graphique se supprime', (await page.locator('.chartcard').count()) === 1);

  console.log('\n· Suppression et remise à zéro');
  await clickCell(page, 'C1');
  await page.keyboard.press('Delete');
  await page.waitForTimeout(220);
  check('la touche Suppr vide la cellule', (await cellText(page, 'C1')) === '');

  await menu(page, 'fichier', 'file:new');
  check('le classeur est vidé', (await cellText(page, 'A1')) === '');
  check('il ne reste qu\'une feuille', (await page.locator('.tab').count()) === 1);

  console.log('\n· Erreurs de page');
  check('aucune erreur JavaScript', errors.length === 0, errors.join(' | '));

  await browser.close();
  console.log(failures === 0
    ? '\nTous les contrôles passent.\n'
    : `\n${failures} contrôle(s) en échec.\n`);
  process.exit(failures === 0 ? 0 : 1);
})();
