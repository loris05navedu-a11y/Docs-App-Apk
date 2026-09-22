/* Tableur — interface.
   Le calcul reste côté Go : cette page affiche l'état que le serveur local
   renvoie après chaque modification, et lui transmet les gestes. */

'use strict';

const $ = (id) => document.getElementById(id);

const state = {
  name: '', path: '', modified: false,
  sheets: [], active: 0,
  rows: 100, columns: 26,
  cells: {}, display: {}, formats: {}, charts: [],
};

const cursor = { row: 0, col: 0, fromRow: 0, fromCol: 0 };
let editor = null;       // { ref, input }
let catalog = null;
let chartKinds = null;
let chartsVisible = false;
let sheetMenuTarget = -1;

/* ---------------------------------------------------------- références */

function columnLabel(index) {
  let i = index, out = '';
  for (;;) {
    out = String.fromCharCode(65 + (i % 26)) + out;
    i = Math.floor(i / 26) - 1;
    if (i < 0) break;
  }
  return out;
}

function columnIndex(label) {
  let n = 0;
  for (const ch of label) n = n * 26 + (ch.charCodeAt(0) - 64);
  return n - 1;
}

const refOf = (row, col) => columnLabel(col) + (row + 1);

function selectedBox() {
  return {
    r1: Math.min(cursor.row, cursor.fromRow), r2: Math.max(cursor.row, cursor.fromRow),
    c1: Math.min(cursor.col, cursor.fromCol), c2: Math.max(cursor.col, cursor.fromCol),
  };
}

function selectedRefs() {
  const b = selectedBox();
  const out = [];
  for (let r = b.r1; r <= b.r2; r++) {
    for (let c = b.c1; c <= b.c2; c++) out.push(refOf(r, c));
  }
  return out;
}

const currentRef = () => refOf(cursor.row, cursor.col);

/* ------------------------------------------------------------- réseau */

async function api(path, body) {
  const init = body === undefined ? {} : {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
  };
  const response = await fetch(path, init);
  const payload = await response.json();
  if (!response.ok) throw new Error(payload.error || 'Opération impossible');
  return payload;
}

async function send(path, body) {
  try {
    adopt(await api(path, body));
    return true;
  } catch (error) {
    toast(error.message, true);
    return false;
  }
}

function adopt(next) {
  Object.assign(state, next);
  state.cells = next.cells || {};
  state.display = next.display || {};
  state.formats = next.formats || {};
  state.charts = next.charts || [];
  state.sheets = next.sheets || [];
  cursor.row = Math.min(cursor.row, state.rows - 1);
  cursor.col = Math.min(cursor.col, state.columns - 1);
  drawGrid();
  drawTabs();
  drawCharts();
  drawTitle();
}

/* ---------------------------------------------------------- affichage */

function drawTitle() {
  $('docName').textContent = state.name || 'Classeur sans titre';
  $('dirtyDot').hidden = !state.modified;
  document.title = (state.modified ? '• ' : '') + (state.name || 'Tableur') + ' — Tableur';
}

const escapeHtml = (text) => String(text).replace(/[&<>"']/g, (c) =>
  ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

function looksNumeric(text) {
  return text !== '' && !isNaN(Number(String(text).replace(/\s/g, '').replace(',', '.')));
}

function drawGrid() {
  const head = ['<thead><tr><th class="corner"></th>'];
  for (let c = 0; c < state.columns; c++) head.push(`<th data-col="${c}">${columnLabel(c)}</th>`);
  head.push('</tr></thead><tbody>');

  const body = [];
  for (let r = 0; r < state.rows; r++) {
    body.push(`<tr><th data-row="${r}">${r + 1}</th>`);
    for (let c = 0; c < state.columns; c++) {
      const ref = refOf(r, c);
      const shown = state.display[ref] || '';
      const f = state.formats[ref] || {};
      let cls = 'cellbox';
      if (shown.startsWith('#')) cls += ' err';
      else if (looksNumeric(shown) && !f.align) cls += ' num';

      let style = '';
      if (f.bold) style += 'font-weight:600;';
      if (f.italic) style += 'font-style:italic;';
      if (f.color) style += `color:${f.color};`;
      if (f.background) style += `background:${f.background};`;
      if (f.align) style += `text-align:${f.align};`;

      body.push(`<td data-row="${r}" data-col="${c}"><span class="${cls}" style="${style}">${
        escapeHtml(shown)}</span></td>`);
    }
    body.push('</tr>');
  }
  $('grid').innerHTML = head.join('') + body.join('') + '</tbody>';
  paint();
}

function cellAt(row, col) {
  return document.querySelector(`td[data-row="${row}"][data-col="${col}"]`);
}

function paint() {
  document.querySelectorAll('td.cursor, td.range').forEach((td) => td.classList.remove('cursor', 'range'));
  document.querySelectorAll('th.axis').forEach((th) => th.classList.remove('axis'));

  const b = selectedBox();
  for (let r = b.r1; r <= b.r2; r++) {
    for (let c = b.c1; c <= b.c2; c++) cellAt(r, c)?.classList.add('range');
  }
  const here = cellAt(cursor.row, cursor.col);
  if (here) {
    here.classList.remove('range');
    here.classList.add('cursor');
    here.scrollIntoView({ block: 'nearest', inline: 'nearest' });
  }
  document.querySelector(`th[data-col="${cursor.col}"]`)?.classList.add('axis');
  document.querySelector(`th[data-row="${cursor.row}"]`)?.classList.add('axis');

  const refs = selectedRefs();
  $('cellRef').textContent = refs.length > 1 ? `${refs[0]}:${refs[refs.length - 1]}` : currentRef();
  $('formulaInput').value = state.cells[currentRef()] || '';
  syncRibbon();
  syncReadout(refs);
}

function syncRibbon() {
  const f = state.formats[currentRef()] || {};
  $('tBold').classList.toggle('on', !!f.bold);
  $('tItalic').classList.toggle('on', !!f.italic);
  $('tLeft').classList.toggle('on', f.align === 'left');
  $('tCenter').classList.toggle('on', f.align === 'center');
  $('tRight').classList.toggle('on', f.align === 'right');
  $('chipText').style.background = f.color || '#a8a29e';
  $('chipFill').style.background = f.background || '#a8a29e';
}

function syncReadout(refs) {
  const numbers = refs
    .map((ref) => state.display[ref] || '')
    .filter((t) => t !== '' && !t.startsWith('#') && looksNumeric(t))
    .map((t) => Number(t.replace(/\s/g, '').replace(',', '.')));

  if (numbers.length === 0) {
    $('readout').textContent = refs.length > 1 ? `${refs.length} cellules` : 'Prêt';
    return;
  }
  const total = numbers.reduce((a, b) => a + b, 0);
  const short = (v) => (Number.isInteger(v) ? String(v)
    : v.toFixed(4).replace(/0+$/, '').replace(/\.$/, ''));
  $('readout').textContent =
    `Somme ${short(total)}  ·  Moyenne ${short(total / numbers.length)}  ·  Nombre ${numbers.length}`;
}

/* -------------------------------------------------- onglets de feuilles */

function drawTabs() {
  $('sheetTabs').innerHTML = state.sheets.map((s, i) =>
    `<button class="tab${i === state.active ? ' active' : ''}" data-sheet-index="${i}">${
      escapeHtml(s.name)}</button>`).join('');
}

/* ----------------------------------------------------------- graphiques */

function drawCharts() {
  const count = state.charts.length;
  $('chartCount').textContent = count === 0 ? 'Graphiques' : `Graphiques (${count})`;
  $('toggleCharts').classList.toggle('on', chartsVisible);
  $('chartPane').hidden = !chartsVisible;
  if (!chartsVisible) return;

  if (count === 0) {
    $('chartList').innerHTML =
      `<div class="emptypane">Aucun graphique sur cette feuille.<br>
       Sélectionnez des valeurs puis créez-en un.</div>`;
    return;
  }
  $('chartList').innerHTML = state.charts.map((c) => {
    const label = (chartKinds || []).find((k) => k.id === c.kind)?.label || c.kind;
    return `<article class="chartcard" data-chart="${c.id}">
      <header>
        <h3>${escapeHtml(c.title || label)}</h3>
        <span class="kindtag">${escapeHtml(label)}</span>
        <button class="icon small" data-chart-edit="${c.id}" title="Modifier"><svg><use href="#i-fx"/></svg></button>
        <button class="icon small" data-chart-remove="${c.id}" title="Supprimer"><svg><use href="#i-close"/></svg></button>
      </header>
      <figure>${Charts.render(c.data)}</figure>
    </article>`;
  }).join('');
}

/* ------------------------------------------------------------- édition */

function moveTo(row, col, extend) {
  commit();
  cursor.row = Math.max(0, Math.min(state.rows - 1, row));
  cursor.col = Math.max(0, Math.min(state.columns - 1, col));
  if (!extend) { cursor.fromRow = cursor.row; cursor.fromCol = cursor.col; }
  paint();
}

function edit(initial) {
  if (editor) return;
  const td = cellAt(cursor.row, cursor.col);
  if (!td) return;
  const ref = currentRef();
  const input = document.createElement('input');
  input.type = 'text';
  input.spellcheck = false;
  input.value = initial !== undefined ? initial : (state.cells[ref] || '');
  td.appendChild(input);
  input.focus();
  if (initial === undefined) input.select();
  editor = { ref, input };

  input.addEventListener('keydown', (event) => {
    if (event.key === 'Enter') { event.preventDefault(); commit(); moveTo(cursor.row + 1, cursor.col, false); }
    else if (event.key === 'Tab') {
      event.preventDefault(); commit();
      moveTo(cursor.row, cursor.col + (event.shiftKey ? -1 : 1), false);
    } else if (event.key === 'Escape') { event.preventDefault(); abort(); }
    event.stopPropagation();
  });
}

function abort() {
  if (!editor) return;
  editor.input.remove();
  editor = null;
  paint();
}

function commit() {
  if (!editor) return;
  const { ref, input } = editor;
  const raw = input.value;
  input.remove();
  editor = null;
  if ((state.cells[ref] || '') !== raw) send('/api/cell', { ref, raw });
}

/* ----------------------------------------------------------- dialogues */

function closeDialog() {
  $('scrim').hidden = true;
  $('dialog').innerHTML = '';
}

function openDialog(title, subtitle, bodyHtml, footerHtml) {
  $('dialog').innerHTML =
    `<header><h2>${title}</h2>${subtitle ? `<p class="sub">${subtitle}</p>` : ''}</header>` +
    `<div class="body">${bodyHtml}</div>` +
    `<footer>${footerHtml || '<button class="btn" data-close>Fermer</button>'}</footer>`;
  $('scrim').hidden = false;
  $('dialog').querySelectorAll('[data-close]').forEach((b) => b.addEventListener('click', closeDialog));
}

const SWATCHES = [
  '#1c1917', '#b91c1c', '#c2703d', '#a8862c', '#0b6e4f', '#1d7fa8', '#8a5fa8', '#be185d', '#57534e',
  '#ffffff', '#fee2e2', '#ffedd5', '#fef3c7', '#e8f3ee', '#e0f2fe', '#ede9fe', '#fce7f3', '#f5f5f4',
];

function openSwatches(title, apply) {
  openDialog(title, 'La couleur s\'applique à la sélection.',
    `<div class="swatches"><button class="none" data-color="" title="Aucune"></button>${
      SWATCHES.map((c) => `<button style="background:${c}" data-color="${c}"></button>`).join('')}</div>`);
  $('dialog').querySelectorAll('[data-color]').forEach((b) => {
    b.addEventListener('click', () => { apply(b.dataset.color); closeDialog(); });
  });
}

async function openFunctions() {
  if (!catalog) catalog = await api('/api/functions');
  const build = (needle) => {
    const q = needle.trim().toUpperCase();
    const groups = catalog
      .map((g) => ({ title: g.title, entries: g.entries.filter((e) =>
        !q || e.signature.toUpperCase().includes(q) || e.description.toUpperCase().includes(q)) }))
      .filter((g) => g.entries.length);
    if (!groups.length) return '<p class="hint">Aucune fonction ne correspond.</p>';
    return groups.map((g) => `<div class="fngroup"><h3>${g.title}</h3>${
      g.entries.map((e) => `<div class="fnrow" data-sig="${escapeHtml(e.signature)}">
        <strong>${escapeHtml(e.signature)}</strong><span>${escapeHtml(e.description)}</span></div>`).join('')
    }</div>`).join('');
  };
  const bind = () => $('dialog').querySelectorAll('.fnrow').forEach((row) => {
    row.addEventListener('click', () => {
      const name = row.dataset.sig.split('(')[0];
      closeDialog();
      edit('=' + name + '(');
    });
  });

  openDialog('Insérer une fonction', 'Environ quatre-vingt-dix fonctions, en français comme en anglais.',
    `<input class="input" id="fnq" placeholder="Rechercher…" autocomplete="off" style="margin-bottom:12px">
     <div id="fnlist">${build('')}</div>`);
  bind();
  $('fnq').addEventListener('input', (e) => { $('fnlist').innerHTML = build(e.target.value); bind(); });
  $('fnq').focus();
}

/** Plage par défaut : la sélection courante si elle couvre plusieurs cellules. */
function selectionRange() {
  const b = selectedBox();
  if (b.r1 === b.r2 && b.c1 === b.c2) return '';
  return `${refOf(b.r1, b.c1)}:${refOf(b.r2, b.c2)}`;
}

async function openChartDialog(existing) {
  if (!chartKinds) chartKinds = await api('/api/chartkinds');
  const chart = existing || { kind: 'box', title: '', labels: '', series: [selectionRange()], bins: 0 };

  openDialog(existing ? 'Modifier le graphique' : 'Nouveau graphique',
    'Les plages restent liées aux cellules : le tracé suit leurs valeurs.',
    `<label class="field"><span>Type</span>
       <div class="kindgrid" id="kinds">${chartKinds.map((k) =>
         `<button type="button" class="kindopt${k.id === chart.kind ? ' on' : ''}" data-kind="${k.id}">
            <strong>${escapeHtml(k.label)}</strong><span>${escapeHtml(k.hint)}</span></button>`).join('')}</div>
     </label>
     <label class="field"><span>Titre</span>
       <input class="input" id="chTitle" value="${escapeHtml(chart.title || '')}" placeholder="Facultatif">
     </label>
     <label class="field"><span>Valeurs</span>
       <input class="input" id="chSeries" value="${escapeHtml((chart.series || []).join(' ; '))}"
              placeholder="B2:B10   —   plusieurs plages séparées par ;">
       <p class="hint" id="chHint"></p>
     </label>
     <label class="field"><span>Libellés</span>
       <input class="input" id="chLabels" value="${escapeHtml(chart.labels || '')}" placeholder="A2:A10 (facultatif)">
     </label>`,
    `<button class="btn" data-close>Annuler</button>
     <button class="btn primary" id="chOk">${existing ? 'Enregistrer' : 'Créer'}</button>`);

  let kind = chart.kind;
  const hints = {
    box: 'Une boîte par plage : médiane, quartiles, moustaches et valeurs aberrantes.',
    scatter: 'Deux plages attendues : d\'abord les abscisses, puis les ordonnées.',
    histogram: 'Une seule plage : les valeurs sont réparties en classes.',
    radar: 'Au moins trois valeurs par plage.',
  };
  const refreshHint = () => { $('chHint').textContent = hints[kind] || 'Une plage par série.'; };
  refreshHint();

  $('kinds').querySelectorAll('[data-kind]').forEach((b) => {
    b.addEventListener('click', () => {
      kind = b.dataset.kind;
      $('kinds').querySelectorAll('.kindopt').forEach((o) => o.classList.toggle('on', o === b));
      refreshHint();
    });
  });

  $('chOk').addEventListener('click', async () => {
    const series = $('chSeries').value.split(';').map((s) => s.trim()).filter(Boolean);
    const payload = {
      action: existing ? 'update' : 'add',
      id: existing ? existing.id : '',
      kind, title: $('chTitle').value,
      labels: $('chLabels').value, series, bins: 0,
    };
    if (await send('/api/chart', payload)) {
      closeDialog();
      if (!chartsVisible) { chartsVisible = true; drawCharts(); }
    }
  });
}

/* ------------------------------------------------------------- commandes */

const commands = {
  'file:new': () => send('/api/file', { action: 'new' }),
  'file:open': () => send('/api/file', { action: 'open' }),
  'file:save': async () => { if (await send('/api/file', { action: 'save' }) && !state.modified) toast('Classeur enregistré'); },
  'file:saveAs': () => send('/api/file', { action: 'saveAs' }),
  'file:exportXlsx': async () => { if (await send('/api/file', { action: 'exportXlsx' })) toast('Exporté au format Excel'); },
  'file:exportCsv': async () => { if (await send('/api/file', { action: 'exportCsv' })) toast('Exporté en CSV'); },

  'edit:clearCells': clearSelection,
  'edit:clearAll': () => {
    if (confirm('Vider entièrement ce classeur ?')) send('/api/op', { op: 'clear' });
  },

  'format:bold': () => toggleFormat('bold'),
  'format:italic': () => toggleFormat('italic'),
  'format:clear': () => send('/api/format', { refs: selectedRefs(), clear: true }),
  'format:textColor': () => openSwatches('Couleur du texte',
    (color) => send('/api/format', { refs: selectedRefs(), color })),
  'format:fillColor': () => openSwatches('Couleur de fond',
    (color) => send('/api/format', { refs: selectedRefs(), background: color })),

  'align:left': () => send('/api/format', { refs: selectedRefs(), align: 'left' }),
  'align:center': () => send('/api/format', { refs: selectedRefs(), align: 'center' }),
  'align:right': () => send('/api/format', { refs: selectedRefs(), align: 'right' }),

  'op:insertRow': () => send('/api/op', { op: 'insertRow', index: cursor.row }),
  'op:deleteRow': () => send('/api/op', { op: 'deleteRow', index: cursor.row }),
  'op:insertColumn': () => send('/api/op', { op: 'insertColumn', index: cursor.col }),
  'op:deleteColumn': () => send('/api/op', { op: 'deleteColumn', index: cursor.col }),
  'op:addRows': () => send('/api/op', { op: 'addRows' }),
  'op:addColumns': () => send('/api/op', { op: 'addColumns' }),

  'sort:asc': () => send('/api/op', { op: 'sort', column: cursor.col, firstRow: cursor.row, ascending: true }),
  'sort:desc': () => send('/api/op', { op: 'sort', column: cursor.col, firstRow: cursor.row, ascending: false }),

  'insert:function': openFunctions,
  'insert:chart': () => openChartDialog(null),
  'panel:charts': () => { chartsVisible = !chartsVisible; drawCharts(); },
};

function toggleFormat(field) {
  const current = state.formats[currentRef()] || {};
  send('/api/format', { refs: selectedRefs(), [field]: !current[field] });
}

async function clearSelection() {
  const refs = selectedRefs().filter((ref) => state.cells[ref] !== undefined);
  if (!refs.length) return;
  try {
    let last = null;
    for (const ref of refs) last = await api('/api/cell', { ref, raw: '' });
    if (last) adopt(last);
  } catch (error) { toast(error.message, true); }
}

function run(name) {
  const fn = commands[name];
  if (fn) fn();
}

/* --------------------------------------------------------------- toast */

let toastTimer = null;
function toast(message, bad) {
  const node = $('toast');
  node.textContent = message;
  node.classList.toggle('bad', !!bad);
  node.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { node.hidden = true; }, 2600);
}

/* ------------------------------------------------------------- liaisons */

function bindMenus() {
  const close = () => {
    document.querySelectorAll('.dropdown').forEach((d) => { d.hidden = true; });
    document.querySelectorAll('.menutrigger').forEach((t) => t.classList.remove('open'));
  };
  document.querySelectorAll('.menutrigger').forEach((trigger) => {
    const panel = document.querySelector(`.dropdown[data-for="${trigger.dataset.menu}"]`);
    trigger.addEventListener('click', (event) => {
      event.stopPropagation();
      const wasOpen = !panel.hidden;
      close();
      if (!wasOpen) {
        panel.hidden = false;
        panel.style.left = trigger.offsetLeft + 'px';
        trigger.classList.add('open');
      }
    });
    // Une fois un menu ouvert, survoler un autre titre le déroule aussitôt.
    trigger.addEventListener('mouseenter', () => {
      if (document.querySelector('.dropdown:not([hidden])')) trigger.click();
    });
  });
  document.addEventListener('click', close);
  document.querySelectorAll('[data-cmd]').forEach((button) => {
    button.addEventListener('click', (event) => {
      event.stopPropagation();
      close();
      run(button.dataset.cmd);
    });
  });
}

function bindGrid() {
  const grid = $('grid');
  grid.addEventListener('mousedown', (event) => {
    const th = event.target.closest('th');
    if (th && th.dataset.col !== undefined) {
      commit();
      cursor.col = Number(th.dataset.col); cursor.fromCol = cursor.col;
      cursor.row = 0; cursor.fromRow = state.rows - 1;
      paint(); return;
    }
    if (th && th.dataset.row !== undefined) {
      commit();
      cursor.row = Number(th.dataset.row); cursor.fromRow = cursor.row;
      cursor.col = 0; cursor.fromCol = state.columns - 1;
      paint(); return;
    }
    const td = event.target.closest('td');
    if (!td) return;
    if (editor && td.contains(editor.input)) return;
    moveTo(Number(td.dataset.row), Number(td.dataset.col), event.shiftKey);
  });
  grid.addEventListener('dblclick', (event) => { if (event.target.closest('td')) edit(); });
}

function bindKeyboard() {
  document.addEventListener('keydown', (event) => {
    if (!$('scrim').hidden) { if (event.key === 'Escape') closeDialog(); return; }
    if (editor) return;

    if (event.ctrlKey || event.metaKey) {
      const key = event.key.toLowerCase();
      const shortcuts = { s: 'file:save', o: 'file:open', n: 'file:new', b: 'format:bold', i: 'format:italic' };
      if (shortcuts[key]) { event.preventDefault(); run(shortcuts[key]); }
      return;
    }
    if (document.activeElement === $('formulaInput')) return;

    const step = (dr, dc) => { event.preventDefault(); moveTo(cursor.row + dr, cursor.col + dc, event.shiftKey); };
    switch (event.key) {
      case 'ArrowUp': return step(-1, 0);
      case 'ArrowDown': return step(1, 0);
      case 'ArrowLeft': return step(0, -1);
      case 'ArrowRight': return step(0, 1);
      case 'PageDown': return step(15, 0);
      case 'PageUp': return step(-15, 0);
      case 'Home': return step(0, -cursor.col);
      case 'Enter': case 'F2': event.preventDefault(); return edit();
      case 'Tab': return step(0, event.shiftKey ? -1 : 1);
      case 'Delete': case 'Backspace': event.preventDefault(); return clearSelection();
      default:
        if (event.key.length === 1 && !event.altKey) { event.preventDefault(); edit(event.key); }
    }
  });
}

function bindFormulaBar() {
  const input = $('formulaInput');
  input.addEventListener('keydown', (event) => {
    if (event.key === 'Enter') {
      event.preventDefault();
      send('/api/cell', { ref: currentRef(), raw: input.value });
      moveTo(cursor.row + 1, cursor.col, false);
      input.blur();
    } else if (event.key === 'Escape') {
      input.value = state.cells[currentRef()] || '';
      input.blur();
    }
    event.stopPropagation();
  });
}

function bindSheets() {
  $('addSheet').addEventListener('click', () => send('/api/sheet', { action: 'add' }));

  $('sheetTabs').addEventListener('click', (event) => {
    const tab = event.target.closest('[data-sheet-index]');
    if (tab) send('/api/sheet', { action: 'select', index: Number(tab.dataset.sheetIndex) });
  });
  $('sheetTabs').addEventListener('dblclick', (event) => {
    const tab = event.target.closest('[data-sheet-index]');
    if (tab) renameSheet(Number(tab.dataset.sheetIndex));
  });
  $('sheetTabs').addEventListener('contextmenu', (event) => {
    const tab = event.target.closest('[data-sheet-index]');
    if (!tab) return;
    event.preventDefault();
    sheetMenuTarget = Number(tab.dataset.sheetIndex);
    const menu = $('sheetMenu');
    menu.hidden = false;
    menu.style.left = Math.min(event.clientX, window.innerWidth - 200) + 'px';
    menu.style.top = (event.clientY - menu.offsetHeight - 6) + 'px';
  });

  document.addEventListener('click', () => { $('sheetMenu').hidden = true; });
  $('sheetMenu').addEventListener('click', (event) => {
    const button = event.target.closest('[data-sheet]');
    if (!button) return;
    $('sheetMenu').hidden = true;
    const index = sheetMenuTarget;
    switch (button.dataset.sheet) {
      case 'rename': return renameSheet(index);
      case 'duplicate': return send('/api/sheet', { action: 'duplicate', index });
      case 'left': return send('/api/sheet', { action: 'move', index, to: Math.max(0, index - 1) });
      case 'right': return send('/api/sheet', {
        action: 'move', index, to: Math.min(state.sheets.length - 1, index + 1) });
      case 'remove': return send('/api/sheet', { action: 'remove', index });
    }
  });
}

function renameSheet(index) {
  const current = state.sheets[index]?.name || '';
  const name = prompt('Nom de la feuille', current);
  if (name !== null && name !== current) send('/api/sheet', { action: 'rename', index, name });
}

function bindCharts() {
  $('chartList').addEventListener('click', (event) => {
    const remove = event.target.closest('[data-chart-remove]');
    if (remove) return send('/api/chart', { action: 'remove', id: remove.dataset.chartRemove });
    const edit = event.target.closest('[data-chart-edit]');
    if (edit) {
      const found = state.charts.find((c) => c.id === edit.dataset.chartEdit);
      if (found) openChartDialog(found);
    }
  });
}

async function start() {
  bindMenus();
  bindGrid();
  bindKeyboard();
  bindFormulaBar();
  bindSheets();
  bindCharts();
  try {
    chartKinds = await api('/api/chartkinds');
    adopt(await api('/api/state'));
  } catch (error) {
    toast("L'application n'a pas pu démarrer : " + error.message, true);
  }
}

start();
