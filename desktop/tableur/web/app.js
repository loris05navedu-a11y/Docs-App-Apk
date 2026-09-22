/* Tableur — interface. Le calcul reste côté Go : cette page affiche l'état
   que le serveur local renvoie après chaque modification. */

'use strict';

const $ = (id) => document.getElementById(id);

const state = {
  rows: 100,
  columns: 26,
  cells: {},
  display: {},
  formats: {},
  name: '',
  path: '',
  modified: false,
  dialogsOk: true,
};

const selection = { row: 0, col: 0, anchorRow: 0, anchorCol: 0 };
let editing = null;      // { ref, input }
let catalog = null;

/* ----------------------------------------------------------- références */

function columnLabel(index) {
  let i = index, out = '';
  for (;;) {
    out = String.fromCharCode(65 + (i % 26)) + out;
    i = Math.floor(i / 26) - 1;
    if (i < 0) break;
  }
  return out;
}

const refOf = (row, col) => columnLabel(col) + (row + 1);

function selectedRefs() {
  const r1 = Math.min(selection.row, selection.anchorRow);
  const r2 = Math.max(selection.row, selection.anchorRow);
  const c1 = Math.min(selection.col, selection.anchorCol);
  const c2 = Math.max(selection.col, selection.anchorCol);
  const out = [];
  for (let r = r1; r <= r2; r++) {
    for (let c = c1; c <= c2; c++) out.push(refOf(r, c));
  }
  return out;
}

/* ---------------------------------------------------------------- réseau */

async function api(path, body) {
  const options = body === undefined
    ? {}
    : { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) };
  const response = await fetch(path, options);
  const payload = await response.json();
  if (!response.ok) throw new Error(payload.error || 'Opération impossible');
  return payload;
}

async function push(path, body) {
  try {
    applyState(await api(path, body));
  } catch (error) {
    toast(error.message, true);
  }
}

function applyState(next) {
  Object.assign(state, next);
  state.cells = next.cells || {};
  state.display = next.display || {};
  state.formats = next.formats || {};
  render();
  renderHeader();
}

/* ------------------------------------------------------------- affichage */

function renderHeader() {
  $('docName').textContent = (state.name || 'Classeur sans titre') + (state.modified ? ' •' : '');
  $('docPath').textContent = state.path || 'Non enregistré';
}

function isNumeric(text) {
  return text !== '' && !isNaN(Number(text.replace(/\s/g, '').replace(',', '.')));
}

function render() {
  const grid = $('grid');
  const head = ['<thead><tr><th class="corner"></th>'];
  for (let c = 0; c < state.columns; c++) {
    head.push(`<th data-col="${c}">${columnLabel(c)}</th>`);
  }
  head.push('</tr></thead>');

  const body = ['<tbody>'];
  for (let r = 0; r < state.rows; r++) {
    body.push(`<tr><th data-row="${r}">${r + 1}</th>`);
    for (let c = 0; c < state.columns; c++) {
      const ref = refOf(r, c);
      const shown = state.display[ref] || '';
      const format = state.formats[ref] || {};
      const classes = ['cellbox'];
      if (shown.startsWith('#')) classes.push('err');
      else if (isNumeric(shown) && !format.align) classes.push('num');

      const style = [];
      if (format.bold) style.push('font-weight:600');
      if (format.italic) style.push('font-style:italic');
      if (format.color) style.push(`color:${format.color}`);
      if (format.background) style.push(`background:${format.background}`);
      if (format.align) style.push(`text-align:${format.align}`);

      body.push(
        `<td data-row="${r}" data-col="${c}">` +
        `<span class="${classes.join(' ')}" style="${style.join(';')}">${escapeHtml(shown)}</span>` +
        `</td>`
      );
    }
    body.push('</tr>');
  }
  body.push('</tbody>');
  grid.innerHTML = head.join('') + body.join('');
  paintSelection();
}

function escapeHtml(text) {
  return text.replace(/[&<>"']/g, (c) => (
    { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]
  ));
}

function cellAt(row, col) {
  return document.querySelector(`td[data-row="${row}"][data-col="${col}"]`);
}

function paintSelection() {
  document.querySelectorAll('td.selected, td.inrange').forEach((td) => {
    td.classList.remove('selected', 'inrange');
  });
  document.querySelectorAll('.axis-active').forEach((th) => th.classList.remove('axis-active'));

  const refs = selectedRefs();
  refs.forEach((ref) => {
    const match = /^([A-Z]+)(\d+)$/.exec(ref);
    const col = columnIndex(match[1]);
    const row = Number(match[2]) - 1;
    const td = cellAt(row, col);
    if (td) td.classList.add('inrange');
  });
  const current = cellAt(selection.row, selection.col);
  if (current) {
    current.classList.remove('inrange');
    current.classList.add('selected');
    current.scrollIntoView({ block: 'nearest', inline: 'nearest' });
  }
  document.querySelector(`th[data-col="${selection.col}"]`)?.classList.add('axis-active');
  document.querySelector(`th[data-row="${selection.row}"]`)?.classList.add('axis-active');

  const ref = refOf(selection.row, selection.col);
  $('cellRef').textContent = refs.length > 1 ? `${refs[0]}:${refs[refs.length - 1]}` : ref;
  $('formulaInput').value = state.cells[ref] || '';
  refreshToolbarState();
  refreshStats(refs);
}

function columnIndex(label) {
  let n = 0;
  for (const ch of label) n = n * 26 + (ch.charCodeAt(0) - 64);
  return n - 1;
}

function refreshToolbarState() {
  const format = state.formats[refOf(selection.row, selection.col)] || {};
  $('btnBold').classList.toggle('active', !!format.bold);
  $('btnItalic').classList.toggle('active', !!format.italic);
  $('btnAlignLeft').classList.toggle('active', format.align === 'left');
  $('btnAlignCenter').classList.toggle('active', format.align === 'center');
  $('btnAlignRight').classList.toggle('active', format.align === 'right');
  $('swText').style.background = format.color || 'currentColor';
  $('swFill').style.background = format.background || 'currentColor';
}

function refreshStats(refs) {
  const numbers = refs
    .map((ref) => state.display[ref] || '')
    .filter((text) => text !== '' && !text.startsWith('#') && isNumeric(text))
    .map((text) => Number(text.replace(/\s/g, '').replace(',', '.')));

  if (numbers.length === 0) {
    $('statusStats').textContent = refs.length > 1
      ? `${refs.length} cellules sélectionnées`
      : 'Prêt';
    return;
  }
  const total = numbers.reduce((a, b) => a + b, 0);
  const average = total / numbers.length;
  $('statusStats').textContent =
    `Somme : ${trim(total)}   ·   Moyenne : ${trim(average)}   ·   Nombre : ${numbers.length}`;
}

function trim(value) {
  return Number.isInteger(value) ? String(value) : value.toFixed(4).replace(/0+$/, '').replace(/\.$/, '');
}

/* --------------------------------------------------------------- édition */

function moveTo(row, col, extend) {
  commitEdit();
  selection.row = Math.max(0, Math.min(state.rows - 1, row));
  selection.col = Math.max(0, Math.min(state.columns - 1, col));
  if (!extend) {
    selection.anchorRow = selection.row;
    selection.anchorCol = selection.col;
  }
  paintSelection();
}

function beginEdit(initial) {
  if (editing) return;
  const td = cellAt(selection.row, selection.col);
  if (!td) return;
  const ref = refOf(selection.row, selection.col);
  const input = document.createElement('input');
  input.type = 'text';
  input.spellcheck = false;
  input.value = initial !== undefined ? initial : (state.cells[ref] || '');
  td.appendChild(input);
  input.focus();
  if (initial === undefined) input.select();
  editing = { ref, input };

  input.addEventListener('keydown', (event) => {
    if (event.key === 'Enter') {
      event.preventDefault();
      commitEdit();
      moveTo(selection.row + 1, selection.col, false);
    } else if (event.key === 'Tab') {
      event.preventDefault();
      commitEdit();
      moveTo(selection.row, selection.col + (event.shiftKey ? -1 : 1), false);
    } else if (event.key === 'Escape') {
      event.preventDefault();
      cancelEdit();
    }
    event.stopPropagation();
  });
}

function cancelEdit() {
  if (!editing) return;
  editing.input.remove();
  editing = null;
  paintSelection();
}

function commitEdit() {
  if (!editing) return;
  const { ref, input } = editing;
  const raw = input.value;
  input.remove();
  editing = null;
  if ((state.cells[ref] || '') !== raw) push('/api/cell', { ref, raw });
}

/* ------------------------------------------------------------- panneaux */

function closePanel() {
  $('overlay').hidden = true;
  $('panel').innerHTML = '';
}

function openPanel(title, bodyHtml, footerHtml) {
  $('panel').innerHTML =
    `<header><h2>${title}</h2></header>` +
    `<div class="body">${bodyHtml}</div>` +
    `<footer>${footerHtml || '<button class="ghost" data-close>Fermer</button>'}</footer>`;
  $('overlay').hidden = false;
  $('panel').querySelectorAll('[data-close]').forEach((b) => b.addEventListener('click', closePanel));
}

const PALETTE = [
  '#0f1729', '#dc2626', '#ea580c', '#d97706', '#059669', '#2563eb', '#7c3aed', '#be185d',
  '#ffffff', '#fee2e2', '#ffedd5', '#fef3c7', '#d1fae5', '#dbeafe', '#ede9fe', '#fce7f3',
];

function openPalette(title, apply) {
  const buttons = PALETTE
    .map((c) => `<button style="background:${c}" data-color="${c}" title="${c}"></button>`)
    .join('');
  openPanel(title, `<div class="palette"><button class="none" data-color="" title="Aucune"></button>${buttons}</div>`);
  $('panel').querySelectorAll('[data-color]').forEach((button) => {
    button.addEventListener('click', () => {
      apply(button.dataset.color);
      closePanel();
    });
  });
}

async function openFunctions() {
  if (!catalog) catalog = await api('/api/functions');
  const render = (needle) => {
    const query = needle.trim().toUpperCase();
    const groups = catalog
      .map((group) => ({
        title: group.title,
        entries: group.entries.filter((e) =>
          !query || e.signature.toUpperCase().includes(query) || e.description.toUpperCase().includes(query)),
      }))
      .filter((group) => group.entries.length > 0);

    if (groups.length === 0) return '<p>Aucune fonction ne correspond.</p>';
    return groups.map((group) =>
      `<div class="fngroup"><h3>${group.title}</h3>` +
      group.entries.map((e) =>
        `<div class="fnrow" data-signature="${escapeHtml(e.signature)}">` +
        `<strong>${escapeHtml(e.signature)}</strong><span>${escapeHtml(e.description)}</span></div>`
      ).join('') +
      `</div>`
    ).join('');
  };

  const bind = () => {
    $('panel').querySelectorAll('.fnrow').forEach((row) => {
      row.addEventListener('click', () => {
        const signature = row.dataset.signature;
        const name = signature.split('(')[0];
        closePanel();
        beginEdit('=' + name + '(');
      });
    });
  };

  openPanel('Insérer une fonction',
    `<input class="search" id="fnSearch" placeholder="Rechercher une fonction…" autocomplete="off">` +
    `<div id="fnList">${render('')}</div>`);
  bind();
  $('fnSearch').addEventListener('input', (event) => {
    $('fnList').innerHTML = render(event.target.value);
    bind();
  });
  $('fnSearch').focus();
}

/* ----------------------------------------------------------------- toast */

let toastTimer = null;
function toast(message, isError) {
  const node = $('toast');
  node.textContent = message;
  node.classList.toggle('error', !!isError);
  node.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { node.hidden = true; }, 2600);
}

/* --------------------------------------------------------------- liaisons */

function bindGrid() {
  const grid = $('grid');

  grid.addEventListener('mousedown', (event) => {
    const th = event.target.closest('th');
    if (th && th.dataset.col !== undefined) {
      commitEdit();
      selection.col = Number(th.dataset.col);
      selection.anchorCol = selection.col;
      selection.row = 0;
      selection.anchorRow = state.rows - 1;
      paintSelection();
      return;
    }
    if (th && th.dataset.row !== undefined) {
      commitEdit();
      selection.row = Number(th.dataset.row);
      selection.anchorRow = selection.row;
      selection.col = 0;
      selection.anchorCol = state.columns - 1;
      paintSelection();
      return;
    }
    const td = event.target.closest('td');
    if (!td) return;
    if (editing && td.contains(editing.input)) return;
    moveTo(Number(td.dataset.row), Number(td.dataset.col), event.shiftKey);
  });

  grid.addEventListener('dblclick', (event) => {
    if (event.target.closest('td')) beginEdit();
  });
}

function bindKeyboard() {
  document.addEventListener('keydown', (event) => {
    if (!$('overlay').hidden) {
      if (event.key === 'Escape') closePanel();
      return;
    }
    if (editing) return;
    if (document.activeElement === $('formulaInput')) return;

    const step = (dr, dc) => {
      event.preventDefault();
      moveTo(selection.row + dr, selection.col + dc, event.shiftKey);
    };

    switch (event.key) {
      case 'ArrowUp': return step(-1, 0);
      case 'ArrowDown': return step(1, 0);
      case 'ArrowLeft': return step(0, -1);
      case 'ArrowRight': return step(0, 1);
      case 'Enter': event.preventDefault(); return beginEdit();
      case 'F2': event.preventDefault(); return beginEdit();
      case 'Tab': return step(0, event.shiftKey ? -1 : 1);
      case 'Home': return step(0, -selection.col);
      case 'Delete':
      case 'Backspace': {
        event.preventDefault();
        const refs = selectedRefs().filter((ref) => state.cells[ref] !== undefined);
        if (refs.length === 0) return;
        Promise.all(refs.map((ref) => api('/api/cell', { ref, raw: '' })))
          .then((results) => applyState(results[results.length - 1]))
          .catch((error) => toast(error.message, true));
        return;
      }
      default:
        // Une frappe imprimable ouvre directement la saisie.
        if (event.key.length === 1 && !event.ctrlKey && !event.altKey && !event.metaKey) {
          event.preventDefault();
          beginEdit(event.key);
        }
    }
  });
}

function bindToolbar() {
  const toggle = (field) => () => {
    const current = state.formats[refOf(selection.row, selection.col)] || {};
    push('/api/format', { refs: selectedRefs(), [field]: !current[field] });
  };
  $('btnBold').addEventListener('click', toggle('bold'));
  $('btnItalic').addEventListener('click', toggle('italic'));

  const align = (value) => () => push('/api/format', { refs: selectedRefs(), align: value });
  $('btnAlignLeft').addEventListener('click', align('left'));
  $('btnAlignCenter').addEventListener('click', align('center'));
  $('btnAlignRight').addEventListener('click', align('right'));

  $('btnTextColor').addEventListener('click', () => {
    openPalette('Couleur du texte', (color) => push('/api/format', { refs: selectedRefs(), color }));
  });
  $('btnFillColor').addEventListener('click', () => {
    openPalette('Couleur de fond', (color) => push('/api/format', { refs: selectedRefs(), background: color }));
  });
  $('btnClearFormat').addEventListener('click', () => {
    push('/api/format', { refs: selectedRefs(), clear: true });
  });

  $('btnFunctions').addEventListener('click', openFunctions);

  document.querySelectorAll('[data-op]').forEach((button) => {
    button.addEventListener('click', () => {
      const op = button.dataset.op;
      const index = op.endsWith('Row') ? selection.row : selection.col;
      push('/api/op', { op, index });
    });
  });

  $('btnSortAsc').addEventListener('click', () =>
    push('/api/op', { op: 'sort', column: selection.col, firstRow: selection.row, ascending: true }));
  $('btnSortDesc').addEventListener('click', () =>
    push('/api/op', { op: 'sort', column: selection.col, firstRow: selection.row, ascending: false }));
}

function bindFile() {
  $('btnNew').addEventListener('click', () => push('/api/file', { action: 'new' }));
  $('btnOpen').addEventListener('click', () => push('/api/file', { action: 'open' }));
  $('btnSave').addEventListener('click', async () => {
    await push('/api/file', { action: 'save' });
    if (!state.modified) toast('Classeur enregistré');
  });

  const menu = $('moreMenu');
  $('btnMore').addEventListener('click', (event) => {
    event.stopPropagation();
    menu.hidden = !menu.hidden;
  });
  document.addEventListener('click', () => { menu.hidden = true; });
  menu.addEventListener('click', (event) => event.stopPropagation());

  menu.querySelectorAll('[data-action]').forEach((button) => {
    button.addEventListener('click', async () => {
      menu.hidden = true;
      const action = button.dataset.action;
      if (action === 'clear') {
        if (!confirm('Effacer tout le contenu du classeur ?')) return;
        return push('/api/op', { op: 'clear' });
      }
      await push('/api/file', { action });
      if (action === 'exportXlsx') toast('Classeur exporté au format Excel');
      if (action === 'exportCsv') toast('Classeur exporté en CSV');
    });
  });
}

function bindFormulaBar() {
  const input = $('formulaInput');
  input.addEventListener('keydown', (event) => {
    if (event.key === 'Enter') {
      event.preventDefault();
      const ref = refOf(selection.row, selection.col);
      push('/api/cell', { ref, raw: input.value });
      moveTo(selection.row + 1, selection.col, false);
      input.blur();
    } else if (event.key === 'Escape') {
      input.value = state.cells[refOf(selection.row, selection.col)] || '';
      input.blur();
    }
    event.stopPropagation();
  });
}

async function start() {
  bindGrid();
  bindKeyboard();
  bindToolbar();
  bindFile();
  bindFormulaBar();
  try {
    applyState(await api('/api/state'));
  } catch (error) {
    toast("L'application n'a pas pu démarrer : " + error.message, true);
  }
}

start();
