/* Tracé des graphiques en SVG.
   Les données arrivent déjà résolues par le serveur : ce fichier ne fait que
   dessiner. Une seule palette, des axes discrets, pas d'effet superflu. */

'use strict';

const Charts = (() => {

  const PALETTE = ['#0b6e4f', '#1d7fa8', '#c2703d', '#8a5fa8',
                   '#b8474d', '#5d7a2e', '#a8862c', '#4a6b8a'];
  const INK = '#1c1917', INK2 = '#57534e', INK3 = '#a8a29e', LINE = '#e7e5e4';

  const W = 320, H = 208;
  const PAD = { top: 14, right: 12, bottom: 30, left: 42 };

  const esc = (s) => String(s).replace(/[&<>"]/g, (c) =>
    ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));

  const fmt = (v) => {
    if (!isFinite(v)) return '';
    const a = Math.abs(v);
    if (a >= 1e6) return (v / 1e6).toFixed(1).replace(/\.0$/, '') + 'M';
    if (a >= 1e4) return (v / 1e3).toFixed(0) + 'k';
    if (Number.isInteger(v)) return String(v);
    return v.toFixed(a < 1 ? 3 : 2).replace(/0+$/, '').replace(/\.$/, '');
  };

  /* Bornes arrondies : un axe qui s'arrête à 47,3 se lit mal. */
  function bounds(values, fromZero) {
    let lo = Math.min(...values), hi = Math.max(...values);
    if (!isFinite(lo) || !isFinite(hi)) return [0, 1];
    if (fromZero) { lo = Math.min(0, lo); hi = Math.max(0, hi); }
    if (lo === hi) { lo -= 1; hi += 1; }
    const span = hi - lo;
    const step = Math.pow(10, Math.floor(Math.log10(span / 4)));
    const nice = [1, 2, 2.5, 5, 10].map((m) => m * step).find((s) => span / s <= 5) || step * 10;
    return [Math.floor(lo / nice) * nice, Math.ceil(hi / nice) * nice];
  }

  function yAxis(lo, hi, plot) {
    const ticks = 4;
    let out = '';
    for (let i = 0; i <= ticks; i++) {
      const value = lo + (hi - lo) * (i / ticks);
      const y = plot.y1 - (plot.y1 - plot.y0) * (i / ticks);
      out += `<line x1="${plot.x0}" y1="${y}" x2="${plot.x1}" y2="${y}" stroke="${LINE}"/>`;
      out += `<text x="${plot.x0 - 6}" y="${y + 3.5}" text-anchor="end"
                font-size="9.5" fill="${INK3}">${esc(fmt(value))}</text>`;
    }
    return out;
  }

  function xLabels(labels, plot, count) {
    if (!labels || labels.length === 0) return '';
    const width = (plot.x1 - plot.x0) / count;
    // Au-delà d'une dizaine de colonnes, on n'affiche qu'un libellé sur deux.
    const stride = count > 10 ? Math.ceil(count / 8) : 1;
    let out = '';
    for (let i = 0; i < count; i += stride) {
      const text = labels[i] || '';
      if (!text) continue;
      const x = plot.x0 + width * (i + 0.5);
      out += `<text x="${x}" y="${plot.y1 + 13}" text-anchor="middle"
                font-size="9.5" fill="${INK2}">${esc(text.slice(0, 9))}</text>`;
    }
    return out;
  }

  // Le titre est déjà porté par l'en-tête de la carte : le répéter dans le
  // tracé volerait de la place au graphique.
  function frame(inner) {
    return `<svg viewBox="0 0 ${W} ${H}" xmlns="http://www.w3.org/2000/svg">${inner}</svg>`;
  }

  const plotArea = (top) => ({
    x0: PAD.left, x1: W - PAD.right,
    y0: top === undefined ? PAD.top : top, y1: H - PAD.bottom,
  });

  /* ------------------------------------------------ boîte à moustache */

  function boxplot(data) {
    const boxes = data.boxes || [];
    if (boxes.length === 0) return null;
    const plot = plotArea();

    const all = [];
    boxes.forEach((b) => {
      all.push(b.min, b.max, b.q1, b.q3, b.median, ...(b.outliers || []));
    });
    const [lo, hi] = bounds(all, false);
    const toY = (v) => plot.y1 - (plot.y1 - plot.y0) * ((v - lo) / (hi - lo));

    let out = yAxis(lo, hi, plot);
    const slot = (plot.x1 - plot.x0) / boxes.length;
    const width = Math.min(46, slot * 0.52);

    boxes.forEach((b, i) => {
      const cx = plot.x0 + slot * (i + 0.5);
      const color = PALETTE[i % PALETTE.length];
      const left = cx - width / 2;

      // Moustaches, puis leurs embouts.
      out += `<line x1="${cx}" y1="${toY(b.max)}" x2="${cx}" y2="${toY(b.q3)}" stroke="${color}" stroke-width="1.2"/>`;
      out += `<line x1="${cx}" y1="${toY(b.q1)}" x2="${cx}" y2="${toY(b.min)}" stroke="${color}" stroke-width="1.2"/>`;
      out += `<line x1="${cx - width / 4}" y1="${toY(b.max)}" x2="${cx + width / 4}" y2="${toY(b.max)}" stroke="${color}" stroke-width="1.2"/>`;
      out += `<line x1="${cx - width / 4}" y1="${toY(b.min)}" x2="${cx + width / 4}" y2="${toY(b.min)}" stroke="${color}" stroke-width="1.2"/>`;

      // La boîte : du premier au troisième quartile.
      const top = toY(b.q3), bottom = toY(b.q1);
      out += `<rect x="${left}" y="${top}" width="${width}" height="${Math.max(1, bottom - top)}"
                fill="${color}" fill-opacity=".16" stroke="${color}" stroke-width="1.2" rx="2"/>`;
      // La médiane, trait plein ; la moyenne, losange creux.
      out += `<line x1="${left}" y1="${toY(b.median)}" x2="${left + width}" y2="${toY(b.median)}"
                stroke="${color}" stroke-width="2"/>`;
      const my = toY(b.mean);
      out += `<path d="M${cx} ${my - 3.2}L${cx + 3.2} ${my}L${cx} ${my + 3.2}L${cx - 3.2} ${my}Z"
                fill="#fff" stroke="${color}" stroke-width="1.1"/>`;

      (b.outliers || []).forEach((v) => {
        out += `<circle cx="${cx}" cy="${toY(v)}" r="2.1" fill="none" stroke="${color}" stroke-width="1.1"/>`;
      });

      const name = (b.name || '').slice(0, 11);
      out += `<text x="${cx}" y="${plot.y1 + 13}" text-anchor="middle" font-size="9.5" fill="${INK2}">${esc(name)}</text>`;
    });
    return frame(out);
  }

  /* --------------------------------------------------- barres et aires */

  function columns(data, horizontal) {
    const series = data.series || [];
    if (series.length === 0) return null;
    const plot = plotArea();
    const count = Math.max(...series.map((s) => s.values.length));
    const all = series.flatMap((s) => s.values);
    const [lo, hi] = bounds(all, true);

    let out = '';
    if (horizontal) {
      const toX = (v) => plot.x0 + (plot.x1 - plot.x0) * ((v - lo) / (hi - lo));
      const slot = (plot.y1 - plot.y0) / count;
      const thickness = Math.min(20, (slot * 0.7) / series.length);
      const zero = toX(0);
      out += `<line x1="${zero}" y1="${plot.y0}" x2="${zero}" y2="${plot.y1}" stroke="${LINE}"/>`;
      for (let i = 0; i < count; i++) {
        series.forEach((s, k) => {
          const v = s.values[i];
          if (v === undefined) return;
          const y = plot.y0 + slot * (i + 0.5) - (thickness * series.length) / 2 + thickness * k;
          const x = Math.min(zero, toX(v));
          out += `<rect x="${x}" y="${y}" width="${Math.abs(toX(v) - zero)}" height="${thickness - 1}"
                    fill="${PALETTE[k % PALETTE.length]}" rx="1.5"/>`;
        });
        const label = (data.labels && data.labels[i]) || '';
        if (label) {
          out += `<text x="${plot.x0 - 6}" y="${plot.y0 + slot * (i + 0.5) + 3.5}" text-anchor="end"
                    font-size="9.5" fill="${INK2}">${esc(label.slice(0, 8))}</text>`;
        }
      }
      return frame(out);
    }

    const toY = (v) => plot.y1 - (plot.y1 - plot.y0) * ((v - lo) / (hi - lo));
    out += yAxis(lo, hi, plot);
    const slot = (plot.x1 - plot.x0) / count;
    const thickness = Math.min(26, (slot * 0.72) / series.length);
    const zero = toY(0);
    for (let i = 0; i < count; i++) {
      series.forEach((s, k) => {
        const v = s.values[i];
        if (v === undefined) return;
        const x = plot.x0 + slot * (i + 0.5) - (thickness * series.length) / 2 + thickness * k;
        const y = Math.min(zero, toY(v));
        out += `<rect x="${x}" y="${y}" width="${thickness - 1.5}" height="${Math.max(1, Math.abs(toY(v) - zero))}"
                  fill="${PALETTE[k % PALETTE.length]}" rx="1.5"/>`;
      });
    }
    out += xLabels(data.labels, plot, count);
    return frame(out);
  }

  function lines(data, filled) {
    const series = data.series || [];
    if (series.length === 0) return null;
    const plot = plotArea();
    const count = Math.max(...series.map((s) => s.values.length));
    if (count < 1) return null;
    const [lo, hi] = bounds(series.flatMap((s) => s.values), filled);
    const toY = (v) => plot.y1 - (plot.y1 - plot.y0) * ((v - lo) / (hi - lo));
    const toX = (i) => count === 1
      ? (plot.x0 + plot.x1) / 2
      : plot.x0 + (plot.x1 - plot.x0) * (i / (count - 1));

    let out = yAxis(lo, hi, plot);
    series.forEach((s, k) => {
      const color = PALETTE[k % PALETTE.length];
      const points = s.values.map((v, i) => `${toX(i)},${toY(v)}`).join(' ');
      if (filled) {
        out += `<polygon points="${toX(0)},${plot.y1} ${points} ${toX(s.values.length - 1)},${plot.y1}"
                  fill="${color}" fill-opacity=".14"/>`;
      }
      out += `<polyline points="${points}" fill="none" stroke="${color}" stroke-width="1.8"
                stroke-linejoin="round" stroke-linecap="round"/>`;
      if (count <= 24) {
        s.values.forEach((v, i) => {
          out += `<circle cx="${toX(i)}" cy="${toY(v)}" r="2.4" fill="#fff" stroke="${color}" stroke-width="1.5"/>`;
        });
      }
    });
    out += xLabels(data.labels, plot, count);
    return frame(out);
  }

  /* ------------------------------------------------------ parts d'un tout */

  function slices(data, donut) {
    const values = (data.series[0] || {}).values || [];
    const total = values.reduce((a, b) => a + Math.abs(b), 0);
    if (total === 0) return null;

    const cx = W / 2 - 34, cy = H / 2, r = 66;
    let angle = -Math.PI / 2;
    let out = '';
    values.forEach((v, i) => {
      const share = Math.abs(v) / total;
      const end = angle + share * Math.PI * 2;
      const color = PALETTE[i % PALETTE.length];
      if (share > 0.9999) {
        out += `<circle cx="${cx}" cy="${cy}" r="${r}" fill="${color}"/>`;
      } else {
        const x0 = cx + r * Math.cos(angle), y0 = cy + r * Math.sin(angle);
        const x1 = cx + r * Math.cos(end), y1 = cy + r * Math.sin(end);
        out += `<path d="M${cx} ${cy}L${x0} ${y0}A${r} ${r} 0 ${share > 0.5 ? 1 : 0} 1 ${x1} ${y1}Z"
                  fill="${color}" stroke="#fff" stroke-width="1.2"/>`;
      }
      angle = end;
    });
    if (donut) {
      out += `<circle cx="${cx}" cy="${cy}" r="${r * 0.58}" fill="#fff"/>`;
      out += `<text x="${cx}" y="${cy + 5}" text-anchor="middle" font-size="14"
                font-weight="600" fill="${INK}">${esc(fmt(total))}</text>`;
    }
    // Légende à droite, une entrée par part.
    values.slice(0, 7).forEach((v, i) => {
      const y = 22 + i * 17;
      const label = (data.labels && data.labels[i]) || `Part ${i + 1}`;
      out += `<rect x="${W - 96}" y="${y - 7}" width="9" height="9" rx="2" fill="${PALETTE[i % PALETTE.length]}"/>`;
      out += `<text x="${W - 83}" y="${y + 1}" font-size="9.5" fill="${INK2}">${esc(label.slice(0, 9))}</text>`;
      out += `<text x="${W - 12}" y="${y + 1}" font-size="9.5" text-anchor="end" fill="${INK3}">${esc(
        (Math.abs(v) / total * 100).toFixed(0))}%</text>`;
    });
    return frame(out);
  }

  /* ------------------------------------------------------------- nuage */

  function scatter(data) {
    const series = data.series || [];
    if (series.length < 2) {
      return note('Le nuage de points demande deux plages : les abscisses puis les ordonnées.');
    }
    const xs = series[0].values, ys = series[1].values;
    const n = Math.min(xs.length, ys.length);
    if (n === 0) return null;
    const plot = plotArea();
    const [xlo, xhi] = bounds(xs.slice(0, n), false);
    const [ylo, yhi] = bounds(ys.slice(0, n), false);
    const toX = (v) => plot.x0 + (plot.x1 - plot.x0) * ((v - xlo) / (xhi - xlo));
    const toY = (v) => plot.y1 - (plot.y1 - plot.y0) * ((v - ylo) / (yhi - ylo));

    let out = yAxis(ylo, yhi, plot);
    for (let i = 0; i < n; i++) {
      out += `<circle cx="${toX(xs[i])}" cy="${toY(ys[i])}" r="3" fill="${PALETTE[0]}" fill-opacity=".72"/>`;
    }
    out += `<text x="${plot.x0}" y="${plot.y1 + 13}" font-size="9.5" fill="${INK3}">${esc(fmt(xlo))}</text>`;
    out += `<text x="${plot.x1}" y="${plot.y1 + 13}" font-size="9.5" text-anchor="end" fill="${INK3}">${esc(fmt(xhi))}</text>`;
    return frame(out);
  }

  /* ------------------------------------------------------ distribution */

  function histogram(data) {
    const bins = data.bins || [];
    if (bins.length === 0) return null;
    const plot = plotArea();
    const [lo, hi] = bounds(bins.map((b) => b.count), true);
    const toY = (v) => plot.y1 - (plot.y1 - plot.y0) * ((v - lo) / (hi - lo));

    let out = yAxis(lo, hi, plot);
    const width = (plot.x1 - plot.x0) / bins.length;
    bins.forEach((b, i) => {
      const x = plot.x0 + width * i;
      out += `<rect x="${x + 0.6}" y="${toY(b.count)}" width="${width - 1.2}"
                height="${Math.max(0, plot.y1 - toY(b.count))}" fill="${PALETTE[0]}" rx="1.5"/>`;
    });
    out += `<text x="${plot.x0}" y="${plot.y1 + 13}" font-size="9.5" fill="${INK3}">${esc(fmt(bins[0].from))}</text>`;
    out += `<text x="${plot.x1}" y="${plot.y1 + 13}" text-anchor="end" font-size="9.5"
              fill="${INK3}">${esc(fmt(bins[bins.length - 1].to))}</text>`;
    return frame(out);
  }

  /* -------------------------------------------------------------- radar */

  function radar(data) {
    const series = data.series || [];
    if (series.length === 0) return null;
    const axes = Math.max(...series.map((s) => s.values.length));
    if (axes < 3) return note('Le radar demande au moins trois valeurs par série.');

    const cx = W / 2, cy = H / 2, r = 68;
    const hi = Math.max(...series.flatMap((s) => s.values), 1);
    const point = (i, v) => {
      const a = -Math.PI / 2 + (i / axes) * Math.PI * 2;
      const d = (v / hi) * r;
      return [cx + d * Math.cos(a), cy + d * Math.sin(a)];
    };

    let out = '';
    for (let ring = 1; ring <= 3; ring++) {
      const pts = Array.from({ length: axes }, (_, i) => point(i, (hi * ring) / 3).join(',')).join(' ');
      out += `<polygon points="${pts}" fill="none" stroke="${LINE}"/>`;
    }
    for (let i = 0; i < axes; i++) {
      const [x, y] = point(i, hi);
      out += `<line x1="${cx}" y1="${cy}" x2="${x}" y2="${y}" stroke="${LINE}"/>`;
      const label = (data.labels && data.labels[i]) || '';
      if (label) {
        const [lx, ly] = point(i, hi * 1.16);
        out += `<text x="${lx}" y="${ly + 3}" text-anchor="middle" font-size="9"
                  fill="${INK2}">${esc(label.slice(0, 7))}</text>`;
      }
    }
    series.forEach((s, k) => {
      const color = PALETTE[k % PALETTE.length];
      const pts = s.values.slice(0, axes).map((v, i) => point(i, v).join(',')).join(' ');
      out += `<polygon points="${pts}" fill="${color}" fill-opacity=".16" stroke="${color}" stroke-width="1.7"/>`;
    });
    return frame(out);
  }

  function note(message) {
    return `<svg viewBox="0 0 ${W} 70" xmlns="http://www.w3.org/2000/svg">
      <text x="${W / 2}" y="38" text-anchor="middle" font-size="11" fill="${INK3}">${esc(message)}</text></svg>`;
  }

  /** render rend le SVG d'un graphique, ou un message si les données manquent. */
  function render(data) {
    if (!data) return note('Aucune donnée.');
    if (data.message) return note(data.message);
    let svg = null;
    switch (data.kind) {
      case 'box':       svg = boxplot(data); break;
      case 'column':    svg = columns(data, false); break;
      case 'bar':       svg = columns(data, true); break;
      case 'line':      svg = lines(data, false); break;
      case 'area':      svg = lines(data, true); break;
      case 'pie':       svg = slices(data, false); break;
      case 'donut':     svg = slices(data, true); break;
      case 'scatter':   svg = scatter(data); break;
      case 'histogram': svg = histogram(data); break;
      case 'radar':     svg = radar(data); break;
      default:          return note('Type de graphique inconnu.');
    }
    return svg || note('Pas assez de valeurs numériques dans la plage choisie.');
  }

  return { render, PALETTE };
})();
