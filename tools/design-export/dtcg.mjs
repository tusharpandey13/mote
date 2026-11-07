// docs/design/tokens.json -> docs/design/tokens.dtcg.json (W3C DTCG 2025.10 format), then validates it.
// Usage: node tools/design-export/dtcg.mjs [--check-only]
import { readFileSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const srcFile = path.join(root, 'docs/design/tokens.json');
const outFile = path.join(root, 'docs/design/tokens.dtcg.json');

const HEX = /^#([0-9A-Fa-f]{2})?([0-9A-Fa-f]{6})$/;
const r4 = (n) => Math.round(n * 1e4) / 1e4;
// tokens.json colours are #RRGGBB or #AARRGGBB (alpha first).
const color = (s) => {
  const m = HEX.exec(s); if (!m) throw new Error('bad colour ' + s);
  const a = m[1] ? parseInt(m[1], 16) / 255 : 1, rgb = m[2];
  const c = [0, 2, 4].map((i) => r4(parseInt(rgb.slice(i, i + 2), 16) / 255));
  const out = { colorSpace: 'srgb', components: c };
  if (a !== 1) out.alpha = r4(a);
  out.hex = '#' + rgb.toUpperCase() + (m[1] ? m[1].toUpperCase() : '');
  return out;
};
const px = (n) => ({ value: n, unit: 'px' });
const tok = ($type, $value, ext) => ({ $type, $value, ...(ext && { $extensions: { 'dev.mote': ext } }) });
const stops = (list) => list.map((e, i) => {
  const [p, c] = Array.isArray(e) ? e : [i / (list.length - 1), e]; // bare colour lists are evenly spaced
  return { color: color(c), position: r4(Number(p)) };
});

const src = JSON.parse(readFileSync(srcFile, 'utf8'));
const gaps = [];
const out = {
  $description: 'Mote design tokens, generated from docs/design/tokens.json by tools/design-export/dtcg.mjs. Do not edit.',
  color: {}, grain: {}, gradient: {}, radius: {}, shadow: {}, typography: {},
};

for (const [theme, t] of Object.entries(src.themes)) {
  out.color[theme] = {}; out.grain[theme] = {}; out.gradient[theme] = {};
  for (const [k, v] of Object.entries(t.color)) {
    if (typeof v === 'string') out.color[theme][k] = tok('color', color(v));
    else out.gradient[theme][k] = tok('gradient', stops(v), { note: 'metalStops: hard-stepped, top to bottom. ringMetalSweep: angular sweep, stops evenly spaced.' });
  }
  for (const grp of ['grainAlpha', 'brushedAlpha']) {
    out.grain[theme][grp] = Object.fromEntries(Object.entries(t[grp]).map(([k, v]) => [k, tok('number', v)]));
  }
  out.gradient[theme].gloss = tok('gradient', stops(t.gloss));
}
for (const theme of Object.keys(src.themes)) {
  const g = out.gradient[theme], t = src.themes[theme], v = t.vignette;
  g.sheen = Object.fromEntries(Object.entries(t.sheen).map(([n, x]) => [n, tok('gradient', stops(x.stops), { angleDeg: x.angleDeg })]));
  g.vignette = tok('gradient', stops(v.stops), { center: v.center, radiusX: v.radiusX, radiusY: v.radiusY, note: 'Elliptical radial.' });
}

for (const [k, v] of Object.entries(src.radius)) {
  if (typeof v === 'number') out.radius[k] = tok('dimension', px(v), k === 'full' || k === 'pill' ? { note: '9999 = fully rounded' } : undefined);
  else gaps.push(`radius.${k}: prose rule "${v}"`);
}

// Shadows: aliases point at the light theme colour of the same name (the app resolves per theme at draw time).
const layer = (l) => {
  const alias = /^#/.test(l.color) ? color(l.color) : `{color.light.${l.color}}`;
  return { color: alias, offsetX: px(0), offsetY: px(l.y ?? 0), blur: px(l.blur ?? 0), spread: px(l.spread ?? 0), ...(l.inset && { inset: true }) };
};
for (const [k, v] of Object.entries(src.shadow)) {
  if (Array.isArray(v)) {
    const ext = {};
    const mul = v.map((l) => l.alphaMul ?? null);
    if (mul.some((x) => x != null)) ext.alphaMul = mul;
    const cm = v.map((l) => l.comment ?? null);
    if (cm.some(Boolean)) ext.layerComments = cm;
    out.shadow[k] = tok('shadow', v.map(layer), { ...ext, themeAlias: 'colour aliases resolve against color.light; swap to color.dark for the dark theme' });
  } else if (k === 'innerWell') {
    for (const th of ['light', 'dark']) {
      const w = v[th];
      out.shadow[`innerWell${th[0].toUpperCase()}${th.slice(1)}`] = tok('shadow', [{ color: color(w.color), offsetX: px(0), offsetY: px(w.y), blur: px(w.blur), spread: px(0), inset: true }],
        { bottomHighlight: color(w.bottomHighlight), note: 'Inner shadow plus a 1dp bottom highlight line (highlight is not a DTCG shadow).' });
    }
  } else gaps.push(`shadow.${k}: ${v.comment ? 'bevel line (references colour hi/lo), not a shadow' : 'unmapped'}`);
}

// Typography: the only machine-readable font spec is the wordmark prose in components.wordmark.font.
out.typography.wordmark = tok('typography', {
  fontFamily: 'Unbounded', fontWeight: 900, fontSize: px(19), letterSpacing: px(2.5), lineHeight: 1,
}, { source: src.components?.wordmark?.font, note: 'Parsed by hand from prose (sp = dp); ALL CAPS is a text transform, not expressible. lineHeight is a placeholder.' });

out.$extensions = {
  'dev.mote': {
    sourceVersion: src.version,
    units: src.units,
    colorFormat: 'tokens.json #AARRGGBB maps to srgb components + alpha; hex keeps #RRGGBBAA',
    dimensionUnit: 'tokens.json dp is exported as px (1 dp = 1 CSS px at the artboard scale)',
    notMapped: {
      textures: 'grain and brushed noise are procedural bitmaps (generation recipes), not tokens: see tokens.json textures',
      materials: 'layered recipes (shadow, gradient, texture, innerLine, strokeGradient) are composition rules: see tokens.json materials',
      components: 'per-component geometry (sizes, ring radii, hit sectors) and prose: see tokens.json components',
      parity: 'CSS vs Compose notes: see tokens.json parity',
      gradientGeometry: 'DTCG gradients carry stops only; angle/centre/radius are in each token $extensions["dev.mote"]',
      shadowAlpha: 'shadow alphaMul and layer comments are in $extensions["dev.mote"]; DTCG shadow colour aliases cannot carry an alpha multiplier',
      typography: 'only the wordmark has a font spec (prose); no type scale exists in tokens.json',
      details: gaps,
    },
  },
};

// ---- validation against DTCG draft type rules ----
const TYPES = new Set(['color', 'dimension', 'number', 'shadow', 'gradient', 'typography']);
function validate(doc) {
  const errs = [];
  const get = (p) => p.split('.').reduce((o, k) => o?.[k], doc);
  const resolve = (v, seen = new Set()) => { // alias -> final token
    const m = /^\{([^{}]+)\}$/.exec(v); if (!m) return null;
    if (seen.has(m[1])) return 'cycle'; seen.add(m[1]);
    const t = get(m[1]); if (!t || t.$value === undefined) return undefined;
    return typeof t.$value === 'string' && t.$value[0] === '{' ? resolve(t.$value, seen) : t;
  };
  const isNum = (n) => typeof n === 'number' && Number.isFinite(n);
  const dim = (d, w, e) => { if (!d || !isNum(d.value) || !['px', 'rem'].includes(d.unit)) e(`${w}: bad dimension`); };
  const col = (c, w, e) => {
    if (typeof c === 'string' && c[0] === '{') { const t = resolve(c); if (!t || t.$type !== 'color') e(`${w}: alias ${c} is not a colour`); return; }
    if (c?.colorSpace !== 'srgb' || !Array.isArray(c.components) || c.components.length !== 3 || !c.components.every((n) => isNum(n) && n >= 0 && n <= 1)) e(`${w}: bad colour`);
    if (c.alpha !== undefined && !(isNum(c.alpha) && c.alpha >= 0 && c.alpha <= 1)) e(`${w}: bad alpha`);
    if (c.hex !== undefined && !/^#[0-9A-F]{6}([0-9A-F]{2})?$/.test(c.hex)) e(`${w}: bad hex`);
  };
  const check = {
    color: col,
    dimension: dim,
    number: (v, w, e) => { if (!isNum(v)) e(`${w}: not a number`); },
    gradient: (v, w, e) => {
      if (!Array.isArray(v) || !v.length) return e(`${w}: gradient needs stops`);
      v.forEach((s, i) => { col(s.color, `${w}[${i}]`, e); if (!isNum(s.position) || s.position < 0 || s.position > 1) e(`${w}[${i}]: position`); });
    },
    shadow: (v, w, e) => {
      for (const s of Array.isArray(v) ? v : [v]) {
        col(s.color, w, e); ['offsetX', 'offsetY', 'blur', 'spread'].forEach((k) => dim(s[k], `${w}.${k}`, e));
        if (s.inset !== undefined && typeof s.inset !== 'boolean') e(`${w}: inset`);
      }
    },
    typography: (v, w, e) => {
      if (typeof v.fontFamily !== 'string' && !Array.isArray(v.fontFamily)) e(`${w}: fontFamily`);
      dim(v.fontSize, `${w}.fontSize`, e); dim(v.letterSpacing, `${w}.letterSpacing`, e);
      if (!isNum(v.fontWeight) || v.fontWeight < 1 || v.fontWeight > 1000) e(`${w}: fontWeight`);
      if (!isNum(v.lineHeight)) e(`${w}: lineHeight`);
    },
  };
  let n = 0;
  const walk = (node, p, inherited) => {
    for (const [k, v] of Object.entries(node)) {
      if (k[0] === '$') continue;
      const here = p ? `${p}.${k}` : k;
      if (/[{}.]/.test(k)) errs.push(`${here}: illegal character in name`);
      if (v.$value !== undefined) {
        n++;
        const type = v.$type ?? inherited;
        if (!TYPES.has(type)) { errs.push(`${here}: unknown or missing $type`); continue; }
        const e = (m) => errs.push(m);
        if (typeof v.$value === 'string' && v.$value[0] === '{') {
          const t = resolve(v.$value);
          if (!t || t === 'cycle') e(`${here}: unresolved alias ${v.$value}`); else if (t.$type !== type) e(`${here}: alias type mismatch`);
        } else check[type](v.$value, here, e);
      } else walk(v, here, v.$type ?? inherited);
    }
  };
  walk(doc, '', undefined);
  return { errs, n };
}

const text = JSON.stringify(out, null, 2) + '\n';
if (!process.argv.includes('--check-only')) writeFileSync(outFile, text);
const { errs, n } = validate(JSON.parse(text));
if (errs.length) { console.error(errs.join('\n')); process.exit(1); }
console.log(`tokens.dtcg.json: ${n} tokens valid, ${gaps.length} unmapped detail(s)`);
